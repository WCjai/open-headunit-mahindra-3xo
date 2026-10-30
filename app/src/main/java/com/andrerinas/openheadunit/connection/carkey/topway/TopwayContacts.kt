package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import java.io.File

/**
 * The phone's contacts, as the car's Bluetooth module downloaded them, so a call card can show
 * who is calling.
 *
 * The module reports an incoming call with the number only (an iPhone sends no name over
 * hands-free). `com.tw.bt` does not answer lookups either, but after every phonebook download it
 * hands the whole phonebook to the in-car voice assistant as a JSON broadcast (see
 * [TopwayBluetooth.CONTACTS_ACTION]). This keeps the latest one in the app's private storage and
 * looks numbers up in it. Nothing leaves the head unit.
 *
 * A phonebook arrives only when the module downloads one: on an iPhone, "Sync Contacts" must be on
 * for the car in the phone's Bluetooth settings, and the download is started from the unit's own
 * phonebook page.
 */
object TopwayContacts {

    private const val FILE_NAME = "topway_contacts.json"

    /** Digits compared from the end: enough to tell numbers apart, few enough to ignore +91 or 0. */
    private const val MATCH_DIGITS = 9

    @Volatile private var byNumber: Map<String, String> = emptyMap()
    @Volatile private var loaded = false
    private var file: File? = null

    /** How many numbers the stored phonebook has. */
    val size: Int get() = byNumber.size

    /** Starts listening for phonebooks and loads the stored one. Once, from Application.onCreate. */
    fun register(context: Context) {
        val app = context.applicationContext
        file = File(app.filesDir, FILE_NAME)
        load()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                if (!TopwayBluetooth.isContactsBroadcast(intent?.extras)) return
                val json = intent?.getStringExtra(TopwayBluetooth.EXTRA_CONTACTS) ?: return
                store(json)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(TopwayBluetooth.ACTION_CALL_STATE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** The contact name for [number], or null when the phonebook has none. */
    fun nameFor(number: String?): String? {
        val key = key(number) ?: return null
        if (!loaded) load()
        return byNumber[key]
    }

    private fun store(json: String) {
        val parsed = parse(json)
        if (parsed.isEmpty()) return
        byNumber = parsed
        loaded = true
        try {
            file?.writeText(json)
        } catch (e: Exception) {
            AppLog.w("TopwayContacts: could not save the phonebook: ${e.message}")
        }
        // The count only: names and numbers stay out of the log.
        AppLog.i("TopwayContacts: phonebook received from the car's Bluetooth, ${parsed.size} numbers")
    }

    private fun load() {
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        byNumber = try {
            parse(f.readText())
        } catch (e: Exception) {
            AppLog.w("TopwayContacts: stored phonebook unreadable: ${e.message}")
            emptyMap()
        }
    }

    /** `[{"number": "...", "name": "..."}, ...]` to number key -> name. Later duplicates lose. */
    internal fun parse(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val name = entry.optString("name").trim()
                val k = key(entry.optString("number")) ?: continue
                if (name.isNotEmpty() && k !in out) out[k] = name
            }
        } catch (e: Exception) {
            AppLog.w("TopwayContacts: phonebook not understood: ${e.message}")
        }
        return out
    }

    /** The last [MATCH_DIGITS] digits, or null for a number too short to match safely. */
    internal fun key(number: String?): String? {
        val digits = number?.filter { it.isDigit() } ?: return null
        if (digits.length < 5) return null
        return digits.takeLast(MATCH_DIGITS)
    }
}
