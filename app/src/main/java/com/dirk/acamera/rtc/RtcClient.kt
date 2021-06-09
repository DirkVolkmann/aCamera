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

    init {
        initPeerConnectionFactory(context)
    }

    private val iceServer = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
            .createIceServer()
    )

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val videoCapturer by lazy { getVideoCapturer(context) }
    private val videoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val audioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val peerConnection by lazy { buildPeerConnection(observer) }

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

    fun startLocalVideoCapture(videoOutput: SurfaceViewRenderer) {
        val surfaceTextureHelper = SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase.eglBaseContext)
        (videoCapturer as VideoCapturer).initialize(surfaceTextureHelper, videoOutput.context, videoSource.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_ID, videoSource)
        videoTrack!!.addSink(videoOutput)

        audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_ID, audioSource)

        val stream = peerConnectionFactory.createLocalMediaStream(STREAM_ID)
        stream.addTrack(videoTrack)
        stream.addTrack(audioTrack)

        peerConnection?.addStream(stream)
    }

    fun enableVideo() {
        if (videoTrack != null) {
            videoTrack!!.setEnabled(true)
        }
    }

    fun disableVideo() {
        if (videoTrack != null) {
            videoTrack!!.setEnabled(false)
        }
    }

    fun enableAudio() {
        if (audioTrack != null) {
            audioTrack!!.setEnabled(true)
        }
    }

    fun disableAudio() {
        if (audioTrack != null) {
            audioTrack!!.setEnabled(false)
        }
    }

    private fun PeerConnection.offer(sdpObserver: SdpObserver) {
        Log.d(TAG, "Sending OFFER to remote client")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {

                setLocalDescription(object : SdpObserver {
                    override fun onSetFailure(p0: String?) {
                    }

                    override fun onSetSuccess() {
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {
                    }

                    override fun onCreateFailure(p0: String?) {
                    }
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }
        }, constraints)
    }

    fun offer(sdpObserver: SdpObserver) = peerConnection?.offer(sdpObserver)

    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        Log.d(TAG, "Received remote session: ${sessionDescription.type}\n${sessionDescription.description}")

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetFailure(p0: String?) {
            }

            override fun onSetSuccess() {
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
            }

            override fun onCreateFailure(p0: String?) {
            }
        }, sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate?) {
        Log.d(TAG, "Adding ICE candidate: ${iceCandidate?.toString()}")
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun destroy() {
        peerConnection?.close()
    }
}
