package com.opticalcenter.spatialnav.settings

import android.content.Context
import com.opticalcenter.spatialnav.local.LocalModelSpec

/** Which assistant answers questions. */
enum class AssistantMode(val title: String, val description: String) {
    ON_DEVICE("On-device AI", "Runs Gemma on your phone. Private and works offline; needs a one-time model download."),
    CLAUDE("Claude (cloud)", "Most capable. Needs internet and a Claude API key."),
    BASIC("Basic offline", "Keyword matching only. Tiny and instant, but understands fewer phrasings."),
}

/** Non-secret app settings. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var assistantMode: AssistantMode
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() }
            ?: AssistantMode.ON_DEVICE
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var localModel: LocalModelSpec
        get() = LocalModelSpec.byId(prefs.getString(KEY_MODEL, null)) ?: LocalModelSpec.GEMMA_4_E2B
        set(value) = prefs.edit().putString(KEY_MODEL, value.id).apply()

    private companion object {
        const val KEY_MODE = "assistant_mode"
        const val KEY_MODEL = "local_model"
    }
}
