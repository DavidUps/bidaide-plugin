package com.bidaide.adb

import com.bidaide.BidaideBundle
import com.bidaide.settings.BidaideSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * Runs Android CLI (the `android` command-line tool) for what it covers: installing APKs, screenshots and typing
 * text. Everything else (shell, settings, run-as, logcat, emulator console, debugger) stays on ddmlib, which the
 * CLI has no equivalent for. When the binary is missing, callers fall back to ddmlib.
 */
object AndroidCli {
    /** Name of this part of the plugin in titles, notifications and action prefixes. */
    const val TITLE = "Android CLI"

    private val LOG = logger<AndroidCli>()
    private const val TIMEOUT_MILLIS = 120_000

    // IDEs launched from the Dock do not inherit the shell PATH, so the usual install locations are probed too.
    private val FALLBACK_LOCATIONS = listOf(
        "/opt/homebrew/bin/android",
        "/usr/local/bin/android",
        System.getProperty("user.home") + "/.local/bin/android",
    )

    fun find(): String? {
        val configured = BidaideSettings.getInstance().androidCliPath
        if (configured.isNotEmpty()) return configured.takeIf { File(it).canExecute() }
        PathEnvironmentVariableUtil.findInPath("android")?.let { return it.path }
        return FALLBACK_LOCATIONS.firstOrNull { File(it).canExecute() }
    }

    val isAvailable: Boolean get() = find() != null

    /** Blocking; never call from the EDT. Returns stdout, throws [AdbException] on failure. */
    fun run(args: List<String>, timeoutMillis: Int = TIMEOUT_MILLIS): String {
        val android = find() ?: throw AdbException(BidaideBundle.message("adb.cli.notInstalled"))
        val commandLine = GeneralCommandLine(listOf(android) + args).withCharset(Charsets.UTF_8)
        LOG.debug("android " + args.joinToString(" "))
        val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
        if (output.isTimeout) throw AdbException(BidaideBundle.message("adb.cli.timeout", args.firstOrNull().orEmpty()))
        if (output.exitCode != 0) {
            throw AdbException(output.stderr.trim().ifEmpty { output.stdout.trim() }.ifEmpty { BidaideBundle.message("adb.cli.exitCode", output.exitCode) })
        }
        return output.stdout
    }

    // --- Pure command builders ---

    /** Full install (no delta: the APK did not come from this build); -t accepts test-only builds, -d a lower versionCode. */
    internal fun installArgs(serial: String, apk: File): List<String> =
        listOf("install", "--device=$serial", "--use-delta-install=false", "--install-options=-t -d", "--apks=${apk.path}")

    internal fun screenshotArgs(serial: String, output: File): List<String> =
        listOf("screen", "capture", "--device=$serial", "--output=${output.path}")

    /** Types into the focused element. Note `input text` clears the field first, unlike `adb shell input text`. */
    internal fun inputTextArgs(serial: String, text: String, submit: Boolean): List<String> =
        listOf("input", "--device=$serial", "text", text, "focused=true") + if (submit) listOf("--submit") else emptyList()

    /** Android Device Hub; without a serial it picks an online device itself and lets the user switch in its window. */
    internal fun deviceHubArgs(serial: String?): List<String> =
        listOf("device", "hub") + listOfNotNull(serial?.let { "--device=$it" })
}
