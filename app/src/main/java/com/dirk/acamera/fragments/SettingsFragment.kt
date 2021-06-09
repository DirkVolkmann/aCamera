package com.dirk.acamera.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.dirk.acamera.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }
}
