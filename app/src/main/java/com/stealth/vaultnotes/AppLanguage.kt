package com.stealth.vaultnotes

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Stores the user's in-app language and applies its locale before Activity creation.
 * Arabic is the default for new installations; an explicit user choice is preserved.
 */
object AppLanguage {
    const val ARABIC = "ar"
    const val ENGLISH = "en"

    private const val PREFERENCES_NAME = "app_language_preferences"
    private const val LANGUAGE_KEY = "selected_language"

    fun getLanguage(context: Context): String {
        val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, ARABIC)
        return if (saved == ENGLISH) ENGLISH else ARABIC
    }

    fun text(context: Context, english: String, arabic: String): String =
        if (getLanguage(context) == ARABIC) arabic else english

    fun setLanguage(context: Context, language: String) {
        val normalized = if (language == ENGLISH) ENGLISH else ARABIC
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, normalized)
            .apply()
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(getLanguage(context))
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }
}
