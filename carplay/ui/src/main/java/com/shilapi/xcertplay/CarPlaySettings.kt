package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.media.NavigationAudioGain
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Open Headunit addition, not part of upstream DiPlay: the CarPlay settings the host app shows in its
 * own settings screen, read from and written to DiPlay's own storage. Only what applies to a wired
 * head unit is here; DiPlay's BYD, cluster and wireless options are left at their defaults.
 *
 * Most changes apply at the next CarPlay connection (unplug and plug in the iPhone).
 */
object CarPlaySettings {

    enum class NightMode { FOLLOW_ANDROID, LIGHT_SENSOR, ALWAYS_DAY, ALWAYS_NIGHT }

    // --- Picture ------------------------------------------------------------------------------

    fun rightHandDrive(context: Context): Boolean = AirPlayPersistence.loadRightHandDrive(context)
    fun setRightHandDrive(context: Context, value: Boolean) = AirPlayPersistence.saveRightHandDrive(context, value)

    fun nightMode(context: Context): NightMode = when (AirPlayPersistence.loadCarPlayNightMode(context)) {
        CarPlayNightMode.AMBIENT -> NightMode.LIGHT_SENSOR
        CarPlayNightMode.DAY -> NightMode.ALWAYS_DAY
        CarPlayNightMode.NIGHT -> NightMode.ALWAYS_NIGHT
        else -> NightMode.FOLLOW_ANDROID
    }

    fun setNightMode(context: Context, mode: NightMode) = AirPlayPersistence.saveCarPlayNightMode(context, when (mode) {
        NightMode.FOLLOW_ANDROID -> CarPlayNightMode.SYSTEM
        NightMode.LIGHT_SENSOR -> CarPlayNightMode.AMBIENT
        NightMode.ALWAYS_DAY -> CarPlayNightMode.DAY
        NightMode.ALWAYS_NIGHT -> CarPlayNightMode.NIGHT
    })

    /** How big CarPlay draws its icons: the screen width it is told, in millimetres. */
    val sizesMm: List<Int> = CarPlaySize.entries.map { it.widthMillimeters }
    fun sizeMm(context: Context): Int = AirPlayPersistence.loadWidthPhysicalMm(context)
    fun setSizeMm(context: Context, mm: Int) = AirPlayPersistence.saveWidthPhysicalMm(context, mm)
    fun sizeName(mm: Int): String? = CarPlaySize.exact(mm)?.label

    /** The picture's resolution against the screen's, in percent. */
    val resolutions: List<Int> = listOf(50, 75, 100, 125, 150)
        .filter { it in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT }
    fun resolution(context: Context): Int = AirPlayPersistence.loadDisplayScalePercent(context)
    fun setResolution(context: Context, percent: Int) = AirPlayPersistence.saveDisplayScalePercent(context, percent)

    val frameRates: List<Int> = (AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS step 10).toList()
    fun frameRate(context: Context): Int = AirPlayPersistence.loadFps(context)
    fun setFrameRate(context: Context, fps: Int) = AirPlayPersistence.saveFps(context, fps)

    fun hevc(context: Context): Boolean = AirPlayPersistence.loadHevcEnabled(context)
    fun setHevc(context: Context, value: Boolean) = AirPlayPersistence.saveHevcEnabled(context, value)

    fun smoothVideo(context: Context): Boolean = AirPlayPersistence.loadSmoothVideo(context)
    fun setSmoothVideo(context: Context, value: Boolean) = AirPlayPersistence.saveSmoothVideo(context, value)

    fun lowLatency(context: Context): Boolean = AirPlayPersistence.loadLowLatencyDecoder(context)
    fun setLowLatency(context: Context, value: Boolean) = AirPlayPersistence.saveLowLatencyDecoder(context, value)

    /** The unit's status and navigation bars hidden over CarPlay. */
    fun hideSystemBars(context: Context): Boolean =
        AirPlayPersistence.loadHideTopBar(context) && AirPlayPersistence.loadHideBottomBar(context)
    fun setHideSystemBars(context: Context, value: Boolean) {
        AirPlayPersistence.saveHideTopBar(context, value)
        AirPlayPersistence.saveHideBottomBar(context, value)
    }

    /** Black rounded corners over the picture, in dp; 0 is off. */
    val cornerRadii: List<Int> = CarPlayBlackCornerOverlay.RADII_DP.filter { it >= 0 && it <= 48 }
    fun cornerRadius(context: Context): Int = AirPlayPersistence.loadBlackCornerRadiusDp(context)
    fun setCornerRadius(context: Context, dp: Int) {
        if (dp in CarPlayBlackCornerOverlay.RADII_DP) AirPlayPersistence.saveBlackCornerRadiusDp(context, dp)
    }

