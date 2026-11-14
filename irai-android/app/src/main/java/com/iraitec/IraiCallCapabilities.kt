package com.iraitec

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Capability hints only. Manufacturer-specific call speech features have no
 * supported public API for Irai to invoke or verify as enabled.
 */
data class IraiCallCapabilities(
    val device: String,
    val hasCellularCalling: Boolean,
    val nativeSpokenRepliesHint: String,
    val rttHint: String,
    val supportedIntegration: Boolean = false
) {
    companion object {
        fun detect(context: Context): IraiCallCapabilities {
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            val phone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            val brand = manufacturer.lowercase(java.util.Locale.US)
            val spoken = when {
                brand.contains("samsung") ->
                    "Samsung Text Call may be available. Check Phone > Settings > Text call. Irai cannot automatically send text to Samsung Text Call."
                brand.contains("google") ->
                    "Some Pixel phones support typed spoken replies through Live Caption. Check Accessibility settings. Irai cannot automatically inject call audio."
                else ->
                    "No verified system integration for spoken replies. Irai can prepare replies, but cannot inject speech into cellular calls."
            }
            val rtt = if (phone) {
                "RTT support depends on your carrier, SIM, phone and dialer settings; Irai cannot guarantee it is enabled."
            } else {
                "No cellular telephony capability was detected."
            }
            return IraiCallCapabilities(
                device = "$manufacturer $model (Android ${Build.VERSION.RELEASE})".trim(),
                hasCellularCalling = phone,
                nativeSpokenRepliesHint = spoken,
                rttHint = rtt
            )
        }
    }
}
