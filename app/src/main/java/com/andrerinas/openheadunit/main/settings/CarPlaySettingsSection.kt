package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.widget.Toast
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.carplay.CarPlayLauncher
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shilapi.xcertplay.CarPlaySettings

/**
 * The Apple CarPlay tab of Head Unit Settings: the CarPlay options that apply to this wired head
 * unit, in the settings list's own rows. Each change is saved at once (CarPlay keeps its own
 * settings, apart from Android Auto's Save button) and applies at the iPhone's next connection.
 *
 * [refresh] rebuilds the list after a change, so a row shows its new value.
 */
class CarPlaySettingsSection(private val context: Context, private val refresh: () -> Unit) {

    fun items(): List<SettingItem> {
        val c = context
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("cpConnection", R.string.cp_category_connection))
        if (!CarPlayLauncher.hasIdentity(c)) {
            items.add(SettingItem.InfoBanner("cpNoIdentity", R.string.cp_info_no_identity))
        }
        items.add(SettingItem.SettingEntry(
            stableId = "cpHowTo",
            nameResId = R.string.cp_how_to_connect,
            value = c.getString(R.string.cp_how_to_connect_value),
            onClick = { },
        ))
        items.add(SettingItem.InfoBanner("cpNextConnection", R.string.cp_info_next_connection))

        // --- Picture ------------------------------------------------------------------------
        items.add(SettingItem.CategoryHeader("cpPicture", R.string.cp_category_picture))
        items.add(toggle("cpRightHandDrive", R.string.cp_right_hand_drive, R.string.cp_right_hand_drive_desc,
            CarPlaySettings.rightHandDrive(c)) { CarPlaySettings.setRightHandDrive(c, it) })

        val nightModes = CarPlaySettings.NightMode.entries
        val nightLabels = listOf(R.string.cp_night_follow_android, R.string.cp_night_light_sensor,
            R.string.cp_night_day, R.string.cp_night_night).map { c.getString(it) }
        items.add(choice("cpNightMode", R.string.cp_night_mode, nightLabels,
            nightModes.indexOf(CarPlaySettings.nightMode(c))) { CarPlaySettings.setNightMode(c, nightModes[it]) })

        val sizes = CarPlaySettings.sizesMm
        val savedSize = CarPlaySettings.sizeMm(c)
        val sizeLabels = sizes.map { sizeLabel(it) }
        items.add(choice("cpSize", R.string.cp_size, sizeLabels, sizes.indexOf(savedSize),
            current = if (savedSize in sizes) null else c.getString(R.string.cp_size_custom, savedSize)) {
            CarPlaySettings.setSizeMm(c, sizes[it])
        })

        val resolutions = CarPlaySettings.resolutions
        items.add(choice("cpResolution", R.string.cp_resolution,
            resolutions.map { c.getString(R.string.cp_resolution_value, it) },
            resolutions.indexOf(CarPlaySettings.resolution(c)),
            current = c.getString(R.string.cp_resolution_value, CarPlaySettings.resolution(c))) {
            CarPlaySettings.setResolution(c, resolutions[it])
        })

        val rates = CarPlaySettings.frameRates
        items.add(choice("cpFrameRate", R.string.cp_frame_rate,
            rates.map { c.getString(R.string.cp_frame_rate_value, it) }, rates.indexOf(CarPlaySettings.frameRate(c)),
            current = c.getString(R.string.cp_frame_rate_value, CarPlaySettings.frameRate(c))) {
            CarPlaySettings.setFrameRate(c, rates[it])
        })

        items.add(toggle("cpHideBars", R.string.cp_hide_bars, R.string.cp_hide_bars_desc,
            CarPlaySettings.hideSystemBars(c)) { CarPlaySettings.setHideSystemBars(c, it) })

        val radii = CarPlaySettings.cornerRadii
        items.add(choice("cpCorners", R.string.cp_corners, radii.map { cornerLabel(it) },
            radii.indexOf(CarPlaySettings.cornerRadius(c))) { CarPlaySettings.setCornerRadius(c, radii[it]) })

        items.add(toggle("cpHevc", R.string.cp_hevc, R.string.cp_hevc_desc, CarPlaySettings.hevc(c)) {
            CarPlaySettings.setHevc(c, it)
        })
        items.add(toggle("cpSmooth", R.string.cp_smooth, R.string.cp_smooth_desc, CarPlaySettings.smoothVideo(c)) {
            CarPlaySettings.setSmoothVideo(c, it)
        })
        items.add(toggle("cpLowLatency", R.string.cp_low_latency, R.string.cp_low_latency_desc, CarPlaySettings.lowLatency(c)) {
            CarPlaySettings.setLowLatency(c, it)
        })

        // --- Sound and calls ----------------------------------------------------------------
        items.add(SettingItem.CategoryHeader("cpSound", R.string.cp_category_sound))
        val levels = CarPlaySettings.navigationLevels
        items.add(choice("cpNavVolume", R.string.cp_nav_volume, levels.map { navLabel(it) },
            levels.indexOf(CarPlaySettings.navigationLevel(c)),
            current = navLabel(CarPlaySettings.navigationLevel(c))) { CarPlaySettings.setNavigationLevel(c, levels[it]) })

