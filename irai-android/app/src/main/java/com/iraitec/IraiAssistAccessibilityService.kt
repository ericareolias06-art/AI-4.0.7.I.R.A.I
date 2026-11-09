package com.iraitec

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.os.Build
import java.util.Locale

/**
 * User-enabled bridge for Irai's hands-on guided assistance.
 * It only acts when Irai explicitly invokes one of these methods.
 */
class IraiAssistAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Do not collect or transmit events in the background.
        // The current screen is inspected only after an explicit user request.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun readVisibleText(maxChars: Int = 10000): String {
        val root = rootInActiveWindow ?: return ""
        val output = StringBuilder()
        appendVisibleText(root, output, maxChars)
        return output.toString().trim()
    }

    private fun appendVisibleText(node: AccessibilityNodeInfo, output: StringBuilder, maxChars: Int) {
        if (output.length >= maxChars) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val value = if (text.isNotBlank()) text else description
        if (value.isNotBlank() && !output.contains(value)) {
            if (output.isNotEmpty()) output.append('\n')
            output.append(value.take(maxChars - output.length))
        }
        for (index in 0 until node.childCount) {
            if (output.length >= maxChars) break
            node.getChild(index)?.let { appendVisibleText(it, output, maxChars) }
        }
    }

    fun tapVisibleText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, target) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    fun enterText(targetField: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditableNode(root, targetField) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollScreen(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return root.performAction(action)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val wanted = target.trim().lowercase(Locale.US)
        if (wanted.isBlank()) return null
        val text = root.text?.toString().orEmpty().lowercase(Locale.US)
        val description = root.contentDescription?.toString().orEmpty().lowercase(Locale.US)
        if (text == wanted || description == wanted || text.contains(wanted) || description.contains(wanted)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findNode(child, target)?.let { return it }
        }
        return null
    }

    private fun findEditableNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val wanted = target.trim().lowercase(Locale.US)
        val hint = if (Build.VERSION.SDK_INT >= 26) root.hintText?.toString() else null
        val label = listOfNotNull(root.text?.toString(), root.contentDescription?.toString(), hint)
            .joinToString(" ").lowercase(Locale.US)
        if (root.isEditable && (wanted.isBlank() || label.contains(wanted))) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findEditableNode(child, target)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile private var activeService: IraiAssistAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + IraiAssistAccessibilityService::class.java.name
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { it.equals(expected, ignoreCase = true) }
        }

        fun readCurrentScreen(): String = activeService?.readVisibleText().orEmpty()
        fun tapText(target: String): Boolean = activeService?.tapVisibleText(target) ?: false
        fun typeIntoField(target: String, value: String): Boolean = activeService?.enterText(target, value) ?: false
        fun scrollForward(): Boolean = activeService?.scrollScreen(true) ?: false
        fun scrollBackward(): Boolean = activeService?.scrollScreen(false) ?: false
        fun goBack(): Boolean = activeService?.goBack() ?: false
    }
}
