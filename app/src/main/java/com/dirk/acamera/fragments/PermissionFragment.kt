package com.dirk.acamera.fragments

import android.Manifest
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
 * The results can be viewed with the "companion object" functions
 */
class PermissionFragment : Fragment() {
    companion object {
        // Self defined request code
        const val PERMISSIONS_REQUEST_CODE = 10
        // Camera permission string
        const val PERMISSION_CAMERA = Manifest.permission.CAMERA
        // Audio permission string
        const val PERMISSION_AUDIO = Manifest.permission.RECORD_AUDIO
        // List of all required permissions
        val PERMISSIONS_REQUIRED: Array<String> = arrayOf(PERMISSION_CAMERA, PERMISSION_AUDIO)

        /* Check if all required permissions are granted */
        fun checkAllPermissionsGranted(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /* Check if the given permission is granted */
        fun checkPermissionGranted(context: Context, permission: Permission) : Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        /* Check if any permission has changed since the last check */
        fun checkPermissionsChanged(context: Context) : Boolean {
            PERMISSIONS_REQUIRED.forEach { permission ->
                if (checkPermissionGranted(context, permission) != permissionsGranted.contains(permission)) {
                    Log.d(TAG, "Permission '$permission' changed")
                    return true
                }
            }
            return false
        }

        /* Check if the permission dialog should be displayed to the user */
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
                permissionsGrantedAddSave(permission)
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

        // TODO: Use registerForActivityResult instead (1/2)
        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
    }

    private fun showPermissionRationaleDialog(permissions: Array<out Permission>) {
        // TODO: Vary dialog depending on which permissions are required
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_required_info))
            .setNeutralButton(getString(R.string.str_continue)) {dialog, _ ->
                dialog.dismiss()
                requestPermissions(permissions, true)
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out Permission>, grantResults: IntArray) {
        // TODO: Use registerForActivityResult instead (2/2)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            Log.d(TAG, "Permission request result arrived")
            for (i in permissions.indices) {
                val permission = permissions[i]
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission '" + permissions[i] + "' was granted")
                    permissionsGrantedAddSave(permission)
                } else {
                    Log.d(TAG, "Permission '" + permissions[i] + "' was denied")
                    permissionsGrantedRemoveSave(permission)
                }
            }
            onCheckPermissionsCompleted()
        }
    }

    private fun permissionsGrantedAddSave(permission: Permission) {
        if (!permissionsGranted.contains(permission))
            permissionsGranted.add(permission)
    }

    private fun permissionsGrantedRemoveSave(permission: Permission) {
        if (permissionsGranted.contains(permission))
            permissionsGranted.remove(permission)
    }
}
