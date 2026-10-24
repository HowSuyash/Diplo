package com.dietcode.diplo.exec

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads and taps other apps' screens. The user enables it once in
 * Settings > Accessibility > Diplo (on Android 13+ sideloaded apps first need
 * App info > ⋮ > "Allow restricted settings").
 *
 * TODO(track A): use the accessibility button (flagRequestAccessibilityButton) to start
 * listening from inside any app, so "screen padho" reads that app, not Diplo.
 */
class DiploAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Visible text on the current screen, top to bottom. Password fields are never read. */
    fun readScreen(maxItems: Int = 30): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || out.size >= maxItems || node.isPassword) return
            val text = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            if (text.isNotEmpty() && node.isVisibleToUser) out += text
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    /** The app in front, e.g. "com.whatsapp", so a tap never lands in the wrong app. */
    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** One thing on the screen the on-device agent can see and act on. */
    data class Element(val seen: ScreenElement, val node: AccessibilityNodeInfo)

    /**
     * What the on-device agent sees: buttons, inputs, toggles, lists and short texts, top to
     * bottom, numbered from 1. Password fields are never included.
     */
    // 80: a feed fills the first 40 and pushes out the tab bar at the bottom ("You", "Profile").
    fun elements(max: Int = 80): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || out.size >= max || node.isPassword) return
            if (node.isVisibleToUser) {
                val label = (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim().orEmpty()
                    .take(60)
                val kind = when {
                    node.isEditable -> ScreenElement.Kind.INPUT
                    node.isCheckable -> ScreenElement.Kind.TOGGLE
                    node.isClickable -> ScreenElement.Kind.BUTTON
                    node.isScrollable -> ScreenElement.Kind.LIST
                    else -> ScreenElement.Kind.TEXT
                }
                if (label.isNotEmpty() || kind != ScreenElement.Kind.TEXT) {
                    val checked = if (node.isCheckable) node.isChecked else null
                    out += Element(ScreenElement(out.size + 1, kind, label, checked), node)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    fun tap(element: Element): Boolean = clickNearest(element.node)

    /** Types into an input, replacing what is there; [submit] then presses the keyboard's search/enter. */
    fun type(element: Element, text: String, submit: Boolean = true): Boolean {
        val node = element.node
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (typed && submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return typed
    }

    /** Scrolls the first scrollable list down a page. */
    fun scrollDown(): Boolean {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Clicks the first view with this resource id, e.g. "com.whatsapp:id/send". */
    fun clickById(viewId: String): Boolean =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()?.let(::clickNearest) ?: false

    /** Clicks the first view whose text or description contains one of [labels]. */
    fun clickByText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return labels.any { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull()?.let(::clickNearest) ?: false
        }
    }

    // Labels are often on a non-clickable child of the real button, so walk up.
    private fun clickNearest(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    companion object {
        @Volatile
        var instance: DiploAccessibilityService? = null
            private set
    }
}
