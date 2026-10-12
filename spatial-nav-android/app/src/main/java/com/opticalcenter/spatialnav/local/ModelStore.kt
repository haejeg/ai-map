package com.opticalcenter.spatialnav.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** An on-device model the app knows how to fetch. */
data class LocalModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    /** Approximate download size, for display and the free-space check. */
    val approxBytes: Long,
    val summary: String,
) {
    companion object {
        /** Gemma 4 models from the LiteRT Community on Hugging Face: Apache-2.0, no login needed. */
        val GEMMA_4_E2B = LocalModelSpec(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            approxBytes = 2_588_000_000,
            summary = "Recommended. Fast enough for voice on most recent phones.",
        )
        val GEMMA_4_E4B = LocalModelSpec(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B",
            fileName = "gemma-4-E4B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            approxBytes = 3_660_000_000,
            summary = "Better answers, slower; needs a high-end phone with lots of RAM.",
        )
        val CATALOG = listOf(GEMMA_4_E2B, GEMMA_4_E4B)

        fun byId(id: String?) = CATALOG.firstOrNull { it.id == id }
    }
}

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data class Downloading(val bytes: Long, val totalBytes: Long?) : ModelStatus {
        val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { bytes.toFloat() / it }
    }
    /** Partially downloaded and stopped; a new download resumes from here. */
    data class Paused(val bytes: Long, val reason: String?) : ModelStatus
    data class Ready(val file: File, val bytes: Long) : ModelStatus
}

/**
 * Downloads, stores and deletes on-device model files under [dir]. Downloads
 * resume from a ".part" file with an HTTP Range request, and the file only
 * becomes "ready" after its length matches what the server announced.
 */
class ModelStore(
    private val dir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    /** Free bytes available for [dir]; injectable for tests. */
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    private val _status = MutableStateFlow(LocalModelSpec.CATALOG.associate { it.id to scan(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status.asStateFlow()

    init {
        dir.mkdirs()
    }

    fun file(spec: LocalModelSpec) = File(dir, spec.fileName)
    private fun partFile(spec: LocalModelSpec) = File(dir, spec.fileName + ".part")

    /** Tracked status, or what's on disk for a model that isn't tracked yet. */
    fun statusOf(spec: LocalModelSpec): ModelStatus = _status.value[spec.id] ?: scan(spec)

    fun readyFile(spec: LocalModelSpec): File? = (statusOf(spec) as? ModelStatus.Ready)?.file

    private fun scan(spec: LocalModelSpec): ModelStatus {
        val f = file(spec)
        if (f.isFile && f.length() > 0) return ModelStatus.Ready(f, f.length())
        val part = partFile(spec)
        return if (part.isFile && part.length() > 0) ModelStatus.Paused(part.length(), null) else ModelStatus.NotDownloaded
    }

    private fun set(spec: LocalModelSpec, status: ModelStatus) = _status.update { it + (spec.id to status) }

    /**
     * Download (or resume) [spec]. Suspends until done; cancel the calling coroutine to pause.
     * Throws [IOException] with a readable message on failure (the partial file is kept for resuming).
     */
    suspend fun download(spec: LocalModelSpec) = withContext(Dispatchers.IO) {
        val target = file(spec)
        if (target.isFile) return@withContext
        val part = partFile(spec)
        val have = if (part.isFile) part.length() else 0L

        val needed = spec.approxBytes - have
        if (freeBytes(dir) < needed + SPACE_MARGIN_BYTES) {
            val gb = (needed + SPACE_MARGIN_BYTES) / 1e9
            throw IOException(String.format(java.util.Locale.US, "Not enough storage: free up about %.1f GB and try again.", gb))
        }

        val request = Request.Builder().url(spec.url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        set(spec, ModelStatus.Downloading(have, null))
        try {
            http.newCall(request).execute().use { response ->
                val resuming = response.code == 206
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("Download failed: empty response.")
                // A 200 to a Range request means the server ignored it: start over.
                val start = if (resuming) have else 0L
                val total = body.contentLength().takeIf { it > 0 }?.let { it + start }

                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(start)
                    out.seek(start)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = start
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written - lastReport >= REPORT_EVERY_BYTES) {
                                lastReport = written
                                set(spec, ModelStatus.Downloading(written, total))
                            }
                        }
                    }
                    if (total != null && written != total) {
                        throw IOException("Download was cut short ($written of $total bytes). Try again to resume.")
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Couldn't save the model file.")
            set(spec, ModelStatus.Ready(target, target.length()))
        } catch (e: CancellationException) {
            set(spec, scan(spec))
            throw e
        } catch (e: IOException) {
            set(spec, ModelStatus.Paused(if (part.isFile) part.length() else 0L, e.message))
            throw e
        }
    }

    /** Install a model from a stream (e.g. a file the user picked), replacing any existing copy. */
    suspend fun import(spec: LocalModelSpec, input: InputStream, sizeHint: Long?) = withContext(Dispatchers.IO) {
        if (sizeHint != null && freeBytes(dir) < sizeHint + SPACE_MARGIN_BYTES) {
            throw IOException("Not enough storage for this model file.")
        }
        val part = partFile(spec)
        set(spec, ModelStatus.Downloading(0, sizeHint))
        try {
            part.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                var lastReport = 0L
                input.use {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = it.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = written
                            set(spec, ModelStatus.Downloading(written, sizeHint))
                        }
                    }
                }
            }
            file(spec).delete()
            if (!part.renameTo(file(spec))) throw IOException("Couldn't save the model file.")
            set(spec, ModelStatus.Ready(file(spec), file(spec).length()))
        } catch (e: Exception) {
            part.delete()
            set(spec, scan(spec))
            throw e
        }
    }

    fun delete(spec: LocalModelSpec) {
        file(spec).delete()
        partFile(spec).delete()
        set(spec, ModelStatus.NotDownloaded)
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024
        /** Keep some headroom so the phone isn't left completely full. */
        const val SPACE_MARGIN_BYTES = 500L * 1024 * 1024
    }
}
