package com.bidaide.settings

import com.bidaide.AndroidBridge
import com.bidaide.BidaideActionTexts
import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhExecutable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.JLabel

class BidaideConfigurable : BoundConfigurable(BidaideBundle.message("settings.displayName")) {

    override fun createPanel(): DialogPanel {
        val settings = BidaideSettings.getInstance()
        val status = JLabel(" ")
        val android = AndroidBridge.instance
        val favorites = JLabel(android?.let(::describeFavorites).orEmpty())
        return panel {
            group(BidaideBundle.message("settings.language.group")) {
                row(BidaideBundle.message("settings.language.label")) {
                    comboBox(BidaideLanguage.entries, textListCellRenderer { it?.displayName })
                        .bindItem(settings::language.toNullableProperty())
                        .comment(BidaideBundle.message("settings.language.comment"))
                }
            }
            group("GitHub CLI") {
                row(BidaideBundle.message("settings.gh.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(BidaideBundle.message("settings.gh.path.title")))
                        .bindText(settings::ghPath)
                        .align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.gh.path.comment"))
                }
                row {
                    button(BidaideBundle.message("settings.gh.check")) {
                        status.text = BidaideBundle.message("settings.gh.checking")
                        val modality = ModalityState.stateForComponent(status)
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val text = describeGhStatus()
                            ApplicationManager.getApplication().invokeLater({ status.text = text }, modality)
                        }
                    }
                    cell(status)
                }
            }
            group(BidaideBundle.message("settings.notifications.group")) {
                row { checkBox(BidaideBundle.message("settings.notifications.enabled")).bindSelected(settings::notificationsEnabled) }
                row(BidaideBundle.message("settings.notifications.poll")) { intTextField(1..120).bindIntText(settings::pollMinutes) }
            }
            group(BidaideBundle.message("settings.savedReplies.group")) {
                row {
                    textArea().bindText(settings::savedReplies).rows(4).align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.savedReplies.comment"))
                }
            }
            // Without ADB the folder only holds CI artifacts and release assets.
            if (android == null) group(BidaideBundle.message("settings.downloads.group")) {
                row(BidaideBundle.message("settings.downloads.dir")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(BidaideBundle.message("settings.downloads.dir.title")))
                        .bindText(settings::captureDir)
                        .align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.downloads.dir.comment"))
                }
            }
            // Android Studio only (see AndroidBridge).
            if (android != null) group("Android CLI") {
                row(BidaideBundle.message("settings.androidCli.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(BidaideBundle.message("settings.androidCli.path.title")))
                        .bindText(settings::androidCliPath)
                        .align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.androidCli.path.comment"))
                }
                row(BidaideBundle.message("settings.adb.favorites")) {
                    button(BidaideBundle.message("settings.adb.favorites.manage")) {
                        if (android.editAdbFavorites()) favorites.text = describeFavorites(android)
                    }.comment(BidaideBundle.message("settings.adb.favorites.comment"))
                    cell(favorites)
                }
                row(BidaideBundle.message("settings.adb.captureDir")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(BidaideBundle.message("settings.adb.captureDir.title")))
                        .bindText(settings::captureDir)
                        .align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.adb.captureDir.comment"))
                }
                row(BidaideBundle.message("settings.adb.customCommands")) {
                    textArea().bindText(settings::customAdbCommands).rows(6).align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.adb.customCommands.comment"))
                }
                row(BidaideBundle.message("settings.adb.profiles")) {
                    textArea().bindText(settings::deviceProfiles).rows(4).align(AlignX.FILL)
                        .comment(BidaideBundle.message("settings.adb.profiles.comment"))
                }
            }
        }
    }

    override fun apply() {
        val before = BidaideSettings.getInstance().language
        super.apply()
        if (BidaideSettings.getInstance().language != before) BidaideActionTexts.apply()
    }

    private fun describeFavorites(android: AndroidBridge): String = when (val count = android.adbFavoritesCount()) {
        0 -> BidaideBundle.message("settings.adb.favorites.none")
        1 -> BidaideBundle.message("settings.adb.favorites.one")
        else -> BidaideBundle.message("settings.adb.favorites.many", count)
    }

    private fun describeGhStatus(): String {
        val path = GhExecutable.find() ?: return BidaideBundle.message("settings.gh.notFound")
        return try {
            val user = GhCli.run(null, listOf("api", "user", "--jq", ".login")).trim()
            BidaideBundle.message("settings.gh.ok", path, user)
        } catch (e: Exception) {
            BidaideBundle.message("settings.gh.noSession", path)
        }
    }
}
