package dev.bold7.essai

import android.Manifest
import android.app.Activity
import android.os.Bundle

/**
 * Asks for one harmless runtime permission, as any app does, so that PermissionController shows
 * its request dialog, then goes away whatever the
 * answer. Extra "permission": calendar (READ_CALENDAR, the default: Allow / Don't allow) or camera
 * (CAMERA: While using the app / Only this time / Don't allow). Always refused during the tests:
 * `su -c 'am start -n dev.bold7.essai/.PermissionActivity --es permission camera'`.
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val permission = if (intent.getStringExtra("permission") == "camera") Manifest.permission.CAMERA else Manifest.permission.READ_CALENDAR
        requestPermissions(arrayOf(permission), 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) = finish()
}
