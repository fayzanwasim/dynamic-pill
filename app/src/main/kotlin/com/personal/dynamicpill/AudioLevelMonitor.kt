package com.personal.dynamicpill

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads the device's output mix with the platform [Visualizer] and reports per-band levels (0..1).
 * Needs the RECORD_AUDIO permission; when it is missing or the device refuses, nothing is reported
 * and the equalizer falls back to its own animation.
 */
class AudioLevelMonitor(
    private val context: Context,
    private val onLevels: (FloatArray?) -> Unit,
) {
    private var visualizer: Visualizer? = null

    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit

        override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
            onLevels(bandLevels(fft))
        }
    }

    fun start() {
        if (visualizer != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO not granted; using the simulated equalizer")
            return
        }
        val created = try {
            Visualizer(GLOBAL_OUTPUT_MIX_SESSION)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Device refused the output visualizer; using the simulated equalizer", e)
            return
        }
        val configured = created.setCaptureSize(Visualizer.getCaptureSizeRange()[1]) == Visualizer.SUCCESS &&
            created.setDataCaptureListener(captureListener, Visualizer.getMaxCaptureRate() / 2, false, true) ==
            Visualizer.SUCCESS &&
            created.setEnabled(true) == Visualizer.SUCCESS
        if (!configured) {
            Log.w(TAG, "Could not configure the output visualizer; using the simulated equalizer")
            created.release()
            return
        }
        visualizer = created
        IslandBus.visualizerLive = true
    }

    fun stop() {
        visualizer?.apply {
            setEnabled(false)
            release()
        }
        visualizer = null
        IslandBus.visualizerLive = false
        onLevels(null)
    }

    private fun bandLevels(fft: ByteArray): FloatArray {
        val bins = fft.size / 2
        return FloatArray(EqualizerView.BAR_COUNT) { band ->
            val end = min(BAND_EDGES[band + 1], bins)
            var peak = 0f
            for (bin in BAND_EDGES[band] until end) {
                peak = maxOf(peak, hypot(fft[2 * bin].toFloat(), fft[2 * bin + 1].toFloat()))
            }
            (sqrt(peak) / FULL_SCALE_ROOT).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val TAG = "DynamicPill"
        const val GLOBAL_OUTPUT_MIX_SESSION = 0

        // FFT bin edges per bar (about 43 Hz per bin at the maximum capture size): bass up to upper mids.
        val BAND_EDGES = intArrayOf(1, 2, 5, 12, 30, 90)

        // Square root of the FFT magnitude that counts as a full-height bar.
        const val FULL_SCALE_ROOT = 11f
    }
}
