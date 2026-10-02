package com.echobharat.schema

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted voice settings: which languages the user has enabled, which one is active,
 * whether incoming speech is read aloud, and whether per-message timings are shown.
 *
 * Language choice is multi-select and sticky. After first setup the app simply works
 * with what is installed — nothing prompts on launch; a prompt appears only when the
 * user explicitly adds a language that is not present yet.
 */
class VoicePreferences private constructor(context: Context) {

    companion object {
        private const val TAG = "VoicePreferences"
        private const val PREFS = "echobharat_voice_prefs"

        private const val KEY_ENABLED = "enabled_languages"
        private const val KEY_ACTIVE = "active_language"
        private const val KEY_AUTO_SPEAK = "auto_speak"
        private const val KEY_SHOW_TIMINGS = "show_timings"

        /** Bundled out of the box, so the app is useful before any download. */
        val DEFAULT_LANGUAGES = setOf("hi", "en")

        @Volatile
        private var instance: VoicePreferences? = null

        fun getInstance(context: Context): VoicePreferences =
            instance ?: synchronized(this) {
                instance ?: VoicePreferences(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabledLanguages = MutableStateFlow(
        prefs.getStringSet(KEY_ENABLED, null)?.toSet()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LANGUAGES
    )
    val enabledLanguages: StateFlow<Set<String>> = _enabledLanguages.asStateFlow()

    private val _activeLanguage = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null) ?: DEFAULT_LANGUAGES.first()
    )
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()


    private val _autoSpeak = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SPEAK, true))
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    /**
     * Shows recognition, translation and synthesis timings under each message — the
     * on-device measurements the README refuses to quote until someone has taken them.
     */
    private val _showTimings = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TIMINGS, false))
    val showTimings: StateFlow<Boolean> = _showTimings.asStateFlow()

    fun enable(lang: String) {
        val next = _enabledLanguages.value + lang
        persistLanguages(next)
        Log.i(TAG, "Enabled '$lang'; now ${next.sorted()}")
    }

    /** Removing the active language moves the cursor to another enabled one. */
    fun disable(lang: String) {
        val next = _enabledLanguages.value - lang
        if (next.isEmpty()) {
            Log.w(TAG, "Refusing to disable '$lang': at least one language must stay enabled")
            return
        }
        persistLanguages(next)
        if (_activeLanguage.value == lang) setActive(next.first())
        Log.i(TAG, "Disabled '$lang'; now ${next.sorted()}")
    }

    fun setActive(lang: String) {
        if (lang !in _enabledLanguages.value) {
            Log.w(TAG, "Activating '$lang' which is not enabled; enabling it too")
            persistLanguages(_enabledLanguages.value + lang)
        }
        _activeLanguage.value = lang
        prefs.edit().putString(KEY_ACTIVE, lang).apply()
        Log.i(TAG, "Active language -> $lang")
    }

    fun setAutoSpeak(enabled: Boolean) {
        _autoSpeak.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply()
    }

    fun setShowTimings(enabled: Boolean) {
        _showTimings.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_TIMINGS, enabled).apply()
    }

    private fun persistLanguages(next: Set<String>) {
        _enabledLanguages.value = next
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()
    }
}
