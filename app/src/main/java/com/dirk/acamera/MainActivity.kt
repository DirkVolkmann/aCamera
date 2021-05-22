package com.dirk.acamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

private const val TAG = "aCamera MainActivity"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private lateinit var rtcClient: RtcClient
    private lateinit var signalingClient: SignalingClient

    private lateinit var callButton: FloatingActionButton
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var remoteViewProgress: ProgressBar
    private lateinit var remoteViewText: TextView
    private lateinit var restartButton: Button

    private val sdpObserver = object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            super.onCreateSuccess(p0)
            signalingClient.send(p0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "onCreate: executed")

        callButton = findViewById(R.id.call_button)
        localView = findViewById(R.id.local_view)
        remoteView = findViewById(R.id.remote_view)
        remoteViewProgress = findViewById(R.id.remote_view_progress)
        remoteViewText = findViewById(R.id.remote_view_text)
        restartButton = findViewById(R.id.restart_button)

        val signalingServer = SignalingServer(this)
        signalingServer.start()

        checkCameraPermission()
    }

    override fun onDestroy() {
        signalingClient.destroy()
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

    private fun onCameraPermissionGranted() {
        rtcClient = RtcClient(
            application,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    signalingClient.send(p0)
                    rtcClient.addIceCandidate(p0)
                }

                override fun onAddStream(p0: MediaStream?) {
                    super.onAddStream(p0)
                    p0?.videoTracks?.get(0)?.addSink(remoteView)
                }
            }
        )
        rtcClient.initSurfaceView(remoteView)
        rtcClient.initSurfaceView(localView)
        rtcClient.startLocalVideoCapture(localView)

        signalingClient = SignalingClient(createSignalingClientListener(), 10)

        callButton.setOnClickListener { rtcClient.call(sdpObserver) }
        callButton.isEnabled = false
        restartButton.setOnClickListener { restartSignalingClient() }
        //restartButton.isEnabled = false

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
            .setMessage("This app need the camera to function")
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

    private fun onCameraPermissionDenied() {
        Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
    }

    /**
     * SignalingClient
     */

    private fun createSignalingClientListener() = object : SignalingClientListener {

        override fun onConnectionEstablished() {
            callButton.isEnabled = true
        }

        override fun onConnectionFailed() {
            callButton.isEnabled = false
            restartButton.isEnabled = true
            remoteViewProgress.isGone = true
            remoteViewText.isGone = true
        }

        override fun onConnectionAborted() {
            callButton.isEnabled = false
            restartButton.isEnabled = true
            remoteViewProgress.isGone = true
            remoteViewText.isGone = true
        }

        override fun onOfferReceived(description: SessionDescription) {
            Log.d(TAG, "Signaling Client received offer")
            rtcClient.onRemoteSessionReceived(description)
            rtcClient.answer(sdpObserver)
            remoteViewProgress.isGone = true
            remoteViewText.isGone = true
        }

        override fun onAnswerReceived(description: SessionDescription) {
            rtcClient.onRemoteSessionReceived(description)
            remoteViewProgress.isGone = true
            remoteViewText.isGone = true
        }

        override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
            rtcClient.addIceCandidate(iceCandidate)
        }
    }

    private fun restartSignalingClient() {
        callButton.isEnabled = false
        restartButton.isEnabled = false
        remoteViewProgress.isGone = false
        remoteViewText.isGone = false
        signalingClient.destroy()
        signalingClient = SignalingClient(createSignalingClientListener(), 10)
    }

    /**
     * Watchdog
     */

    // TODO: watchdog restarts signaling server and client when they crash
}
