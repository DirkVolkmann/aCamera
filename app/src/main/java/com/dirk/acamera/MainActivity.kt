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
    private lateinit var signalingServer: SignalingServer

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var remoteViewProgress: ProgressBar
    private lateinit var remoteViewText: TextView

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

        localView = findViewById(R.id.local_view)
        remoteView = findViewById(R.id.remote_view)
        remoteViewProgress = findViewById(R.id.remote_view_progress)
        remoteViewText = findViewById(R.id.remote_view_text)

        remoteViewText.text = "Creating signaling server..."
        signalingServer = SignalingServer(createSignalingServerListener(), this)
        remoteViewText.text = "Starting signaling server..."
        signalingServer.run()

        remoteViewText.text = "Checking camera permissions..."
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
        remoteViewText.text = "Creating RTC client..."
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

        remoteViewText.text = "Creating signaling client..."
        signalingClient = SignalingClient(createSignalingClientListener(), 10)

        remoteViewText.text = "Waiting for connection..."
        //callButton.setOnClickListener { rtcClient.call(sdpObserver) }
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

    private fun onCameraPermissionDenied() {
        remoteViewProgress.isGone = true
        remoteViewText.text = "This app needs the camera to function"
        Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
    }

    /**
     * Signaling Server
     */

    private fun createSignalingServerListener() = object : SignalingServerListener {
        override fun onConnectionEstablished() {
            if (signalingClient.state == SignalingClient.State.WAITING_FOR_CLIENT) {
                Log.d(TAG, "Another client connected, sending offer...")
                remoteViewText.text = "Calling client..."
                rtcClient.offer(sdpObserver)
            }
        }

        override fun onConnectionAborted() {
            if (signalingClient.state != SignalingClient.State.CONNECTION_ABORTED) {
                Log.d(TAG,"Another client disconnected")
                signalingClient.state = SignalingClient.State.WAITING_FOR_CLIENT
                remoteViewText.text = "Waiting for connection..."
                //rtcClient.close()
            }
        }
    }

    /**
     * Signaling Client
     */

    private fun createSignalingClientListener() = object : SignalingClientListener {

        override fun onConnectionEstablished() {
            if (signalingServer.connections >= 2) {
                signalingClient.state = SignalingClient.State.WAITING_FOR_ANSWER
                Log.d(TAG, "Another client is already connected, sending offer...")
                //remoteViewText.text = "Calling client..."
                rtcClient.offer(sdpObserver)
            }
            else {
                //signalingClient.state = SignalingClient.State.WAITING_FOR_CLIENT
                //remoteViewText.text = "Waiting for connection..."
            }
        }

        override fun onConnectionFailed() {
            signalingClient.state = SignalingClient.State.CONNECTION_FAILED
            remoteViewProgress.isGone = true
            remoteViewText.text = "Connection failed"
        }

        override fun onConnectionAborted() {
            signalingClient.state = SignalingClient.State.CONNECTION_ABORTED
            remoteViewProgress.isGone = true
            remoteViewText.text = "Connection aborted"
            //rtcClient.close()
        }

        override fun onOfferReceived(description: SessionDescription) {
            Log.e(TAG, "This should not happen")
        }

        override fun onAnswerReceived(description: SessionDescription) {
            signalingClient.state = SignalingClient.State.WAITING_FOR_CONNECTION
            remoteViewText.text = "Waiting for peer connection to be established"
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
