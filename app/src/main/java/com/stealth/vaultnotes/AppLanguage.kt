package com.stealth.vaultnotes

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
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

    fun updateLauncherAlias(context: Context, language: String) {
        val packageManager = context.packageManager
        val arabicAlias = ComponentName(context, "${context.packageName}.MainActivityArabic")
        val englishAlias = ComponentName(context, "${context.packageName}.MainActivityEnglish")
        val useArabic = language != ENGLISH

        val destinationAlias = if (useArabic) arabicAlias else englishAlias
        val currentAlias = if (useArabic) englishAlias else arabicAlias
        val flags = PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: update both launcher entries atomically.
                packageManager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(
                            destinationAlias,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            flags
                        ),
                        PackageManager.ComponentEnabledSetting(
                            currentAlias,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            flags
                        )
                    )
                )
            } else {
                // Older Android versions: enable the destination before disabling
                // the previous entry, with synchronous package-manager updates.
                packageManager.setComponentEnabledSetting(
                    destinationAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    flags
                )
                packageManager.setComponentEnabledSetting(
                    currentAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    flags
                )
            }

            Log.i(
                "AppLanguage",
                "Launcher alias updated: language=" + language +
                    ", Arabic=" + packageManager.getComponentEnabledSetting(arabicAlias) +
                    ", English=" + packageManager.getComponentEnabledSetting(englishAlias)
            )
        } catch (error: Exception) {
            // A launcher-label update must never crash or interrupt the notes app.
            Log.e("AppLanguage", "Could not update launcher alias for " + language, error)
        }
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
