package com.antts.app

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AnTTSAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Initial implementation: content extraction and reading engine are added incrementally.
        // Text-field events are deliberately observed here for the future voice-input confirmation flow.
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }
}
