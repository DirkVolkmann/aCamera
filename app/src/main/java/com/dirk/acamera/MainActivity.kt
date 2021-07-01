package com.dirk.acamera

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.dirk.acamera.fragments.Permission


private const val TAG = "aCamera MainActivity"

// List of granted permissions since last request
// Used to check if request have changed while app was running in the background
val permissionsGranted = mutableListOf<Permission>()

class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false);

        setContentView(R.layout.activity_main)
        container = findViewById(R.id.fragment_container)
    }
}
