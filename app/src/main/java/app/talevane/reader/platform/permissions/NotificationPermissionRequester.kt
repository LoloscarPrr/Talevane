package app.talevane.reader.platform.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** Keeps Android runtime-permission plumbing out of BookFlow's activity shell. */
object NotificationPermissionRequester {
    private const val REQUEST_CODE = 404

    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        }
    }
}
