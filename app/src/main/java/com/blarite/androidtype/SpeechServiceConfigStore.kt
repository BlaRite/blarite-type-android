package com.blarite.androidtype

import android.content.Context

// This enum answers a simple UI question: where did the active Soniox key come from?
// The setup screen uses it to explain whether the app is using a saved key,
// a bundled fallback key, or no key at all.
enum class SpeechServiceApiKeySource {
    SAVED,
    BUNDLED,
    MISSING
}

// This object is a tiny wrapper around SharedPreferences.
// SharedPreferences is Android's simple built-in key/value storage for small settings.
// We keep all Soniox-related app settings here so both the activity and IME can read them.
object SpeechServiceConfigStore {
    // This is the name of the SharedPreferences file on disk.
    private const val preferencesName = "soniox_voice_keyboard"
    // These are the individual keys inside that preferences file.
    private const val apiKeyPreference = "soniox_api_key"
    private const val languageHintsPreference = "soniox_language_hints"

    // Read the user-saved API key.
    // `trim()` removes accidental spaces that may have been pasted before or after the key.
    fun getStoredApiKey(context: Context): String {
        return context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(apiKeyPreference, "")
            .orEmpty()
            .trim()
    }

    // Save or replace the API key entered by the user.
    // `apply()` writes asynchronously, which is fine for lightweight settings like this.
    fun saveApiKey(context: Context, apiKey: String) {
        context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(apiKeyPreference, apiKey.trim())
            .apply()
    }

    // Remove the saved API key so the app falls back to the bundled BuildConfig key, if any.
    fun clearApiKey(context: Context) {
        context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(apiKeyPreference)
            .apply()
    }

    // Return the raw language-hints text exactly as the user typed it, after trimming edges.
    // We keep a text version because the settings screen edits this value as a single text field.
    fun getStoredLanguageHintsText(context: Context): String {
        return context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(languageHintsPreference, "")
            .orEmpty()
            .trim()
    }

    // Save the comma-separated language hints string from the UI.
    fun saveLanguageHints(context: Context, languageHints: String) {
        context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(languageHintsPreference, languageHints.trim())
            .apply()
    }

    // Remove any saved language hints and return to "no hints" behavior.
    fun clearLanguageHints(context: Context) {
        context
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(languageHintsPreference)
            .apply()
    }

    // Convert the saved comma-separated text into the list format expected by the Soniox client.
    // Example: "en, es" becomes listOf("en", "es").
    fun getLanguageHints(context: Context): List<String> {
        return getStoredLanguageHintsText(context)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    // Decide which API key should actually be used at runtime.
    // The saved key wins because the user may want to override the bundled fallback.
    fun getEffectiveApiKey(context: Context): String {
        val storedApiKey = getStoredApiKey(context)
        return if (storedApiKey.isNotBlank()) {
            storedApiKey
        } else {
            BuildConfig.SONIOX_API_KEY.trim()
        }
    }

    // Report the source of the currently active key so the UI can explain it clearly.
    fun getApiKeySource(context: Context): SpeechServiceApiKeySource {
        return when {
            getStoredApiKey(context).isNotBlank() -> SpeechServiceApiKeySource.SAVED
            BuildConfig.SONIOX_API_KEY.isNotBlank() -> SpeechServiceApiKeySource.BUNDLED
            else -> SpeechServiceApiKeySource.MISSING
        }
    }
}
