package com.antts.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class AnTTSAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var overlay: ReadingOverlay? = null
    private val fragments = mutableListOf<AccessibilityNodeInfo>()
    private var current = 0
    private var initialized = false

    companion object { @Volatile var isRunning = false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        tts = TextToSpeech(this, this)
        overlay = ReadingOverlay()
        overlay?.show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            initialized = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val root = rootInActiveWindow ?: return
            val next = TextExtractor.extract(root)
            if (next.isNotEmpty() && !sameText(next)) {
                fragments.clear(); fragments.addAll(next)
                current = current.coerceIn(0, fragments.lastIndex)
                overlay?.setProgress(current, fragments.size)
            }
        }
    }

    private fun sameText(next: List<AccessibilityNodeInfo>): Boolean = next.size == fragments.size && next.take(3).map { it.text?.toString() } == fragments.take(3).map { it.text?.toString() }

    override fun onInterrupt() { tts?.stop(); overlay?.setPlaying(false) }
    override fun onDestroy() { isRunning = false; overlay?.hide(); tts?.stop(); tts?.shutdown(); fragments.clear(); super.onDestroy() }

    private fun speak(index: Int) {
        if (!initialized || fragments.isEmpty()) return
        current = index.coerceIn(0, fragments.lastIndex)
        val node = fragments[current]
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_TO_POSITION, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, current) })
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "antts_$current")
        overlay?.setProgress(current, fragments.size)
    }

    private fun toggle() {
        if (tts?.isSpeaking == true) { tts?.stop(); overlay?.setPlaying(false) }
        else { speak(current); overlay?.setPlaying(true) }
    }

    private fun move(delta: Int) { if (fragments.isNotEmpty()) { speak((current + delta).coerceIn(0, fragments.lastIndex)); overlay?.setPlaying(true) } }

    private inner class ReadingOverlay {
        private val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        private val root = FrameLayout(this@AnTTSAccessibilityService)
        private val progress = View(this@AnTTSAccessibilityService)
        private val traffic = TextView(this@AnTTSAccessibilityService)
        private var shown = false
        private var downX = 0f
        private var downY = 0f

        fun show() {
            if (shown) return
            val s = AppSettings(this@AnTTSAccessibilityService)
            val bg = View(this@AnTTSAccessibilityService).apply { setBackgroundColor(Color.argb((s.backgroundAlpha * 2.55).toInt(), 0, 0, 0)) }
            root.addView(bg, FrameLayout.LayoutParams(-1, -1))
            traffic.apply { text = TrafficMonitor(this@AnTTSAccessibilityService).monthlyText(); textSize = 8f; setTextColor(Color.argb(100, 255, 255, 255)); gravity = Gravity.CENTER; includeFontPadding = false }
            root.addView(traffic, FrameLayout.LayoutParams(-1, -1))
            progress.setBackgroundColor(Color.argb((s.progressAlpha * 2.55).toInt(), 255, 255, 255))
            root.addView(progress, FrameLayout.LayoutParams(0, -1))
            root.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; true }
                    MotionEvent.ACTION_UP -> { val dx = e.rawX - downX; if (abs(dx) > 30) move(if (dx > 0) 1 else -1) else toggle(); true }
                    else -> true
                }
            }
            val params = WindowManager.LayoutParams(-1, dp(s.barHeightDp), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = 0 }
            wm.addView(root, params); shown = true
        }
        fun setProgress(index: Int, total: Int) { if (total <= 0 || !shown) return; val p = (index + 1).toFloat() / total; root.post { progress.layout(0, 0, (root.width * p).toInt().coerceAtLeast(1), root.height) } }
        fun setPlaying(playing: Boolean) { }
        fun hide() { if (shown) { wm.removeView(root); shown = false } }
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}

private object TextExtractor {
    fun extract(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(root, candidates)
        val unique = LinkedHashMap<String, AccessibilityNodeInfo>()
        candidates.sortedBy { it.viewIdResourceName ?: "" }.forEach { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.length >= 2 && !looksLikeControl(text)) unique.putIfAbsent(text, node)
        }
        return unique.values.toList()
    }
    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && node.text?.toString()?.trim()?.isNotBlank() == true) out += node
        for (i in 0 until node.childCount) walk(node.getChild(i), out)
    }
    private fun looksLikeControl(value: String): Boolean {
        val t = value.lowercase(Locale.getDefault())
        return t.length < 3 || t in setOf("ok", "cancel", "назад", "меню", "далее", "поделиться", "закрыть")
    }
}
