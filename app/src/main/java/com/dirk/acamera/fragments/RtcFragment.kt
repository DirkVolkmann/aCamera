package com.dirk.acamera.fragments

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.navigation.Navigation
import com.dirk.acamera.*
import com.dirk.acamera.rtc.PeerConnectionObserver
import com.dirk.acamera.rtc.RtcClient
import com.dirk.acamera.rtc.SimpleSdpObserver
import com.dirk.acamera.signaling.SignalingClient
import com.dirk.acamera.signaling.SignalingClientListener
import com.dirk.acamera.signaling.SignalingServer
import com.dirk.acamera.signaling.SignalingServerListener
import com.dirk.acamera.utils.buildBulletList
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

private const val TAG = "aCamera RtcFragment"

@ObsoleteCoroutinesApi
class RtcFragment : Fragment() {

    private lateinit var container: ConstraintLayout
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var audioButton: ImageView
    private lateinit var videoButton: ImageView

    private lateinit var rtcClient: RtcClient
    private lateinit var signalingClient: SignalingClient
    private lateinit var signalingServer: SignalingServer

    private var isVideoEnabled = true
    private var isAudioEnabled = true

    private val sdpObserver = object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            super.onCreateSuccess(p0)
            signalingClient.send(p0)
        }
    }

    /**
     * Fragment Lifecycle
     */

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_rtc, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated called")

        container = view as ConstraintLayout

        localView = container.findViewById(R.id.local_view)
        audioButton = container.findViewById(R.id.button_audio)
        videoButton = container.findViewById(R.id.button_video)

        setButtonListeners()

        showConnectionBox(activityGetString(R.string.conn_status_signaling_server))
        signalingServer = SignalingServer(createSignalingServerListener(), requireContext())

        showConnectionBox(activityGetString(R.string.conn_status_signaling_client))
        signalingClient = SignalingClient(createSignalingClientListener())

        showConnectionBox(activityGetString(R.string.conn_status_rtc_client))
        rtcClient = RtcClient(requireActivity().application, createPeerConnectionObserver())
        rtcClient.initSurfaceView(localView)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        checkPermissions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView called")

        signalingClient.destroy()
        signalingServer.stop()
    }

    /**
     * Views
     */

    private fun showConnectionBox(text: CharSequence, textSecondary: CharSequence? = null, textNotice: CharSequence? = null, showProgressBar: Boolean = true) {
        val connectionText = container.findViewById<TextView>(R.id.connection_text)
        val connectionTextSecondary = container.findViewById<TextView>(R.id.connection_text_secondary)
        val connectionTextNotice = container.findViewById<TextView>(R.id.connection_text_notice)
        val connectionProgress = container.findViewById<ProgressBar>(R.id.connection_progress)
        val connectionContainer = container.findViewById<CardView>(R.id.connection_container)

        connectionText.text = text
        connectionText.isGone = false

        if (textSecondary != null) {
            connectionTextSecondary.text = textSecondary
            connectionTextSecondary.isGone = false
        } else {
            connectionTextSecondary.isGone = true
        }

        if (textNotice != null) {
            connectionTextNotice.text = textNotice
            connectionTextNotice.isGone = false
        } else {
            connectionTextNotice.isGone = true
        }

        connectionProgress.isGone = (!showProgressBar)

        connectionContainer.isGone = false
    }

    private fun hideConnectionBox() {
        container.findViewById<CardView>(R.id.connection_container).isGone = true
    }

    private fun activityGetString(resId: Int) : String {
        return requireActivity().getString(resId)
    }

    /**
     * Permissions
     */

    private fun checkPermissions() {
        if (PermissionFragment.checkPermissionsChanged(requireContext())) {
            Log.d(TAG, "Permissions changed")
            if (!PermissionFragment.checkAllPermissionsGranted(requireContext())) {
                Log.d(TAG, "Checking permissions...")
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    RtcFragmentDirections.actionRtcToPermission()
                )
            } else {
                Log.d(TAG, "All permissions granted")
            }
        } else {
            Log.d(TAG, "Permissions have not changed")
        }

        if (PermissionFragment.checkPermissionGranted(requireContext(), PERMISSION_CAMERA)) {
            onCameraPermissionGranted() // activate camera functions
        } else {
            onCameraPermissionDenied()  // deactivate camera function
        }

        if (PermissionFragment.checkPermissionGranted(requireContext(), PERMISSION_AUDIO)) {
            onAudioPermissionGranted()  // activate audio functions
        } else {
            onAudioPermissionDenied()   // deactivate audio functions
        }
    }

    private fun onCameraPermissionGranted() {
        Log.d(TAG, "Camera permission was granted")
        if (isVideoEnabled) {
            enableVideo()
            val streamUrl = "172.16.42.3:8080"
            val bulletList = SpannableStringBuilder(buildBulletList(resources.getStringArray(R.array.how_to_connect), 40))
            showConnectionBox(activityGetString(R.string.conn_status_remote_client), bulletList, streamUrl)
        } else {
            Log.d(TAG, "Video disabled, noting to do")
        }
    }

    private fun onCameraPermissionDenied() {
        Log.d(TAG, "Camera permission was not granted")
        disableVideo()
        videoButton.setOnClickListener {
            Toast.makeText(context, activityGetString(R.string.permission_required_info), Toast.LENGTH_LONG).show()
        }
        showConnectionBox(activityGetString(R.string.permission_required_info))
        Toast.makeText(context, activityGetString(R.string.permission_denied), Toast.LENGTH_LONG).show()
    }

    private fun onAudioPermissionGranted() {
        Log.d(TAG, "Audio permission was granted")
        if (isAudioEnabled) {
            enableAudio()
        } else {
            Log.d(TAG, "Audio disabled, nothing to do")
        }
    }

    private fun onAudioPermissionDenied() {
        Log.d(TAG, "Audio permission was not granted")
        disableAudio()
        audioButton.setOnClickListener {
            Toast.makeText(context, activityGetString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * RTC Client
     */

    private fun createPeerConnectionObserver() = object : PeerConnectionObserver() {
        override fun onIceCandidate(p0: IceCandidate?) {
            super.onIceCandidate(p0)
            signalingClient.send(p0)
            rtcClient.addIceCandidate(p0)
        }
    }

    /**
     * Signaling Server
     */

    private fun createSignalingServerListener() = object : SignalingServerListener {
        override fun onConnectionEstablished() {
        if (signalingClient.state == SignalingClient.State.CONNECTION_ESTABLISHED) {
                Log.d(TAG, "Remote client connected, sending offer...")
                showConnectionBox(activityGetString(R.string.conn_status_connecting))
                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionAborted() {
            if (signalingClient.state != SignalingClient.State.CONNECTION_ABORTED) {
                Log.d(TAG,"Remote client disconnected")
                showConnectionBox(activityGetString(R.string.conn_status_remote_client))
            }
        }
    }

    /**
     * Signaling Client
     */

    private fun createSignalingClientListener() = object : SignalingClientListener {

        override fun onConnectionEstablished() {
            if (signalingServer.connections >= 2) {
                Log.d(TAG, "Another client is already connected, sending offer...")
                showConnectionBox(activityGetString(R.string.conn_status_connecting))
                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionFailed() {
            if (signalingClient.retriesDone < signalingClient.retriesTotal) {
                signalingClient.connect(1000)
            } else {
                showConnectionBox(
                    activityGetString(R.string.conn_status_failed),
                    showProgressBar = false
                )
            }
        }

        override fun onConnectionAborted() {
            showConnectionBox(activityGetString(R.string.conn_status_aborted), showProgressBar = false)
        }

        override fun onOfferReceived(description: SessionDescription) {
            Log.e(TAG, "Offer received - this should not happen!")
        }

        override fun onAnswerReceived(description: SessionDescription) {
            hideConnectionBox()
            rtcClient.onRemoteSessionReceived(description)
        }

        override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
            rtcClient.addIceCandidate(iceCandidate)
        }
    }

    /**
     * Watchdog
     */

    // TODO: watchdog restarts signaling server and client when they crash

    /**
     * Media
     */

    private fun setButtonListeners() {
        videoButton.setOnClickListener {
            if (isVideoEnabled) {
                disableVideo()
            } else {
                enableVideo()
            }
        }

        audioButton.setOnClickListener {
            if (isAudioEnabled) {
                disableAudio()
            } else {
                enableAudio()
            }
        }
    }

    private fun enableVideo() {
        Log.d(TAG, "Enabling video...")
        videoButton.isClickable = false
        videoButton.setImageResource(R.drawable.ic_videocam_black_24dp)
        rtcClient.enableVideo(localView)
        isVideoEnabled = true
        videoButton.isClickable = true
    }

    private fun disableVideo() {
        Log.d(TAG, "Disabling video...")
        videoButton.isClickable = false
        videoButton.setImageResource(R.drawable.ic_videocam_off_black_24dp)
        rtcClient.disableVideo()
        isVideoEnabled = false
        videoButton.isClickable = true
    }

    private fun enableAudio() {
        Log.d(TAG, "Enabling audio...")
        audioButton.isClickable = false
        audioButton.setImageResource(R.drawable.ic_mic_black_24dp)
        rtcClient.enableAudio()
        isAudioEnabled = true
        audioButton.isClickable = true
    }

    private fun disableAudio() {
        Log.d(TAG, "Disabling audio...")
        audioButton.isClickable = false
        audioButton.setImageResource(R.drawable.ic_mic_off_black_24dp)
        rtcClient.disableAudio()
        isAudioEnabled = false
        audioButton.isClickable = true
    }
}
