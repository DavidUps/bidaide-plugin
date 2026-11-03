package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import com.bidaide.Downloads
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Screenshots, recordings and app data: everything that moves files between the device and this machine. Blocking. */
object AdbFiles {
    private const val REMOTE_DIR = "/data/local/tmp"
    private const val TAR_TIMEOUT_SECONDS = 180L

    fun captureDir(): File = Downloads.captureDir()

    fun backupDir(packageName: String): File = File(captureDir(), "bidaide-backups/$packageName").apply { mkdirs() }

    private fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")

    /** Saves a PNG screenshot and puts the image on the clipboard. */
    fun screenshot(device: IDevice): File {
        val file = File(captureDir(), "screenshot-${safe(device.name)}-${stamp()}.png")
        val image = if (AndroidCli.isAvailable) {
            AndroidCli.run(AndroidCli.screenshotArgs(device.serialNumber, file))
            ImageIO.read(file) ?: throw AdbException(BidaideBundle.message("adb.files.noScreenshot"))
        } else {
            val captured = device.screenshot?.asBufferedImage() ?: throw AdbException(BidaideBundle.message("adb.files.noScreenshot"))
            ImageIO.write(captured, "png", file)
            captured
        }
        ApplicationManager.getApplication().invokeLater { CopyPasteManager.getInstance().setContents(ImageTransferable(image)) }
        return file
    }

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any = image
    }

    // --- Screen recording: `screenrecord` runs on the device until it gets SIGINT (or its 3 minute limit). ---

    private val recordings = ConcurrentHashMap<String, String>()

    fun isRecording(device: IDevice): Boolean = recordings.containsKey(device.serialNumber)

    fun startRecording(device: IDevice) {
        val remote = "/sdcard/bidaide-${stamp()}.mp4"
        recordings[device.serialNumber] = remote
        ApplicationManager.getApplication().executeOnPooledThread {
            // Returns when the recording is stopped; failures surface when the file is pulled.
            runCatching { AdbShell.run(device, "screenrecord --bit-rate 8000000 $remote", timeoutSeconds = 200) }
        }
    }

    fun stopRecording(device: IDevice): File {
        val remote = recordings.remove(device.serialNumber) ?: throw AdbException(BidaideBundle.message("adb.files.notRecording"))
        AdbShell.run(device, "pkill -2 screenrecord || kill -2 \$(pidof screenrecord)")
        // screenrecord needs a moment to finalise the MP4 after the signal.
        Thread.sleep(2_000)
        val file = File(captureDir(), "recording-${safe(device.name)}-${stamp()}.mp4")
        device.pullFile(remote, file.path)
        AdbShell.run(device, "rm -f $remote")
        return file
    }

    // --- App data. The app's private folder is only reachable through `run-as`, so it travels as a tar via the shell user. ---

    /** Copies databases, shared preferences and files of a debuggable app into a local folder. */
    fun pullAppData(device: IDevice, packageName: String): File {
        val tar = pullTar(device, packageName, "databases shared_prefs files", File(captureDir(), "$packageName-data-${stamp()}.tar"))
        val dir = File(tar.path.removeSuffix(".tar")).apply { mkdirs() }
        val extract = CapturingProcessHandler(GeneralCommandLine("tar", "-xf", tar.path, "-C", dir.path)).runProcess(60_000)
        // Without a local tar the archive itself is still a usable result.
        if (extract.exitCode != 0) return tar
        tar.delete()
        return dir
    }

    /** Copies only the databases (with their -wal/-shm companions) into a fresh temporary folder, for browsing. */
    fun pullDatabases(device: IDevice, packageName: String): File {
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("bidaide-db", packageName, true)
        val tar = pullTar(device, packageName, "databases", File(dir, "databases.tar"))
        val extract = CapturingProcessHandler(GeneralCommandLine("tar", "-xf", tar.path, "-C", dir.path)).runProcess(60_000)
        if (extract.exitCode != 0) throw AdbException(BidaideBundle.message("adb.files.cannotExtract", extract.stderr.trim()))
        tar.delete()
        return dir
    }

    /** Snapshot of the whole private data folder (minus caches), to be restored later with [restore]. */
    fun backup(device: IDevice, packageName: String): File =
        pullTar(device, packageName, "--exclude=./cache --exclude=./code_cache --exclude=./lib .", File(backupDir(packageName), "${stamp()}.tar"))

    private fun pullTar(device: IDevice, packageName: String, what: String, target: File): File {
        val remote = "$REMOTE_DIR/bidaide-pull.tar"
        // Missing folders (an app without databases, say) are not an error, hence the 2>/dev/null inside.
        val out = AdbShell.run(device, "run-as $packageName sh -c 'tar -cf - $what 2>/dev/null' > $remote", TAR_TIMEOUT_SECONDS)
        AdbShell.checkRunAs(out, packageName)
        try {
            device.pullFile(remote, target.path)
        } finally {
            AdbShell.run(device, "rm -f $remote")
        }
        if (target.length() == 0L) throw AdbException(BidaideBundle.message("adb.files.cannotRead", packageName))
        return target
    }

    /** Replaces the app's data with a [backup] archive and restarts the app. */
    fun restore(device: IDevice, packageName: String, archive: File) {
        val remote = "$REMOTE_DIR/bidaide-restore.tar"
        AdbShell.clearData(device, packageName)
        device.pushFile(archive.path, remote)
        try {
            val out = AdbShell.run(device, "cat $remote | run-as $packageName tar -xf -", TAR_TIMEOUT_SECONDS)
            AdbShell.checkRunAs(out, packageName)
        } finally {
            AdbShell.run(device, "rm -f $remote")
        }
        AdbShell.start(device, packageName)
    }

    // --- Things only the adb binary can do (pairing, reverse port forwarding). ---

    fun adbExecutable(project: Project): File =
        AndroidSdkUtils.findAdb(project).adbPath ?: throw AdbException(BidaideBundle.message("adb.files.noAdb"))

    fun adb(project: Project, vararg args: String): String {
        val adb = adbExecutable(project)
        val output = CapturingProcessHandler(GeneralCommandLine(listOf(adb.path) + args)).runProcess(30_000)
        val text = (output.stdout + output.stderr).trim()
        if (output.isTimeout) throw AdbException(BidaideBundle.message("adb.files.timeout", args.firstOrNull().orEmpty()))
        // adb reports several failures with exit code 0, so the text is checked too.
        if (output.exitCode != 0 || text.startsWith("error", ignoreCase = true) || text.contains("failed", ignoreCase = true)) {
            throw AdbException(text.ifEmpty { BidaideBundle.message("adb.files.exitCode", output.exitCode) })
        }
        return text
    }
}
