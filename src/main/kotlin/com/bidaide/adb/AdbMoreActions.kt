package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.github.issues.IssuesPanel
import com.bidaide.settings.BidaideSettings
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

private fun VirtualFile.isApk() = !isDirectory && extension.equals("apk", ignoreCase = true)

/** Installs an APK picked from disk. */
class AdbInstallApkAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("apk").withTitle(BidaideBundle.message("adb.installApk.title"))
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        ApkInstaller.install(project, VfsUtilCore.virtualToIoFile(file))
    }
}

/** Installs the APK selected in the project view; only shown on APK files. */
class AdbInstallSelectedApkAction : AdbBaseAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE)?.isApk() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isApk() } ?: return
        ApkInstaller.install(project, VfsUtilCore.virtualToIoFile(file))
    }
}

/** Fake GPS position, through the emulator console. */
class AdbLocationAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val history = History(project, "Bidaide.Adb.Locations")
        val input = Messages.showInputDialog(
            project, BidaideBundle.message("adb.location.prompt"), BidaideBundle.message("adb.location.title"), null,
            history.values().firstOrNull().orEmpty(), null,
        ) ?: return
        val position = parsePosition(input)
        if (position == null) {
            Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.location.invalid", input))
            return
        }
        history.remember(input.trim())
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            AdbTasks.run(project, BidaideBundle.message("adb.location.task"), devices, null) { ctx ->
                // The console wants longitude first.
                emulatorConsole(ctx, "geo", "fix", position.second.toString(), position.first.toString())
                BidaideBundle.message("adb.location.done", position.first, position.second)
            }
        }
    }

    companion object {
        /** "lat, lon" → pair, or null when it is not a position on Earth. */
        fun parsePosition(text: String): Pair<Double, Double>? {
            val parts = text.split(',', ';').map { it.trim().toDoubleOrNull() }
            val lat = parts.getOrNull(0) ?: return null
            val lon = parts.getOrNull(1) ?: return null
            return (lat to lon).takeIf { parts.size == 2 && lat in -90.0..90.0 && lon in -180.0..180.0 }
        }
    }
}

/** Types text into the focused field of the device: long emails, test credentials, URLs. */
class AdbSendTextAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val history = History(project, "Bidaide.Adb.Texts")
        val combo = ComboBox(history.values().toTypedArray()).apply { isEditable = true }
        val enterBox = JBCheckBox(BidaideBundle.message("adb.sendText.enter"))
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("adb.sendText.title")
                setOKButtonText(BidaideBundle.message("adb.sendText.ok"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row(BidaideBundle.message("adb.sendText.text")) { cell(combo).align(AlignX.FILL).comment(BidaideBundle.message(if (AndroidCli.isAvailable) "adb.sendText.commentCli" else "adb.sendText.comment")) }
                row { cell(enterBox) }
            }

            override fun getPreferredFocusedComponent() = combo

            override fun doValidate(): ValidationInfo? {
                val text = combo.editor.item?.toString().orEmpty()
                return when {
                    text.isEmpty() -> ValidationInfo(BidaideBundle.message("adb.sendText.empty"), combo)
                    !AndroidCli.isAvailable && text.any { it.code > 126 || it.code < 32 } -> ValidationInfo(BidaideBundle.message("adb.sendText.notAscii"), combo)
                    else -> null
                }
            }
        }
        if (!dialog.showAndGet()) return
        val text = combo.editor.item.toString()
        history.remember(text)
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            AdbTasks.run(project, BidaideBundle.message("adb.sendText.task"), devices, null) { ctx ->
                AdbShell.inputText(ctx.device, text, enterBox.isSelected)
                BidaideBundle.message("adb.sendText.done", text.length)
            }
        }
    }
}

/** What goes into a bug report issue. */
object BugReports {
    class Info(val device: String, val android: String, val sdk: String, val packageName: String, val appVersion: String?)

    /** GitHub rejects issue bodies over 65536 characters; the tail of the log is the part that matters. */
    private const val MAX_LOG_CHARS = 50_000

    fun collect(device: IDevice, packageName: String): Info = Info(
        device = listOf(AdbShell.property(device, "ro.product.manufacturer"), AdbShell.property(device, "ro.product.model"))
            .filter { it.isNotBlank() }.joinToString(" ").ifEmpty { device.name } + if (device.isEmulator) BidaideBundle.message("adb.bugReport.emulator") else "",
        android = AdbShell.property(device, "ro.build.version.release"),
        sdk = AdbShell.property(device, "ro.build.version.sdk"),
        packageName = packageName,
        appVersion = AdbShell.versionName(device, packageName),
    )

    fun body(info: Info, logcat: String): String = buildString {
        append("## ${BidaideBundle.message("adb.bugReport.what")}\n\n\n## ${BidaideBundle.message("adb.bugReport.steps")}\n\n1. \n\n")
        append("## ${BidaideBundle.message("adb.bugReport.environment")}\n\n")
        append("- App: `${info.packageName}` ${info.appVersion ?: BidaideBundle.message("adb.bugReport.unknownVersion")}\n")
        append("- ${BidaideBundle.message("adb.bugReport.device")}: ${info.device}\n")
        append("- Android: ${info.android} (API ${info.sdk})\n")
        val log = logcat.trim().takeLast(MAX_LOG_CHARS)
        if (log.isNotEmpty()) {
            append("\n<details><summary>Logcat</summary>\n\n```\n").append(log.replace("```", "'''")).append("\n```\n\n</details>\n")
        }
    }
}

/** Screenshot + log + device details → a prefilled "new issue" form. */
class AdbBugReportAction : AdbBaseAction() {
    private class Collected(val body: String, val screenshot: File?)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, pkg ->
            val packageName = pkg ?: return@resolve
            val device = devices.first()
            AdbTasks.fetch(
                project, BidaideBundle.message("adb.bugReport.title"),
                work = {
                    // Some screens (secure flag) cannot be captured; the report is still worth filing.
                    val screenshot = runCatching { AdbFiles.screenshot(device) }.getOrNull()
                    Collected(BugReports.body(BugReports.collect(device, packageName), AdbShell.logcatDump(device, packageName, LOG_LINES)), screenshot)
                },
            ) { collected ->
                collected.screenshot?.let { file ->
                    // The GitHub API cannot attach images to an issue; pasting on github.com is the only way.
                    NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
                        .createNotification(
                            BidaideBundle.message("adb.bugReport.title"), BidaideBundle.message("adb.bugReport.screenshot", file.name), NotificationType.INFORMATION,
                        )
                        .addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
                        .notify(project)
                }
                IssuesPanel.create(project, "", collected.body)
            }
        }
    }

    private companion object {
        const val LOG_LINES = 400
    }
}

/** The device profiles the user defined in Settings, listed inline in the ADB popup. */
class AdbProfilesGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        DeviceProfile.parse(BidaideSettings.getInstance().deviceProfiles).map { profile ->
            object : AdbBaseAction() {
                init {
                    templatePresentation.text = BidaideBundle.message("adb.profile.action", profile.name)
                    templatePresentation.description = profile.settings.entries.joinToString(", ") { "${it.key}=${it.value}" }
                }

                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    profile.problem()?.let {
                        Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.profile.problem", profile.name, it))
                        return
                    }
                    AdbTargetResolver.resolve(project, profile.needsPackage) { devices, packageName ->
                        AdbTasks.run(project, BidaideBundle.message("adb.profile.task", profile.name), devices, packageName) { ctx -> profile.apply(ctx.device, packageName) }
                    }
                }
            }
        }.toTypedArray()
}