        val buffers = CarPlaySettings.musicBuffers
        items.add(choice("cpMusicBuffer", R.string.cp_music_buffer,
            buffers.map { c.getString(R.string.cp_music_buffer_value, it) }, buffers.indexOf(CarPlaySettings.musicBuffer(c)),
            current = c.getString(R.string.cp_music_buffer_value, CarPlaySettings.musicBuffer(c))) {
            CarPlaySettings.setMusicBuffer(c, buffers[it])
        })
        items.add(toggle("cpAudioFocus", R.string.cp_audio_focus, R.string.cp_audio_focus_desc, CarPlaySettings.audioFocus(c)) {
            CarPlaySettings.setAudioFocus(c, it)
        })
        items.add(toggle("cpBufferedMusic", R.string.cp_buffered_music, R.string.cp_buffered_music_desc,
            CarPlaySettings.bufferedMusic(c)) { CarPlaySettings.setBufferedMusic(c, it) })
        items.add(toggle("cpDropBluetooth", R.string.cp_drop_bluetooth, R.string.cp_drop_bluetooth_desc,
            com.andrerinas.openheadunit.carplay.CarPlaySession.dropBluetoothDuringCarPlay(c)) {
            com.andrerinas.openheadunit.carplay.CarPlaySession.setDropBluetoothDuringCarPlay(c, it)
        })
        items.add(toggle("cpEcho", R.string.cp_echo, R.string.cp_echo_desc, CarPlaySettings.echoCancellation(c)) {
            CarPlaySettings.setEchoCancellation(c, it)
        })
        items.add(toggle("cpVoice", R.string.cp_voice, R.string.cp_voice_desc, CarPlaySettings.clearerVoices(c)) {
            CarPlaySettings.setClearerVoices(c, it)
        })

        // --- Troubleshooting ----------------------------------------------------------------
        items.add(SettingItem.CategoryHeader("cpTrouble", R.string.cp_category_trouble))
        items.add(toggle("cpFpsCounter", R.string.cp_fps_counter, R.string.cp_fps_counter_desc, CarPlaySettings.fpsCounter(c)) {
            CarPlaySettings.setFpsCounter(c, it)
        })
        items.add(toggle("cpDebugLogs", R.string.cp_debug_logs, R.string.cp_debug_logs_desc, CarPlaySettings.debugLogs(c)) {
            CarPlaySettings.setDebugLogs(c, it)
        })
        items.add(SettingItem.SettingEntry(
            stableId = "cpReport",
            nameResId = R.string.cp_report,
            value = c.getString(R.string.cp_report_desc),
            onClick = { saveReport() },
        ))
        return items
    }

    private fun toggle(id: String, name: Int, description: Int, checked: Boolean, save: (Boolean) -> Unit) =
        SettingItem.ToggleSettingEntry(
            stableId = id,
            nameResId = name,
            descriptionResId = description,
            isChecked = checked,
            onCheckedChanged = { value ->
                save(value)
                AppLog.i("CarPlay settings: $id = $value")
                refresh()
            },
        )

    /** A row showing the current choice; a tap lists the choices. [current] names a saved value not in the list. */
    private fun choice(id: String, name: Int, labels: List<String>, selected: Int, current: String? = null, save: (Int) -> Unit) =
        SettingItem.SettingEntry(
            stableId = id,
            nameResId = name,
            value = current ?: labels.getOrNull(selected).orEmpty(),
            searchKeywords = labels.joinToString(" "),
            onClick = {
                MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                    .setTitle(name)
                    .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                        save(index)
                        AppLog.i("CarPlay settings: $id = ${labels[index]}")
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
        )

    private fun sizeLabel(mm: Int): String = when (CarPlaySettings.sizeName(mm)) {
        "Large" -> context.getString(R.string.cp_size_large)
        "Medium" -> context.getString(R.string.cp_size_medium)
        "Small" -> context.getString(R.string.cp_size_small)
        else -> context.getString(R.string.cp_size_custom, mm)
    }

    private fun cornerLabel(dp: Int): String =
        if (dp == 0) context.getString(R.string.cp_corners_off) else context.getString(R.string.cp_corners_value, dp)

    private fun navLabel(db: Int): String =
        if (db == 0) context.getString(R.string.cp_nav_volume_full) else context.getString(R.string.cp_nav_volume_value, db)

    private fun saveReport() {
        val app = context.applicationContext
        ToastUtils.showToast(app, app.getString(R.string.cp_report_saving), Toast.LENGTH_SHORT, force = true)
        Thread({
            val result = runCatching { CarPlaySettings.saveDiagnosticReport(app, BuildConfig.VERSION_NAME) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { where ->
                    AppLog.i("CarPlay settings: report saved to $where")
                    ToastUtils.showToast(app, app.getString(R.string.cp_report_saved, where), Toast.LENGTH_LONG, force = true)
                }.onFailure { error ->
                    AppLog.e("CarPlay settings: report failed: ${error.message}")
                    ToastUtils.showToast(app, app.getString(R.string.cp_report_failed, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG, force = true)
                }
            }
        }, "carplay-report").start()
    }
}
