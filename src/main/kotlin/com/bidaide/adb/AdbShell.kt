package com.bidaide.adb

import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import java.util.concurrent.TimeUnit

class AdbException(message: String) : Exception(message)

/** Thin wrappers over ddmlib shell commands. Everything here blocks; never call from the EDT. */
object AdbShell {
    private const val TIMEOUT_SECONDS = 20L

    fun run(device: IDevice, command: String, timeoutSeconds: Long = TIMEOUT_SECONDS): String {
        val receiver = CollectingOutputReceiver()
        device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
        return receiver.output.trim()
    }

    /** Single-quotes [value] for the device shell. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // --- Device settings. `settings get` prints "null" for keys that were never written. ---

    fun getSetting(device: IDevice, namespace: String, key: String): String? =
        run(device, "settings get $namespace $key").takeUnless { it.isEmpty() || it == "null" }

    fun putSetting(device: IDevice, namespace: String, key: String, value: String) {
        run(device, "settings put $namespace $key $value")
    }

    /** Flips a 0/1 setting and returns the new state. */
    fun toggleSetting(device: IDevice, namespace: String, key: String): Boolean {
        val enable = getSetting(device, namespace, key) != "1"
        putSetting(device, namespace, key, if (enable) "1" else "0")
        return enable
    }

    fun toggleNightMode(device: IDevice): Boolean {
        val enable = !run(device, "cmd uimode night").contains("yes")
        setNightMode(device, enable)
        return enable
    }

    fun setNightMode(device: IDevice, on: Boolean) {
        run(device, "cmd uimode night ${if (on) "yes" else "no"}")
    }

    fun setAnimations(device: IDevice, on: Boolean) {
        ANIMATION_SCALES.forEach { putSetting(device, "global", it, if (on) "1" else "0") }
    }

    /** Locks the screen to portrait or landscape; null hands rotation back to the sensor. */
    fun setRotation(device: IDevice, landscape: Boolean?) {
        if (landscape == null) {
            putSetting(device, "system", "accelerometer_rotation", "1")
        } else {
            putSetting(device, "system", "accelerometer_rotation", "0")
            putSetting(device, "system", "user_rotation", if (landscape) "1" else "0")
        }
    }

    /** Turns the screen a quarter and back. Returns whether it is now landscape. */
    fun toggleRotation(device: IDevice): Boolean {
        val landscape = getSetting(device, "system", "user_rotation") != "1"
        setRotation(device, landscape)
        return landscape
    }

    /** `input text` treats spaces as argument separators and wants them as %s; everything else is shell-quoted. */
    internal fun inputTextCommand(text: String): String = "input text ${quote(text.replace(" ", "%s"))}"

    /**
     * Types [text] into whatever has focus on the device. With Android CLI any character works (and the field is
     * cleared first); without it, `input` is only reliable for ASCII.
     */
    fun inputText(device: IDevice, text: String, pressEnter: Boolean) {
        if (AndroidCli.isAvailable) {
            AndroidCli.run(AndroidCli.inputTextArgs(device.serialNumber, text, pressEnter))
            return
        }
        val out = run(device, inputTextCommand(text))
        if (out.contains("Exception") || out.contains("Error")) throw AdbException(out.lineSequence().first())
        if (pressEnter) run(device, "input keyevent KEYCODE_ENTER")
    }

    /** Process id of the running app, or null when it is not running. */
    fun pidOf(device: IDevice, packageName: String): String? =
        run(device, "pidof $packageName").split(Regex("\\s+")).firstOrNull { it.toIntOrNull() != null }

    fun property(device: IDevice, name: String): String = run(device, "getprop $name")

