package com.antts.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * TalkBack-style document extraction without importing TalkBack's UI or native payload.
 *
 * The important distinction from a coordinate sorter is that the accessibility tree's natural
 * child order is the primary order. Explicit traversalBefore/traversalAfter links are applied on
 * top of that order, matching TalkBack's OrderedTraversalStrategy model. Geometry is used only for
 * visibility and duplicate detection, never as the primary reading order.
 */
internal object TalkBackTextExtractor {
    private const val MAX_ANCESTOR_DEPTH = 8
    private const val DUPLICATE_OVERLAP = 0.82f

    private val auxiliaryMarkers = setOf(
        "toolbar", "actionbar", "action_bar", "appbar", "app_bar", "topbar", "top_bar",
        "header", "url_bar", "urlbar", "omnibox", "location_bar", "search_bar", "searchbar",
        "tablayout", "tab_layout", "tabbar", "tab_bar", "tabstrip", "bottomnavigation",
        "bottom_navigation", "bottom_nav", "bottomappbar", "bottom_app_bar", "bottombar",
        "bottom_bar", "footer", "navigation_bar", "navbar", "nav_bar", "snackbar",
        "navigationrail", "navigation_rail", "nav_rail", "navrail", "drawer", "drawerlayout",
        "sidebar", "side_bar", "side_nav", "sidenav", "sidesheet", "side_sheet",
        "sidepanel", "side_panel", "adview", "ad_view", "advertisement", "banner", "fab"
    )

    private val controlRoles = setOf(
        "button", "imagebutton", "switch", "checkbox", "radiobutton", "togglebutton",
        "chip", "tab", "menuitem", "spinner", "seekbar", "floatingactionbutton",
        "ratingbar", "progressbar", "edittext", "autocompletetextview"
    )

    fun extract(root: AccessibilityNodeInfo, metrics: ScreenMetrics): List<ExtractedBlock> {
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        flattenVisible(root, metrics, allNodes, visited)

        val candidates = allNodes.filter { node ->
            isSpeakableCandidate(node, metrics) && !hasSpeakableDescendant(node, metrics)
        }.toMutableList()

        applyTraversalLinks(candidates)
        val unique = removeDuplicates(candidates)
        return unique.map { node ->
            ExtractedBlock(speakableText(node), node)
        }
    }

    /** TalkBack's speakable text starts with node text and falls back to the content description. */
    private fun speakableText(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim().orEmpty()

    /** Natural DFS order is retained; this is the accessibility tree's document order. */
    private fun flattenVisible(
        node: AccessibilityNodeInfo?,
        metrics: ScreenMetrics,
        out: MutableList<AccessibilityNodeInfo>,
        visited: MutableSet<AccessibilityNodeInfo>
    ) {
        if (node == null || !visited.add(node) || !node.isVisibleToUser) return
        val rect = bounds(node)
        if (!isInViewport(rect, metrics) || isAuxiliaryContainer(node, rect, metrics)) return

        out += node
        for (index in 0 until node.childCount) {
            flattenVisible(node.getChild(index), metrics, out, visited)
        }
    }

    /** Mirrors TalkBack's distinction between a speaking node and an actionable child. */
    private fun isSpeakableCandidate(node: AccessibilityNodeInfo, metrics: ScreenMetrics): Boolean {
        val text = speakableText(node)
        if (text.length < 2 || !text.any(Char::isLetter)) return false
        if (isAuxiliaryContainer(node, bounds(node), metrics)) return false
        if (node.isEditable) return false

        val identity = identity(node)
        if (controlRoles.any { identity.contains(it) }) return false
        // Short actionable labels are controls, not article text. Long text remains readable.
        if ((node.isClickable || node.isCheckable || node.isFocusable) && text.length < 45) return false

        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < MAX_ANCESTOR_DEPTH) {
            val parentIdentity = identity(parent)
            if (auxiliaryMarkers.any { parentIdentity.contains(it) }) return false
            if (controlRoles.any { parentIdentity.contains(it) } && text.length < 45) return false
            parent = parent.parent
        }
        return true
    }

    private fun hasSpeakableDescendant(node: AccessibilityNodeInfo, metrics: ScreenMetrics): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        fun walk(parent: AccessibilityNodeInfo): Boolean {
            for (index in 0 until parent.childCount) {
                val child = parent.getChild(index) ?: continue
                if (!visited.add(child) || !child.isVisibleToUser) continue
                val childRect = bounds(child)
                if (!isInViewport(childRect, metrics)) continue
                if (isSpeakableCandidate(child, metrics)) return true
                if (walk(child)) return true
            }
            return false
        }
        return walk(node)
    }

    /** Apply explicit Android traversal links with a stable topological sort. */
    private fun applyTraversalLinks(nodes: MutableList<AccessibilityNodeInfo>) {
        if (nodes.size < 2) return
        val positions = IdentityHashMap<AccessibilityNodeInfo, Int>()
        nodes.forEachIndexed { index, node -> positions[node] = index }
        val outgoing = Array(nodes.size) { LinkedHashSet<Int>() }
        val indegree = IntArray(nodes.size)

        fun addEdge(before: AccessibilityNodeInfo?, after: AccessibilityNodeInfo?) {
            val from = before?.let { positions[it] } ?: return
            val to = after?.let { positions[it] } ?: return
            if (from == to || !outgoing[from].add(to)) return
            indegree[to]++
        }

        nodes.forEach { node ->
            addEdge(node, node.traversalBefore)
            addEdge(node.traversalAfter, node)
        }

        val ready = java.util.PriorityQueue<Int>()
        indegree.forEachIndexed { index, degree -> if (degree == 0) ready.add(index) }
        val orderedIndexes = ArrayList<Int>(nodes.size)
        while (ready.isNotEmpty()) {
            val current = ready.remove()
            orderedIndexes += current
            outgoing[current].forEach { next ->
                if (--indegree[next] == 0) ready.add(next)
            }
        }

        // Broken/cyclic app-provided traversal metadata must not destroy reading order.
        if (orderedIndexes.size == nodes.size) {
            val reordered = orderedIndexes.map(nodes::get)
            nodes.clear()
            nodes.addAll(reordered)
        }
    }

    /** Remove exact duplicates and overlapping descendants while retaining repeated real text. */
    private fun removeDuplicates(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(nodes.size)
        val seen = HashSet<String>()
        for (node in nodes) {
            val text = speakableText(node)
            val rect = bounds(node)
            val coarseKey = "$text|${rect.left / 8}|${rect.top / 8}|${rect.right / 8}|${rect.bottom / 8}"
            if (!seen.add(coarseKey)) continue
            if (result.any { previous ->
                    speakableText(previous) == text && overlap(bounds(previous), rect) >= DUPLICATE_OVERLAP
                }) continue
            result += node
        }
        return result
    }

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

    private fun isInViewport(rect: Rect, metrics: ScreenMetrics): Boolean =
        rect.width() > 0 && rect.height() > 0 &&
            rect.bottom > metrics.topBoundary && rect.top < metrics.bottomBoundary &&
            rect.right > 0 && rect.left < metrics.screenWidth

    private fun identity(node: AccessibilityNodeInfo): String =
        listOf(node.className?.toString(), node.viewIdResourceName).joinToString(" ")
            .lowercase(Locale.ROOT)

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun overlap(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top).toLong()
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (smaller <= 0) 0f else intersection.toFloat() / smaller.toFloat()
    }

    private fun dp(metrics: ScreenMetrics, value: Int): Int = (value * metrics.density).toInt()
}
