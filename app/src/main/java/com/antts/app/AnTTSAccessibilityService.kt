package com.antts.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class AnTTSAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var overlay: ReadingOverlay? = null
    private val blocks = mutableListOf<ExtractedBlock>()
    private var current = 0
    private var reading = false
    private var ttsReady = false
    private var lastSnapshot = ""

    companion object { @Volatile var isRunning = false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        tts = TextToSpeech(this, this)
        overlay = ReadingOverlay().also { it.show() }
        refreshBlocks()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.language = Locale.getDefault()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) { main.post { reading = false; overlay?.setPlaying(false) } }
            override fun onDone(utteranceId: String?) { main.post { if (reading) advanceAfterSpeech() } }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (!reading || blocks.isEmpty()) refreshBlocks()
    }

    private fun refreshBlocks() {
        val root = rootInActiveWindow ?: return
        val fresh = TextExtractor.extract(root)
        val snapshot = fresh.joinToString("\u0000") { it.text }
        if (fresh.isNotEmpty() && snapshot != lastSnapshot) {
            blocks.clear(); blocks.addAll(fresh); lastSnapshot = snapshot
            current = current.coerceIn(0, blocks.lastIndex)
            overlay?.setProgress(current, blocks.size)
        }
    }

    private fun startOrPause() {
        if (reading) { reading = false; tts?.stop(); overlay?.setPlaying(false); return }
        refreshBlocks()
        if (blocks.isEmpty() || !ttsReady) return
        reading = true; overlay?.setPlaying(true); speakCurrent()
    }

    private fun speakCurrent() {
        if (!reading || blocks.isEmpty()) return
        current = current.coerceIn(0, blocks.lastIndex)
        val block = blocks[current]
        block.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        bringIntoView(block.node)
        tts?.speak(block.text, TextToSpeech.QUEUE_FLUSH, null, "antts-$current-${System.nanoTime()}")
        overlay?.setProgress(current, blocks.size)
    }

    private fun advanceAfterSpeech() {
        if (current + 1 < blocks.size) { current++; speakCurrent() }
        else scrollAndLoadNextPage()
    }

    private fun scrollAndLoadNextPage() {
        val root = rootInActiveWindow
        val scrollable = findScrollable(root)
        if (scrollable == null || !scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            reading = false; overlay?.setPlaying(false); return
        }
        main.postDelayed({
            val before = lastSnapshot
            refreshBlocks()
            if (blocks.isNotEmpty() && lastSnapshot != before) { current = 0; speakCurrent() }
            else { reading = false; overlay?.setPlaying(false) }
        }, 450)
    }

    private fun move(delta: Int) {
        refreshBlocks()
        if (blocks.isEmpty()) return
        current = (current + delta).coerceIn(0, blocks.lastIndex)
        if (!reading) { reading = true; overlay?.setPlaying(true) }
        tts?.stop(); speakCurrent()
    }

    private fun bringIntoView(node: AccessibilityNodeInfo) {
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable && !node.isVisibleToUser) parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            parent = parent.parent
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    override fun onInterrupt() { reading = false; tts?.stop(); overlay?.setPlaying(false) }
    override fun onDestroy() { isRunning = false; reading = false; overlay?.hide(); tts?.stop(); tts?.shutdown(); blocks.clear(); super.onDestroy() }

    private inner class ReadingOverlay {
        private val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        private val root = FrameLayout(this@AnTTSAccessibilityService)
        private val progress = View(this@AnTTSAccessibilityService)
        private val traffic = TextView(this@AnTTSAccessibilityService)
        private var shown = false
        private var downX = 0f
        private var downY = 0f
        private var barWidth = 0
        private val trafficMonitor = TrafficMonitor(this@AnTTSAccessibilityService)
        private val trafficRefresh = object : Runnable {
            override fun run() {
                if (!shown) return
                traffic.text = trafficMonitor.monthlyText()
                main.postDelayed(this, 2_000L)
            }
        }

        fun show() {
            if (shown) return
            val settings = AppSettings(this@AnTTSAccessibilityService)
            root.setBackgroundColor(Color.TRANSPARENT)
            val black = View(this@AnTTSAccessibilityService).apply { setBackgroundColor(Color.argb((settings.backgroundAlpha * 2.55).toInt(), 0, 0, 0)) }
            root.addView(black, FrameLayout.LayoutParams(-1, -1))
            val progressColor = settings.progressColor
            progress.setBackgroundColor(Color.argb(
                (settings.progressAlpha * 2.55).toInt(),
                Color.red(progressColor), Color.green(progressColor), Color.blue(progressColor)
            ))
            root.addView(progress, FrameLayout.LayoutParams(0, -1))
            traffic.apply {
                text = trafficMonitor.monthlyText()
                textSize = 27f
                setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL))
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
            }
            root.addView(traffic, FrameLayout.LayoutParams(-1, -1))
            root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - downX
                        if (abs(dx) >= 28f) move(if (dx > 0) 1 else -1) else startOrPause()
                        true
                    }
                    else -> true
                }
            }
            val params = WindowManager.LayoutParams(
                -1, dp(settings.barHeightDp), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 0
            }
            wm.addView(root, params); shown = true
            main.post(trafficRefresh)
        }

        fun setProgress(index: Int, total: Int) {
            if (total <= 0 || !shown) return
            root.post {
                barWidth = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
                val width = (barWidth * (index + 1).toFloat() / total).toInt().coerceIn(1, barWidth)
                progress.layout(0, 0, width, root.height)
            }
        }
        fun setPlaying(playing: Boolean) { root.contentDescription = if (playing) "AnTTS: чтение включено" else "AnTTS: чтение остановлено" }
        fun hide() {
            if (shown) {
                shown = false
                main.removeCallbacks(trafficRefresh)
                wm.removeView(root)
            }
        }
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}

private object TextExtractor {
    fun extract(root: AccessibilityNodeInfo): List<ExtractedBlock> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collect(root, candidates)
        val unique = LinkedHashMap<String, AccessibilityNodeInfo>()
        candidates.forEach { node ->
            val value = node.text?.toString()?.trim().orEmpty()
            if (value.length >= 3 && !isControl(node, value)) unique.putIfAbsent(value, node)
        }
        return unique.values.map { ExtractedBlock(it.text.toString().trim(), it) }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotBlank()) out += node
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun isControl(node: AccessibilityNodeInfo, value: String): Boolean {
        if (node.isClickable || node.isCheckable || node.isEditable) return true
        val normalized = value.lowercase(Locale.getDefault())
        return normalized in setOf("ok", "cancel", "назад", "меню", "далее", "закрыть", "поделиться", "search", "back")
    }
}

private data class ExtractedBlock(val text: String, val node: AccessibilityNodeInfo)
