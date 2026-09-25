package com.example.glassstream.wearables

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.types.DisplayState

/**
 * Snapshot of the DAT connection state for the UI.
 *
 * Covers Stage 2 (registration + discovery) and Stage 4 (device session lifecycle).
 * The SDK's own [DeviceSessionState] is stored directly so the UI shows the real state machine.
 */
data class WearablesUiState(
    val initialized: Boolean = false,
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: List<DeviceIdentifier> = emptyList(),
    val hasActiveDevice: Boolean = false,
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    // Stage 5: camera stream state + proof that frames are arriving.
    val streamState: StreamState = StreamState.STOPPED,
    val frameCount: Long = 0,
    val lastFrameInfo: String? = null,
    // Stage 5 (photo path): still capture, working around the firmware video-stream bug.
    val isCapturingPhoto: Boolean = false,
    val capturedPhoto: Bitmap? = null,
    // Stage 6: glasses microphone audio (rides the camera stream).
    val audioEnabled: Boolean = false,
    val audioFrameCount: Long = 0,
    val lastAudioInfo: String? = null,
    val audioLevel: Float = 0f, // peak amplitude of the last frame, 0..1
    // Stage 8: display output on the Ray-Ban Display.
    val isDisplayAttached: Boolean = false,
    val displayState: DisplayState? = null,
    val displayContentSent: Boolean = false,
    val recentError: String? = null,
) {
    val isRegistered: Boolean =
        registrationState == RegistrationState.REGISTERED ||
            registrationState == RegistrationState.UNREGISTERING

    val isRegistering: Boolean = registrationState == RegistrationState.REGISTERING

    // Registration can only be started once the SDK is initialized and not already busy.
    val canStartRegistration: Boolean = initialized && !isRegistering && !isRegistered

    /** A session exists and is connected or connecting. */
    val hasSession: Boolean =
        sessionState == DeviceSessionState.STARTING ||
            sessionState == DeviceSessionState.STARTED ||
            sessionState == DeviceSessionState.PAUSED ||
            sessionState == DeviceSessionState.STOPPING

    /** The session is fully connected — glasses are ready for camera/display (later stages). */
    val isSessionActive: Boolean = sessionState == DeviceSessionState.STARTED

    // Need to be registered with at least one device discovered before a session can be created.
    val canStartSession: Boolean = isRegistered && devices.isNotEmpty() && !hasSession

    /** The stream is attached and not in a terminal state. */
    val hasStream: Boolean =
        streamState != StreamState.STOPPED && streamState != StreamState.CLOSED

    val isStreaming: Boolean = streamState == StreamState.STREAMING

    // Can only start a camera stream once the session is fully connected and no stream is running.
    val canStartStream: Boolean = isSessionActive && !hasStream

    // Photo capture needs an attached stream (from addCamera), but not flowing video frames —
    // capturePhoto() works even while the firmware video-stream bug keeps frames at 0.
    val canCapturePhoto: Boolean = hasStream && !isCapturingPhoto

    // Display output needs a connected session; the display capability attaches on demand.
    val canUseDisplay: Boolean = isSessionActive
}
