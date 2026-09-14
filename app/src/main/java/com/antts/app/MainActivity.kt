package com.antts.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = "AnTTS\n\nВключите службу AnTTS в настройках специальных возможностей."
            textSize = 20f
            setPadding(32, 32, 32, 32)
        }
        setContentView(view)
    }
}
