package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import java.util.Locale
import java.util.MissingResourceException

/**
 * Application locale metadata generated from the model's supported language
 * manifest. The manifest supplies language codes, not country-specific locales.
 */
data class LocaleCatalogueEntry(
    val localeTag: String,
    val iso6391Language: String,
    val iso6392Language: String,
    val iso6393Language: String,
    val iso3166Alpha2: String,
    val iso3166Alpha3: String,
    val iso3166Numeric: String,
    val supertonicLanguage: String,
    val displayName: String,
    val enabled: Boolean = true
)

object LocaleCatalogue {
    @Volatile
    private var cachedEntries: List<LocaleCatalogueEntry>? = null

    fun entries(context: Context): List<LocaleCatalogueEntry> {
        cachedEntries?.let { return it }

        synchronized(this) {
            cachedEntries?.let { return it }

            val generated = SupertonicLanguageManifest
                .localeLanguages(context)
                .mapNotNull { code ->
                    createEntry(code)
                }

            cachedEntries = generated
            return generated
        }
    }

    private fun createEntry(languageCode: String): LocaleCatalogueEntry? {
        val locale = Locale.forLanguageTag(languageCode)
        if (locale.language.isBlank() || locale.language == "und") {
            return null
        }

        val iso3 = try {
            locale.getISO3Language()
        } catch (_: MissingResourceException) {
            return null
        }

        return LocaleCatalogueEntry(
            localeTag = languageCode,
            iso6391Language = languageCode,
            iso6392Language = iso3,
            iso6393Language = iso3,
            iso3166Alpha2 = "",
            iso3166Alpha3 = "",
            iso3166Numeric = "",
            supertonicLanguage = languageCode,
            displayName = locale.getDisplayLanguage(Locale.ENGLISH)
                .ifBlank { languageCode }
        )
    }

    fun findByTag(context: Context, localeTag: String): LocaleCatalogueEntry? =
        entries(context).firstOrNull {
            it.localeTag.equals(localeTag, ignoreCase = true)
        }

    fun findBySupertonicLanguage(
        context: Context,
        language: String
    ): LocaleCatalogueEntry? =
        entries(context).firstOrNull {
            it.supertonicLanguage.equals(language, ignoreCase = true)
        }

    fun enabledEntries(context: Context): List<LocaleCatalogueEntry> =
        entries(context).filter { it.enabled }
}
