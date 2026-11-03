package com.bidaide.adb

import com.bidaide.BidaideBundle
import com.intellij.openapi.project.Project
import java.io.File

/** Installs APK files that did not come out of this project's own build: downloads, CI artifacts, release assets. */
object ApkInstaller {
    private const val INSTALL_TIMEOUT_MILLIS = 5 * 60 * 1000

    /** Must be called on the EDT: it may ask which device to use. */
    fun install(project: Project, apk: File) {
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            AdbTasks.run(project, BidaideBundle.message("adb.apk.install", apk.name), devices, null) { ctx ->
                if (AndroidCli.isAvailable) {
                    AndroidCli.run(AndroidCli.installArgs(ctx.device.serialNumber, apk), timeoutMillis = INSTALL_TIMEOUT_MILLIS)
                } else {
                    // -t accepts test-only builds, -d a lower versionCode than the installed one.
                    ctx.device.installPackage(apk.path, true, "-t", "-d")
                }
                BidaideBundle.message("adb.apk.installed", apk.name)
            }
        }
    }
}
