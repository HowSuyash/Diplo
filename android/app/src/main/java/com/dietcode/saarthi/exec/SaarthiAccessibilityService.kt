package com.dietcode.saarthi.exec

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads and taps other apps' screens. The user enables it once in
 * Settings > Accessibility > Saarthi (on Android 13+ sideloaded apps first need
 * App info > ⋮ > "Allow restricted settings").
 *
 * TODO(track A): use the accessibility button (flagRequestAccessibilityButton) to start
 * listening from inside any app, so "screen padho" reads that app, not Saarthi.
 */
class SaarthiAccessibilityService : AccessibilityService() {

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
        var instance: SaarthiAccessibilityService? = null
            private set
    }
}
