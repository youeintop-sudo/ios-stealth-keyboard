package com.stealth.ioskeyboard

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import kotlinx.coroutines.*
import kotlin.random.Random

class IosInputMethodService : InputMethodService() {

    private lateinit var prefs: SharedPreferences
    private lateinit var audioEngine: IosAudioEngine
    private var typingJob: Job? = null
    var isAutoTyping: Boolean = false
        private set

    // رصد إيماءات زر المسافة
    private var lastSpaceTapTime: Long = 0
    private val doubleTapThreshold = 280L
    private val spaceHoldHandler = Handler(Looper.getMainLooper())
    private var isSpaceHolding = false

    // عناصر فقاعة العرض المنبثقة (Key Preview)
    private var popupWindow: PopupWindow? = null
    private var popupTextView: TextView? = null

    // خريطة لربط الحروف بعناصر الواجهة الرسومية
    private val keyButtons = mutableMapOf<Char, Button>()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ios_keyboard_prefs", Context.MODE_PRIVATE)
        audioEngine = IosAudioEngine()
        setupKeyPopup()
    }

    private fun setupKeyPopup() {
        val popupView = layoutInflater.inflate(R.layout.ios_key_popup, null)
        popupTextView = popupView.findViewById(R.id.popup_text)
        popupWindow = PopupWindow(popupView, 140, 180).apply {
            isTouchable = false
            isClippingEnabled = false
        }
    }

    override fun onCreateInputView(): View {
        val keyboardView = layoutInflater.inflate(R.layout.keyboard_ios_layout, null)

        // 1. مفتاح الإيقاف الطارئ (Kill Switch): لمس الشاشة يوقف التايبر فوراً
        keyboardView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && isAutoTyping) {
                stopAutoTypingImmediately()
                return@setOnTouchListener true
            }
            false
        }

        // 2. ربط أزرار الأحرف برمجياً
        mapAllKeys(keyboardView)

        // 3. برمجة زر المسافة بمحفزات التفعيل السرية
        val spaceBtn = keyboardView.findViewById<Button>(R.id.key_space)
        setupSpaceTriggers(spaceBtn)

        // 4. برمجة زر الحذف Backspace
        keyboardView.findViewById<Button>(R.id.key_delete)?.setOnClickListener {
            if (isAutoTyping) { stopAutoTypingImmediately(); return@setOnClickListener }
            audioEngine.playClick()
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        // 5. برمجة زر Return / Enter
        keyboardView.findViewById<Button>(R.id.key_enter)?.setOnClickListener {
            if (isAutoTyping) { stopAutoTypingImmediately(); return@setOnClickListener }
            audioEngine.playClick()
            performUniversalSend()
        }

        return keyboardView
    }

    private fun mapAllKeys(root: View) {
        val keyMapping = mapOf(
            'q' to R.id.btn_q, 'w' to R.id.btn_w, 'e' to R.id.btn_e, 'r' to R.id.btn_r,
            't' to R.id.btn_t, 'y' to R.id.btn_y, 'u' to R.id.btn_u, 'i' to R.id.btn_i,
            'o' to R.id.btn_o, 'p' to R.id.btn_p,
            'a' to R.id.btn_a, 's' to R.id.btn_s, 'd' to R.id.btn_d, 'f' to R.id.btn_f,
            'g' to R.id.btn_g, 'h' to R.id.btn_h, 'j' to R.id.btn_j, 'k' to R.id.btn_k,
            'l' to R.id.btn_l,
            'z' to R.id.btn_z, 'x' to R.id.btn_x, 'c' to R.id.btn_c, 'v' to R.id.btn_v,
            'b' to R.id.btn_b, 'n' to R.id.btn_n, 'm' to R.id.btn_m
        )

        keyButtons.clear()
        for ((char, resId) in keyMapping) {
            val btn = root.findViewById<Button>(resId)
            if (btn != null) {
                keyButtons[char] = btn
                btn.setOnClickListener {
                    if (isAutoTyping) {
                        stopAutoTypingImmediately()
                        return@setOnClickListener
                    }
                    // كتابة يدوية طبيعية مع صوت وفقاعة آيفون
                    currentInputConnection?.commitText(char.toString(), 1)
                    audioEngine.playClick()
                    showKeyPopup(btn, char.toString())
                }
            }
        }
    }

    private fun setupSpaceTriggers(spaceBtn: Button) {
        val longPressRunnable = Runnable {
            if (isSpaceHolding && !isAutoTyping) {
                startAutoTypingSequence()
            }
        }

        spaceBtn.setOnTouchListener { _, event ->
            if (isAutoTyping) {
                stopAutoTypingImmediately()
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    isSpaceHolding = true

                    // رصد النقر المزدوج السريع (Double Tap)
                    if (now - lastSpaceTapTime < doubleTapThreshold) {
                        spaceHoldHandler.removeCallbacks(longPressRunnable)
                        startAutoTypingSequence()
                        return@setOnTouchListener true
                    }
                    lastSpaceTapTime = now

                    // فحص الضغط المطول الخفيف (500 ميلي ثانية)
                    spaceHoldHandler.postDelayed(longPressRunnable, 500L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSpaceHolding = false
                    spaceHoldHandler.removeCallbacks(longPressRunnable)
                    if (event.action == MotionEvent.ACTION_UP && !isAutoTyping) {
                        currentInputConnection?.commitText(" ", 1)
                        audioEngine.playClick()
                    }
                }
            }
            false
        }
    }

    // --- المحرك البرمجي للتوليد والكتابة التلقائية ---
    private fun startAutoTypingSequence() {
        if (isAutoTyping) return
        isAutoTyping = true

        val mode = prefs.getString("typing_mode", "lines") ?: "lines"
        val speedDelay = prefs.getLong("typing_speed_delay", 100L)
        val replaceSpaceWithPlus = prefs.getBoolean("replace_space_plus", false)
        val autoSendEnabled = prefs.getBoolean("auto_send_enabled", true)

        typingJob = CoroutineScope(Dispatchers.Main).launch {
            // اختيار النص بناءً على النمط المحدد
            val rawText = if (mode == "lines") {
                getNextLine()
            } else {
                generateRandomVocabulary()
            }

            if (rawText.isBlank()) {
                isAutoTyping = false
                return@launch
            }

            // تطبيق استبدال المسافة بـ + إذا كان مفعلاً
            val textToType = if (replaceSpaceWithPlus) {
                rawText.replace(" ", " + ")
            } else {
                rawText
            }

            // محاكاة الكتابة حرفاً بحرف مع التزامن البصري والصوتي الكامل
            for (char in textToType) {
                if (!isAutoTyping) break

                simulateKeyFeedback(char)
                currentInputConnection?.commitText(char.toString(), 1)

                if (speedDelay > 0) {
                    delay(speedDelay)
                }
            }

            // تنفيذ الإرسال التلقائي الفائق عند الانتهاء
            if (autoSendEnabled && isAutoTyping) {
                delay(120L)
                performUniversalSend()
            }

            isAutoTyping = false
        }
    }

    private fun simulateKeyFeedback(c: Char) {
        audioEngine.playClick()

        val btn = keyButtons[c.lowercaseChar()]
        if (btn != null) {
            btn.isPressed = true
            showKeyPopup(btn, c.toString())

            Handler(Looper.getMainLooper()).postDelayed({
                btn.isPressed = false
                popupWindow?.dismiss()
            }, 60L)
        }
    }

    private fun showKeyPopup(btn: Button, text: String) {
        try {
            popupTextView?.text = text
            val coords = IntArray(2)
            btn.getLocationOnScreen(coords)
            popupWindow?.showAtLocation(
                btn,
                0,
                coords[0] - (140 - btn.width) / 2,
                coords[1] - 150
            )
        } catch (e: Exception) {
            // تفادي أي خطأ إذا كانت النافذة معروضة مسبقاً
        }
    }

    private fun getNextLine(): String {
        val linesRaw = prefs.getString("lines_database", "") ?: ""
        val lines = linesRaw.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        var index = prefs.getInt("line_cycle_index", 0)
        if (index >= lines.size) index = 0
        val selected = lines[index]
        prefs.edit().putInt("line_cycle_index", index + 1).apply()
        return selected
    }

    private fun generateRandomVocabulary(): String {
        val vocabRaw = prefs.getString("vocab_database", "") ?: ""
        val words = vocabRaw.split(Regex("[,\\s\n]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""

        val minWords = prefs.getInt("vocab_min_words", 20)
        val maxWords = prefs.getInt("vocab_max_words", 30)
        val targetCount = Random.nextInt(minWords, maxWords + 1)

        val selected = mutableListOf<String>()
        repeat(targetCount) {
            selected.add(words[Random.nextInt(words.size)])
        }
        return selected.joinToString(" ")
    }

    // --- بروتوكول الإرسال العام الفائق الشامل (Universal Auto-Send) ---
    private fun performUniversalSend() {
        val ic = currentInputConnection ?: return

        // 1. محاولة الإرسال عبر محرك تطبيقات الدردشة
        var sent = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        if (!sent) sent = ic.performEditorAction(EditorInfo.IME_ACTION_GO)
        if (!sent) sent = ic.performEditorAction(EditorInfo.IME_ACTION_DONE)

        // 2. محاكاة ضغط زر Enter الفيزيائي كحل احتياطي شامل
        val eventTime = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    private fun stopAutoTypingImmediately() {
        isAutoTyping = false
        typingJob?.cancel()
        popupWindow?.dismiss()
        keyButtons.values.forEach { it.isPressed = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoTypingImmediately()
    }
}
