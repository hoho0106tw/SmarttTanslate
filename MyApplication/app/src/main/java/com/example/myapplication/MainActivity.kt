package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray


// Data model for professional bilingual translation pairs
data class TranslationPair(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val target: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false

    // Real-time state
    private val currentSourceText = mutableStateOf("")
    private val currentTargetText = mutableStateOf("")
    private val translationHistory = mutableStateListOf<TranslationPair>()

    private val isRunning = mutableStateOf(false)
    private val isJapaneseToChinese = mutableStateOf(true)
    private val isMicEnabled = mutableStateOf(true)
    private val isSpeakerEnabled = mutableStateOf(true)

    // Flag to prevent feedback loops (mic hearing system output)
    private val isSystemSpeaking = AtomicBoolean(false)
    private val isSystemSpeakingState = mutableStateOf(false) 

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startTranslation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            TranslatorTheme {
                TranslationScreen(
                    currentSource = currentSourceText.value,
                    currentTarget = currentTargetText.value,
                    history = translationHistory,
                    isRunning = isRunning.value,
                    isJapaneseToChinese = isJapaneseToChinese.value,
                    isMicEnabled = isMicEnabled.value,
                    isSpeakerEnabled = isSpeakerEnabled.value,
                    isSystemSpeaking = isSystemSpeakingState.value,
                    onToggleRunning = { if (isRunning.value) stopTranslation() else checkPermissionAndStart() },
                    onSwapLanguages = { isJapaneseToChinese.value = !isJapaneseToChinese.value },
                    onToggleMic = { isMicEnabled.value = !isMicEnabled.value },
                    onToggleSpeaker = { isSpeakerEnabled.value = !isSpeakerEnabled.value }
                )
            }
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startTranslation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startTranslation() {
        isRunning.value = true
        currentTargetText.value = getString(R.string.connecting_to_engine)
        connectWebSocket()
    }

    private fun stopTranslation() {
        isRunning.value = false
        isRecording = false
        audioRecord?.apply {
            try { stop(); release() } catch (e: Exception) { Log.e("MainActivity", "Stop audioRecord", e) }
        }
        audioRecord = null
        audioTrack?.apply {
            try { stop(); release() } catch (e: Exception) { Log.e("MainActivity", "Stop audioTrack", e) }
        }
        audioTrack = null
        webSocket?.close(1000, getString(R.string.user_stopped))
        webSocket = null
        isSystemSpeaking.set(false)
        isSystemSpeakingState.value = false
    }

    private fun connectWebSocket() {
        val apiKey = ""
        val endpoint = "wss://
        val deploymentName = ""

        val wsUrl = ""

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("api-key", apiKey)
            .build()

        val sourceLang = if (isJapaneseToChinese.value) getString(R.string.language_japanese) else getString(R.string.language_chinese_tw)
        val targetLang = if (isJapaneseToChinese.value) getString(R.string.language_chinese_tw) else getString(R.string.language_japanese)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sessionUpdate = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply { put("instructions",
                            "You are a strict real-time interpreter. " +
                                    "Translate incoming $sourceLang into $targetLang as literally and faithfully as possible. " +
                                    "Output ONLY the translated text. " +
                                    "Do not add explanations, guesses, inferred meaning, summaries, or conversational filler. " +
                                    "Do not complete unfinished sentences. " +
                                    "If the audio is unclear, incomplete, noisy, or ambiguous, translate only the parts that are clearly heard. " +
                                    "If a segment cannot be understood confidently, omit it rather than guessing. " +
                                    "Never reinterpret or embellish the speaker's meaning. " +
                                    "Ignore echo, background playback, or your own previous output."
                        )
                        // 👉 只輸出文字（避免 loop）
                      //  put("modalities", JSONArray().put("text"))

                        put("input_audio_format", "pcm16")
                        put("output_audio_format", "pcm16")

                         put("input_audio_transcription", JSONObject().apply { put("model", "whisper-1") })
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                        })
                    })
                }
                webSocket.send(sessionUpdate.toString())
                startAudioRecording()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "response.created" -> {
                            isSystemSpeaking.set(true)
                            lifecycleScope.launch(Dispatchers.Main) { isSystemSpeakingState.value = true }
                            webSocket.send(JSONObject().apply { put("type", "input_audio_buffer.clear") }.toString())
                        }
                        "response.audio.delta" -> {
                            isSystemSpeaking.set(true)
                            if (isSpeakerEnabled.value) playPcm16Audio(json.optString("delta"))
                        }
                        "response.audio_transcript.delta" -> {
                            val delta = json.optString("delta")
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (currentTargetText.value.contains(getString(R.string.connecting_to_engine).substringBefore("..."))) currentTargetText.value = delta
                                else currentTargetText.value += delta
                            }
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val transcript = json.optString("transcript")
                            if (transcript.isNotBlank()) {
                                lifecycleScope.launch(Dispatchers.Main) { currentSourceText.value = transcript }
                            }
                        }
                        "response.done" -> {
                            lifecycleScope.launch(Dispatchers.Main) {
                                delay(2500) 
                                isSystemSpeaking.set(false)
                                isSystemSpeakingState.value = false
                                
                                if (currentSourceText.value.isNotEmpty() || currentTargetText.value.isNotEmpty()) {
                                    translationHistory.add(0, TranslationPair(
                                        source = currentSourceText.value,
                                        target = currentTargetText.value
                                    ))
                                    currentSourceText.value = ""
                                    currentTargetText.value = ""
                                }
                            }
                        }
                    }
                } catch (e: Exception) { Log.e("AOAI", "Message parse error", e) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lifecycleScope.launch(Dispatchers.Main) {
                    currentTargetText.value = getString(R.string.engine_error)
                    stopTranslation()
                }
            }
        })
    }

    private fun playPcm16Audio(base64Audio: String) {
        try {
            val audioData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
            if (audioTrack == null) {
                val sampleRate = 24000
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
            }
            audioTrack?.write(audioData, 0, audioData.size)
        } catch (e: Exception) { Log.e("MainActivity", "Playback error", e) }
    }

    private fun startAudioRecording() {
        val sampleRate = 24000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioRecord!!.audioSessionId)?.enabled = true

            audioRecord?.startRecording()
            isRecording = true
            lifecycleScope.launch(Dispatchers.Main) { currentTargetText.value = getString(R.string.listening) }

            lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && isMicEnabled.value && !isSystemSpeaking.get()) {
                        val base64Audio = android.util.Base64.encodeToString(buffer.copyOfRange(0, read), android.util.Base64.NO_WRAP)
                        webSocket?.send(JSONObject().apply { put("type", "input_audio_buffer.append"); put("audio", base64Audio) }.toString())
                    }
                }
            }
        } catch (e: Exception) { Log.e("MainActivity", "Record error", e) }
    }
}

