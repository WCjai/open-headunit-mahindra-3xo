package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import androidx.core.content.pm.PackageInfoCompat
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.Method

/**
 * One open channel on a TopWay unit's `android.tw.john.TWUtil`, the framework class every TopWay
 * app uses to talk to the MCU.
 *
 * The class exists only on TopWay firmware, so everything is reflection. A channel opens a set of
 * message ids, receives the MCU's messages for them on its own thread, and can send commands. The
 * steering-wheel key receiver opens only the key id and never writes; the learning screen opens
 * the learning ids and writes exactly the commands the stock learning app sends.
 *
 * Crash guard. TWUtil is native code on firmware we cannot test against. A marker is committed to
 * disk before the native open; if the process dies before it is cleared, the next [open] of that
 * channel refuses until the app is updated, so a bad unit costs one crash rather than a loop.
 */
class TwUtilChannel(
    private val name: String,
    private val ids: ShortArray,
    /** TWUtil's constructor argument, or null for its no-argument constructor. See [CTOR_VARIANTS]. */
    private val ctorArg: Int? = null,
    /**
     * The serial speed for `open(short[], int)`, which the Bluetooth app uses for its module
     * (115200); null for the plain `open(short[])` every other TopWay app uses.
     */
    private val baud: Int? = null,
) {

    companion object {
        const val CLASS_NAME = "android.tw.john.TWUtil"
        private const val PREFS = "carkey_topway"
        private const val KEY_CTOR = "ctor_variant"

        /** Stored for "the no-argument constructor". */
        const val NO_ARG = -1

        /**
         * The constructors TopWay's own apps use, in the order to try them: the steering-wheel app
         * `TWUtil(17)`, the music, radio, video and EQ apps `TWUtil()`, the system service
         * `TWUtil(16)`. What the number selects is inside TWUtil, which no app carries, so the
         * learning screen finds the one this firmware answers on by trying them.
         */
        val CTOR_VARIANTS = intArrayOf(17, NO_ARG, 16)

        /** The constructor that was seen to receive messages on this unit, or null if none is known yet. */
        fun workingCtor(context: Context): Int? {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (p.contains(KEY_CTOR)) p.getInt(KEY_CTOR, NO_ARG) else null
        }

        fun rememberWorkingCtor(context: Context, variant: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_CTOR, variant).apply()
        }

        /** [NO_ARG] as null, for the constructor parameter. */
        fun ctorArgOf(variant: Int): Int? = if (variant == NO_ARG) null else variant

        fun describeCtor(variant: Int): String = if (variant == NO_ARG) "TWUtil()" else "TWUtil($variant)"

        val twUtilClass: Class<*>? by lazy {
            try {
                Class.forName(CLASS_NAME)
            } catch (e: Throwable) {
                null
            }
        }

        /** Whether this firmware has TWUtil at all, i.e. whether this is a TopWay unit. */
        val available: Boolean get() = twUtilClass != null

        @Volatile private var exemptionTried = false
        @Volatile private var apiLogged = false

        /**
         * Lets this app reach TopWay's own framework classes.
         *
         * TWUtil is a vendor addition to the framework, not public API, so from Android 9 an app
         * targeting a recent SDK can be refused its methods; TopWay's own apps are system apps and
         * exempt. The runtime's own exemption list fixes that for the `Landroid/tw/` prefix only.
         * It is reached through reflection on reflection, which works on Android 9 and 10 and is
         * refused from 11, where this only logs and the open reports what failed.
         */
        private fun exemptTopwayClasses() {
            if (exemptionTried || Build.VERSION.SDK_INT < 28) return
            exemptionTried = true
            try {
                val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
                )
                val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
                val setExemptions = getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)
                ) as Method
                setExemptions.invoke(getRuntime.invoke(null), arrayOf("Landroid/tw/"))
                AppLog.i("TwUtilChannel: TopWay framework classes exempted from hidden-API checks")
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                AppLog.w("TwUtilChannel: could not exempt TopWay classes from hidden-API checks: " +
                    "${cause.javaClass.simpleName}: ${cause.message}")
            }
        }

        /** TWUtil's real method list, once: the line that settles a signature that differs on a firmware. */
        private fun logApiOnce(cls: Class<*>) {
            if (apiLogged) return
            apiLogged = true
            try {
                val api = cls.methods.filter { it.declaringClass != Any::class.java }
                    .joinToString { m -> m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
                val ctors = cls.constructors.joinToString { c -> c.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
                AppLog.i("TwUtilChannel: TWUtil constructors: $ctors; methods: $api")
            } catch (e: Throwable) {
                AppLog.w("TwUtilChannel: could not list TWUtil methods: ${e.message}")
            }
        }
    }

    /** Receives every message on the channel's ids, on the channel's own thread. */
    fun interface Listener {
        fun onMessage(what: Int, arg1: Int, arg2: Int, obj: Any?)
    }

    private var thread: HandlerThread? = null
    private var util: Any? = null
    private val handlerTag = "OpenHeadunit-$name"

    val isOpen: Boolean get() = util != null

    /**
     * Opens the channel and starts delivering to [listener].
     *
     * @return null when open, or a one-line reason it is not, suitable for showing to the user.
     */
    fun open(context: Context, listener: Listener): String? {
        if (util != null) return null
        val cls = twUtilClass ?: return "not a TopWay unit (no $CLASS_NAME)"
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val openingKey = "opening_$name"
        val disabledKey = "disabled_for_version_$name"
        val version = appVersion(app)

        if (prefs.getBoolean(openingKey, false)) {
            prefs.edit().putBoolean(openingKey, false).putLong(disabledKey, version).commit()
            return "off - opening it crashed the app last time; reinstall or update to retry"
        }
        if (prefs.getLong(disabledKey, -1L) == version) {
            return "off - opening it crashed this version of the app earlier; reinstall or update to retry"
        }

        exemptTopwayClasses()
        logApiOnce(cls)
        val worker = HandlerThread("TwUtil-$name").also { it.start() }
        thread = worker
        val handler = object : Handler(worker.looper) {
            override fun handleMessage(msg: Message) {
                // An odd message must never take the thread down with it.
                try {
                    listener.onMessage(msg.what, msg.arg1, msg.arg2, msg.obj)
                } catch (e: Throwable) {
                    AppLog.w("TwUtilChannel[$name]: message could not be handled: ${e.message}")
                }
            }
        }

        // Committed, not applied: the marker has to be on disk before the native call that might
        // take the process down with it.
        prefs.edit().putBoolean(openingKey, true).commit()
        try {
            val instance = newInstance(cls)
            val result = if (baud != null) {
                cls.getMethod("open", ShortArray::class.java, Int::class.javaPrimitiveType).invoke(instance, ids, baud)
            } else {
                cls.getMethod("open", ShortArray::class.java).invoke(instance, ids)
            }
            if (result != 0) {
                closeQuietly(cls, instance)
                stopThread()
                return "not listening - TWUtil.open(${idList()}) returned $result"
            }
            find(cls, "start")?.invoke(instance)
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(instance, handlerTag, handler)
            util = instance
            AppLog.i("TwUtilChannel[$name]: open on ${idList()} with ${describeCtor(ctorArg ?: NO_ARG)}")
            return null
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            stopThread()
            return "failed - ${cause.javaClass.simpleName}: ${cause.message}"
        } finally {
            prefs.edit().putBoolean(openingKey, false).apply()
        }
    }

    /**
     * Sends one command, `write(what)`, `write(what, a)` or `write(what, a, b)`, the three forms the
     * stock apps use. Returns TWUtil's answer, or null when the channel is closed or the call failed.
     */
    fun write(vararg args: Int): Int? {
        val cls = twUtilClass ?: return null
        val instance = util ?: return null
        if (args.isEmpty() || args.size > 3) return null
        return try {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            cls.getMethod("write", *types).invoke(instance, *args.toTypedArray()) as? Int
        } catch (e: Throwable) {
            AppLog.w("TwUtilChannel[$name]: write(${args.joinToString { "0x%X".format(it) }}) failed: ${(e.cause ?: e).message}")
            null
        }
    }

    /** `write(what, a, b, obj)`: the form the Bluetooth app uses to send song titles to the car's display. */
    fun writeObject(what: Int, a: Int, b: Int, obj: Any?): Int? {
        val cls = twUtilClass ?: return null
        val instance = util ?: return null
        return try {
            val int = Int::class.javaPrimitiveType!!
            cls.getMethod("write", int, int, int, Any::class.java).invoke(instance, what, a, b, obj) as? Int
        } catch (e: Throwable) {
            AppLog.w("TwUtilChannel[$name]: write(0x%X, %d, %d, object) failed: %s".format(what, a, b, (e.cause ?: e).message))
            null
        }
    }

    /**
     * `write(what, a, b, obj1, obj2)`: the form the Bluetooth app uses to pass a device address,
     * a name or a PIN along with a command.
     */
    fun writeObjects(what: Int, a: Int, b: Int, obj1: Any?, obj2: Any?): Int? {
        val cls = twUtilClass ?: return null
        val instance = util ?: return null
        return try {
            val int = Int::class.javaPrimitiveType!!
            cls.getMethod("write", int, int, int, Any::class.java, Any::class.java)
                .invoke(instance, what, a, b, obj1, obj2) as? Int
        } catch (e: Throwable) {
            AppLog.w("TwUtilChannel[$name]: write(0x%X, %d, %d, objects) failed: %s".format(what, a, b, (e.cause ?: e).message))
            null
        }
    }

    fun close() {
        val cls = twUtilClass
        val instance = util
        util = null
        if (cls != null && instance != null) {
            try {
                find(cls, "removeHandler", String::class.java)?.invoke(instance, handlerTag)
            } catch (e: Throwable) {
                AppLog.w("TwUtilChannel[$name]: removeHandler failed: ${e.message}")
            }
            try {
                find(cls, "stop")?.invoke(instance)
            } catch (e: Throwable) {
                AppLog.w("TwUtilChannel[$name]: stop failed: ${e.message}")
            }
            closeQuietly(cls, instance)
        }
        stopThread()
    }

    private fun idList() = ids.joinToString(",") { "0x%X".format(it.toInt() and 0xFFFF) }

    private fun stopThread() {
        thread?.quit()
        thread = null
    }

    private fun closeQuietly(cls: Class<*>, instance: Any) {
        try {
            find(cls, "close")?.invoke(instance)
        } catch (e: Throwable) {
            AppLog.w("TwUtilChannel[$name]: close failed: ${e.message}")
        }
    }

    /**
     * [ctorArg] decides the constructor. Built on the caller's thread, which must be the main thread:
     * TWUtil may tie its own message delivery to the looper of the thread that created it.
     */
    private fun newInstance(cls: Class<*>): Any {
        ctorArg?.let { return cls.getConstructor(Int::class.javaPrimitiveType).newInstance(it) }
        cls.constructors.firstOrNull { it.parameterTypes.isEmpty() }?.let { return it.newInstance() }
        return cls.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
    }

    private fun find(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        try {
            cls.getMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            null
        }

    private fun appVersion(context: Context): Long =
        try {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        } catch (e: Exception) {
            0L
        }
}
