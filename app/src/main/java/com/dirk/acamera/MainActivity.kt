package com.dirk.acamera

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity


private const val TAG = "aCamera MainActivity"

/**
 * Permission variables
 */

// Self defined request code
const val PERMISSIONS_REQUEST_CODE = 10
// Camera permission string
const val PERMISSION_CAMERA = Manifest.permission.CAMERA
// Audio permission string
const val PERMISSION_AUDIO = Manifest.permission.RECORD_AUDIO
// List of all required permissions
val PERMISSIONS_REQUIRED = listOf(PERMISSION_CAMERA, PERMISSION_AUDIO)
// List of granted permissions since last request
// Used to check if request have changed while app was running in the background
val permissionsGranted = mutableMapOf(PERMISSION_CAMERA to false, PERMISSION_AUDIO to false)

class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val w: Window = window
            w.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.fragment_container)
    }
}
