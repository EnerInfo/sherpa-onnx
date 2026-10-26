@file:OptIn(ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.tts.engine.ui.theme.SherpaOnnxTtsEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.TimeSource

const val TAG = "sherpa-onnx-tts-engine"

class MainActivity : ComponentActivity() {
    // TODO(fangjun): Save settings in ttsViewModel
    private val ttsViewModel: TtsViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null

    // see
    // https://developer.android.com/reference/kotlin/android/media/AudioTrack
    private lateinit var track: AudioTrack

    private var stopped: Boolean = false

    private var samplesChannel = Channel<FloatArray>(capacity = 128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Start to initialize TTS")
        TtsEngine.createTts(this)
        Log.i(TAG, "Finish initializing TTS")

        Log.i(TAG, "Start to initialize AudioTrack")
        initAudioTrack()
        Log.i(TAG, "Finish initializing AudioTrack")

        val preferenceHelper = PreferenceHelper(this)

        setContent {
            SherpaOnnxTtsEngineTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    Scaffold { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            Column(modifier = Modifier.padding(16.dp)) {

                                Text(
                                    "Next-gen Kaldi: TTS Engine",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                )

                                var selectedLanguage by remember {
                                    mutableStateOf(TtsEngine.supertonicLang)
                                }
                                var languageMenuExpanded by remember {
                                    mutableStateOf(false)
                                }
                                var speakerMenuExpanded by remember {
                                    mutableStateOf(false)
                                }
                                var testText by remember {
                                    mutableStateOf(
                                        LocaleCatalogue.entries(applicationContext)
                                            .firstOrNull {
                                                it.supertonicLanguage == selectedLanguage
                                            }
                                            ?.let {
                                                getSampleText(it.iso6393Language)
                                            }
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "This is a text to speech test."
                                    )
                                }
                                val textFieldFocusRequester = remember {
                                    FocusRequester()
                                }
                                var textFieldValue by remember {
                                    mutableStateOf(TextFieldValue(text = testText))
                                }
                                var pendingCaretPosition by remember {
                                    mutableStateOf<Int?>(null)
                                }
                                var pendingFocusRequest by remember {
                                    mutableStateOf(false)
                                }
                                var startEnabled by remember {
                                    mutableStateOf(true)
                                }
                                var playEnabled by remember {
                                    mutableStateOf(false)
                                }
                                var saveEnabled by remember {
                                    mutableStateOf(false)
                                }
                                var shareEnabled by remember {
                                    mutableStateOf(false)
                                }
                                var rtfText by remember {
                                    mutableStateOf("")
                                }
                                var editingFile by remember {
                                    mutableStateOf<String?>(null)
                                }
                                var originalTestText by remember {
                                    mutableStateOf("")
                                }

                                val scrollState = rememberScrollState(0)

                                val context = LocalContext.current

                                val saveLauncher =
                                    rememberLauncherForActivityResult(
                                        contract = ActivityResultContracts.CreateDocument(
                                            "audio/wav"
                                        )
                                    ) { uri ->
                                        if (uri != null) {
                                            try {
                                                val srcFile = File(
                                                    application.filesDir.absolutePath +
                                                        "/generated.wav"
                                                )
                                                contentResolver
                                                    .openOutputStream(uri)
                                                    ?.use { output ->
                                                        srcFile.inputStream().use { input ->
                                                            input.copyTo(output)
                                                        }
                                                    }
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Audio saved",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to save audio: $e")
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Failed to save audio",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }

                                val numSpeakers = TtsEngine.tts!!.numSpeakers()

                                val localeEntries = LocaleCatalogue.entries(context)

                                val voicePairs =
                                    VoicePairCatalogue.entries(context, numSpeakers)
                                        .filter {
                                            it.language.supertonicLanguage ==
                                                selectedLanguage
                                        }

                                val languageLabel = localeEntries.firstOrNull {
                                    it.supertonicLanguage == selectedLanguage
                                }?.displayName ?: selectedLanguage


                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 4.dp)
                                ) {
                                    Text("Language")
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        AppButton(
                                            onClick = {
                                                languageMenuExpanded = true
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(languageLabel)
                                        }

                                        DropdownMenu(
                                            expanded = languageMenuExpanded,
                                            onDismissRequest = {
                                                languageMenuExpanded = false
                                            }
                                        ) {
                                            localeEntries.forEach { entry ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            if (entry.supertonicLanguage == selectedLanguage) {
                                                                "${entry.displayName} (selected)"
                                                            } else {
                                                                entry.displayName
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        selectedLanguage =
                                                            entry.supertonicLanguage
                                                        TtsEngine.supertonicLang =
                                                            entry.supertonicLanguage
                                                        preferenceHelper.setLanguage(
                                                            entry.supertonicLanguage
                                                        )
                                                        testText =
                                                            getSampleText(entry.iso6393Language)
                                                                .ifBlank {
                                                                    "This is a text to speech test in ${entry.displayName}."
                                                                }
                                                        languageMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 4.dp)
                                ) {
                                    Text("Voice")
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        AppButton(
                                            onClick = {
                                                speakerMenuExpanded = true
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = numSpeakers > 0
                                        ) {
                                            Text(
                                                "Speaker ${
                                                    TtsEngine.speakerId.coerceIn(
                                                        0,
                                                        (numSpeakers - 1).coerceAtLeast(0)
                                                    )
                                                }"
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = speakerMenuExpanded,
                                            onDismissRequest = {
                                                speakerMenuExpanded = false
                                            }
                                        ) {
                                            voicePairs.forEach { pair ->
                                                val sid = pair.speakerId
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            if (sid == TtsEngine.speakerId) {
                                                                "Speaker $sid (selected)"
                                                            } else {
                                                                "Speaker $sid"
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        TtsEngine.speakerId = sid
                                                        preferenceHelper.setSid(sid)
                                                        speakerMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                                LaunchedEffect(
                                    testText,
                                    pendingCaretPosition,
                                    pendingFocusRequest
                                ) {
                                    if (textFieldValue.text != testText) {
                                        val position = pendingCaretPosition
                                            ?.coerceIn(0, testText.length)
                                            ?: testText.length

                                        textFieldValue = TextFieldValue(
                                            text = testText,
                                            selection = TextRange(position)
                                        )
                                    }

                                    if (pendingFocusRequest) {
                                        withFrameNanos { }
                                        textFieldFocusRequester.requestFocus()
                                        pendingFocusRequest = false
                                    }

                                    pendingCaretPosition = null
                                }

                                OutlinedTextField(
                                    value = textFieldValue,
                                    onValueChange = {
                                        textFieldValue = it
                                        testText = it.text
                                    },
                                    label = {
                                        Text(
                                            editingFile?.let {
                                                "Editing $it"
                                            } ?: "Please input your text here"
                                        )
                                    },
                                    maxLines = 25,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                        .focusRequester(textFieldFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown ||
                                                (event.key != Key.DirectionUp &&
                                                    event.key != Key.DirectionDown)
                                            ) {
                                                false
                                            } else {
                                                val text = textFieldValue.text
                                                val caret = textFieldValue.selection.start
                                                val safeCaret = caret.coerceIn(0, text.length)
                                                val lineStart = text.lastIndexOf(
                                                    '\n',
                                                    (safeCaret - 1).coerceAtLeast(0)
                                                ).let { if (safeCaret == 0) -1 else it } + 1
                                                val lineEnd = text.indexOf('\n', safeCaret)
                                                    .let { if (it < 0) text.length else it }
                                                val column = safeCaret - lineStart

                                                if (event.key == Key.DirectionUp) {
                                                    if (lineStart == 0) {
                                                        true
                                                    } else {
                                                        val previousEnd = lineStart - 1
                                                        val previousStart = text.lastIndexOf(
                                                            '\n',
                                                            (previousEnd - 1).coerceAtLeast(0)
                                                        ).let {
                                                            if (previousEnd == 0) -1 else it
                                                        } + 1
                                                        val target = (
                                                            previousStart + column
                                                        ).coerceAtMost(previousEnd)
                                                        textFieldValue = textFieldValue.copy(
                                                            selection = TextRange(target)
                                                        )
                                                        true
                                                    }
                                                } else {
                                                    if (lineEnd == text.length) {
                                                        true
                                                    } else {
                                                        val nextStart = lineEnd + 1
                                                        val nextEnd = text.indexOf(
                                                            '\n',
                                                            nextStart
                                                        ).let {
                                                            if (it < 0) text.length else it
                                                        }
                                                        val target = (
                                                            nextStart + column
                                                        ).coerceAtMost(nextEnd)
                                                        textFieldValue = textFieldValue.copy(
                                                            selection = TextRange(target)
                                                        )
                                                        true
                                                    }
                                                }
                                            }
                                        }
                                        .wrapContentHeight(),
                                    singleLine = false
                                )

                                Row {
                                    AppButton(
                                        enabled = startEnabled && editingFile == null,
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            Log.i(TAG, "Clicked, text: $testText")

                                            if (
                                                testText.isBlank() ||
                                                testText.isEmpty()
                                            ) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Please input some text to generate",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                startEnabled = false
                                                playEnabled = false
                                                saveEnabled = false
                                                shareEnabled = false
                                                stopped = false

                                                track.pause()
                                                track.flush()
                                                track.play()
                                                rtfText = ""

                                                Log.i(
                                                    TAG,
                                                    "Started with text $testText"
                                                )

                                                scope.launch {
                                                    for (samples in samplesChannel) {
                                                        if (samples.isEmpty()) {
                                                            break
                                                        }

                                                        Log.i(
                                                            TAG,
                                                            "Received ${samples.count()} samples"
                                                        )
                                                        track.write(
                                                            samples,
                                                            0,
                                                            samples.size,
                                                            AudioTrack.WRITE_BLOCKING
                                                        )

                                                        if (stopped) {
                                                            break
                                                        }
                                                    }

                                                    Log.i(TAG, "Draining the channel")

                                                    // drain remaining
                                                    while (!samplesChannel.isEmpty) {
                                                        samplesChannel.tryReceive()
                                                            .getOrNull()
                                                    }

                                                    Log.i(TAG, "Channel drained")
                                                }

                                                CoroutineScope(Dispatchers.Default).launch {
                                                    val timeSource = TimeSource.Monotonic
                                                    val startTime = timeSource.markNow()

                                                    val selectedLanguage =
                                                        TtsEngine.supertonicLang
                                                    val selectedSpeaker =
                                                        TtsEngine.speakerId
                                                    val voiceConfiguration =
                                                        preferenceHelper.getVoiceConfiguration(
                                                            selectedLanguage,
                                                            selectedSpeaker
                                                        )

                                                    val testSpeed = TtsEngine.speed

                                                    val genConfig = GenerationConfig(
                                                        sid = selectedSpeaker,
                                                        speed = testSpeed
                                                    )

                                                    if (TtsEngine.isSupertonic) {
                                                        genConfig.extra =
                                                            preferenceHelper.buildSupertonicExtra(
                                                                selectedLanguage,
                                                                selectedSpeaker,
                                                                testSpeed
                                                            )
                                                    }

                                                    var genText = testText

                                                    if (TtsEngine.usePhonemize) {
                                                        // Phonemize outside of sherpa-onnx with
                                                        // piper-phonemize and pass the phoneme
                                                        // codepoints.
                                                        genConfig.phonemeCodepoints =
                                                            TtsEngine.phonemize(testText)

                                                        // The text has already been phonemized.
                                                        genText = ""
                                                    }

                                                    val audio =
                                                        TtsEngine.tts!!
                                                            .generateWithConfigAndCallback(
                                                                text = genText,
                                                                config = genConfig,
                                                                callback = ::callback
                                                            )

                                                    val elapsed =
                                                        startTime.elapsedNow()
                                                            .inWholeMilliseconds
                                                            .toFloat() / 1000
                                                    val audioDuration =
                                                        audio.samples.size /
                                                            TtsEngine.tts!!
                                                                .sampleRate()
                                                                .toFloat()
                                                    val RTF = String.format(
                                                        "   Number of threads: %d   |   Elapsed: %.3f s   |   Audio duration: %.3f s   |   RTF: %.3f/%.3f = %.3f",
                                                        TtsEngine.tts!!.config.model.numThreads,
                                                        elapsed,
                                                        audioDuration,
                                                        elapsed,
                                                        audioDuration,
                                                        elapsed / audioDuration
                                                    )

                                                    scope.launch {
                                                        Log.i(TAG, "send 0 samples")
                                                        samplesChannel.send(FloatArray(0))
                                                        Log.i(TAG, "send 0 samples done")
                                                    }

                                                    val filename =
                                                        application.filesDir.absolutePath +
                                                            "/generated.wav"

                                                    val ok =
                                                        audio.samples.isNotEmpty() &&
                                                            audio.save(filename)

                                                    if (ok) {
                                                        withContext(Dispatchers.Main) {
                                                            startEnabled = true
                                                            playEnabled = true
                                                            saveEnabled = true
                                                            shareEnabled = true
                                                            rtfText = RTF
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Start")
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(5.dp),
                                        enabled = playEnabled,
                                        onClick = {
                                            stopped = true
                                            track.pause()
                                            track.flush()
                                            onClickPlay()
                                        }
                                    ) {
                                        Text("Play")
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            onClickStop()
                                            startEnabled = true
                                        }
                                    ) {
                                        Text("Stop")
                                    }

                                    AppButton(
                                        enabled = saveEnabled,
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            saveLauncher.launch("generated.wav")
                                        }
                                    ) {
                                        Text("Save")
                                    }

                                    AppButton(
                                        enabled = shareEnabled,
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            val file = File(
                                                application.filesDir.absolutePath +
                                                    "/generated.wav"
                                            )

                                            if (!file.exists()) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    "No audio to share",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "com.k2fsa.sherpa.onnx.tts.engine.fileprovider",
                                                    file
                                                )

                                                val intent =
                                                    Intent(Intent.ACTION_SEND).apply {
                                                        type = "audio/wav"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                    }

                                                startActivity(
                                                    Intent.createChooser(
                                                        intent,
                                                        "Share audio"
                                                    )
                                                )
                                            }
                                        }
                                    ) {
                                        Text("Share")
                                    }
                                    androidx.compose.foundation.layout.Spacer(
                                        modifier = Modifier.weight(1f)
                                    )

                                    var displayedSpeed by remember {
                                        mutableStateOf(TtsEngine.speed)
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(3.dp),
                                        onClick = {
                                            val speed = (
                                                displayedSpeed - 0.1f
                                            ).coerceAtLeast(MIN_TTS_SPEED)
                                            TtsEngine.speed = speed
                                            displayedSpeed = speed
                                            preferenceHelper.setSpeed(speed)
                                        }
                                    ) {
                                        Text("Slow")
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(3.dp),
                                        onClick = {},
                                        enabled = false
                                    ) {
                                        Text(
                                            String.format(
                                                "%.1f×",
                                                displayedSpeed
                                            )
                                        )
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(3.dp),
                                        onClick = {
                                            val speed = (
                                                displayedSpeed + 0.1f
                                            ).coerceAtMost(MAX_TTS_SPEED)
                                            TtsEngine.speed = speed
                                            displayedSpeed = speed
                                            preferenceHelper.setSpeed(speed)
                                        }
                                    ) {
                                        Text("Fast")
                                    }

                                    androidx.compose.foundation.layout.Spacer(
                                        modifier = Modifier.weight(1f)
                                    )
                                    AppButton(
                                        modifier = Modifier.padding(5.dp),
                                        inverted = editingFile ==
                                            "pronunciation_dictionary.json",
                                        onClick = {
                                            val fileName =
                                                "pronunciation_dictionary.json"
                                            if (editingFile == fileName) {
                                                try {
                                                    File(filesDir, fileName)
                                                        .writeText(
                                                            testText,
                                                            Charsets.UTF_8
                                                        )
                                                    testText = originalTestText
                                                    editingFile = null
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Dictionary saved",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Save failed: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            } else if (editingFile == null) {
                                                try {
                                                    val file = File(
                                                        filesDir,
                                                        fileName
                                                    )
                                                    if (!file.isFile) {
                                                        assets.open(fileName).use {
                                                            input ->
                                                            file.outputStream()
                                                                .use { output ->
                                                                    input.copyTo(output)
                                                                }
                                                        }
                                                    }
                                                    originalTestText = testText
                                                    testText = file.readText(Charsets.UTF_8)
                                                    textFieldValue = TextFieldValue(
                                                        text = testText,
                                                        selection = TextRange(0)
                                                    )
                                                    pendingCaretPosition = 0
                                                    pendingFocusRequest = true

                                                    editingFile = fileName

                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Read failed: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Dict")
                                    }

                                    AppButton(
                                        modifier = Modifier.padding(5.dp),
                                        inverted = editingFile ==
                                            "voice_pairs.json",
                                        onClick = {
                                            val fileName =
                                                "voice_pairs.json"
                                            if (editingFile == fileName) {
                                                try {
                                                    File(filesDir, fileName)
                                                        .writeText(
                                                            testText,
                                                            Charsets.UTF_8
                                                        )
                                                    testText = originalTestText
                                                    editingFile = null
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Voice-pair file saved",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Save failed: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            } else if (editingFile == null) {
                                                try {
                                                    val file = File(
                                                        filesDir,
                                                        fileName
                                                    )
                                                    if (!file.isFile) {
                                                        assets.open(fileName).use {
                                                            input ->
                                                            file.outputStream()
                                                                .use { output ->
                                                                    input.copyTo(output)
                                                                }
                                                        }
                                                    }
                                                    originalTestText = testText
                                                    testText = file.readText(Charsets.UTF_8)
                                                    textFieldValue = TextFieldValue(
                                                        text = testText,
                                                        selection = TextRange(0)
                                                    )
                                                    pendingCaretPosition = 0
                                                    pendingFocusRequest = true
                                                    editingFile = fileName

                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Read failed: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Lang")
                                    }

                                    if (editingFile != null) {
                                        AppButton(
                                            modifier = Modifier.padding(5.dp),
                                            onClick = {
                                                testText = originalTestText
                                                editingFile = null
                                            }
                                        ) {
                                            Text("Discard")
                                        }
                                    }
                                }

                                if (rtfText.isNotEmpty()) {
                                    Row {
                                        Text(rtfText)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopMediaPlayer()
        super.onDestroy()
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun onClickPlay() {
        val filename = application.filesDir.absolutePath + "/generated.wav"
        stopMediaPlayer()
        mediaPlayer = MediaPlayer.create(
            applicationContext,
            Uri.fromFile(File(filename))
        )
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        track.pause()
        track.flush()
        stopMediaPlayer()
    }

    // this function is called from C++
    private fun callback(samples: FloatArray): Int {
        if (!stopped) {
            val samplesCopy = samples.copyOf()

            scope.launch {
                Log.i(TAG, "callback called with ${samplesCopy.count()} samples")
                val ok = samplesChannel.trySend(samplesCopy).isSuccess
                Log.i(TAG, "callback called with $ok")
            }

            return 1
        } else {
            track.stop()
            Log.i(TAG, " return 0")
            return 0
        }
    }

    private fun initAudioTrack() {
        val sampleRate = TtsEngine.tts!!.sampleRate()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        Log.i(TAG, "sampleRate: $sampleRate, buffLength: $bufLength")

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr,
            format,
            bufLength,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        track.play()
    }
}

@Composable
private fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inverted: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    val contentColor = if (inverted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val containerColor = if (inverted) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                outlineColor
            } else {
                outlineColor.copy(alpha = 0.35f)
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor.copy(alpha = 0.38f)
        ),
        content = content
    )
}