    // --- Sound and calls ----------------------------------------------------------------------

    /** Navigation prompts lowered by this many dB (0 to -20); music and calls keep their volume. */
    val navigationLevels: List<Int> = listOf(0, -3, -6, -10, -15, NavigationAudioGain.MIN_DB).distinct()
    fun navigationLevel(context: Context): Int = AirPlayPersistence.loadNavigationAudioAttenuationDb(context)
    fun setNavigationLevel(context: Context, db: Int) {
        AirPlayPersistence.saveNavigationAudioAttenuationDb(context, db)
        CarPlayBackgroundSession.snapshot()?.sink?.setNavigationAttenuationDb(db)
    }

    /** How much music is held before it plays over the cable, in ms. More rides out stutters; less starts sooner. */
    val musicBuffers: List<Int> = MediaAudioBuffer.wiredPresets
    fun musicBuffer(context: Context): Int = AirPlayPersistence.loadMediaBufferMillis(context, wireless = false)
    fun setMusicBuffer(context: Context, millis: Int) = AirPlayPersistence.saveMediaBufferMillis(context, false, millis)

    fun audioFocus(context: Context): Boolean = AirPlayPersistence.loadAudioFocusEnabled(context)
    fun setAudioFocus(context: Context, value: Boolean) = AirPlayPersistence.saveAudioFocusEnabled(context, value)

    fun bufferedMusic(context: Context): Boolean = AirPlayPersistence.loadMainBufferedAudio(context)
    fun setBufferedMusic(context: Context, value: Boolean) = AirPlayPersistence.saveMainBufferedAudio(context, value)

    fun echoCancellation(context: Context): Boolean = AirPlayPersistence.loadCallEchoCancellation(context)
    fun setEchoCancellation(context: Context, value: Boolean) = AirPlayPersistence.saveCallEchoCancellation(context, value)

    fun clearerVoices(context: Context): Boolean = AirPlayPersistence.loadCallVoiceFilter(context)
    fun setClearerVoices(context: Context, value: Boolean) = AirPlayPersistence.saveCallVoiceFilter(context, value)

    // --- Troubleshooting ----------------------------------------------------------------------

    fun fpsCounter(context: Context): Boolean = AirPlayPersistence.loadFpsCounter(context)
    fun setFpsCounter(context: Context, value: Boolean) = AirPlayPersistence.saveFpsCounter(context, value)

    fun debugLogs(context: Context): Boolean = AirPlayPersistence.loadDebugLogsEnabled(context)
    fun setDebugLogs(context: Context, value: Boolean) = AirPlayPersistence.saveDebugLogsEnabled(context, value)

    /** True while an iPhone is connected or connecting: most changes wait for its next connection. */
    fun sessionRunning(): Boolean = CarPlayBackgroundSession.hasSession()

    /**
     * A plain-text report of CarPlay's state, settings and recent log, saved in Downloads/OpenHeadunit
     * (or the app's own files where the unit refuses). Reads files: call it off the main thread.
     * Returns where it was saved.
     */
    fun saveDiagnosticReport(context: Context, hostVersion: String): String {
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Open Headunit $hostVersion · Apple CarPlay report · $stamp")
            appendLine(LastConnectionProblem.report(app))
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}; board ${Build.BOARD}")
            appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
            appendLine("CarPlay screen: ${CarPlayScreen.report(app)}")
            appendLine("Video: ${if (hevc(app)) "HEVC" else "H.264"}, ${frameRate(app)} fps, resolution ${resolution(app)}%, size ${sizeMm(app)} mm")
            appendLine("Right-hand drive: ${rightHandDrive(app)}; night mode: ${nightMode(app)}")
            appendLine("Audio focus: ${audioFocus(app)}; music buffer ${musicBuffer(app)} ms; navigation ${navigationLevel(app)} dB")
            appendLine()
            val logs = File(app.filesDir, "logs")
            for (name in listOf("previous.log", "diplay.log")) {
                val file = File(logs, name)
                if (!file.isFile) continue
                appendLine("--- $name (last 600 lines) ---")
                runCatching { file.readLines().takeLast(600) }.getOrDefault(emptyList()).forEach { line ->
                    DiagnosticRedactor.redact(line)?.let { appendLine(it) }
                }
                appendLine()
            }
        }
        val saved = DiagnosticExportStore.saveWithoutPicker(app, "carplay-report-$stamp.txt", report)
        return saved.savedPath ?: if (saved.savedInApp) "the app's own files" else "Downloads/OpenHeadunit"
    }
}
