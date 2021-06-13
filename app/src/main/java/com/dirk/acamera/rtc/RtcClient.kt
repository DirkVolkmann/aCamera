package com.dirk.acamera.rtc

import android.app.Application
import android.content.Context
import android.util.Log
import org.webrtc.*

private const val TAG = "aCamera RtcClient"

class RtcClient(
    context: Application,
    observer: PeerConnection.Observer
) {

    companion object {
        private const val VIDEO_ID = "acamera_video"
        private const val AUDIO_ID = "acamera_audio"
        private const val STREAM_ID = "acamera_stream"

    }

    private val rootEglBase: EglBase = EglBase.create()

    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var isVideoInitialized = false
    private var isAudioInitialized = false
    private var isStreamInitialized = false

    init {
        initPeerConnectionFactory(context)
    }

    private val iceServer = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
            .createIceServer()
    )

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val mediaStream by lazy { buildMediaStream(STREAM_ID) }
    private val videoCapturer by lazy { getVideoCapturer(context) }
    private val videoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val audioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val peerConnection by lazy { buildPeerConnection(observer) }
    private val surfaceTextureHelper by lazy { buildSurfaceTextureHelper() }

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

    private fun buildPeerConnection(observer: PeerConnection.Observer) = peerConnectionFactory.createPeerConnection(
        iceServer,
        observer
    )

    private fun buildMediaStream(id: String) = peerConnectionFactory.createLocalMediaStream(id)

    private fun buildSurfaceTextureHelper() = SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase.eglBaseContext)

    private fun getVideoCapturer(context: Context) =
        Camera2Enumerator(context).run {
            deviceNames.find {
                isFrontFacing(it)
            }?.let {
                createCapturer(it, null)
            } ?: throw IllegalStateException()
        }

    fun initSurfaceView(view: SurfaceViewRenderer) = view.run {
        setMirror(true)
        setEnableHardwareScaler(true)
        init(rootEglBase.eglBaseContext, null)
    }

    /**
     * Media Stream
     */

    private fun reloadMediaStream() {
        Log.d(TAG, "Reloading stream...")

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

        Log.d(TAG, "Reloading stream done")
    }

    /**
     * Video
     */

    private fun initVideo(videoOutput: SurfaceViewRenderer) {
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
        videoCapturer.startCapture(1280, 720, 30)
    }

    fun enableVideo(videoOutput: SurfaceViewRenderer) {
        if (videoTrack == null) {
            initVideo(videoOutput)
        } else {
            startVideo()
        }
        videoTrack?.setEnabled(true)

        reloadMediaStream()
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

        reloadMediaStream()
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
        peerConnection?.close()
    }
}
