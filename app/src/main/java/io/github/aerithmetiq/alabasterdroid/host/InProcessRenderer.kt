package io.github.aerithmetiq.alabasterdroid.host

import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the game's page out of a 32-bit renderer process. Alabaster Droid's own code, not
 * alabaster-android's.
 *
 * WebView normally runs a page in a separate sandboxed process, started in the WebView package's
 * *primary* ABI. Some firmwares ship a WebView whose primary ABI is 32-bit (the Retroid Pocket Nova's
 * `com.android.webview` 109 is `armeabi-v7a`), so the page, and with it the whole game engine, runs
 * as 32-bit code even though this app is 64-bit. Measured on that device, that costs the game twice:
 *
 *  - 32-bit code may only use the cores that still support it (five of the Snapdragon 8 Gen 2's
 *    eight, none of them the fast ones), so the engine's one busy thread waited for a core 10-14 % of
 *    the time and the game ran at 45-55 fps;
 *  - the process has a 4 GB address space, which the game's decoded audio fills within minutes
 *    (`RESOURCE FAILURE: Couldn't decode audio file`).
 *
 * With the page in this app's own 64-bit process instead, the same scene held 59-60 fps at
 * 1920x1080 and the ceiling is gone. Android's device-wide switch for that
 * (`cmd webviewupdate disable-multiprocess`) needs a shell; this does it for this app only: WebView
 * decides by a command-line switch that its start-up code adds when the system says "multi-process",
 * and the switch can be taken off again before the engine starts.
 *
 * It reaches into WebView's own classes, which a release WebView renames, so everything here is best
 * effort: if this WebView is built differently, nothing changes and the page runs in the sandboxed
 * process as before. The
 * sandbox it gives up separates untrusted web content from the app; the only content this WebView
 * ever loads is the user's own copy of the game, from local files.
 */
object InProcessRenderer {

    /** WebView's switch for "run pages in a sandboxed renderer process" (Chromium `AwSwitches`). */
    private const val SANDBOXED_RENDERER = "webview-sandboxed-renderer"

    /**
     * Whether a native library folder is a 32-bit ABI's (`…/lib/arm`, `…/lib/x86`); null when the
     * folder does not say.
     */
    fun is32BitLibDir(dir: String?): Boolean? = when (dir?.trimEnd('/')?.substringAfterLast('/')) {
        "arm", "x86" -> true
        "arm64", "x86_64" -> false
        else -> null
    }

    /** Whether this 64-bit process would get a 32-bit renderer from the device's WebView. */
    fun needed(context: Context): Boolean {
        if (!Process.is64Bit()) return false
        val webView = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (e: Exception) {
            null
        }
        return is32BitLibDir(webView?.applicationInfo?.nativeLibraryDir) == true
    }

    /**
     * Takes the sandboxed-renderer switch off WebView's command line. Must run before anything starts
     * WebView's engine (the first `WebView`, or a call such as `WebSettings.getDefaultUserAgent`).
     * Returns what happened, for the diagnostics record.
     */
    fun apply(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "not tried: needs Android 9"
        return try {
            /* Asking for the class loader creates WebView's provider, which builds the command line. */
            val loader = WebView.getWebViewClassLoader()
            val commandLine = findCommandLine(context, loader)
                ?: return "failed: WebView's command line was not found"
            if (!commandLine.has(SANDBOXED_RENDERER)) return "already in this process"
            if (commandLine.remove(SANDBOXED_RENDERER)) "in this process (${commandLine.type.name})"
            else "failed: the switch stayed (${commandLine.type.name})"
        } catch (e: Throwable) {
            "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Chromium's `org.chromium.base.CommandLine`, as far as this needs it. A release WebView renames
     * its classes and methods, so the methods are found by their signatures and told apart by what
     * they do, never by name.
     */
    private class CommandLine(val type: Class<*>, private val instance: Any) {
        private val stringMethods = type.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
        }
        private val hasSwitch = stringMethods.single { it.returnType == Boolean::class.javaPrimitiveType }

        fun has(switch: String): Boolean = hasSwitch.invoke(instance, switch) == true

        /**
         * `removeSwitch` and `appendSwitch` have the same signature. Each candidate is tried until the
         * switch is gone; appending a switch that is already there changes nothing, and removing
         * takes every copy off.
         */
        fun remove(switch: String): Boolean {
            for (method in stringMethods.filter { it.returnType == Void.TYPE }) {
                method.invoke(instance, switch)
                if (!has(switch)) return true
            }
            return false
        }
    }

    private fun findCommandLine(context: Context, loader: ClassLoader): CommandLine? {
        runCatching { Class.forName("org.chromium.base.CommandLine", false, loader) }.getOrNull()
            ?.let { named -> commandLineOf(named)?.let { return it } }
        /* Renamed: look through the WebView package's classes for the one with CommandLine's shape. */
        val apk = WebViewCompat.getCurrentWebViewPackage(context)?.applicationInfo?.sourceDir ?: return null
        @Suppress("DEPRECATION")
        val dex = dalvik.system.DexFile(apk)
        try {
            for (name in dex.entries()) {
                /* A renamed class sits in the default package; skipping the rest keeps this quick. */
                if (name.indexOf('.') != -1) continue
                val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                if (looksLikeCommandLine(type)) commandLineOf(type)?.let { return it }
            }
        } finally {
            runCatching { dex.close() }
        }
        return null
    }

    /**
     * CommandLine's shape: an abstract class holding a static `AtomicReference`, with a static
     * no-argument method returning its own type, and instance methods `(String) -> boolean`,
     * `(String) -> String`, `(String) -> void` and `() -> String[]`. In WebView 109 exactly one class
     * has it. Reading a class's members does not run any of its code.
     */
    private fun looksLikeCommandLine(type: Class<*>): Boolean {
        if (!Modifier.isAbstract(type.modifiers) || type.isInterface) return false
        val fields = runCatching { type.declaredFields }.getOrNull() ?: return false
        if (fields.none { Modifier.isStatic(it.modifiers) && it.type == AtomicReference::class.java }) return false
        val methods = runCatching { type.declaredMethods }.getOrNull() ?: return false
        if (methods.count { Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == type } != 1) return false
        val text = arrayOf<Class<*>>(String::class.java)
        fun instance(returns: Class<*>?, params: Array<Class<*>>) = methods.count {
            !Modifier.isStatic(it.modifiers) && it.returnType == returns && it.parameterTypes.contentEquals(params)
        }
        return instance(Boolean::class.javaPrimitiveType, text) == 1 &&
            instance(String::class.java, text) == 1 &&
            instance(Void.TYPE, text) >= 1 &&
            instance(Array<String>::class.java, emptyArray()) == 1
    }

    /**
     * The live command line behind [type], accepted only if it already carries the switch this is
     * about: no other class would answer true to that exact name.
     */
    private fun commandLineOf(type: Class<*>): CommandLine? {
        val getter = type.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == type
        } ?: return null
        val instance = getter.invoke(null) ?: return null
        val found = runCatching { CommandLine(type, instance) }.getOrNull() ?: return null
        return found.takeIf { runCatching { it.has(SANDBOXED_RENDERER) }.getOrDefault(false) }
    }
}
