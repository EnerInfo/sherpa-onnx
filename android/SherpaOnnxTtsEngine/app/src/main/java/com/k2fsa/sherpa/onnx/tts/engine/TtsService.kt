package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper

import android.media.AudioFormat
import com.k2fsa.sherpa.onnx.GenerationConfig
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import android.util.Log

/*
https://developer.android.com/reference/java/util/Locale#getISO3Language()
https://developer.android.com/reference/java/util/Locale#getISO3Country()

eng, USA,
eng, USA, POSIX
eng,
eng, GBR
afr,
afr, NAM
afr, ZAF
agq
agq, CMR
aka,
aka, GHA
amh,
amh, ETH
ara,
ara, 001
ara, ARE
ara, BHR,
deu
deu, AUT
deu, BEL
deu, CHE
deu, ITA
deu, ITA
deu, LIE
deu, LUX
spa,
spa, 419
spa, ARG,
spa, BRA
fra,
fra, BEL,
fra, FRA,

E  Failed to check TTS data, no activity found for Intent
{ act=android.speech.tts.engine.CHECK_TTS_DATA pkg=com.k2fsa.sherpa.chapter5 })

E Failed to get default language from engine com.k2fsa.sherpa.chapter5
Engine failed voice data integrity check (null return)com.k2fsa.sherpa.chapter5
Failed to get default language from engine com.k2fsa.sherpa.chapter5

*/

class TtsService : TextToSpeechService() {
    private data class VoiceSelection(
        val languageCode: String,
        val supertonicCode: String,
        val speakerId: Int
    )

    private var selectedVoice: VoiceSelection? = null

    private val preferenceHelper: PreferenceHelper by lazy {
        PreferenceHelper(application)
    }

    private val supportedLanguages: List<LocaleCatalogueEntry>
        get() = LocaleCatalogue.entries(application)

    private fun findByAndroidLanguage(languageCode: String): LocaleCatalogueEntry? =
        supportedLanguages.firstOrNull {
            it.iso6391Language.equals(languageCode, ignoreCase = true) ||
                it.iso6392Language.equals(languageCode, ignoreCase = true) ||
                it.iso6393Language.equals(languageCode, ignoreCase = true)
        }

    private fun getSpeakerCount(): Int {
        return try {
            if (TtsEngine.tts == null) TtsEngine.createTts(application)
            TtsEngine.tts?.numSpeakers() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize TTS for voice discovery", e)
            0
        }
    }

    private fun voiceName(languageCode: String, speakerId: Int) =
        "supertonic-$languageCode-speaker-$speakerId"

    private fun parseVoiceName(name: String): VoiceSelection? {
        val parts = name.split("-")
        if (parts.size != 4 || parts[0] != "supertonic" || parts[2] != "speaker") {
            return null
        }

        val language = LocaleCatalogue.entries(application).firstOrNull {
            it.supertonicLanguage == parts[1]
        } ?: return null

        val sid = parts[3].toIntOrNull() ?: return null
        val count = getSpeakerCount()
        if (sid !in 0 until count) return null

        return VoiceSelection(
            language.iso6392Language,
            language.supertonicLanguage,
            sid
        )
    }

    override fun onGetVoices(): List<Voice> {
        val count = getSpeakerCount()
        if (count <= 0) return emptyList()

        return VoicePairCatalogue.entries(application, count)
            .map { pair ->
                val locale = Locale.forLanguageTag(pair.language.localeTag)
                Voice(
                    pair.voiceName,
                    locale,
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet()
                )
            }
    }

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (parseVoiceName(voiceName) != null) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

    override fun onLoadVoice(voiceName: String): Int {
        val selection = parseVoiceName(voiceName)
        if (selection == null) {
            Log.w(TAG, "Requested unsupported voice: $voiceName")
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        selectedVoice = selection
        Log.i(
            TAG,
            "Loaded voice: language=${selection.languageCode}, speaker=${selection.speakerId}"
        )
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()

        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) {
            onLoadLanguage(TtsEngine.lang2, "", "")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val lang = _lang ?: ""

        return if (findByAndroidLanguage(lang) != null) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        val language = selectedVoice?.languageCode ?: TtsEngine.lang ?: "eng"
        return arrayOf(language, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""

        return if (findByAndroidLanguage(lang) != null) {
            Log.i(TAG, "loading supported language: $lang")
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}, ${TtsEngine.lang2}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            return
        }
        val language = request.language
        val country = request.country
        val variant = request.variant
        // Map Android TTS speech rate (where 100 == normal) to engine speed (1.0 == normal)
        // Allow per-request override from external apps; fallback to engine default if absent.
        val text = request.charSequenceText.toString()
        val dictionaryLanguage = findByAndroidLanguage(language)
            ?.supertonicLanguage
            ?: language
        val synthesisText = PronunciationDictionary.process(
            application,
            dictionaryLanguage,
            text
        )
        val rate = runCatching { request.speechRate }.getOrDefault(-1)
        val engineSpeed = if (rate > 0) {
            // Map 100 -> 1.0f
            val mapped = rate / 100.0f
            mapped.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
        } else {
            // Fallback to current engine/global setting
            TtsEngine.speed
        }

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }
        Log.i(TAG, "text: $text, engineSpeed: $engineSpeed")
        val tts = TtsEngine.tts!!

        // Note that AudioFormat.ENCODING_PCM_FLOAT requires API level >= 24
        // callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_FLOAT, 1)

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank()) {
            callback.done()
            return
        }

        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            // convert FloatArray to ByteArray
            val samples = floatArrayToByteArray(floatSamples)
            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < samples.size) {
                val bytesToWrite = Math.min(maxBufferSize, samples.size - offset)
                callback.audioAvailable(samples, offset, bytesToWrite)
                offset += bytesToWrite
            }

            // 1 means to continue
            // 0 means to stop
            return 1
        }

        Log.i(TAG, "text: $text")
        val speakerCount = tts.numSpeakers()
        val defaultSpeakerId = TtsEngine.speakerId.coerceIn(
            0, (speakerCount - 1).coerceAtLeast(0)
        )
        val sid = selectedVoice
            ?.speakerId
            ?.takeIf { it in 0 until speakerCount }
            ?: defaultSpeakerId
        val voice = selectedVoice
        val requestedEntry = findByAndroidLanguage(language)
        if (requestedEntry == null) {
            callback.error()
            return
        }
        val requestedLanguage = requestedEntry.supertonicLanguage
        if (voice != null &&
            !voice.supertonicCode.equals(
                requestedEntry.supertonicLanguage,
                ignoreCase = true
            )
        ) {
            Log.w(
                TAG,
                "Request language $language differs from selected voice ${voice.languageCode}"
            )
            callback.error()
            return
        }
        val selectedSid = voice?.speakerId ?: sid
        val selectedSupertonicLanguage = voice?.supertonicCode ?: requestedLanguage
        val genConfig = GenerationConfig(sid = selectedSid, speed = engineSpeed)
        if (TtsEngine.isSupertonic) {
            val preferenceHelper = PreferenceHelper(application)
            genConfig.extra = preferenceHelper.buildSupertonicExtra(
                selectedSupertonicLanguage,
                selectedSid,
                engineSpeed
            )
        }
        var genText = synthesisText
        if (TtsEngine.usePhonemize) {
            // Phonemize the dictionary-processed text.
            genConfig.phonemeCodepoints = TtsEngine.phonemize(synthesisText)
            // The text has already been phonemized; pass an empty string.
            genText = ""
        }
        tts.generateWithConfigAndCallback(
            text = genText,
            config = genConfig,
            callback = ttsCallback,
        )

        callback.done()
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}
