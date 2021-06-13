package com.dirk.acamera.fragments

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.dirk.acamera.*

private const val TAG = "aCamera PermissionFragment"

typealias Permission = String

/**
 * This fragments only purpose is to request permissions
 * Depending on the result it will show different parts of the app
 */
class PermissionFragment : Fragment() {
    companion object {
        fun checkAllPermissionsGranted(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun checkPermissionGranted(context: Context, permission: Permission) : Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        fun checkPermissionsChanged(context: Context) : Boolean {
            PERMISSIONS_REQUIRED.forEach { permission ->
                if (checkPermissionGranted(context, permission) != permissionsGranted[permission]) {
                    Log.d(TAG, "Permission '$permission' changed")
                    return true
                }
            }
            return false
        }

        fun checkShowDialog(activity: Activity, permissions: Array<out Permission>) : Boolean {
            permissions.forEach {
                if (shouldShowRequestPermissionRationale(activity, it)) {
                    return true
                }
            }
            return false
        }
    }

    // List of all denied permissions that will be requested again
    private val permissionsToRequest = PERMISSIONS_REQUIRED.toMutableList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllPermissions()
    }

    private fun checkAllPermissions() {
        Log.d(TAG, "Checking permissions...")

        // Check permissions that are not yet granted
        PERMISSIONS_REQUIRED.forEach { permission ->
            if (checkPermissionGranted(requireContext(), permission)) {
                Log.d(TAG, "Permission '$permission' already granted")
                permissionsToRequest.remove(permission)
                permissionsGranted[permission] = true
            } else {
                Log.d(TAG, "Permission '$permission' not yet granted")
            }
        }

        // We only need to ask for permissions if there are any denied
        if (permissionsToRequest.isEmpty()) {
            onCheckPermissionsCompleted()
        } else {
            requestPermissions(permissionsToRequest.toTypedArray())
        }
    }

    private fun onCheckPermissionsCompleted() {
        Log.d(TAG, "Permission check completed, switching to other fragment")
        lifecycleScope.launchWhenStarted {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                PermissionFragmentDirections.actionPermissionToRtc()
            )
        }
    }

    private fun requestPermissions(permissions: Array<out Permission>, skipDialog: Boolean = false) {
        Log.d(TAG, "Requesting permissions...")

        if (!skipDialog && checkShowDialog(requireActivity(), permissions)) {
            Log.d(TAG, "Showing dialog first...")
            showPermissionRationaleDialog(permissions)
            return
        }

        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
    }

    private fun showPermissionRationaleDialog(permissions: Array<out Permission>) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_required_info))
            .setNeutralButton(getString(R.string.str_continue)) {dialog, _ ->
                dialog.dismiss()
                requestPermissions(permissions, true)
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out Permission>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            Log.d(TAG, "Permission request result arrived")
            for (i in permissions.indices) {
                val permission = permissions[i]
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission '" + permissions[i] + "' was granted")
                    permissionsGranted[permission] = true
                } else {
                    Log.d(TAG, "Permission '" + permissions[i] + "' was denied")
                    permissionsGranted[permission] = false
                }
            }
            onCheckPermissionsCompleted()
        }
    }
}
