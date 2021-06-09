package com.dirk.acamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Html
import android.text.Html.fromHtml
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import com.dirk.acamera.rtc.PeerConnectionObserver
import com.dirk.acamera.rtc.RtcClient
import com.dirk.acamera.rtc.SimpleSdpObserver
import com.dirk.acamera.signaling.SignalingClient
import com.dirk.acamera.signaling.SignalingClientListener
import com.dirk.acamera.signaling.SignalingServer
import com.dirk.acamera.signaling.SignalingServerListener
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.w3c.dom.Text
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

private const val TAG = "aCamera MainActivity"

@ObsoleteCoroutinesApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private lateinit var rtcClient: RtcClient
    private lateinit var signalingClient: SignalingClient
    private lateinit var signalingServer: SignalingServer

    private lateinit var localView: SurfaceViewRenderer

    private val sdpObserver = object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            super.onCreateSuccess(p0)
            signalingClient.send(p0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.local_view)

        setButtonListeners()

        showConnectionBox("Creating signaling server...")
        signalingServer = SignalingServer(createSignalingServerListener(), this)
    }

    override fun onResume() {
        super.onResume()

        showConnectionBox("Checking permissions...")
        checkCameraPermission()
    }

    override fun onDestroy() {
        signalingClient.destroy()
        signalingServer.stop()
        super.onDestroy()
    }

    /**
     * Views
     */

    private fun showConnectionBox(text: String, textSecondary: String? = null, showProgressBar: Boolean = true) {
        val connText = findViewById<TextView>(R.id.connection_text)
        val connTextSec = findViewById<TextView>(R.id.connection_text_secondaray)
        val connProg = findViewById<ProgressBar>(R.id.connection_progress)
        val connCon = findViewById<CardView>(R.id.connection_container)

        connText.text = text
        connText.isGone = false

        if (textSecondary != null) {
            connTextSec.text = textSecondary
            connTextSec.isGone = false
        } else {
            connTextSec.isGone = true
        }

        connProg.isGone = (!showProgressBar)

        connCon.isGone = false
    }

    private fun hideConnectionBox() {
        findViewById<CardView>(R.id.connection_container).isGone = true
    }

    /**
     * Permissions
     */

    // TODO: Move permission stuff to own class/fragment

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        } else {
            onCameraPermissionGranted()
        }
    }

    private fun requestCameraPermission(dialogShown: Boolean = false) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION) && !dialogShown) {
            showPermissionRationaleDialog()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage("This app needs the camera to function")
            .setPositiveButton("Grant") { dialog, _ ->
                dialog.dismiss()
                requestCameraPermission(true)
            }
            .setNegativeButton("Deny") { dialog, _ ->
                dialog.dismiss()
                onCameraPermissionDenied()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onCameraPermissionGranted()
        } else {
            onCameraPermissionDenied()
        }
    }

    private fun onCameraPermissionGranted() {
        showConnectionBox("Creating RTC client...")
        rtcClient = RtcClient(
            application,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    signalingClient.send(p0)
                    rtcClient.addIceCandidate(p0)
                }
            }
        )
        rtcClient.initSurfaceView(localView)
        rtcClient.startLocalVideoCapture(localView)

        showConnectionBox("Creating local signaling client...")
        signalingClient = SignalingClient(createSignalingClientListener())

        val streamUrl: String = "172.16.42.3:8080"
        showConnectionBox(
            "Waiting for remote client...",
            "&#8226; Start a web browser on the device you are streaming to<br>&#8226; Type the following URL in the address bar: $streamUrl"
        )
    }

    private fun onCameraPermissionDenied() {
        showConnectionBox("This app needs the camera to function")
        Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
    }

    /**
     * Signaling Server
     */

    private fun createSignalingServerListener() = object : SignalingServerListener {
        override fun onConnectionEstablished() {
            Log.d(TAG, "onConnectionEstablished called")

            if (signalingClient.state == SignalingClient.State.CONNECTION_ESTABLISHED) {
                Log.d(TAG, "Remote client connected, sending offer...")

                showConnectionBox("Calling remote client...")

                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionAborted() {
            Log.d(TAG, "onConnectionAborted called")

            if (signalingClient.state != SignalingClient.State.CONNECTION_ABORTED) {
                Log.d(TAG,"Remote client disconnected")

                showConnectionBox("Waiting for remote client...")
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

                showConnectionBox("Calling remote client...")

                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionFailed() {
            showConnectionBox("Connection failed", showProgressBar = false)
        }

        override fun onConnectionAborted() {
            showConnectionBox("Connection aborted", showProgressBar = false)
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

    // TODO: Move to separate file

    private var isVideoEnabled = true
    private var isAudioEnabled = true

    private fun setButtonListeners() {
        val audioButton = findViewById<ImageView>(R.id.button_audio)
        val videoButton = findViewById<ImageView>(R.id.button_video)

        val audioButtonEnabledSrc = R.drawable.ic_mic_black_24dp
        val audioButtonDisabledSrc = R.drawable.ic_mic_off_black_24dp
        val videoButtonEnabledSrc = R.drawable.ic_videocam_black_24dp
        val videoButtonDisabledSrc = R.drawable.ic_videocam_off_black_24dp

        audioButton.setOnClickListener {
            isAudioEnabled = if (isAudioEnabled) {
                audioButton.setImageResource(audioButtonDisabledSrc)
                rtcClient.disableAudio()
                false
            } else {
                audioButton.setImageResource(audioButtonEnabledSrc)
                rtcClient.enableAudio()
                true
            }
        }

        videoButton.setOnClickListener {
            isVideoEnabled = if (isVideoEnabled) {
                videoButton.setImageResource(videoButtonDisabledSrc)
                rtcClient.disableVideo()
                false
            } else {
                videoButton.setImageResource(videoButtonEnabledSrc)
                rtcClient.enableVideo()
                true
            }
        }
    }

}
