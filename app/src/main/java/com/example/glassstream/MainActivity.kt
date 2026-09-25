package com.example.glassstream

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.glassstream.ui.theme.GlassstreamTheme
import com.example.glassstream.wearables.WearablesUiState
import com.example.glassstream.wearables.WearablesViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {

    companion object {
        // Android runtime permissions the DAT SDK needs before initialize().
        val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)
    }

    private val viewModel: WearablesViewModel by viewModels()

    // Requests the Android permissions, then initializes the DAT SDK once granted.
    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                viewModel.initialize(this)
            }
        }

    // Wearable (glasses) permission request — redirects to the Meta AI app. Bridged to a suspend
    // function so the ViewModel can await the result; a Mutex serializes concurrent requests.
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val wearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            permissionContinuation?.resume(status)
            permissionContinuation = null
        }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablePermissionLauncher.launch(permission)
            }
        }
    }

    // Stage 7 — audio OUTPUT. The glasses are a standard Bluetooth A2DP device, not a DAT capability;
    // TextToSpeech plays through Android's audio system, which routes to the glasses when they're the
    // connected media output.
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun speakToGlasses(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "glassstream-tts")
    }

    /** Name of the connected Bluetooth audio output, if any — so routing to the glasses is verifiable. */
    private fun bluetoothAudioOutputName(): String? {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            ?.productName
            ?.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // USAGE_MEDIA → A2DP high-fidelity path, which is what routes to the glasses.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }
        enableEdgeToEdge()
        setContent {
            GlassstreamTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val activity = LocalContext.current.findActivity()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionScreen(
                        state = state,
                        onRegister = { activity?.let(viewModel::startRegistration) },
                        onUnregister = { activity?.let(viewModel::startUnregistration) },
                        onStartSession = viewModel::startSession,
                        onEndSession = viewModel::endSession,
                        onStartStream = { viewModel.startStreaming(::requestWearablesPermission) },
                        onStopStream = viewModel::stopStreaming,
                        onCapturePhoto = viewModel::capturePhoto,
                        onSetSurface = viewModel::setSurface,
                        onSpeak = { speakToGlasses("Hello from GlassStream") },
                        bluetoothOutput = bluetoothAudioOutputName(),
                        onShowDisplay = viewModel::showConnectedOnDisplay,
                        onClearDisplay = viewModel::clearDisplay,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Gate everything behind the required Android permissions.
        permissionLauncher.launch(PERMISSIONS)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

@Composable
private fun ConnectionScreen(
    state: WearablesUiState,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onCapturePhoto: () -> Unit,
    onSetSurface: (android.view.Surface?) -> Unit,
    onSpeak: () -> Unit,
    bluetoothOutput: String?,
    onShowDisplay: () -> Unit,
    onClearDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("GlassStream")
        Text("SDK initialized: ${state.initialized}")
        Text("Registration: ${state.registrationState}")
        Text("Registered: ${state.isRegistered}")
        Text("Devices found: ${state.devices.size}")
        Text("Active device: ${state.hasActiveDevice}")
        Text("Session: ${state.sessionState}")
        Text("Session active: ${state.isSessionActive}")
        Text("Stream: ${state.streamState}")
        Text("Frames received: ${state.frameCount}")
        state.lastFrameInfo?.let { Text("Last frame: $it") }
        Text("Audio (glasses mic): ${if (state.audioEnabled) "on" else "off"}")
        if (state.audioEnabled) {
            Text("Audio frames: ${state.audioFrameCount}")
            state.lastAudioInfo?.let { Text("Last audio: $it") }
            AudioLevelBar(level = state.audioLevel)
        }
        state.recentError?.let { Text("Error: $it") }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onRegister, enabled = state.canStartRegistration) {
            Text("Register with Meta AI")
        }
        OutlinedButton(onClick = onUnregister, enabled = state.isRegistered) {
            Text("Unregister")
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onStartSession, enabled = state.canStartSession) {
            Text("Connect to glasses (start session)")
        }
        OutlinedButton(onClick = onEndSession, enabled = state.hasSession) {
            Text("Disconnect (end session)")
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onStartStream, enabled = state.canStartStream) {
            Text("Start camera stream")
        }
        OutlinedButton(onClick = onStopStream, enabled = state.hasStream) {
            Text("Stop camera stream")
        }

        // Live preview: mounted only while a stream is attached. The decoded HEVC frames render
        // straight to this Surface (GPU YUV→RGB); the ViewModel owns the decoder.
        if (state.hasStream) {
            Text("Live preview:")
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidExternalSurface(
                    modifier = Modifier
                        .height(360.dp)
                        .aspectRatio(9f / 16f),
                ) {
                    onSurface { surface, _, _ ->
                        onSetSurface(surface)
                        surface.onDestroyed { onSetSurface(null) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onCapturePhoto, enabled = state.canCapturePhoto) {
            Text(if (state.isCapturingPhoto) "Capturing…" else "Capture photo")
        }
        state.capturedPhoto?.let { bitmap ->
            Text("Captured: ${bitmap.width}x${bitmap.height}")
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo captured from the glasses camera",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Stage 7 — audio output. Plays via Android TTS; routes to the glasses when they're the
        // connected Bluetooth (A2DP) output. Independent of the DAT session.
        Text("Bluetooth audio out: ${bluetoothOutput ?: "none detected"}")
        Button(onClick = onSpeak) {
            Text("Speak \"Hello from GlassStream\"")
        }

        Spacer(Modifier.height(16.dp))

        // Stage 8 — display output on the Ray-Ban Display (real DAT capability).
        Text("Display: ${state.displayState ?: "not attached"}")
        Text("Content sent: ${state.displayContentSent}")
        Button(onClick = onShowDisplay, enabled = state.canUseDisplay) {
            Text("Show \"CONNECTED\" on glasses display")
        }
        OutlinedButton(onClick = onClearDisplay, enabled = state.isDisplayAttached) {
            Text("Clear display")
        }
    }
}

/** Simple horizontal meter: fills proportionally to the mic peak level (0..1). */
@Composable
private fun AudioLevelBar(level: Float) {
    val clamped = level.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color(0xFF303030)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(16.dp)
                .background(Color(0xFF4CAF50)),
        )
    }
}

/** Walk the ContextWrapper chain to find the hosting Activity (needed for DAT calls). */
private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
