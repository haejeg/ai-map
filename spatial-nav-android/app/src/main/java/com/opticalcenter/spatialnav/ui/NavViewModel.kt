package com.opticalcenter.spatialnav.ui

import android.app.Application
import android.hardware.GeomagneticField
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.opticalcenter.spatialnav.BuildConfig
import com.opticalcenter.spatialnav.agent.Agent
import com.opticalcenter.spatialnav.agent.Assistant
import com.opticalcenter.spatialnav.agent.ClaudeAssistant
import com.opticalcenter.spatialnav.agent.NavigationTools
import com.opticalcenter.spatialnav.agent.PlaceResult
import com.opticalcenter.spatialnav.agent.TelemetrySnapshot
import com.opticalcenter.spatialnav.agent.ToolResult
import com.opticalcenter.spatialnav.data.MapDataException
import com.opticalcenter.spatialnav.data.MapDataSource
import com.opticalcenter.spatialnav.data.MockDataSource
import com.opticalcenter.spatialnav.data.tomtom.TomTomDataSource
import com.opticalcenter.spatialnav.navigation.NavigationManager
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.TripState
import com.opticalcenter.spatialnav.local.LiteRtChat
import com.opticalcenter.spatialnav.local.LocalAssistant
import com.opticalcenter.spatialnav.local.LocalModelSpec
import com.opticalcenter.spatialnav.local.ModelStatus
import com.opticalcenter.spatialnav.local.ModelStore
import com.opticalcenter.spatialnav.settings.AppPreferences
import com.opticalcenter.spatialnav.settings.AssistantMode
import com.opticalcenter.spatialnav.settings.ApiKeyRepository
import com.opticalcenter.spatialnav.settings.ApiService
import com.opticalcenter.spatialnav.settings.KeyStatus
import com.opticalcenter.spatialnav.settings.KeyVerifier
import com.opticalcenter.spatialnav.settings.KeystoreSecretStore
import com.opticalcenter.spatialnav.settings.NetworkKeyVerifier
import com.opticalcenter.spatialnav.settings.Verification
import com.opticalcenter.spatialnav.spatial.GeoPoint
import com.opticalcenter.spatialnav.spatial.SpatialEngine
import com.opticalcenter.spatialnav.spatial.UserPose
import com.opticalcenter.spatialnav.telemetry.HeadingMath
import com.opticalcenter.spatialnav.telemetry.HeadingSensor
import com.opticalcenter.spatialnav.telemetry.HeadingSource
import com.opticalcenter.spatialnav.telemetry.LocationTracker
import com.opticalcenter.spatialnav.voice.SpeechInput
import com.opticalcenter.spatialnav.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

enum class Speaker { USER, TOOL, ASSISTANT }

data class LogEntry(val id: Long, val speaker: Speaker, val text: String)

/** A dismissible banner; [opensSettings] makes tapping it open the API key screen. */
data class Notice(val text: String, val opensSettings: Boolean = false)

/** App-wide UI state: telemetry (lat, lon, heading), trip, search, assistant and key state. */
data class NavUiState(
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(UserPose(DEFAULT_POSITION.lat, DEFAULT_POSITION.lon, 0.0)),
    val headingSource: HeadingSource = HeadingSource.NONE,
    val speedMps: Float? = null,
    /** Places to show as map markers (latest search or "what's around me"). */
    val markers: List<PlaceResult> = emptyList(),
    val highlighted: Set<String> = emptySet(),
    val trip: TripState? = null,
    val searchResults: List<PlaceResult> = emptyList(),
    val searching: Boolean = false,
    val showTraffic: Boolean = true,
    /** TomTom key for the traffic-flow tile layer, or null when there is none. */
    val trafficTileKey: String? = null,
    val log: List<LogEntry> = emptyList(),
    val thinking: Boolean = false,
    val listening: Boolean = false,
    val partialTranscript: String = "",
    val speechInputAvailable: Boolean = true,
    val simulateHeading: Boolean = false,
    val notice: Notice? = null,
    val assistantLabel: String = "",
    val liveData: Boolean = false,
    val keys: Map<ApiService, KeyStatus> = emptyMap(),
    val assistantMode: AssistantMode = AssistantMode.ON_DEVICE,
    val localModelId: String = LocalModelSpec.GEMMA_4_E2B.id,
    val modelStatus: Map<String, ModelStatus> = emptyMap(),
    /** True while the on-device model is being loaded into memory. */
    val modelLoading: Boolean = false,
) {
    companion object {
        /** Fallback pose until the first GPS fix arrives (Union Square, San Francisco). */
        val DEFAULT_POSITION = GeoPoint(37.7880, -122.4075)
    }
}

