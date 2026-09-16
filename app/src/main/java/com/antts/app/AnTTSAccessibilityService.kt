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
    private var pendingInputNode: AccessibilityNodeInfo? = null
    private var lastInputSpoken = ""
    private var inputRevision = 0L
    private var inputChangeStart = -1
    private var inputSentenceIndex = 0
    private var activeUtteranceId: String? = null
    private var speechGeneration = 0L
    private val inputDebounce = Runnable { announcePendingInput() }

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
            override fun onError(utteranceId: String?) { main.post { if (utteranceId == activeUtteranceId) { reading = false; overlay?.setPlaying(false) } } }
            override fun onDone(utteranceId: String?) { main.post { if (reading && utteranceId == activeUtteranceId) advanceAfterSpeech() } }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val source = event.source
            if (source != null && isEditable(source)) {
                pendingInputNode = source
                inputChangeStart = event.fromIndex.takeIf { it >= 0 } ?: -1
                inputRevision++
                main.removeCallbacks(inputDebounce)
                main.postDelayed(inputDebounce, 750L)
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            event.source?.let { if (isEditable(it)) pendingInputNode = it }
        }
        if (!reading || blocks.isEmpty()) refreshBlocks()
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString().orEmpty()
        return className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun announcePendingInput() {
        if (!AppSettings(this).speakInputAfterVoice || !ttsReady || reading) return
        val node = pendingInputNode ?: return
        val text = node.text?.toString().orEmpty()
        if (text.isBlank() || text == lastInputSpoken) return
        lastInputSpoken = text
        inputSentenceIndex = sentenceIndexAt(text, inputChangeStart.takeIf { it >= 0 } ?: node.textSelectionStart)
        speakInputSentence(node, inputSentenceIndex, inputChangeStart.takeIf { it >= 0 } ?: node.textSelectionStart)
    }

    private fun inputSentences(text: String): List<IntRange> =
        Regex("[^.!?\\n]+(?:[.!?]+|$)").findAll(text).map { it.range }.toList()

    private fun sentenceIndexAt(text: String, position: Int): Int {
        val sentences = inputSentences(text)
        if (sentences.isEmpty()) return 0
        val p = position.coerceIn(0, text.length)
        return sentences.indexOfFirst { p in it }.takeIf { it >= 0 } ?: sentences.lastIndex
    }

    private fun speakInputSentence(node: AccessibilityNodeInfo, index: Int, startAt: Int = -1) {
        val text = node.text?.toString().orEmpty()
        val sentences = inputSentences(text)
        if (sentences.isEmpty()) return
        inputSentenceIndex = index.coerceIn(0, sentences.lastIndex)
        val range = sentences[inputSentenceIndex]
        val start = if (startAt in range) startAt else range.first
        val spoken = text.substring(start, range.last + 1).trim()
        if (spoken.isBlank()) return
        activeUtteranceId = "antts-input-${inputRevision}-${System.nanoTime()}"
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId)
    }

    private fun refreshBlocks() {
        val root = rootInActiveWindow ?: return
        val fresh = try { TextExtractor.extract(root) } catch (e: Exception) { return }
        val snapshot = fresh.joinToString("\u0000") {
            val r = Rect(); it.node.getBoundsInScreen(r)
            "${it.text}|${r.top}|${r.bottom}|${it.node.viewIdResourceName}"
        }
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
        if (!block.node.isVisibleToUser) {
            bringIntoView(block.node)
            val generation = speechGeneration
            main.postDelayed({ if (reading && speechGeneration == generation) speakCurrent() }, 180)
            return
        }
        block.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        activeUtteranceId = "antts-block-$current-${speechGeneration++}-${System.nanoTime()}"
        tts?.speak(block.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId)
        overlay?.setProgress(current, blocks.size)
    }

    private fun advanceAfterSpeech() {
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
        }
        else scrollAndLoadNextPage()
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
        if (target == null) { onDone(); return }
        val rect = Rect(); target.getBoundsInScreen(rect)
        if (rect.height() < 100) { onDone(); return }
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

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).coerceIn(0, 160) else 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        if (!reading && pendingInputNode != null) {
            val node = pendingInputNode!!
            val text = node.text?.toString().orEmpty()
            if (inputSentences(text).isNotEmpty()) {
                tts?.stop()
                speakInputSentence(node, inputSentenceIndex + delta)
                return
            }
        }
        refreshBlocks()
        if (blocks.isEmpty()) return
        current = (current + delta).coerceIn(0, blocks.lastIndex)
        if (!reading) { reading = true; overlay?.setPlaying(true) }
        tts?.stop(); speakCurrent()
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
        return candidates.maxByOrNull { areaOnScreen(it) }
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

    override fun onInterrupt() { reading = false; speechGeneration++; activeUtteranceId = null; tts?.stop(); overlay?.setPlaying(false) }
    override fun onDestroy() {
        isRunning = false
        reading = false
        speechGeneration++
        activeUtteranceId = null
        main.removeCallbacks(inputDebounce)
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
    /** How many parents up to check for "this text actually belongs to a control" (icon+label rows, chips, tabs). */
    private const val MAX_ANCESTOR_DEPTH = 3

    /** Whole-subtree exclusion: app bars, bottom nav, tabs, side nav, snackbars, FABs — never main content. */
    private val CHROME_CONTAINER_MARKERS = listOf(
        "toolbar", "actionbar", "appbarlayout", "bottomnavigationview", "bottomappbar",
        "tablayout", "navigationview", "navigationrailview", "snackbar", "floatingactionbutton"
    )

    /** Leaf/ancestor exclusion: the text itself is a control, not body content. */
    private val CONTROL_CLASS_MARKERS = listOf(
        "button", "switch", "checkbox", "radiobutton", "togglebutton",
        "chip", "tab", "menuitem", "spinner", "seekbar"
    )

    fun extract(root: AccessibilityNodeInfo): List<ExtractedBlock> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collect(root, candidates)
        val unique = LinkedHashMap<String, AccessibilityNodeInfo>()
        candidates.forEach { node ->
            val value = node.text?.toString()?.trim().orEmpty()
            val rect = Rect(); node.getBoundsInScreen(rect)
            val key = "$value|${rect.left}|${rect.top}|${rect.right}|${rect.bottom}"
            if (value.length >= 3 && !isControlOrChrome(node)) unique.putIfAbsent(key, node)
        }
        return unique.values.map { node ->
            ExtractedBlock(node.text.toString().trim(), node)
        }.sortedWith(compareBy<ExtractedBlock> { block -> screenTop(block.node) }.thenBy { block -> screenLeft(block.node) })
    }

    private fun screenTop(node: AccessibilityNodeInfo): Int = node.boundsInScreen().top
    private fun screenLeft(node: AccessibilityNodeInfo): Int = node.boundsInScreen().left

    private fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

    private fun classNameOf(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return
        if (CHROME_CONTAINER_MARKERS.any { classNameOf(node).contains(it) }) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotBlank()) out += node
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    /** True if this node (or a close-by ancestor) is a control rather than page content. */
    private fun isControlOrChrome(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isCheckable || node.isEditable) return true
        if (CONTROL_CLASS_MARKERS.any { classNameOf(node).contains(it) }) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
            if (parent.isClickable || parent.isCheckable) return true
            if (CONTROL_CLASS_MARKERS.any { classNameOf(parent).contains(it) }) return true
            parent = parent.parent
            depth++
        }
        return false
    }
}

private data class ExtractedBlock(val text: String, val node: AccessibilityNodeInfo)
