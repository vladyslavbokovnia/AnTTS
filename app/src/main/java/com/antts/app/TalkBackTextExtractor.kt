package com.antts.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.abs

/**
 * Compact extraction layer based on TalkBack's speakable-node and traversal model.
 *
 * It deliberately keeps only the accessibility tree processing needed by AnTTS:
 * visible-node traversal, speakable text fallback, leaf de-duplication, role-aware
 * filtering, and document-order sorting. TalkBack's UI, gestures, braille, image
 * captioning, and native screen-understanding components are not included.
 */
internal object TalkBackTextExtractor {
    private const val MAX_ANCESTOR_DEPTH = 4

    private val auxiliaryMarkers = setOf(
        "toolbar", "actionbar", "action_bar", "appbar", "app_bar", "topbar", "top_bar",
        "header", "url_bar", "urlbar", "omnibox", "location_bar", "search_bar", "searchbar",
        "tablayout", "tab_layout", "tabbar", "tab_bar", "tabstrip", "bottomnavigation",
        "bottom_navigation", "bottom_nav", "bottomappbar", "bottom_app_bar", "bottombar",
        "bottom_bar", "footer", "navigation_bar", "navbar", "nav_bar", "snackbar",
        "navigationrail", "navigation_rail", "nav_rail", "navrail", "drawer", "drawerlayout",
        "sidebar", "side_bar", "side_nav", "sidenav", "sidesheet", "side_sheet",
        "sidepanel", "side_panel", "adview", "ad_view", "advertisement", "banner"
    )

    private val controlRoles = setOf(
        "button", "imagebutton", "switch", "checkbox", "radiobutton", "togglebutton",
        "chip", "tab", "menuitem", "spinner", "seekbar", "floatingactionbutton",
        "ratingbar", "progressbar", "edittext", "autocompletetextview"
    )

    fun extract(root: AccessibilityNodeInfo, metrics: ScreenMetrics): List<ExtractedBlock> {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collect(root, metrics, candidates)

        val unique = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (node in candidates) {
            val text = speakableText(node).trim()
            if (text.length < 2 || !text.any(Char::isLetter)) continue
            val bounds = bounds(node)
            val key = "$text|${bounds.top / 12}|${bounds.left / 12}"
            unique.putIfAbsent(key, node)
        }

        val rowTolerance = (metrics.density * 8).toInt()
        return unique.values.map { node ->
            ExtractedBlock(speakableText(node).trim(), node)
        }.sortedWith { left, right ->
            val a = bounds(left.node)
            val b = bounds(right.node)
            if (abs(a.top - b.top) <= rowTolerance) a.left.compareTo(b.left)
            else a.top.compareTo(b.top)
        }
    }

    /** TalkBack-style speakable fallback: visible text first, then content description. */
    private fun speakableText(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim().orEmpty()

    private fun collect(
        node: AccessibilityNodeInfo?,
        metrics: ScreenMetrics,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || !node.isVisibleToUser) return
        val rect = bounds(node)
        if (!isInViewport(rect, metrics) || isAuxiliaryContainer(node, rect, metrics)) return

        val text = speakableText(node)
        // Prefer the deepest speakable node, matching TalkBack's tree traversal behavior.
        if (text.isNotBlank() && !isControlOrDecorative(node, rect, metrics) && !hasSpeakableChild(node)) {
            out += node
        }
        for (index in 0 until node.childCount) {
            collect(node.getChild(index), metrics, out)
        }
    }

    private fun hasSpeakableChild(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (speakableText(child).isNotBlank() || hasSpeakableChild(child)) return true
        }
        return false
    }

    private fun isInViewport(rect: Rect, metrics: ScreenMetrics): Boolean =
        rect.width() > 0 && rect.height() > 0 &&
            rect.bottom > metrics.topBoundary && rect.top < metrics.bottomBoundary &&
            rect.right > 0 && rect.left < metrics.screenWidth

    private fun isAuxiliaryContainer(
        node: AccessibilityNodeInfo,
        rect: Rect,
        metrics: ScreenMetrics
    ): Boolean {
        val name = identity(node)
        if (auxiliaryMarkers.any { name.contains(it) }) return true

        val shortText = speakableText(node).length < 50
        val fullWidth = rect.width() >= metrics.screenWidth * 0.75
        val topPanel = rect.top <= metrics.topBoundary + dp(metrics, 12) &&
            rect.bottom <= metrics.topBoundary + dp(metrics, 80) && fullWidth && !node.isScrollable
        val bottomPanel = rect.bottom >= metrics.bottomBoundary - dp(metrics, 12) &&
            rect.top >= metrics.bottomBoundary - dp(metrics, 80) && fullWidth && !node.isScrollable
        return shortText && (topPanel || bottomPanel) &&
            (name.contains("bar") || name.contains("head") || name.contains("nav") ||
                name.contains("foot") || name.contains("toolbar"))
    }

    private fun isControlOrDecorative(
        node: AccessibilityNodeInfo,
        rect: Rect,
        metrics: ScreenMetrics
    ): Boolean {
        val name = identity(node)
        if (node.isEditable || controlRoles.any { name.contains(it) }) return true
        if (node.isClickable && speakableText(node).length < 45) return true
        if (auxiliaryMarkers.any { name.contains(it) }) return true

        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < MAX_ANCESTOR_DEPTH) {
            val parentName = identity(parent)
            if (auxiliaryMarkers.any { parentName.contains(it) } || controlRoles.any { parentName.contains(it) }) return true
            if (parent.isClickable && speakableText(node).length < 45) {
                val parentRect = bounds(parent)
                if (parentRect.height() <= dp(metrics, 64) || parentRect.width() <= dp(metrics, 220)) return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun identity(node: AccessibilityNodeInfo): String =
        listOf(node.className?.toString(), node.viewIdResourceName).joinToString(" ")
            .lowercase(Locale.ROOT)

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun dp(metrics: ScreenMetrics, value: Int): Int = (value * metrics.density).toInt()
}