class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private var firstFix: GeoPoint? = null
    private var declination = 0.0
    private var smoothedMagnetic: Double? = null
    /** Elapsed-realtime of the last heading taken from GPS course; compass is ignored while it is fresh. */
    private var lastCourseAt = 0L

    private val locationTracker = LocationTracker(app)
    private val headingSensor = HeadingSensor(app)
    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var logId = 0L

    private val speechOutput = SpeechOutput(app)

    private val keys = ApiKeyRepository(
        store = KeystoreSecretStore(app),
        buildDefaults = mapOf(
            ApiService.TOMTOM to BuildConfig.TOMTOM_API_KEY,
            ApiService.ANTHROPIC to BuildConfig.ANTHROPIC_API_KEY,
        ),
    )
    private val verifier: KeyVerifier = NetworkKeyVerifier()

    private val prefs = AppPreferences(app)
    private val modelStore = ModelStore(File(app.filesDir, "models"))
    private val modelCacheDir = File(app.cacheDir, "litertlm")
    private var downloadJob: Job? = null

    /** Map data, navigation and tools: everything that depends on the TomTom key. */
    private class Services(
        val data: MapDataSource,
        val navigation: NavigationManager,
        val tools: NavigationTools,
        val scope: CoroutineScope,
    )

    private lateinit var services: Services

    /** The assistant answering questions; rebuilt when the mode, model or keys change. */
    private var assistant: Assistant? = null
    private var assistantCleanup: () -> Unit = {}
    private var warmUpJob: Job? = null

    private val speechInput = SpeechInput(
        context = app,
        onPartial = { text -> _state.update { it.copy(partialTranscript = text) } },
        onFinal = { text ->
            _state.update { it.copy(partialTranscript = "") }
            ask(text)
        },
        onListeningChanged = { listening -> _state.update { it.copy(listening = listening) } },
        onError = { message -> _state.update { it.copy(notice = Notice(message), partialTranscript = "") } },
    )

    init {
        _state.update { it.copy(speechInputAvailable = speechInput.isAvailable) }
        _state.update { it.copy(assistantMode = prefs.assistantMode, localModelId = prefs.localModel.id) }
        rebuildServices()
        viewModelScope.launch { keys.revision.drop(1).collect { rebuildServices() } }
        viewModelScope.launch { keys.status.collect { status -> _state.update { it.copy(keys = status) } } }
        viewModelScope.launch {
            modelStore.status.collect { status ->
                val wasReady = _state.value.modelStatus[prefs.localModel.id] is ModelStatus.Ready
                _state.update { it.copy(modelStatus = status) }
                val isReady = status[prefs.localModel.id] is ModelStatus.Ready
                // The selected model just finished downloading (or was deleted): switch assistants.
                if (wasReady != isReady && prefs.assistantMode == AssistantMode.ON_DEVICE) rebuildAssistant()
            }
        }
    }

    private fun rebuildServices() {
        if (::services.isInitialized) {
            services.navigation.stop()
            services.scope.cancel()
        }
        val tomTomKey = keys.key(ApiService.TOMTOM)
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

        val data: MapDataSource = tomTomKey?.let { TomTomDataSource(it) }
            ?: MockDataSource { firstFix ?: NavUiState.DEFAULT_POSITION }
        val navigation = NavigationManager(
            data = data,
            telemetry = { _state.value.telemetry },
            scope = scope,
            speak = { text ->
                appendLog(Speaker.ASSISTANT, text)
                speechOutput.speak(text, interrupt = false)
            },
            travelHeading = { if (isCourseFresh()) _state.value.telemetry.pose.heading else null },
        )
        val tools = NavigationTools(telemetry = { _state.value.telemetry }, data = data, navigation = navigation)

        services = Services(data, navigation, tools, scope)
        scope.launch { navigation.trip.collect { trip -> _state.update { it.copy(trip = trip) } } }
        _state.update { it.copy(liveData = data.supportsRouting, trafficTileKey = tomTomKey, searchResults = emptyList()) }
        rebuildAssistant()
    }

    /** Pick the assistant for the current mode, falling back to the basic one when its requirement is missing. */
    private fun rebuildAssistant() {
        warmUpJob?.cancel()
        assistantCleanup()
        assistantCleanup = {}
        val tools = services.tools
        val basic = Agent(tools)

        val chosen: Assistant = when (prefs.assistantMode) {
            AssistantMode.ON_DEVICE -> {
                val spec = prefs.localModel
                modelStore.readyFile(spec)?.let { file ->
                    val chat = LiteRtChat(file, spec.name, modelCacheDir, LocalAssistant.SYSTEM_PROMPT, NavigationTools.DEFINITIONS)
                    LocalAssistant(chat, tools, fallback = basic, modelName = spec.name).also { local ->
                        assistantCleanup = { local.close() }
                        warmUp(local)
                    }
                } ?: basic
            }
            AssistantMode.CLAUDE -> keys.key(ApiService.ANTHROPIC)?.let { key ->
                val client = AnthropicOkHttpClient.builder().apiKey(key).build()
                assistantCleanup = { client.close() }
                ClaudeAssistant(tools, client)
            } ?: basic
            AssistantMode.BASIC -> basic
        }
        assistant = chosen
        _state.update { it.copy(assistantLabel = chosen.label) }
        refreshNotice()
    }

    /** Load the on-device model in the background so the first question doesn't wait for it. */
    private fun warmUp(local: LocalAssistant) {
        _state.update { it.copy(modelLoading = true) }
        warmUpJob = viewModelScope.launch {
            try {
                local.warmUp()
                _state.update { it.copy(assistantLabel = local.label) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showNotice("The on-device model couldn't load (${e.message}). Using the basic assistant for now.")
            } finally {
                _state.update { it.copy(modelLoading = false) }
            }
        }
    }

    /** One banner for whatever setup is missing, most important first. */
    private fun refreshNotice() {
        val mode = prefs.assistantMode
        val needs = buildList {
            if (mode == AssistantMode.ON_DEVICE && modelStore.readyFile(prefs.localModel) == null) {
                add("download the on-device AI model")
            }
            if (mode == AssistantMode.CLAUDE && keys.key(ApiService.ANTHROPIC) == null) add("add a Claude key")
            if (keys.key(ApiService.TOMTOM) == null) add("add a TomTom key for live maps")
        }
        _state.update {
            it.copy(
                notice = when {
                    needs.isNotEmpty() -> Notice(
                        "Setup: ${needs.joinToString(" and ")}. Tap to open settings.",
                        opensSettings = true,
                    )
                    !headingSensor.isAvailable -> Notice("No compass sensor; use Simulate heading.")
                    else -> it.notice?.takeUnless { n -> n.opensSettings }
                },
            )
        }
    }

    // --- Assistant & on-device model ---------------------------------------

    fun setAssistantMode(mode: AssistantMode) {
        if (mode == prefs.assistantMode) return
        prefs.assistantMode = mode
        _state.update { it.copy(assistantMode = mode) }
        rebuildAssistant()
    }

    fun selectLocalModel(spec: LocalModelSpec) {
        if (spec == prefs.localModel) return
        prefs.localModel = spec
        _state.update { it.copy(localModelId = spec.id) }
        if (prefs.assistantMode == AssistantMode.ON_DEVICE) rebuildAssistant()
    }

    fun downloadModel(spec: LocalModelSpec) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                modelStore.download(spec)
            } catch (e: IOException) {
                showNotice(e.message ?: "The model download failed.")
            }
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel(spec: LocalModelSpec) {
        if (spec == prefs.localModel && prefs.assistantMode == AssistantMode.ON_DEVICE) {
            // Release the model file before deleting it.
            assistantCleanup()
            assistantCleanup = {}
        }
        modelStore.delete(spec)
    }

    /** Install a model file the user picked (e.g. copied over USB) as [spec]. */
    fun importModel(spec: LocalModelSpec, uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it > 0 }
                val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open that file.")
                modelStore.import(spec, input, size)
            } catch (e: IOException) {
                showNotice(e.message ?: "Couldn't import the model file.")
            }
        }
    }

    // --- API keys ----------------------------------------------------------

    /** Verify [rawKey] with the service, then save it unless the service rejected it. */
    fun saveKey(service: ApiService, rawKey: String) {
        val key = rawKey.trim()
        service.formatProblem(key)?.let {
            keys.setVerification(service, Verification.Invalid(it))
            return
        }
        keys.setVerification(service, Verification.Checking)
        viewModelScope.launch {
            when (val result = verifier.verify(service, key)) {
                is Verification.Invalid -> keys.setVerification(service, result)
                else -> {
                    // Unreachable (offline etc.) still saves: the key may well be fine.
                    keys.save(service, key)
                    keys.setVerification(service, result)
                }
            }
        }
    }

    /** Re-check the key currently in use. */
    fun testKey(service: ApiService) {
        val key = keys.key(service) ?: return
        keys.setVerification(service, Verification.Checking)
        viewModelScope.launch { keys.setVerification(service, verifier.verify(service, key)) }
    }

    fun removeKey(service: ApiService) = keys.remove(service)

    /** Verify keys that haven't been checked this session (called when settings opens). */
    fun checkUnverifiedKeys() {
        for ((service, status) in _state.value.keys) {
            if (status.preview != null && status.verification == Verification.Unknown) testKey(service)
        }
    }

    // --- Telemetry ---------------------------------------------------------

    /** Start sensors; call when the UI becomes visible. Safe to call repeatedly. */
    fun startTracking() {
        if (headingJob == null && headingSensor.isAvailable) {
            headingJob = viewModelScope.launch {
                headingSensor.readings().collect { reading ->
                    if (_state.value.simulateHeading || isCourseFresh()) return@collect
                    smoothedMagnetic = HeadingMath.smooth(smoothedMagnetic, reading.magneticHeading)
                    setHeading(HeadingMath.trueHeading(smoothedMagnetic!!, declination), reading.source)
                }
            }
        }
        if (locationJob == null && locationTracker.hasPermission()) {
            locationJob = viewModelScope.launch {
                locationTracker.updates()
                    .catch { e -> _state.update { it.copy(notice = Notice("GPS unavailable: ${e.message}")) } }
                    .collect(::onLocation)
            }
        }
    }

    fun stopTracking() {
        headingJob?.cancel()
        locationJob?.cancel()
        headingJob = null
        locationJob = null
    }

    private fun onLocation(loc: Location) {
        val point = GeoPoint(loc.latitude, loc.longitude)
        if (firstFix == null) firstFix = point
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time,
        ).declination.toDouble()

        // While moving, the GPS course is a better "facing" than the compass (which points where the phone points).
        val useCourse = !_state.value.simulateHeading && loc.hasBearing() && loc.hasSpeed() && loc.speed >= COURSE_MIN_SPEED_MPS
        if (useCourse) lastCourseAt = SystemClock.elapsedRealtime()

        _state.update { s ->
            val pose = s.telemetry.pose.copy(
                lat = loc.latitude,
                lon = loc.longitude,
                heading = if (useCourse) loc.bearing.toDouble() else s.telemetry.pose.heading,
            )
            s.copy(
                telemetry = s.telemetry.copy(
                    pose = pose,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    hasFix = true,
                ),
                headingSource = if (useCourse) HeadingSource.GPS_COURSE else s.headingSource,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
            )
        }
        refreshMarkers()
        services.navigation.onLocation(point, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
    }

    private fun isCourseFresh() = SystemClock.elapsedRealtime() - lastCourseAt < COURSE_FRESH_MS

    fun setSimulateHeading(enabled: Boolean) {
        _state.update { it.copy(simulateHeading = enabled) }
        smoothedMagnetic = null
        setHeading(_state.value.telemetry.pose.heading, if (enabled) HeadingSource.MANUAL else HeadingSource.NONE)
    }

    fun setManualHeading(degrees: Double) {
        if (!_state.value.simulateHeading) return
        setHeading(SpatialEngine.normalizeAngle(degrees), HeadingSource.MANUAL)
    }

    private fun setHeading(heading: Double, source: HeadingSource) {
        _state.update { s ->
            s.copy(telemetry = s.telemetry.copy(pose = s.telemetry.pose.copy(heading = heading)), headingSource = source)
        }
        refreshMarkers()
    }

    /** Recompute marker directions (left/right/...) for the current pose. */
    private fun refreshMarkers() {
        _state.update { s ->
            if (s.markers.isEmpty()) return@update s
            val pose = s.telemetry.pose
            s.copy(
                markers = s.markers.map { r ->
                    val a = SpatialEngine.analyze(pose, r.place.location)
                    r.copy(distanceM = a.distance.roundToInt(), bearing = a.bearing, direction = a.direction)
                },
            )
        }
    }

    // --- Search & trips ----------------------------------------------------

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            when (val result = services.tools.searchPlaces(q)) {
                is ToolResult.Places -> _state.update {
                    it.copy(searchResults = result.results, searching = false, markers = result.results)
                }
                is ToolResult.Error -> _state.update { it.copy(searching = false, notice = Notice(result.message)) }
                else -> _state.update { it.copy(searching = false) }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = emptyList()) }

    fun navigateTo(place: Place) {
        _state.update { it.copy(searchResults = emptyList()) }
        viewModelScope.launch {
            try {
                val result = services.navigation.start(place, announce = true)
                if (result is ToolResult.Error) {
                    val needsKey = !services.data.supportsRouting
                    showNotice(result.message.replaceFirstChar { it.uppercaseChar() }, opensSettings = needsKey)
                }
            } catch (e: MapDataException) {
                showNotice(e.message ?: "Couldn't get a route.")
            }
        }
    }

    fun stopNavigation() {
        services.navigation.stop()
    }

    fun toggleTraffic() = _state.update { it.copy(showTraffic = !it.showTraffic) }

    // --- Assistant ---------------------------------------------------------

    fun ask(utterance: String) {
        val text = utterance.trim()
        if (text.isEmpty()) return
        appendLog(Speaker.USER, text)
        _state.update { it.copy(thinking = true) }
        val assistant = assistant ?: return
        viewModelScope.launch {
            val result = assistant.respond(text)
            for (step in result.trace) {
                val args = step.input.entries.joinToString(", ") { (k, v) -> "\"$k\": \"$v\"" }
                appendLog(Speaker.TOOL, "${step.tool}({$args})")
                when (val output = step.output) {
                    is ToolResult.Surroundings -> _state.update { s ->
                        s.copy(markers = output.pois, highlighted = output.pois.map { it.place.id }.toSet())
                    }
                    is ToolResult.Places -> _state.update { s ->
                        s.copy(markers = output.results, highlighted = output.results.map { it.place.id }.toSet())
                    }
                    else -> Unit
                }
            }
            _state.update { it.copy(thinking = false) }
            appendLog(Speaker.ASSISTANT, result.text)
            speechOutput.speak(result.text, interrupt = true)
        }
    }

    /** Tap to talk: start listening, or — if already listening — finish now and send what was heard. */
    fun toggleListening() {
        if (speechInput.isListening) {
            speechInput.stop()
            return
        }
        speechOutput.stop() // don't transcribe our own voice
        _state.update { it.copy(notice = it.notice?.takeIf { n -> n.opensSettings }, partialTranscript = "") }
        speechInput.start()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun showNotice(message: String, opensSettings: Boolean = false) =
        _state.update { it.copy(notice = Notice(message, opensSettings)) }

    private fun appendLog(speaker: Speaker, text: String) {
        _state.update { it.copy(log = (it.log + LogEntry(logId++, speaker, text)).takeLast(MAX_LOG)) }
    }

    override fun onCleared() {
        services.navigation.stop()
        assistantCleanup()
        speechInput.destroy()
        speechOutput.shutdown()
    }

    private companion object {
        const val COURSE_MIN_SPEED_MPS = 3f
        const val COURSE_FRESH_MS = 3_000L
        const val MAX_LOG = 60
    }
}
