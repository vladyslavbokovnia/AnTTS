package com.antts.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply { text = "AnTTS"; textSize = 30f; setTextColor(Color.BLACK) }
        val subtitle = TextView(this).apply {
            text = "Чтение основного текста через специальные возможности"
            textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 20)
        }
        root.addView(title)
        root.addView(subtitle)
        status = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 16) }
        root.addView(status)
        val enable = Button(this).apply {
            text = "Открыть настройки специальных возможностей"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(enable, match())
        addSeek(root, "Прозрачность прогресс-бара", settings.progressAlpha, 10, 100) { settings.progressAlpha = it }
        addSeek(root, "Прозрачность чёрного фона", settings.backgroundAlpha, 0, 100) { settings.backgroundAlpha = it }
        addSeek(root, "Высота панели (dp)", settings.barHeightDp, 16, 64) { settings.barHeightDp = it }
        val modeLabel = TextView(this).apply { text = "Режим прокрутки"; textSize = 16f; setPadding(0, 16, 0, 4) }
        root.addView(modeLabel)
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Плавная постоянная", "Постраничная"))
            setSelection(if (settings.scrollMode == "page") 1 else 0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { settings.scrollMode = if (position == 1) "page" else "smooth" }
            }
        }
        root.addView(mode, match())
        val date = EditText(this).apply {
            hint = "Дата начала учёта: ГГГГ-ММ-ДД"
            setText(settings.manualStartDate)
            inputType = android.text.InputType.TYPE_CLASS_DATETIME
            setPadding(0, 20, 0, 4)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) settings.manualStartDate = text.toString().trim() }
        }
        root.addView(date, match())
        val help = TextView(this).apply {
            text = "Оставьте дату пустой, чтобы считать с первого дня текущего месяца. Нажатие на верхнюю полосу запускает/останавливает чтение; свайп меняет блок."
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, 16, 0, 0)
        }
        root.addView(help)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addSeek(root: LinearLayout, label: String, initial: Int, min: Int, max: Int, save: (Int) -> Unit) {
        val text = TextView(this).apply { textSize = 16f; setPadding(0, 16, 0, 0) }
        val seek = SeekBar(this).apply { this.max = max - min; progress = initial - min }
        fun update() { text.text = "$label: ${seek.progress + min}"; save(seek.progress + min) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { update() }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        update(); root.addView(text); root.addView(seek, match())
    }

    private fun match(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)
    override fun onResume() { super.onResume(); if (::status.isInitialized) status.text = if (AnTTSAccessibilityService.isRunning) "Служба AnTTS включена" else "Сначала включите службу AnTTS" }
}