    internal fun parseVersionName(dumpsys: String): String? =
        dumpsys.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("versionName=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun versionName(device: IDevice, packageName: String): String? = parseVersionName(run(device, "dumpsys package $packageName"))

    /** The last [lines] log lines, of the app's process when it is running, else of the whole device. */
    fun logcatDump(device: IDevice, packageName: String, lines: Int): String {
        val pid = pidOf(device, packageName)
        return run(device, "logcat -d -v threadtime -t $lines${pid?.let { " --pid=$it" }.orEmpty()}", timeoutSeconds = 30)
    }

    fun toggleAirplaneMode(device: IDevice): Boolean {
        val enable = !run(device, "cmd connectivity airplane-mode").contains("enabled")
        run(device, "cmd connectivity airplane-mode ${if (enable) "enable" else "disable"}")
        return enable
    }

    private val ANIMATION_SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    /** Turns the three system animation scales off, or back to 1x. Returns whether animations are now on. */
    fun toggleAnimations(device: IDevice): Boolean {
        val enable = getSetting(device, "global", ANIMATION_SCALES.last())?.toFloatOrNull() == 0f
        setAnimations(device, enable)
        return enable
    }

    /** Switches between the default font size and a large one. Returns the scale now in effect. */
    fun toggleFontScale(device: IDevice): String {
        val current = getSetting(device, "system", "font_scale")?.toFloatOrNull() ?: 1f
        val next = if (current > 1.05f) "1.0" else LARGE_FONT_SCALE
        putSetting(device, "system", "font_scale", next)
        return next
    }

    private const val LARGE_FONT_SCALE = "1.3"

    /** Per-app language (Android 13+). A blank [locales] goes back to the system language. */
    fun setAppLocale(device: IDevice, packageName: String, locales: String) {
        val out = run(device, "cmd locale set-app-locales $packageName --locales ${quote(locales.trim())}")
        if (out.contains("Error", ignoreCase = true) || out.contains("Unknown command")) throw AdbException(out)
    }

    /** Opens [url] with a VIEW intent; restricted to [packageName] when given. */
    fun openDeepLink(device: IDevice, url: String, packageName: String?) {
        val out = run(device, "am start -W -a android.intent.action.VIEW -d ${quote(url)}${packageName?.let { " $it" }.orEmpty()}")
        if (out.contains("Error")) throw AdbException(out.lineSequence().firstOrNull { it.contains("Error") } ?: out)
    }

    /** Sends the app to the background and kills its process, as the system does under memory pressure. */
    fun killInBackground(device: IDevice, packageName: String) {
        run(device, "input keyevent KEYCODE_HOME")
        Thread.sleep(1_000)
        run(device, "am kill $packageName")
    }

    /** Fails with a clear message when `run-as` is refused, which is what happens for non-debuggable builds. */
    fun checkRunAs(output: String, packageName: String) {
        if (output.contains("run-as:")) {
            throw AdbException(BidaideBundle.message("adb.shell.runAs", packageName, output.lineSequence().first()))
        }
    }

    fun isInstalled(device: IDevice, packageName: String): Boolean =
        run(device, "pm list packages $packageName").lineSequence().any { it.trim() == "package:$packageName" }

    fun forceStop(device: IDevice, packageName: String) {
        run(device, "am force-stop $packageName")
    }

    fun clearData(device: IDevice, packageName: String) {
        val out = run(device, "pm clear $packageName")
        if (!out.contains("Success")) throw AdbException(out.ifEmpty { BidaideBundle.message("adb.shell.clearFailed") })
    }

    /** Wipes only cache dirs. Needs `run-as`, so it only works for debuggable builds. */
    fun clearCache(device: IDevice, packageName: String) {
        val out = run(device, "run-as $packageName sh -c 'rm -rf cache/* code_cache/*'")
        if (out.isNotEmpty()) throw AdbException(out)
    }

    fun uninstall(device: IDevice, packageName: String) {
        device.uninstallPackage(packageName)?.let { throw AdbException(it) }
    }

    fun launcherActivity(device: IDevice, packageName: String): String {
        val out = run(device, "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName")
        return parseLauncherActivity(out)
            ?: throw AdbException(BidaideBundle.message("adb.shell.noLauncher", packageName))
    }

    fun start(device: IDevice, packageName: String, waitForDebugger: Boolean = false) {
        val component = launcherActivity(device, packageName)
        val debugFlag = if (waitForDebugger) "-D " else ""
        val out = run(device, "am start ${debugFlag}-n $component -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        if (out.contains("Error")) throw AdbException(out)
    }

    fun requestedPermissions(device: IDevice, packageName: String): List<String> =
        parseRequestedPermissions(run(device, "dumpsys package $packageName"))

    /** Returns how many permissions changed; non-runtime permissions are rejected by `pm` and skipped. */
    fun setPermissions(device: IDevice, packageName: String, grant: Boolean): Int {
        val verb = if (grant) "grant" else "revoke"
        return requestedPermissions(device, packageName).count { permission ->
            run(device, "pm $verb $packageName $permission").isEmpty()
        }
    }

    fun setService(device: IDevice, service: String, enabled: Boolean) {
        run(device, "svc $service ${if (enabled) "enable" else "disable"}")
    }

    internal fun parseLauncherActivity(output: String): String? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }

    internal fun parseRequestedPermissions(dumpsys: String): List<String> {
        val result = LinkedHashSet<String>()
        var inSection = false
        for (line in dumpsys.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed == "requested permissions:") {
                inSection = true
            } else if (inSection) {
                // Section ends at the next "header:" line; entries look like "android.permission.X" or "android.permission.X: restricted=true".
                if (trimmed.isEmpty() || (trimmed.endsWith(":") && !trimmed.contains('.'))) {
                    inSection = false
                } else {
                    result += trimmed.substringBefore(':')
                }
            }
        }
        return result.toList()
    }
}
