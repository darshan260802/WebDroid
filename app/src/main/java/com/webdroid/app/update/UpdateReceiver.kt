package com.webdroid.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Results from Android's package installer, and the first start after an update. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        // WebDroid is in the foreground (the user just tapped Install).
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { AppUpdater.onInstallResult(context, PackageInstaller.STATUS_FAILURE, "Couldn't open the installer") }
                    }
                } else {
                    AppUpdater.onInstallResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppUpdater.onUpdated(context)
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.webdroid.app.UPDATE_INSTALL_STATUS"
    }
}
