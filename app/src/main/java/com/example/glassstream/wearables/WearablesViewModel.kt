package com.example.glassstream.wearables

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glassstream.stream.HevcDecoder
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns all DAT SDK interaction for GlassStream (Stage 2).
 *
 * Responsibilities:
 * - Initialize the DAT SDK once Android runtime permissions are granted.
 * - Register / unregister the app with the Meta AI app.
 * - Observe registration state and discovered devices, mirroring them into [uiState].
 *
 * Session creation and streaming come in later stages and are deliberately not here yet.
 */
class WearablesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WearablesUiState())
    val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

    // AutoDeviceSelector picks the first available wearable; used from Stage 4 onward,
    // but we observe its active-device flow now so the UI can show "connected" status.
    val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }

    private var monitoringStarted = false
    private var deviceSelectorJob: Job? = null

    // Stage 4: the active device session and the jobs observing its state/errors.
    private var session: DeviceSession? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null

    // Stage 5: the camera capability, its video stream, and observers.
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var videoJob: Job? = null
    // Running frame tally, incremented off the StateFlow so counting doesn't recompose the UI.
    private var frameCounter = 0L

    // Stage 5b: on-device HEVC decode → live preview. The decoder renders to a Surface supplied by
    // the UI. Per-frame byte copy + decode runs off the main thread on a single-threaded dispatcher
    // so frames stay serialized and the UI never janks at the frame rate.
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val decoderLock = Any()
    @Volatile private var hevcDecoder: HevcDecoder? = null
    @Volatile private var decoderSurface: Surface? = null
    // Latest HEVC config (VPS/SPS/PPS) so a decoder created after stream start can be primed.
    @Volatile private var configFrame: ByteArray? = null

    /**
     * Initialize the DAT SDK and begin observing its state.
     *
     * MUST be called after the required Android permissions (BLUETOOTH, BLUETOOTH_CONNECT,
     * INTERNET) are granted. Calling any other Wearables API before this yields
     * WearablesError.NOT_INITIALIZED.
     */
    fun initialize(activity: Activity) {
        if (_uiState.value.initialized) return
        Wearables.initialize(activity)
        _uiState.update { it.copy(initialized = true) }
        startMonitoring()
    }

    private fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        // React to registration changes (available, registering, registered, unregistering...).
        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                _uiState.update { it.copy(registrationState = state) }
            }
        }

        // Updates as glasses are discovered, connected, or disconnected.
        viewModelScope.launch {
            Wearables.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices.toList()) }
            }
        }

        // Tracks whether a device has actually been selected/activated.
        deviceSelectorJob = viewModelScope.launch {
            deviceSelector.activeDeviceFlow().collect { device ->
                _uiState.update { it.copy(hasActiveDevice = device != null) }
            }
        }
    }

    /** Launch the Meta AI registration flow. Requires the SDK to be initialized. */
    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    /** Launch the Meta AI unregistration flow. */
    fun startUnregistration(activity: Activity) {
        Wearables.startUnregistration(activity)
    }

    /**
     * Stage 4: create and start a [DeviceSession] to the selected glasses.
     *
     * AutoDeviceSelector picks the first available device. No camera/display capability is
     * attached here — that comes in later stages; this only proves the connection.
     */
    fun startSession() {
        if (_uiState.value.hasSession) return
        Wearables.createSession(deviceSelector)
            .onSuccess { created ->
                session = created
                // Subscribe before start() so no initial state transitions are missed.
                observeSession(created)
                _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
                created.start()
            }
            .onFailure { error, _ ->
                _uiState.update { it.copy(recentError = error.description) }
                cleanupSession()
            }
    }

    /** End the current session. The state collector drives cleanup when STOPPED arrives. */
    fun endSession() {
        val current = session ?: return
        _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
        current.stop()
    }

    private fun observeSession(session: DeviceSession) {
        sessionStateJob = viewModelScope.launch {
            session.state.collect { state ->
                _uiState.update { it.copy(sessionState = state) }
                if (state == DeviceSessionState.STOPPED) {
                    cleanupSession()
                }
            }
        }
        sessionErrorJob = viewModelScope.launch {
            session.errors.collect { error ->
                _uiState.update { it.copy(recentError = error.description) }
            }
        }
    }

    private fun cleanupSession() {
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        session = null
    }

    /**
     * Stage 5: start the glasses camera stream and prove frames arrive.
     *
     * Requires an active session. The wearable CAMERA permission is checked first (a query with no
     * redirect); if it isn't granted, [requestPermission] runs the real request, which redirects to
     * the Meta AI app. On grant, the camera capability is attached and the stream started.
     */
    fun startStreaming(requestPermission: suspend (Permission) -> PermissionStatus) {
        if (!_uiState.value.isSessionActive) {
            _uiState.update { it.copy(recentError = "Start a session first") }
            return
        }
        if (stream != null) return
        viewModelScope.launch {
            val granted = ensureCameraPermission(requestPermission)
            if (!granted) {
                _uiState.update { it.copy(recentError = "Camera permission denied") }
                return@launch
            }
            beginStream()
        }
    }

    private suspend fun ensureCameraPermission(
        requestPermission: suspend (Permission) -> PermissionStatus,
    ): Boolean {
        val current = Wearables.checkPermissionStatus(Permission.CAMERA)
            .getOrDefault(PermissionStatus.Denied)
        if (current == PermissionStatus.Granted) return true
        return requestPermission(Permission.CAMERA) == PermissionStatus.Granted
    }

    private fun beginStream() {
        val current = session ?: return
        if (stream != null) return
        current
            .addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = FRAME_RATE,
                    compressVideo = true,
                )
            )
            .onSuccess { addedCamera ->
                camera = addedCamera
                val added = addedCamera.stream
                stream = added
                _uiState.update { it.copy(frameCount = 0, lastFrameInfo = null) }
                // Subscribe before start() so no initial transitions/frames are missed.
                setupStreamListeners(added)
                _uiState.update { it.copy(streamState = StreamState.STARTING) }
                added.start().onFailure { error, _ ->
                    Log.e(TAG, "Failed to start stream: ${error.description}")
                    _uiState.update { it.copy(recentError = error.description) }
                    clearStreamResources()
                }
            }
            .onFailure { error, _ ->
                Log.e(TAG, "Failed to add camera: ${error.description}")
                _uiState.update { it.copy(recentError = error.description) }
            }
    }

    /**
     * Capture a still photo from the glasses camera.
     *
     * Requires an attached stream (from [startStreaming]) but not flowing video frames — this works
     * even while the Ray-Ban Display firmware bug (Meta issue #178) keeps the video stream silent.
     */
    fun capturePhoto() {
        val current = stream
        if (current == null) {
            _uiState.update { it.copy(recentError = "Start the camera stream first") }
            return
        }
        if (_uiState.value.isCapturingPhoto) return
        _uiState.update { it.copy(isCapturingPhoto = true) }
        viewModelScope.launch {
            current.capturePhoto()
                .onSuccess { photo ->
                    // Decode off the main thread; the glasses may return a HEIC byte buffer.
                    val bitmap = withContext(Dispatchers.Default) { decodePhoto(photo) }
                    _uiState.update {
                        it.copy(
                            isCapturingPhoto = false,
                            capturedPhoto = bitmap,
                            recentError = if (bitmap == null) "Failed to decode photo" else it.recentError,
                        )
                    }
                }
                .onFailure { error, _ ->
                    Log.e(TAG, "capturePhoto failed: ${error.description}")
                    _uiState.update { it.copy(isCapturingPhoto = false, recentError = error.description) }
                }
        }
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? =
        when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val buffer = photo.data.duplicate().apply { rewind() }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }

    /** Stop the camera stream but keep the session connected. */
    fun stopStreaming() {
        val current = camera ?: return
        _uiState.update { it.copy(streamState = StreamState.STOPPING) }
        current.stop()
        // The stream-state collector converges teardown when STOPPED/CLOSED arrives.
    }

    /**
     * Supply (or clear) the [Surface] the decoded preview renders to. Called by the UI as the
     * preview surface is created/destroyed. Clearing it tears the decoder down.
     */
    fun setSurface(surface: Surface?) {
        synchronized(decoderLock) {
            decoderSurface = surface
            if (surface == null) {
                hevcDecoder?.stop()
                hevcDecoder = null
            }
        }
    }

    private fun setupStreamListeners(stream: Stream) {
        videoJob = viewModelScope.launch(frameDispatcher) {
            stream.videoStream.collect { handleVideoFrame(it) }
        }
        streamStateJob = viewModelScope.launch {
            var hasBeenActive = false
            stream.state.collect { state ->
                _uiState.update { it.copy(streamState = state) }
                val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
                if (!isTerminal) {
                    hasBeenActive = true
                } else if (hasBeenActive) {
                    hasBeenActive = false
                    clearStreamResources()
                }
            }
        }
        streamErrorJob = viewModelScope.launch {
            stream.errorStream.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                _uiState.update { it.copy(recentError = error.description) }
            }
        }
    }

    // Counts frames (5a) AND feeds them to the HEVC decoder for the live preview (5b). Logs every
    // frame; updates the UI occasionally so Compose doesn't recompose at the frame rate.
    private fun handleVideoFrame(frame: VideoFrame) {
        val count = frameCounter + 1
        frameCounter = count
        val info = "${frame.width}x${frame.height}, ${frame.buffer.remaining()} bytes" +
            if (frame.isCompressed) " (HEVC)" else ""
        Log.d(TAG, "frame #$count: $info")
        if (count == 1L || count % 15 == 0L) {
            _uiState.update { it.copy(frameCount = count, lastFrameInfo = info) }
        }

        // Copy the frame bytes out (the SDK reuses the buffer after this returns).
        val buffer = frame.buffer
        val bytes = ByteArray(buffer.remaining())
        val pos = buffer.position()
        buffer.get(bytes)
        buffer.position(pos)
        if (frame.isCodecConfig) configFrame = bytes

        // Lazily create the decoder once a Surface is available; prime it with the cached config in
        // case the surface arrived after the config frame. Guarded so a concurrent setSurface(null)
        // can't leave a decoder bound to a released Surface.
        synchronized(decoderLock) {
            val surface = decoderSurface
            if (hevcDecoder == null && surface != null) {
                hevcDecoder = HevcDecoder().also { decoder ->
                    decoder.start(frame.width, frame.height, surface)
                    configFrame?.let { decoder.decodeFrame(it, 0) }
                }
            }
            hevcDecoder?.decodeFrame(bytes, frame.presentationTimeUs)
        }
    }

    private fun clearStreamResources() {
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        frameCounter = 0
        synchronized(decoderLock) {
            hevcDecoder?.stop()
            hevcDecoder = null
        }
        configFrame = null
        // STOPPED is restartable, so only close() detaches the capability; without it the next
        // addCamera() fails with "a capability of this type is already active".
        camera?.close()
        camera = null
        stream = null
        _uiState.update { it.copy(streamState = StreamState.STOPPED) }
    }

    fun clearError() {
        _uiState.update { it.copy(recentError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        deviceSelectorJob?.cancel()
        clearStreamResources()
        session?.stop()
        cleanupSession()
    }

    companion object {
        private const val TAG = "GlassStream"
        private const val FRAME_RATE = 24
    }
}