@Composable
fun TranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0D47A1), // Professional Navy Blue
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE3F2FD), // Subtle light blue
            secondary = Color(0xFF37474F), // Professional Slate Gray
            surface = Color.White,
            background = Color(0xFFF8F9FA) // Clean neutral background
        ),
        typography = Typography(),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
    currentSource: String,
    currentTarget: String,
    history: List<TranslationPair>,
    isRunning: Boolean,
    isJapaneseToChinese: Boolean,
    isMicEnabled: Boolean,
    isSpeakerEnabled: Boolean,
    isSystemSpeaking: Boolean,
    onToggleRunning: () -> Unit,
    onSwapLanguages: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    IconButton(onClick = onToggleMic) {
                        Icon(if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = stringResource(R.string.content_description_mic), 
                            tint = if (isMicEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onToggleSpeaker) {
                        Icon(if (isSpeakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, contentDescription = stringResource(R.string.content_description_speaker),
                            tint = if (isSpeakerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            LanguageSelectorCard(isJapaneseToChinese, onSwapLanguages)
            Spacer(modifier = Modifier.height(24.dp))
            StatusPill(isRunning, isSystemSpeaking)
            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (currentSource.isEmpty() && currentTarget.isEmpty() && !isRunning) "Translate speech in real-time." else currentSource,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = currentTarget,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 34.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            ControlButton(isRunning, onToggleRunning)
            Spacer(modifier = Modifier.height(24.dp))
            HistorySection(history)
        }
    }
}

@Composable
fun LanguageSelectorCard(isJapaneseToChinese: Boolean, onSwap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val lang1 = if (isJapaneseToChinese) stringResource(R.string.language_japanese) else stringResource(R.string.language_chinese)
            val lang2 = if (isJapaneseToChinese) stringResource(R.string.language_chinese) else stringResource(R.string.language_japanese)

            Text(lang1, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onSwap, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = stringResource(R.string.content_description_swap), tint = MaterialTheme.colorScheme.primary)
            }
            Text(lang2, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun StatusPill(isRunning: Boolean, isSystemSpeaking: Boolean) {
    val statusText = when {
        isSystemSpeaking -> stringResource(R.string.status_system_speaking)
        isRunning -> stringResource(R.string.status_engine_active)
        else -> stringResource(R.string.status_standby)
    }
    val color by animateColorAsState(
        targetValue = when {
            isSystemSpeaking -> Color(0xFF4CAF50)
            isRunning -> Color(0xFF2196F3)
            else -> Color.Gray
        }, label = "pill"
    )

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Text(statusText, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = color)
        }
    }
}

@Composable
fun ControlButton(isRunning: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Icon(if (isRunning) Icons.Default.Stop else Icons.Default.GraphicEq, contentDescription = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(if (isRunning) stringResource(R.string.stop_session) else stringResource(R.string.start_interpreting), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistorySection(history: List<TranslationPair>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Recent Translations", style = MaterialTheme.typography.titleMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(history, key = { it.id }) { pair ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(pair.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(pair.target, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
