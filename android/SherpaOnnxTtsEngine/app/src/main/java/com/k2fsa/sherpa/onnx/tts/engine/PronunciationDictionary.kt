package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

object PronunciationDictionary {
    private const val TAG = "PronunciationDictionary"
    private const val CONFIG_FILE = "pronunciation_dictionary.json"
    private const val ASSET_PATH = "pronunciation_dictionary.json"
    private const val CONFIG_VERSION = 1

    private data class Rule(
        val language: String,
        val source: String,
        val replacement: String,
        val enabled: Boolean
    )

    @Volatile
    private var cachedRules: List<Rule>? = null

    @Volatile
    private var cachedConfigFile: File? = null

    private fun getConfigFile(context: Context): File =
        File(context.applicationContext.filesDir, CONFIG_FILE)

    @Synchronized
    private fun ensureConfigFile(context: Context): File? {
        val appContext = context.applicationContext
        val destination = getConfigFile(appContext)

        if (destination.isFile) return destination

        return try {
            val contents = appContext.assets.open(ASSET_PATH).use {
                it.readBytes()
            }

            parseRules(contents.toString(Charsets.UTF_8))

            val temporary = File(
                appContext.filesDir,
                "$CONFIG_FILE.tmp"
            )

            try {
                temporary.outputStream().use { output ->
                    output.write(contents)
                    output.fd.sync()
                }

                if (!temporary.renameTo(destination) &&
                    !destination.isFile
                ) {
                    throw IOException("Unable to install $CONFIG_FILE")
                }
            } finally {
                temporary.delete()
            }

            destination.takeIf { it.isFile }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize pronunciation dictionary", e)
            null
        }
    }

    private fun parseRules(contents: String): List<Rule> {
        val root = JSONObject(contents)

        require(root.optInt("version", -1) == CONFIG_VERSION) {
            "Unsupported dictionary version"
        }

        val array = root.optJSONArray("rules")
            ?: throw IllegalArgumentException("Missing rules array")

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val language = item.optString("language", "")
                    .trim()
                    .lowercase(Locale.ROOT)
                val source = item.optString("source", "")
                val replacement = item.optString("replacement", "")
                val enabled = item.optBoolean("enabled", false)

                if (language.isNotEmpty() &&
                    source.isNotEmpty() &&
                    enabled
                ) {
                    add(
                        Rule(
                            language = language,
                            source = source,
                            replacement = replacement,
                            enabled = true
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    private fun rules(context: Context): List<Rule> {
        val appContext = context.applicationContext
        val configFile = getConfigFile(appContext)

        if (cachedConfigFile == configFile && cachedRules != null) {
            return cachedRules!!
        }

        val file = ensureConfigFile(appContext)
        val loadedRules = try {
            if (file == null) {
                emptyList()
            } else {
                parseRules(file.readText(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read pronunciation dictionary", e)
            emptyList()
        }

        cachedConfigFile = configFile
        cachedRules = loadedRules
        Log.i(TAG, "Loaded ${loadedRules.size} pronunciation rules")

        return loadedRules
    }

    fun process(
        context: Context,
        language: String,
        text: String
    ): String {
        if (text.isEmpty()) return text

        val languageCode = language.trim().lowercase(Locale.ROOT)
        if (languageCode.isEmpty()) return text

        var result = text

        rules(context)
            .asSequence()
            .filter { it.language == languageCode }
            .sortedByDescending { it.source.length }
            .forEach { rule ->
                val pattern = Regex(
                    "(?<![\\p{L}\\p{N}_])" +
                        Regex.escape(rule.source) +
                        "(?![\\p{L}\\p{N}_])",
                    RegexOption.IGNORE_CASE
                )

                result = pattern.replace(result) { match ->
                    rule.replacement
                }
            }

        return result
    }
}
