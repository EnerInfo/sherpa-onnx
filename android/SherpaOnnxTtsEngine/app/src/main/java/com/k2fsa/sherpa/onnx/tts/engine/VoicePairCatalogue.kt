package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class VoicePair(
    val language: LocaleCatalogueEntry,
    val speakerId: Int
) {
    val languageCode: String
        get() = language.supertonicLanguage

    val voiceName: String
        get() = "supertonic-$languageCode-speaker-$speakerId"
}

object VoicePairCatalogue {

    private const val TAG = "VoicePairCatalogue"
    private const val CONFIG_FILE = "voice_pairs.json"
    private const val CONFIG_VERSION = 1
    private const val ASSET_PATH = "voice_pairs.json"

    private data class PairKey(
        val language: String,
        val speakerId: Int
    )

    @Volatile
    private var cachedPairs: Set<PairKey>? = null

    @Volatile
    private var cachedConfigFile: File? = null

    private fun getConfigFile(context: Context): File =
        File(context.applicationContext.filesDir, CONFIG_FILE)

    /**
     * Creates the persistent configuration from the bundled asset
     * only when the persistent file does not already exist.
     *
     * An existing configuration is never overwritten automatically.
     */
    @Synchronized
    private fun ensureConfigFile(context: Context): File? {
        val appContext = context.applicationContext
        val destination = getConfigFile(appContext)

        if (destination.isFile) {
            return destination
        }

        return try {
            val contents = appContext.assets.open(ASSET_PATH).use {
                it.readBytes()
            }

            // Validate the asset before installing it.
            parseConfig(contents.toString(Charsets.UTF_8))

            val temporary = File(
                appContext.filesDir,
                "$CONFIG_FILE.tmp"
            )

            try {
                temporary.outputStream().use { output ->
                    output.write(contents)
                    output.fd.sync()
                }

                if (!temporary.renameTo(destination)) {
                    // Another initializer may have created the file.
                    if (!destination.isFile) {
                        throw IOException(
                            "Unable to install $CONFIG_FILE"
                        )
                    }
                }
            } finally {
                temporary.delete()
            }

            destination.takeIf { it.isFile }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize voice configuration", e)
            null
        }
    }

    /**
     * Parses the JSON configuration.
     *
     * Invalid entries are ignored. An invalid document or unsupported
     * configuration version causes the entire configuration to be rejected.
     */
    private fun parseConfig(contents: String): Set<PairKey> {
        val root = JSONObject(contents)

        require(root.optInt("version", -1) == CONFIG_VERSION) {
            "Unsupported configuration version"
        }

        val array = root.optJSONArray("enabledPairs")
            ?: throw IllegalArgumentException(
                "Missing enabledPairs array"
            )

        val result = mutableSetOf<PairKey>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue

            val language = item.optString("language", "")
                .trim()
                .lowercase()

            val speakerId = item.optInt("speakerId", -1)

            if (language.isNotEmpty() && speakerId >= 0) {
                result.add(PairKey(language, speakerId))
            }
        }

        return result
    }

    /**
     * Loads the enabled-pair list once per process.
     * If loading fails, no pairs are enabled.
     */
    @Synchronized
    private fun enabledPairs(context: Context): Set<PairKey> {
        val appContext = context.applicationContext
        val configFile = getConfigFile(appContext)

        if (cachedConfigFile == configFile && cachedPairs != null) {
            return cachedPairs!!
        }

        val file = ensureConfigFile(appContext)
        val pairs = try {
            if (file == null) {
                emptySet()
            } else {
                parseConfig(file.readText(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read voice configuration", e)
            emptySet()
        }

        cachedConfigFile = configFile
        cachedPairs = pairs

        Log.i(TAG, "Loaded ${pairs.size} enabled voice pairs")
        return pairs
    }

    /**
     * Returns only pairs that are explicitly enabled and supported
     * by the current language catalogue and runtime speaker count.
     */
    fun entries(
        context: Context,
        speakerCount: Int
    ): List<VoicePair> {
        if (speakerCount <= 0) return emptyList()

        val enabled = enabledPairs(context)

        if (enabled.isEmpty()) return emptyList()

        return LocaleCatalogue.entries(context).flatMap { language ->
            val languageCode = language.supertonicLanguage.lowercase()

            (0 until speakerCount)
                .filter { speakerId ->
                    PairKey(languageCode, speakerId) in enabled
                }
                .map { speakerId ->
                    VoicePair(
                        language = language,
                        speakerId = speakerId
                    )
                }
        }
    }
}
