import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale
import kotlin.math.round
import com.k2fsa.sherpa.onnx.tts.engine.LocaleCatalogue
import com.k2fsa.sherpa.onnx.tts.engine.SupertonicLanguageManifest

class PreferenceHelper(context: Context) {

    private val PREFS_NAME = "com.k2fsa.sherpa.onnx.tts.engine"
    private val SPEED_KEY = "speed"
    private val SID_KEY = "speaker_id"
    private val LANGUAGE_KEY = "language"

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "PreferenceHelper"
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 5.0f


        private val DEFAULT_CONFIGURATION = VoiceConfiguration()

        private fun configurationPrefix(language: String, speakerId: Int): String =
            "voice_config_${language}_speaker_${speakerId}_"
    }

    fun setSpeed(value: Float) {
        sharedPreferences.edit().putFloat(SPEED_KEY, value).apply()
    }

    private fun localeEnabledKey(localeTag: String): String =
        "locale_enabled_${localeTag.lowercase(Locale.ROOT)}"

    private fun voiceEnabledKey(language: String, speakerId: Int): String =
        "voice_enabled_${language}_speaker_${speakerId}"

    fun isLocaleEnabled(localeTag: String, defaultEnabled: Boolean = true): Boolean {
        require(LocaleCatalogue.entries(appContext).any {
            it.localeTag.equals(localeTag, ignoreCase = true)
        }) { "Unsupported locale: $localeTag" }

        val key = localeEnabledKey(localeTag)
        if (sharedPreferences.contains(key)) {
            return sharedPreferences.getBoolean(key, defaultEnabled)
        }

        val legacyTag = when (localeTag.lowercase(Locale.ROOT)) {
            "en" -> "en-US"
            "bg" -> "bg-BG"
            else -> null
        }

        if (legacyTag != null) {
            val legacyKey = localeEnabledKey(legacyTag)
            if (sharedPreferences.contains(legacyKey)) {
                val legacyValue = sharedPreferences.getBoolean(legacyKey, defaultEnabled)
                sharedPreferences.edit().putBoolean(key, legacyValue).apply()
                return legacyValue
            }
        }

        return defaultEnabled
    }

    fun setLocaleEnabled(localeTag: String, enabled: Boolean) {
        require(LocaleCatalogue.entries(appContext).any {
            it.localeTag.equals(localeTag, ignoreCase = true)
        }) { "Unsupported locale: $localeTag" }

        sharedPreferences.edit()
            .putBoolean(localeEnabledKey(localeTag), enabled)
            .apply()
    }

    fun isVoiceEnabled(
        language: String,
        speakerId: Int,
        defaultEnabled: Boolean = true
    ): Boolean {
        requireValidIdentity(language, speakerId)
        return sharedPreferences.getBoolean(
            voiceEnabledKey(language, speakerId),
            defaultEnabled
        )
    }

    fun setVoiceEnabled(language: String, speakerId: Int, enabled: Boolean) {
        requireValidIdentity(language, speakerId)
        sharedPreferences.edit()
            .putBoolean(voiceEnabledKey(language, speakerId), enabled)
            .apply()
    }

    fun getSpeed(): Float = sharedPreferences.getFloat(SPEED_KEY, 1.0f)

    fun setSid(value: Int) {
        sharedPreferences.edit().putInt(SID_KEY, value).apply()
    }

    fun getSid(): Int = sharedPreferences.getInt(SID_KEY, 0)

    fun setLanguage(value: String) {
        require(SupertonicLanguageManifest.isSelectable(appContext, value)) {
            "Unsupported Supertonic language: $value"
        }
        sharedPreferences.edit().putString(LANGUAGE_KEY, value).apply()
    }

    fun getLanguage(): String {
        val saved = sharedPreferences.getString(LANGUAGE_KEY, "en") ?: "en"
        return if (SupertonicLanguageManifest.isSelectable(appContext, saved)) {
            saved
        } else {
            "en"
        }
    }

    fun getVoiceConfiguration(language: String, speakerId: Int): VoiceConfiguration {
        requireValidIdentity(language, speakerId)
        val prefix = configurationPrefix(language, speakerId)

        val minimum = readFloat(prefix + "minimum_speed", DEFAULT_CONFIGURATION.minimumSpeed)
            .validatedFloat("minimum_speed", MIN_SPEED, MAX_SPEED, DEFAULT_CONFIGURATION.minimumSpeed)

        val maximum = readFloat(prefix + "maximum_speed", DEFAULT_CONFIGURATION.maximumSpeed)
            .validatedFloat("maximum_speed", MIN_SPEED, MAX_SPEED, DEFAULT_CONFIGURATION.maximumSpeed)
            .let { if (it < minimum) {
                Log.w(TAG, "Saved maximum speed is below minimum for $language speaker $speakerId; using default maximum.")
                maxOf(DEFAULT_CONFIGURATION.maximumSpeed, minimum)
            } else it }

        val reference = readFloat(prefix + "reference_speed", DEFAULT_CONFIGURATION.referenceSpeed)
            .validatedFloat("reference_speed", minimum, maximum, DEFAULT_CONFIGURATION.referenceSpeed)
            .let { (round(it / 0.05f) * 0.05f).coerceIn(minimum, maximum) }

        val steps = readInt(prefix + "denoising_steps", DEFAULT_CONFIGURATION.denoisingSteps)
            .validatedInt("denoising_steps", 1, 100, DEFAULT_CONFIGURATION.denoisingSteps)

        val pause = readFloat(prefix + "pause_duration", DEFAULT_CONFIGURATION.pauseDuration)
            .validatedFloat("pause_duration", 0.0f, 5.0f, DEFAULT_CONFIGURATION.pauseDuration)

        val maxChunk = readInt(prefix + "maximum_chunk_length", DEFAULT_CONFIGURATION.maximumChunkLength)
            .validatedInt("maximum_chunk_length", 1, 10000, DEFAULT_CONFIGURATION.maximumChunkLength)

        val seed = readInt(prefix + "random_seed", DEFAULT_CONFIGURATION.randomSeed)
            .let {
                if (it < -1) {
                    Log.w(TAG, "Invalid random seed for $language speaker $speakerId; using default.")
                    DEFAULT_CONFIGURATION.randomSeed
                } else it
            }

        return VoiceConfiguration(
            referenceSpeed = reference,
            minimumSpeed = minimum,
            maximumSpeed = maximum,
            denoisingSteps = steps,
            pauseDuration = pause,
            maximumChunkLength = maxChunk,
            randomSeed = seed
        )
    }

    fun setVoiceConfiguration(
        language: String,
        speakerId: Int,
        configuration: VoiceConfiguration
    ) {
        requireValidIdentity(language, speakerId)
        val validated = validateConfiguration(configuration)
        val prefix = configurationPrefix(language, speakerId)

        sharedPreferences.edit()
            .putFloat(prefix + "reference_speed", validated.referenceSpeed)
            .putFloat(prefix + "minimum_speed", validated.minimumSpeed)
            .putFloat(prefix + "maximum_speed", validated.maximumSpeed)
            .putInt(prefix + "denoising_steps", validated.denoisingSteps)
            .putFloat(prefix + "pause_duration", validated.pauseDuration)
            .putInt(prefix + "maximum_chunk_length", validated.maximumChunkLength)
            .putInt(prefix + "random_seed", validated.randomSeed)
            .apply()
    }

    fun buildSupertonicExtra(
        language: String,
        speakerId: Int,
        speed: Float
    ): Map<String, String> {
        val configuration = getVoiceConfiguration(language, speakerId)

        val safeSpeed = if (speed.isFinite() && speed > 0.0f) {
            speed
        } else {
            Log.w(TAG, "Invalid synthesis speed $speed; using reference speed.")
            configuration.referenceSpeed
        }

        return mapOf(
            "lang" to language,
            "speed" to safeSpeed.toString(),
            "num_steps" to configuration.denoisingSteps.toString(),
            "silence_duration" to configuration.pauseDuration.toString(),
            "max_len" to configuration.maximumChunkLength.toString(),
            "seed" to configuration.randomSeed.toString()
        )
    }

    private fun validateConfiguration(configuration: VoiceConfiguration): VoiceConfiguration {
        val minimum = configuration.minimumSpeed.validatedFloat(
            "minimum_speed", MIN_SPEED, MAX_SPEED, DEFAULT_CONFIGURATION.minimumSpeed
        )
        val maximum = configuration.maximumSpeed.validatedFloat(
            "maximum_speed", minimum, MAX_SPEED, maxOf(DEFAULT_CONFIGURATION.maximumSpeed, minimum)
        )
        val reference = configuration.referenceSpeed.validatedFloat(
            "reference_speed", minimum, maximum, DEFAULT_CONFIGURATION.referenceSpeed.coerceIn(minimum, maximum)
        )
        val quantizedReference = (round(reference / 0.05f) * 0.05f).coerceIn(minimum, maximum)

        return VoiceConfiguration(
            referenceSpeed = quantizedReference,
            minimumSpeed = minimum,
            maximumSpeed = maximum,
            denoisingSteps = configuration.denoisingSteps.validatedInt(
                "denoising_steps", 1, 100, DEFAULT_CONFIGURATION.denoisingSteps
            ),
            pauseDuration = configuration.pauseDuration.validatedFloat(
                "pause_duration", 0.0f, 5.0f, DEFAULT_CONFIGURATION.pauseDuration
            ),
            maximumChunkLength = configuration.maximumChunkLength.validatedInt(
                "maximum_chunk_length", 1, 10000, DEFAULT_CONFIGURATION.maximumChunkLength
            ),
            randomSeed = if (configuration.randomSeed >= -1) configuration.randomSeed else {
                Log.w(TAG, "Invalid random seed; using default.")
                DEFAULT_CONFIGURATION.randomSeed
            }
        )
    }

    private fun requireValidIdentity(language: String, speakerId: Int) {
        require(SupertonicLanguageManifest.isSelectable(appContext, language)) {
            "Unsupported Supertonic language: $language"
        }
        require(speakerId >= 0) {
            "Speaker ID must not be negative: $speakerId"
        }
    }

    private fun readFloat(key: String, defaultValue: Float): Float =
        try {
            sharedPreferences.getFloat(key, defaultValue)
        } catch (exception: ClassCastException) {
            Log.w(TAG, "Invalid stored type for $key; using default.", exception)
            defaultValue
        }

    private fun readInt(key: String, defaultValue: Int): Int =
        try {
            sharedPreferences.getInt(key, defaultValue)
        } catch (exception: ClassCastException) {
            Log.w(TAG, "Invalid stored type for $key; using default.", exception)
            defaultValue
        }

    private fun Float.validatedFloat(
        name: String,
        minimum: Float,
        maximum: Float,
        defaultValue: Float
    ): Float {
        if (!isFinite() || this < minimum || this > maximum) {
            Log.w(TAG, "Invalid $name value $this; using default $defaultValue.")
            return defaultValue.coerceIn(minimum, maximum)
        }
        return this
    }

    private fun Int.validatedInt(
        name: String,
        minimum: Int,
        maximum: Int,
        defaultValue: Int
    ): Int {
        if (this < minimum || this > maximum) {
            Log.w(TAG, "Invalid $name value $this; using default $defaultValue.")
            return defaultValue.coerceIn(minimum, maximum)
        }
        return this
    }
}
