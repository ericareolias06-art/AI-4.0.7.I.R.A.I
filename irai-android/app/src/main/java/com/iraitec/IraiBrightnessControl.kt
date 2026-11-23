package com.iraitec

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlin.math.roundToInt

/** Handles only explicit brightness commands, never arbitrary AI-generated instructions. */
object IraiBrightnessControl {
    private val target = Regex("(?:brightness|screen brightness|screen light)")
    private val percent = Regex("(?:set |to |at |brightness )?(\\d{1,3})\\s*(?:percent|%)")
    private val change = Regex("(?:turn|set|make|adjust|lower|raise|increase|decrease|dim|brighten|reduce)")

    fun isCommand(input: String): Boolean {
        val text = input.lowercase(java.util.Locale.US).trim()
        if (!target.containsMatchIn(text) &&
            text !in setOf("make it brighter", "make it darker", "dim the screen", "brighten the screen",
                "minimum brightness", "maximum brightness")) return false
        return change.containsMatchIn(text) || text.contains("brighter") ||
            text.contains("darker") || text.contains("minimum") || text.contains("maximum")
    }

    fun canControl(activity: Activity): Boolean = Settings.System.canWrite(activity)

    fun requestPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${activity.packageName}"))
        try { activity.startActivity(intent) }
        catch (_: Exception) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun execute(activity: Activity, command: String): String {
        if (!canControl(activity)) {
            requestPermission(activity)
            return "Please allow Irai to modify system settings. Then ask me to adjust brightness again."
        }
        val text = command.lowercase(java.util.Locale.US).trim()
        return try {
            val current = Settings.System.getInt(activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
            val currentPercent = (current * 100f / 255f).roundToInt()
            val exact = percent.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val next = when {
                exact != null -> exact
                text.contains("minimum") || text.contains("lowest") -> 5
                text.contains("maximum") || text.contains("max brightness") -> 100
                text.contains("brighter") || text.contains("increase") ||
                    text.contains("raise") || text.contains("turn up") ||
                    text.contains("brighten") -> currentPercent + 15
                text.contains("darker") || text.contains("lower") ||
                    text.contains("decrease") || text.contains("turn down") ||
                    text.contains("dim") || text.contains("reduce") -> currentPercent - 15
                else -> return "Tell me to raise, lower, or set brightness to a percentage."
            }.coerceIn(5, 100)
            Settings.System.putInt(activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val value = (next * 255f / 100f).roundToInt().coerceIn(1, 255)
            val updated = Settings.System.putInt(activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value)
            if (!updated) "I couldn't change your screen brightness."
            else {
                // Do not let an activity-specific brightness override system brightness.
                val params = activity.window.attributes
                params.screenBrightness = -1f
                activity.window.attributes = params
                "Brightness set to $next percent."
            }
        } catch (error: Exception) {
            Log.w("IRAI_BRIGHTNESS", "Brightness adjustment failed", error)
            "I couldn't change brightness on this phone. Check Modify system settings permission."
        }
    }
}
