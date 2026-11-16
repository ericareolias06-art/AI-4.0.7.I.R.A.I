package com.iraitec

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Non-blocking Google Play flexible updates for Play-installed builds.
 * Availability is determined by Google Play, not by a remote custom server.
 */
class IraiPlayUpdates(private val activity: Activity) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var asking = false
    private var installPromptShowing = false
    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            activity.runOnUiThread { offerRestart() }
        }
    }

    init {
        manager.registerListener(installListener)
    }

    fun check(manual: Boolean = false) {
        if (asking || activity.isFinishing || activity.isDestroyed) return
        asking = true
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                asking = false
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    offerRestart()
                    return@addOnSuccessListener
                }
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    AlertDialog.Builder(activity)
                        .setTitle("IraiTec update available")
                        .setMessage("Google Play has a newer IraiTec version. Download the update while you keep using Irai?")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Update") { _, _ ->
                            try {
                                manager.startUpdateFlowForResult(
                                    info, activity,
                                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                                    REQUEST_CODE
                                )
                            } catch (e: Exception) {
                                Log.w("IRAI_UPDATES", "Could not start Play update", e)
                            }
                        }.show()
                } else if (manual) {
                    android.widget.Toast.makeText(activity, "No Google Play update is available for this account yet.", android.widget.Toast.LENGTH_LONG).show()
                }
            }.addOnFailureListener {
                asking = false
                if (manual && !activity.isFinishing) {
                    android.widget.Toast.makeText(activity, "Google Play update check is unavailable. Open the Play Store to check.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun offerRestart() {
        if (installPromptShowing || activity.isFinishing || activity.isDestroyed) return
        installPromptShowing = true
        AlertDialog.Builder(activity)
            .setTitle("IraiTec update downloaded")
            .setMessage("Restart IraiTec to finish installing the update?")
            .setNegativeButton("Later") { _, _ -> installPromptShowing = false }
            .setPositiveButton("Restart") { _, _ ->
                installPromptShowing = false
                manager.completeUpdate()
            }
            .setOnCancelListener { installPromptShowing = false }
            .show()
    }

    fun close() {
        manager.unregisterListener(installListener)
    }

    companion object {
        const val REQUEST_CODE = 4021
    }
}
