package com.stealth.ioskeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class IosSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ios_settings)

        prefs = getSharedPreferences("ios_keyboard_prefs", Context.MODE_PRIVATE)

        val editLines = findViewById<EditText>(R.id.edit_lines_box)
        val editVocab = findViewById<EditText>(R.id.edit_vocab_box)
        val seekSpeed = findViewById<SeekBar>(R.id.seek_typing_speed)
        val txtSpeedLabel = findViewById<TextView>(R.id.txt_speed_label)
        val switchReplaceSpace = findViewById<Switch>(R.id.switch_replace_space)
        val switchAutoSend = findViewById<Switch>(R.id.switch_auto_send)
        val editMinWords = findViewById<EditText>(R.id.edit_min_words)
        val editMaxWords = findViewById<EditText>(R.id.edit_max_words)
        val radioGroupMode = findViewById<RadioGroup>(R.id.radio_group_mode)
        val rbLines = findViewById<RadioButton>(R.id.rb_lines)
        val rbVocab = findViewById<RadioButton>(R.id.rb_vocab)

        // تحميل الإعدادات المحفوظة مسبقاً
        editLines.setText(prefs.getString("lines_database", ""))
        editVocab.setText(prefs.getString("vocab_database", ""))
        val currentSpeed = prefs.getLong("typing_speed_delay", 100L).toInt()
        seekSpeed.progress = currentSpeed
        updateSpeedLabel(txtSpeedLabel, currentSpeed)

        switchReplaceSpace.isChecked = prefs.getBoolean("replace_space_plus", false)
        switchAutoSend.isChecked = prefs.getBoolean("auto_send_enabled", true)
        editMinWords.setText(prefs.getInt("vocab_min_words", 20).toString())
        editMaxWords.setText(prefs.getInt("vocab_max_words", 30).toString())

        if (prefs.getString("typing_mode", "lines") == "vocab") {
            rbVocab.isChecked = true
        } else {
            rbLines.isChecked = true
        }

        // مستمع تغيير سرعة الكتابة
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel(txtSpeedLabel, progress)
                prefs.edit().putLong("typing_speed_delay", progress.toLong()).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // زر الحفظ
        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            prefs.edit().apply {
                putString("lines_database", editLines.text.toString())
                putString("vocab_database", editVocab.text.toString())
                putBoolean("replace_space_plus", switchReplaceSpace.isChecked)
                putBoolean("auto_send_enabled", switchAutoSend.isChecked)
                putInt("vocab_min_words", editMinWords.text.toString().toIntOrNull() ?: 20)
                putInt("vocab_max_words", editMaxWords.text.toString().toIntOrNull() ?: 30)
                putString("typing_mode", if (radioGroupMode.checkedRadioButtonId == R.id.rb_lines) "lines" else "vocab")
                apply()
            }
            Toast.makeText(this, "Configuration Saved Successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSpeedLabel(label: TextView, ms: Int) {
        val wpm = if (ms == 0) "Instant (0ms)" else "${(60000 / (ms * 6))} WPM"
        label.text = "Typing Delay: ${ms}ms ($wpm)"
    }
}
