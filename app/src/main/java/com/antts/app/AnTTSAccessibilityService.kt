package com.antts.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var overlay: ReadingOverlay? = null
    private val blocks = mutableListOf<ExtractedBlock>()
    private var current = 0
    private var reading = false
    private var isSpeakingInput = false
    private var ttsReady = false
    private var lastSnapshot = ""
    private var pendingInputNode: AccessibilityNodeInfo? = null
    private var lastRecordedCursor = -1
    private var inputRevision = 0L
    private var inputSentenceIndex = 0
    private var activeUtteranceId: String? = null
    private var speechGeneration = 0L

    companion object {
        @Volatile
        var isRunning = false
    }

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

            override fun onError(utteranceId: String?) {
                main.post {
                    if (utteranceId == activeUtteranceId) {
                        isSpeakingInput = false
                        reading = false
                        overlay?.setPlaying(false)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                main.post {
                    if (utteranceId == activeUtteranceId) {
                        if (isSpeakingInput) {
                            isSpeakingInput = false
                            overlay?.setPlaying(false)
                        } else if (reading) {
                            advanceAfterSpeech()
                        }
                    }
                }
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && isEditable(source)) {
                    pendingInputNode = source
                    val from = event.fromIndex
                    val added = event.addedCount
                    if (from >= 0) {
                        lastRecordedCursor = from + added
                    }
                    inputRevision++
                }
                return
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source
                if (source != null && isEditable(source)) {
                    pendingInputNode = source
                    val from = event.fromIndex
                    if (from >= 0) {
                        lastRecordedCursor = from
                    }
                }
                return
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                event.source?.let {
                    if (isEditable(it)) {
                        pendingInputNode = it
                        if (it.textSelectionStart >= 0) {
                            lastRecordedCursor = it.textSelectionStart
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pendingInputNode = null
                lastRecordedCursor = -1
                if (reading) {
                    reading = false
                    tts?.stop()
                    overlay?.setPlaying(false)
                }
            }
        }
        if (!reading || blocks.isEmpty()) {
            refreshBlocks()
        }
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString().orEmpty()
        return className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun findActiveInputNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (isEditable(focused)) return focused
        }
        pendingInputNode?.let { node ->
            if (node.refresh() && isEditable(node)) return node
        }
        try {
            for (window in windows) {
                val winRoot = window.root ?: continue
                val focused = winRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditable(focused)) {
                    return focused
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Speaks the focused input field's sentence where the cursor is currently located.
     * Returns true if handled, or false if there's no input to speak so normal page reading can run.
     */
    private fun speakPendingInput(): Boolean {
        if (!AppSettings(this).speakInputAfterVoice || !ttsReady) return false
        val node = findActiveInputNode() ?: return false
        node.refresh()
        val text = node.text?.toString().orEmpty()
        if (text.isBlank()) return false

        val sentences = InputSentenceParser.parseSentences(text)
        if (sentences.isEmpty()) return false

        val cursor = when {
            node.textSelectionStart >= 0 -> node.textSelectionStart
            lastRecordedCursor in 0..text.length -> lastRecordedCursor
            else -> text.length
        }

        inputSentenceIndex = InputSentenceParser.sentenceIndexAt(sentences, cursor)
        speakInputSentence(node, inputSentenceIndex)
        return true
    }

    private fun speakInputSentence(node: AccessibilityNodeInfo, index: Int) {
        val text = node.text?.toString().orEmpty()
        val sentences = InputSentenceParser.parseSentences(text)
        if (sentences.isEmpty()) return
        inputSentenceIndex = index.coerceIn(0, sentences.lastIndex)
        val span = sentences[inputSentenceIndex]
        val spoken = span.text
        if (spoken.isBlank()) return

        isSpeakingInput = true
        overlay?.setPlaying(true)
        activeUtteranceId = "antts-input-${inputRevision}-${System.nanoTime()}"
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId)
    }

    private fun getScreenMetrics(): ScreenMetrics {
        val dm = resources.displayMetrics
        val overlayHeight = dp(AppSettings(this).barHeightDp)
        val statusBarHeight = statusBarHeight()
        val topBoundary = maxOf(statusBarHeight, overlayHeight)
        val bottomBoundary = dm.heightPixels - navigationBarHeight()
        return ScreenMetrics(
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            density = dm.density,
            topBoundary = topBoundary,
            bottomBoundary = bottomBoundary
        )
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).coerceIn(0, 160) else dp(24)
    }

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).coerceIn(0, 160) else dp(48)
    }

    private fun refreshBlocks() {
        val root = rootInActiveWindow ?: return
        val metrics = getScreenMetrics()
        val fresh = try {
            TalkBackTextExtractor.extract(root, metrics)
        } catch (e: Exception) {
            return
        }
        val snapshot = fresh.joinToString("\u0000") {
            val r = Rect()
            it.node.getBoundsInScreen(r)
            "${it.text}|${r.top}|${r.bottom}|${it.node.viewIdResourceName}"
        }
        if (fresh.isNotEmpty() && snapshot != lastSnapshot) {
            blocks.clear()
            blocks.addAll(fresh)
            lastSnapshot = snapshot
            current = current.coerceIn(0, blocks.lastIndex)
            overlay?.setProgress(current, blocks.size)
        }
    }

    private fun startOrPause() {
        if (reading || isSpeakingInput) {
            reading = false
            isSpeakingInput = false
            tts?.stop()
            overlay?.setPlaying(false)
            return
        }
        if (speakPendingInput()) return
        refreshBlocks()
        if (blocks.isEmpty() || !ttsReady) return
        current = 0
        reading = true
        overlay?.setPlaying(true)
        speakCurrent()
    }

    private fun speakCurrent() {
        if (!reading || blocks.isEmpty()) return
        current = current.coerceIn(0, blocks.lastIndex)
        val block = blocks[current]
        if (!block.node.isVisibleToUser) {
            bringIntoView(block.node)
            val generation = speechGeneration
            main.postDelayed({
                if (reading && speechGeneration == generation) speakCurrent()
            }, 180)
            return
        }
        block.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        activeUtteranceId = "antts-block-$current-${speechGeneration++}-${System.nanoTime()}"
        tts?.speak(block.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId)
        overlay?.setProgress(current, blocks.size)
    }

    private fun advanceAfterSpeech() {
        if (!reading) return
        if (current + 1 < blocks.size) {
            val next = blocks[current + 1]
            if (AppSettings(this).scrollMode == "smooth" && nearBottom(next.node)) {
                val generation = speechGeneration
                val nextText = next.text
                smoothScrollBy(next.node) {
                    if (reading && speechGeneration == generation) {
                        refreshBlocks()
                        val matched = blocks.indexOfFirst { it.text == nextText }
                        current = if (matched >= 0) matched else (current + 1).coerceAtMost(blocks.lastIndex)
                        speakCurrent()
                    }
                }
            } else {
                current++
                speakCurrent()
            }
        } else {
            scrollAndLoadNextPage()
        }
    }

    private fun nearBottom(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.bottom > resources.displayMetrics.heightPixels - navigationBarHeight() - dp(72)
    }

    private fun scrollParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) return parent
            parent = parent.parent
        }
        return null
    }

    /** Real continuous scroll: drags the scrollable container slowly instead of jumping a full page. */
    private fun smoothScrollBy(node: AccessibilityNodeInfo, onDone: () -> Unit) {
        val target = scrollParent(node)
        if (target == null) {
            onDone()
            return
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.height() < 100) {
            onDone()
            return
        }
        val x = (rect.left + rect.right) / 2f
        val startY = rect.bottom - rect.height() * 0.1f
        val endY = rect.top + rect.height() * 0.1f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val duration = 700L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone() }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone() }
        }, main)
        if (!dispatched) onDone()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun scrollAndLoadNextPage() {
        if (!reading) return
        val root = rootInActiveWindow
        val scrollable = findScrollable(root)
        if (scrollable == null) {
            finishReading()
            return
        }
        val before = lastSnapshot
        val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (!scrolled) {
            finishReading()
            return
        }
        main.postDelayed({
            if (!reading) return@postDelayed
            refreshBlocks()
            if (blocks.isNotEmpty() && lastSnapshot != before) {
                current = 0
                speakCurrent()
            } else {
                finishReading()
            }
        }, 500)
    }

    private fun finishReading() {
        reading = false
        activeUtteranceId = null
        overlay?.setPlaying(false)
    }

    private fun move(delta: Int) {
        val inputNode = findActiveInputNode()
        if (!reading && inputNode != null) {
            val text = inputNode.text?.toString().orEmpty()
            val sentences = InputSentenceParser.parseSentences(text)
            if (sentences.isNotEmpty()) {
                tts?.stop()
                speakInputSentence(inputNode, inputSentenceIndex + delta)
                return
            }
        }
        refreshBlocks()
        if (blocks.isEmpty()) return
        current = (current + delta).coerceIn(0, blocks.lastIndex)
        if (!reading) {
            reading = true
            overlay?.setPlaying(true)
        }
        tts?.stop()
        speakCurrent()
    }

    private fun bringIntoView(node: AccessibilityNodeInfo) {
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable && !node.isVisibleToUser) {
                parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                break
            }
            parent = parent.parent
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectScrollables(node, candidates)
        return candidates.filter {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.height() >= dp(120) && !isSideRailOrDrawer(it, r)
        }.maxByOrNull { areaOnScreen(it) } ?: candidates.maxByOrNull { areaOnScreen(it) }
    }

    private fun isSideRailOrDrawer(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        val dm = resources.displayMetrics
        val railMaxW = dp(96)
        if (rect.left <= 0 && rect.right <= railMaxW) return true
        if (rect.right >= dm.widthPixels && rect.left >= dm.widthPixels - railMaxW) return true
        val className = node.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        return className.contains("drawer") || className.contains("navigationrail")
    }

    private fun collectScrollables(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isScrollable) out += node
        for (i in 0 until node.childCount) collectScrollables(node.getChild(i), out)
    }

    private fun areaOnScreen(node: AccessibilityNodeInfo): Long {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width().toLong() * rect.height().toLong()
    }

    override fun onInterrupt() {
        reading = false
        isSpeakingInput = false
        speechGeneration++
        activeUtteranceId = null
        tts?.stop()
        overlay?.setPlaying(false)
    }

    override fun onDestroy() {
        isRunning = false
        reading = false
        isSpeakingInput = false
        speechGeneration++
        activeUtteranceId = null
        pendingInputNode = null
        overlay?.hide()
        tts?.stop()
        tts?.shutdown()
        blocks.clear()
        super.onDestroy()
    }

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
            val black = View(this@AnTTSAccessibilityService).apply {
                setBackgroundColor(Color.argb((settings.backgroundAlpha * 2.55).toInt(), 0, 0, 0))
            }
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
            wm.addView(root, params)
            shown = true
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

        fun setPlaying(playing: Boolean) {
            root.contentDescription = if (playing) "AnTTS: чтение включено" else "AnTTS: чтение остановлено"
        }

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

