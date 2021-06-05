package com.dirk.acamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionText: TextView

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
        connectionProgress = findViewById(R.id.connection_progress)
        connectionText = findViewById(R.id.connection_text)

        connectionText.text = "Creating signaling server..."
        signalingServer = SignalingServer(createSignalingServerListener(), this)
        connectionText.text = "Starting signaling server..."
    }

    override fun onResume() {
        super.onResume()

        connectionText.text = "Checking camera permissions..."
        checkCameraPermission()
    }

    override fun onDestroy() {
        signalingClient.destroy()
        signalingServer.stop()
        super.onDestroy()
    }

    /**
     * Permissions
     */

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
        connectionText.text = "Creating RTC client..."
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

        connectionText.text = "Creating local signaling client..."
        signalingClient = SignalingClient(createSignalingClientListener())

        connectionText.text = "Waiting for remote client..."
    }

    private fun onCameraPermissionDenied() {
        connectionProgress.isGone = true
        connectionText.isGone = false
        connectionText.text = "This app needs the camera to function"
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

                connectionProgress.isGone = false
                connectionText.isGone = false
                connectionText.text = "Calling remote client..."

                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionAborted() {
            Log.d(TAG, "onConnectionAborted called")

            if (signalingClient.state != SignalingClient.State.CONNECTION_ABORTED) {
                Log.d(TAG,"Remote client disconnected")

                connectionProgress.isGone = false
                connectionText.isGone = false
                connectionText.text = "Waiting for remote client..."
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

                connectionProgress.isGone = false
                connectionText.isGone = false
                connectionText.text = "Calling remote client..."

                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionFailed() {
            connectionProgress.isGone = true
            connectionText.isGone = false
            connectionText.text = "Connection failed"
        }

        override fun onConnectionAborted() {
            connectionProgress.isGone = true
            connectionText.isGone = false
            connectionText.text = "Connection aborted"
        }

        override fun onOfferReceived(description: SessionDescription) {
            Log.e(TAG, "Offer received - this should not happen!")
        }

        override fun onAnswerReceived(description: SessionDescription) {
            connectionProgress.isGone = true
            connectionText.isGone = true

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
}
