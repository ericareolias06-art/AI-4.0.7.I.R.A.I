package com.iraitec

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/**
 * Conservative preflight for the currently bundled 1.2B LiteRT-LM model.
 * This is a safety gate, not a claim that a device can run inference.
 */
internal class IraiDeviceCapabilities(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    data class Check(val supported: Boolean, val reason: String? = null)

    fun checkBeforeInference(): Check {
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            return Check(false, "This phone needs a supported 64-bit processor for Irai's current local AI.")
        }
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_MEMORY) {
            return Check(
                false,
                "Not enough free memory for Irai's current AI model. Close other apps and try again."
            )
        }
        return Check(true)
    }

    fun checkBeforeDownload(estimatedBytes: Long, partialBytes: Long): Check {
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            return Check(false, "This device does not support the current 64-bit local AI runtime.")
        }
        val freeBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        val needed = (estimatedBytes - partialBytes).coerceAtLeast(0L) + DOWNLOAD_HEADROOM
        if (freeBytes < needed) {
            return Check(false, "Not enough free storage to download Irai's AI model.")
        }
        return Check(true)
    }

    companion object {
        // Conservative starting limits; adjust after physical-device profiling.
        private const val MIN_AVAILABLE_MEMORY = 1_100_000_000L
        private const val DOWNLOAD_HEADROOM = 300_000_000L
    }
}