data class ScreenMetrics(
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val topBoundary: Int,
    val bottomBoundary: Int
)

data class SentenceSpan(
    val contentStart: Int,
    val contentEnd: Int,
    val spanEnd: Int,
    val text: String
)

object InputSentenceParser {
    fun parseSentences(text: String): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()
        val spans = mutableListOf<SentenceSpan>()
        var start = 0
        val len = text.length

        while (start < len) {
            while (start < len && text[start].isWhitespace()) {
                start++
            }
            if (start >= len) break

            var end = start
            while (end < len) {
                val ch = text[end]
                if (ch == '\n' || ch == '\r') {
                    end++
                    break
                }
                if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                    if (ch == '.' && end > start && text[end - 1].isDigit() && end + 1 < len && text[end + 1].isDigit()) {
                        end++
                        continue
                    }
                    while (end < len && (text[end] == '.' || text[end] == '!' || text[end] == '?' || text[end] == '…')) {
                        end++
                    }
                    while (end < len && text[end] in "\"'»”’)]") {
                        end++
                    }
                    break
                }
                end++
            }

            var spanEnd = end
            while (spanEnd < len && text[spanEnd].isWhitespace()) {
                spanEnd++
            }

            val sentenceText = text.substring(start, end).trim()
            if (sentenceText.isNotEmpty()) {
                spans.add(
                    SentenceSpan(
                        contentStart = start,
                        contentEnd = end,
                        spanEnd = spanEnd,
                        text = sentenceText
                    )
                )
            }
            start = spanEnd
        }
        return spans
    }

    fun sentenceIndexAt(sentences: List<SentenceSpan>, cursor: Int): Int {
        if (sentences.isEmpty()) return 0
        if (cursor <= sentences.first().contentStart) return 0
        for (i in sentences.indices) {
            val span = sentences[i]
            if (cursor >= span.contentStart && cursor <= span.spanEnd) {
                return i
            }
        }
        return sentences.lastIndex
    }
}

data class ExtractedBlock(val text: String, val node: AccessibilityNodeInfo)
