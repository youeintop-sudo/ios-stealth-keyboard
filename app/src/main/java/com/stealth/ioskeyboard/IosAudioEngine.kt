package com.stealth.ioskeyboard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.exp
import kotlin.math.sin

class IosAudioEngine {

    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val durationMs = 28 // مدة نقرة الآيفون بالميلي ثانية
    private val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
    private val audioBuffer = ShortArray(numSamples)

    init {
        generateIosClickWaveform()
        setupAudioTrack()
    }

    // توليد المعادلة الفيزيائية لنقرة آبل
    private fun generateIosClickWaveform() {
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val wave = 0.8 * sin(2.0 * Math.PI * 1150.0 * t) + 0.3 * sin(2.0 * Math.PI * 180.0 * t)
            val envelope = exp(-t * 220.0)
            val sample = (wave * envelope * Short.MAX_VALUE * 0.75).toInt()
            audioBuffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun setupAudioTrack() {
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                audioBuffer.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).apply {
                write(audioBuffer, 0, audioBuffer.size)
            }
        } catch (e: Exception) {
            audioTrack = null
        }
    }

    // تشغيل فوري بدون أي تأخير زمني
    fun playClick() {
        audioTrack?.let {
            try {
                it.stop()
                it.reloadStaticData()
                it.play()
            } catch (e: Exception) {
                setupAudioTrack()
                it.play()
            }
        }
    }
}
