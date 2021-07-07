package com.dirk.acamera.rtc

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import com.dirk.acamera.utils.Ratio
import com.dirk.acamera.utils.reduceRatio
import org.webrtc.*

private const val TAG = "aCamera RtcClient"

class RtcClient(
    context: Application,
    observer: PeerConnection.Observer
) {
    enum class Camera {
        NONE,
        FRONT,
        BACK
    }

    companion object {
        private const val VIDEO_ID = "acamera_video"
        private const val AUDIO_ID = "acamera_audio"
        private const val STREAM_ID = "acamera_stream"
    }

    private lateinit var surfaceViewRenderer: SurfaceViewRenderer

    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var isVideoInitialized = false
    private var isAudioInitialized = false
    private var isStreamInitialized = false
    private var cameraUsed = Camera.NONE
    private var isFlashEnabled = false
    private var resolution = Ratio(1280, 720)
    private var framerate = 30

    private val rootEglBase: EglBase = EglBase.create()
    private val iceServer = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val mediaStream by lazy { peerConnectionFactory.createLocalMediaStream(STREAM_ID) }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camera2Enumerator by lazy { FlashCamera2Enumerator(context, cameraManager) }
    private val cameraSwitchHandler by lazy { createCameraSwitchHandler() }
    private val videoCapturer by lazy { getLocalVideoCapturer() }
    private val videoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val audioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val peerConnection by lazy { buildPeerConnection(observer) }
    private val surfaceTextureHelper by lazy { SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase.eglBaseContext) }

    init {
        Log.d(TAG, "Creating RTC Client...")
        initPeerConnectionFactory(context)
        Log.d(TAG, "Creating RTC Client done")
    }

    private fun initPeerConnectionFactory(context: Application) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun buildPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory
            .builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()
    }

    private fun buildPeerConnection(observer: PeerConnection.Observer) =
        peerConnectionFactory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServer).apply {
                // disable CPU overuse detection or the configured resolution
                // will most likely be ignored
                enableCpuOveruseDetection = false
            },
            observer
        )

    /**
     * Camera
     */

    private fun getLocalVideoCapturer() = camera2Enumerator.run {
        val fc2c: FlashCameraVideoCapturer.CameraEventsHandler? = null
        getBackCamera()?.let {
            createCapturer(it, fc2c).also {
                cameraUsed = Camera.BACK
                surfaceViewRenderer.setMirror(false)
            }
        } ?: getFrontCamera()?.let {
            createCapturer(it, fc2c).also {
                cameraUsed = Camera.FRONT
                surfaceViewRenderer.setMirror(true)
            }
        } ?: throw IllegalStateException()
    }

    fun initSurfaceView(view: SurfaceViewRenderer) = view.run {
        Log.d(TAG, "Initializing surface view...")
        setEnableHardwareScaler(true)
        init(rootEglBase.eglBaseContext, null)
    }.also {
        surfaceViewRenderer = view
        Log.d(TAG, "Initializing surface view done")
    }

    private fun getFrontCamera() = camera2Enumerator.run {
        deviceNames.find {
            isFrontFacing(it)
        }
    }

    private fun getBackCamera() = camera2Enumerator.run {
        deviceNames.find {
            isBackFacing(it)
        }
    }

    fun switchCamera() {
        Log.d(TAG, "Switching camera...")
        videoCapturer.switchCamera(cameraSwitchHandler)
    }

    private fun createCameraSwitchHandler() = object : FlashCameraVideoCapturer.CameraSwitchHandler {
        override fun onCameraSwitchDone(p0: Boolean) {
            if (cameraUsed == Camera.FRONT) {
                cameraUsed = Camera.BACK
                surfaceViewRenderer.setMirror(false)
                Log.d(TAG, "Switched to back camera")
            } else {
                cameraUsed = Camera.FRONT
                surfaceViewRenderer.setMirror(true)
                Log.d(TAG, "Switched to front camera")
            }
        }

        override fun onCameraSwitchError(p0: String?) {
            Log.e(TAG, "Could not switch camera: $p0")
        }
    }

    fun setFlashlight(enabled: Boolean) {
        isFlashEnabled = enabled
        restartVideo()
    }

    /**
     * Media Stream
     */

    private fun updateMediaStream() {
        Log.d(TAG, "Updating stream...")

        if (!isVideoInitialized && videoTrack != null) {
            Log.d(TAG, "Initializing video track...")
            mediaStream.addTrack(videoTrack).also {
                Log.d(TAG, "Initializing video track done")
                isVideoInitialized = it
            }
        }
        if (!isAudioInitialized && audioTrack != null) {
            Log.d(TAG, "Initializing audio track...")
            mediaStream.addTrack(audioTrack).also {
                Log.d(TAG, "Initializing audio track done")
                isAudioInitialized = it
            }
        }

        if (!isStreamInitialized) {
            Log.d(TAG, "Adding stream to peer connection...")
            peerConnection?.addStream(mediaStream).also {
                if (it != null) {
                    Log.d(TAG, "Adding stream to peer connection done")
                    isStreamInitialized = it
                } else {
                    Log.e(TAG, "Could not add stream, no peer connection available")
                }
            }
        }

        Log.d(TAG, "Updating stream done")
    }

    /**
     * Video
     */

    private fun initVideo(videoOutput: SurfaceViewRenderer) {
        // Get supported formats for each available camera
        cameraManager.cameraIdList.forEach { camera ->
            Log.v(TAG, "Getting supported formats for camera [$camera]...")
            camera2Enumerator.getSupportedFormats(camera)?.forEach {
                val ratio = reduceRatio(it.width, it.height)
                Log.v(TAG, "Format: ${it.imageFormat} Size: ${it.width}x${it.height} (${ratio.width}x${ratio.height}) FPS: ${it.framerate}")
            }
        }

        videoCapturer.initialize(
            surfaceTextureHelper,
            videoOutput.context,
            videoSource.capturerObserver
        )
        startVideo()

        videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_ID, videoSource).apply {
            addSink(videoOutput)
        }
    }

    private fun startVideo() {
        videoCapturer.startCapture(resolution.width, resolution.height, framerate, isFlashEnabled)
    }

    private fun restartVideo() {
        videoCapturer.changeCaptureFormat(resolution.width, resolution.height, framerate, isFlashEnabled)
    }

    private fun stopVideo() {
        videoCapturer.stopCapture()
    }

    fun enableVideo(videoOutput: SurfaceViewRenderer) {
        if (videoTrack == null) {
            initVideo(videoOutput)
        } else {
            startVideo()
        }
        videoTrack?.setEnabled(true)
        updateMediaStream()
    }

    fun disableVideo() {
        videoTrack?.setEnabled(false)
    }

    /**
     * Audio
     */

    private fun initAudio() {
        audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_ID, audioSource)
    }

    fun enableAudio() {
        if (audioTrack == null) {
            initAudio()
        }
        audioTrack?.setEnabled(true)
        updateMediaStream()
    }

    fun disableAudio() {
        audioTrack?.setEnabled(false)
    }

    /**
     * Peer Connection
     */

    private fun PeerConnection.offer(sdpObserver: SdpObserver) {
        Log.d(TAG, "Adding constraints to 'OFFER'...")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        Log.d(TAG, "Adding constraints to 'OFFER' done")

        Log.d(TAG, "Creating 'OFFER'...")
        createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d(TAG, "Creating 'OFFER' success")

                Log.d(TAG, "Setting local description...")
                setLocalDescription(object : SdpObserver {
                    override fun onSetFailure(p0: String?) {
                        Log.d(TAG, "Setting local description failed")
                    }

                    override fun onSetSuccess() {
                        Log.d(TAG, "Setting local description success")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {
                        Log.d(TAG, "Creating local description success")
                    }

                    override fun onCreateFailure(p0: String?) {
                        Log.d(TAG, "Creating local description failed")
                    }
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }
        }, constraints)
    }

    fun offer(sdpObserver: SdpObserver) = peerConnection?.offer(sdpObserver)

    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        Log.d(TAG, "Received '${sessionDescription.type}'")
        Log.v(TAG, sessionDescription.description)

        Log.d(TAG, "Setting remote description...")
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetFailure(p0: String?) {
                Log.d(TAG, "Setting remote description failed")
            }

            override fun onSetSuccess() {
                Log.d(TAG, "Setting remote description success")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d(TAG, "Creating remote description success")
            }

            override fun onCreateFailure(p0: String?) {
                Log.d(TAG, "Creating remote description failed")
            }
        }, sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate?) {
        Log.v(TAG, "Adding ICE candidate: ${iceCandidate?.toString()}")
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun destroy() {
        stopVideo()
        peerConnection?.close()
    }
}
