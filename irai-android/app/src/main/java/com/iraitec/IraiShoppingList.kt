package com.iraitec

import android.content.Context
import org.json.JSONArray

/** Small on-device shopping list, independent of the AI model and cloud. */
class IraiShoppingList(context: Context) {
    private val prefs = context.getSharedPreferences("irai_shopping_list", Context.MODE_PRIVATE)
    private val key = "items"

    @Synchronized
    fun items(): List<String> {
        val stored = prefs.getString(key, "[]") ?: "[]"
        return try {
            val json = JSONArray(stored)
            (0 until json.length()).mapNotNull { i ->
                json.optString(i).trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun add(item: String): Boolean {
        val cleaned = item.trim().replace(Regex("\\s+"), " ").take(120)
        if (cleaned.isBlank()) return false
        val current = items()
        if (current.any { it.equals(cleaned, ignoreCase = true) }) return false
        return prefs.edit().putString(key, JSONArray(current + cleaned).toString()).commit()
    }

    @Synchronized
    fun remove(item: String): Boolean {
        val current = items()
        val remaining = current.filterNot { it.equals(item.trim(), ignoreCase = true) }
        if (remaining.size == current.size) return false
        return prefs.edit().putString(key, JSONArray(remaining).toString()).commit()
    }
}
