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
import androidx.lifecycle.lifecycleScope
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
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

private const val TAG = "aCamera RtcFragment"

@ObsoleteCoroutinesApi
class RtcFragment : Fragment() {

    // Views
    private lateinit var container: ConstraintLayout
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var audioButton: ImageView
    private lateinit var videoButton: ImageView

    // Flags
    private var isVideoEnabled = true
    private var isAudioEnabled = true
    private var hasVideoPermission = false
    private var hasAudioPermission = false

    // Networking
    private lateinit var signalingClient: SignalingClient
    private lateinit var signalingServer: SignalingServer
    private lateinit var sdpObserver: SimpleSdpObserver
    private lateinit var rtcClient: RtcClient

    // Other
    private lateinit var streamUrl: String
    private lateinit var howToConnectList: SpannableStringBuilder

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

        // Initialize views
        container = view as ConstraintLayout
        localView = container.findViewById(R.id.local_view)
        audioButton = container.findViewById(R.id.button_audio)
        videoButton = container.findViewById(R.id.button_video)

        // Get values from settings
        streamUrl = "172.16.42.3:8080" // TODO: Read IP from device and port from app settings
        howToConnectList = SpannableStringBuilder(buildBulletList(resources.getStringArray(R.array.how_to_connect), 40))

        // Initialize networking services
        signalingClient = SignalingClient(createSignalingClientListener())
        signalingServer = SignalingServer(createSignalingServerListener(), requireContext())
        sdpObserver = createSdpObserver()
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
        rtcClient.destroy()
        signalingClient.destroy()
        signalingServer.stop()
    }

    /**
     * UI
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

    private fun updateUi() {

        // Update video button
        videoButton.let {
            it.isClickable = false
            if (hasVideoPermission) {
                // Set listener if permission is granted
                it.setOnClickListener {
                    if (isVideoEnabled) {
                        disableVideo()
                    } else {
                        enableVideo()
                    }
                }
                // Set button style
                if (isVideoEnabled) {
                    // Button is enabled
                    it.setImageResource(R.drawable.ic_videocam_black_24dp)
                    it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_secondary)
                } else {
                    // Button is disabled
                    it.setImageResource(R.drawable.ic_videocam_off_black_24dp)
                    it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_primary)
                }
                // Hide message on local view
                container.findViewById<TextView>(R.id.local_view_message).isGone = true
            } else {
                // Set listener if permission is denied
                it.setOnClickListener {
                    Toast.makeText(context, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
                }
                // Button style if no permission
                it.setImageResource(R.drawable.ic_videocam_off_black_24dp)
                it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_error)
                // Show local view message
                container.findViewById<TextView>(R.id.local_view_message).let { textView ->
                    textView.text = getString(R.string.camera_permission_denied_info)
                    textView.isGone = false
                }
            }
            it.isClickable = true
        }

        // Update audio button
        audioButton.let {
            it.isClickable = false
            if (hasAudioPermission) {
                // Set listener if permission is granted
                it.setOnClickListener {
                    if (isAudioEnabled) {
                        disableAudio()
                    } else {
                        enableAudio()
                    }
                }
                // Set button style
                if (isAudioEnabled) {
                    // Button is enabled
                    it.setImageResource(R.drawable.ic_mic_black_24dp)
                    it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_secondary)
                } else {
                    // Button is disabled
                    it.setImageResource(R.drawable.ic_mic_off_black_24dp)
                    it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_primary)
                }
            } else {
                // Set listener if permission is denied
                it.setOnClickListener {
                    Toast.makeText(context, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
                }
                // Button style if no permission
                it.setImageResource(R.drawable.ic_mic_off_black_24dp)
                it.backgroundTintList = requireContext().getColorStateList(R.color.design_default_color_error)
            }
            it.isClickable = true
        }
    }

    /**
     * Permissions
     */

    private fun checkPermissions() {
        // Ask for any permissions if necessary
        if (PermissionFragment.checkPermissionsChanged(requireContext()) ||
            PermissionFragment.checkShowDialog(requireActivity(), PERMISSIONS_REQUIRED.toTypedArray())) {
            Log.d(TAG, "Permissions changed or show dialog")
            if (!PermissionFragment.checkAllPermissionsGranted(requireContext())) {
                Log.d(TAG, "Checking permissions...")
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    RtcFragmentDirections.actionRtcToPermission()
                )
                Log.d(TAG, "Checking permissions done")
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
        hasVideoPermission = true
        if (isVideoEnabled) {
            enableVideo()
        }
    }

    private fun onCameraPermissionDenied() {
        Log.d(TAG, "Camera permission was not granted")
        hasVideoPermission = false
        disableVideo()
    }

    private fun onAudioPermissionGranted() {
        Log.d(TAG, "Audio permission was granted")
        hasAudioPermission = true
        if (isAudioEnabled) {
            enableAudio()
        }
    }

    private fun onAudioPermissionDenied() {
        Log.d(TAG, "Audio permission was not granted")
        hasAudioPermission = false
        disableAudio()
    }

    /**
     * Peer Connection Observer
     */

    private fun createPeerConnectionObserver() = object : PeerConnectionObserver() {
        override fun onIceCandidate(p0: IceCandidate?) {
            super.onIceCandidate(p0)
            signalingClient.send(p0)
            rtcClient.addIceCandidate(p0)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            super.onConnectionChange(newState)
            Log.d(TAG, "New connection state: $newState")
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                // Launch in main thread so we can edit views
                lifecycleScope.launchWhenStarted { hideConnectionBox() }
            }
        }

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            super.onSignalingChange(p0)
            Log.d(TAG, "New signaling state: $p0")
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            super.onIceConnectionChange(p0)
            Log.d(TAG, "New ICE connections state: $p0")
        }
    }

    /**
     * SDP Observer
     */

    private fun createSdpObserver() = object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            super.onCreateSuccess(p0)
            signalingClient.send(p0)
        }
    }

    /**
     * Signaling Server
     */

    private fun createSignalingServerListener() = object : SignalingServerListener {
        override fun onConnectionEstablished() {
        if (signalingClient.state == SignalingClient.State.CONNECTION_ESTABLISHED) {
                Log.d(TAG, "Remote client connected")
                sendOffer()
            }
        }

        override fun onConnectionAborted() {
            if (signalingClient.state != SignalingClient.State.CONNECTION_ABORTED) {
                Log.d(TAG,"Remote client disconnected")
                showConnectionBox(getString(R.string.conn_status_remote_client), howToConnectList, streamUrl)
            }
        }
    }

    /**
     * Signaling Client
     */

    private fun createSignalingClientListener() = object : SignalingClientListener {

        override fun onConnectionEstablished() {
            if (signalingServer.connections >= 2) {
                Log.d(TAG, "Remote client is already connected")
                sendOffer()
            }
        }

        override fun onConnectionFailed() {
            if (signalingClient.retriesDone < signalingClient.retriesTotal) {
                signalingClient.connect(1000)
            } else {
                showConnectionBox(
                    getString(R.string.conn_status_failed),
                    showProgressBar = false
                )
            }
        }

        override fun onConnectionAborted() {
            showConnectionBox(getString(R.string.conn_status_aborted), showProgressBar = false)
        }

        override fun onOfferReceived(description: SessionDescription) {
            Log.e(TAG, "Received 'OFFER' ... this should not happen!")
        }

        override fun onAnswerReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
        }

        override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
            rtcClient.addIceCandidate(iceCandidate)
        }
    }

    private fun sendOffer() {
        Log.d(TAG, "Sending 'OFFER'...")
        if (permissionsGranted.isNotEmpty()) {
            showConnectionBox(getString(R.string.conn_status_connecting))
        }
        rtcClient.offer(sdpObserver)
    }

    /**
     * Watchdog
     */

    // TODO: watchdog restarts signaling server and client when they crash

    /**
     * Buttons
     */

    private fun enableVideo() {
        Log.d(TAG, "Enabling video...")
        rtcClient.enableVideo(localView)
        isVideoEnabled = true
        Log.d(TAG, "Enabling video done")
        updateUi()
    }

    private fun disableVideo() {
        Log.d(TAG, "Disabling video...")
        rtcClient.disableVideo()
        isVideoEnabled = false
        Log.d(TAG, "Disabling video done")
        updateUi()
    }

    private fun enableAudio() {
        Log.d(TAG, "Enabling audio...")
        rtcClient.enableAudio()
        isAudioEnabled = true
        Log.d(TAG, "Enabling audio done")
        updateUi()
    }

    private fun disableAudio() {
        Log.d(TAG, "Disabling audio...")
        rtcClient.disableAudio()
        isAudioEnabled = false
        Log.d(TAG, "Disabling audio done")
        updateUi()
    }
}
