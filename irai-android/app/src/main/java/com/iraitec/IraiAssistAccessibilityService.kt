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
        if (output.length >= maxChars || !node.isVisibleToUser) return
        val isSensitiveInput = node.isPassword || node.isEditable
        val text = if (isSensitiveInput) "" else node.text?.toString()?.trim().orEmpty()
        val description = if (node.isPassword) "" else node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (!node.isPassword && Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        // Never copy what a user typed into a field into screen-reading AI requests.
        val value = if (node.isEditable) hint.ifBlank { description }
            else if (text.isNotBlank()) text else description
        if (value.isNotBlank() && !output.contains(value)) {
            if (output.isNotEmpty()) output.append('\n')
            output.append(value.take(maxChars - output.length))
        }
        for (index in 0 until node.childCount) {
            if (output.length >= maxChars) break
            node.getChild(index)?.let { appendVisibleText(it, output, maxChars) }
        }
    }

    /**
     * Summarizes visible, non-password form controls so Irai can explain
     * online applications without reading hidden or password content.
     */
    fun describeVisibleForm(maxItems: Int = 80): String {
        val root = rootInActiveWindow ?: return ""
        val items = mutableListOf<String>()
        appendFormControls(root, items, maxItems)
        return items.distinct().joinToString("\n")
    }

    private fun appendFormControls(node: AccessibilityNodeInfo, items: MutableList<String>, maxItems: Int) {
        if (items.size >= maxItems || !node.isVisibleToUser) return
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
        val labelParts = if (node.isEditable) {
            listOf(node.contentDescription?.toString()?.trim().orEmpty(), hint.trim())
        } else {
            listOf(node.text?.toString()?.trim().orEmpty(), node.contentDescription?.toString()?.trim().orEmpty(), hint.trim())
        }
        val label = labelParts.firstOrNull { it.isNotBlank() }.orEmpty()
        val className = node.className?.toString()?.substringAfterLast('.')?.lowercase(Locale.US).orEmpty()
        val resourceLabel = node.viewIdResourceName
            ?.substringAfterLast("/")
            ?.replace('_', ' ')
            .orEmpty()
        val fieldName = label.ifBlank { resourceLabel.ifBlank { "Unlabeled control" } }
        if (node.isEditable && !node.isPassword) {
            val currentValue = node.text?.toString()?.trim().orEmpty()
            // Report completion and validation state without copying personal answers into the AI request.
            val completion = if (currentValue.isNotBlank() && currentValue != label) {
                "; has an answer entered (value hidden)"
            } else {
                "; appears empty or value unavailable"
            }
            val validation = if (node.isContentInvalid) "; app marks this field invalid" else ""
            items.add("Text field: $fieldName$completion$validation")
        } else if (node.isCheckable || className.contains("checkbox") || className.contains("radiobutton") || className.contains("switch")) {
            val state = if (node.isChecked) "selected" else "not selected"
            val kind = when {
                className.contains("radio") -> "Radio option"
                className.contains("switch") -> "Switch"
                else -> "Checkbox"
            }
            items.add("$kind: $fieldName; $state")
        } else if (className.contains("spinner") || className.contains("dropdown") || className.contains("autocomplet")) {
            items.add("Dropdown or selection field: $fieldName" + if (label.isNotBlank()) "; current selection may be shown above" else "")
        } else if (node.isClickable && label.isNotBlank()) {
            val kind = when {
                className.contains("button") -> "Button"
                className.contains("tab") -> "Tab"
                className.contains("menu") -> "Menu item"
                else -> "Selectable control"
            }
            items.add("$kind: $label" + if (node.isSelected) "; currently selected" else "")
        }
        for (index in 0 until node.childCount) {
            if (items.size >= maxItems) break
            node.getChild(index)?.let { appendFormControls(it, items, maxItems) }
        }
    }

    fun tapVisibleText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestMatchingNode(root, target, editableOnly = false) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            current = current.parent
        }
        return false
    }

    fun setCheckableState(target: String, checked: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestMatchingNode(root, target, editableOnly = false) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isCheckable) {
                if (current.isChecked == checked) return true
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun enterText(targetField: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestMatchingNode(root, targetField, editableOnly = true) ?: return false
        if (!node.isEnabled) return false
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
        return scrollScrollableNode(root, action)
    }

    private fun scrollScrollableNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isScrollable && node.performAction(action)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (scrollScrollableNode(child, action)) return true
        }
        return node.performAction(action)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Prefer exact, enabled, actionable matches over broad substring matches.
     * This prevents a short target such as "Continue" from selecting an
     * unrelated container whose description merely contains that word.
     */
    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo,
        target: String,
        editableOnly: Boolean
    ): AccessibilityNodeInfo? {
        val wanted = target.trim().lowercase(Locale.US)
        if (wanted.isBlank()) return null
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectMatchingNodes(root, wanted, editableOnly, candidates)
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        wanted: String,
        editableOnly: Boolean,
        candidates: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (!node.isVisibleToUser || node.isPassword) return
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
        val parts = listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            hint,
            node.viewIdResourceName?.substringAfterLast("/")?.replace('_', ' ').orEmpty()
        ).map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }
        // For taps, include labeled descendants too: many apps put the text
        // on a child node while the actual click action belongs to its parent.
        val eligible = if (editableOnly) node.isEditable else true
        if (eligible && parts.isNotEmpty()) {
            val exact = parts.any { it == wanted }
            val partial = parts.any { it.contains(wanted) }
            if (exact || partial) {
                val longestLabel = parts.maxOfOrNull { it.length } ?: 0
                val score = (if (exact) 1000 else 100) +
                    (if (node.isEnabled) 50 else 0) +
                    (if (node.isClickable) 25 else 0) +
                    (if (node.isEditable) 25 else 0) -
                    longestLabel.coerceAtMost(80)
                candidates.add(score to node)
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let {
                collectMatchingNodes(it, wanted, editableOnly, candidates)
            }
        }
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
        fun describeCurrentForm(): String = activeService?.describeVisibleForm().orEmpty()
        fun tapText(target: String): Boolean = activeService?.tapVisibleText(target) ?: false
        fun typeIntoField(target: String, value: String): Boolean = activeService?.enterText(target, value) ?: false
        fun setOptionChecked(target: String, checked: Boolean): Boolean = activeService?.setCheckableState(target, checked) ?: false
        fun scrollForward(): Boolean = activeService?.scrollScreen(true) ?: false
        fun scrollBackward(): Boolean = activeService?.scrollScreen(false) ?: false
        fun goBack(): Boolean = activeService?.goBack() ?: false
    }
}
