package com.bidaide.adb

import com.bidaide.BidaideBundle
import com.bidaide.settings.BidaideSettings
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.text.Normalizer
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** An ADB operation that can be pinned: its action id and the text shown in the popup. */
data class FavoriteCandidate(val id: String, val text: String)

/** Pure favourite logic: stored as action ids, one per line, kept in the order of the ADB group. */
object AdbFavorites {
    const val OPERATIONS_GROUP_ID = "Bidaide.Adb.Operations"
    const val GROUP_ID = "Bidaide.Adb.Favorites"
    const val MANAGE_ID = "Bidaide.Adb.ManageFavorites"

    fun parse(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun serialize(ids: List<String>): String = ids.joinToString("\n")

    /** Drops ids of actions that no longer exist (renamed or removed in a later version). */
    fun existing(ids: List<String>, exists: (String) -> Boolean): List<String> = ids.filter(exists)

    /** The [selected] ids in the order they appear in [candidates]; unknown ids are dropped. */
    fun ordered(selected: Set<String>, candidates: List<FavoriteCandidate>): List<String> =
        candidates.map { it.id }.filter { it in selected }.distinct()

    /** Whether [candidate] matches the filter [query]: every word, ignoring case, accents and the "Android CLI:" prefix. */
    fun matches(candidate: FavoriteCandidate, query: String): Boolean {
        val text = fold(candidate.text.removePrefix(AndroidCli.TITLE + ":"))
        return fold(query).split(' ').filter { it.isNotEmpty() }.all { it in text }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

/** Reads the favourites and the pinnable operations from the IDE; not unit tested because it needs the ActionManager. */
internal object AdbFavoritesStore {
    fun ids(): List<String> {
        val manager = ActionManager.getInstance()
        return AdbFavorites.existing(AdbFavorites.parse(BidaideSettings.getInstance().favoriteAdbActions)) { manager.getAction(it) != null }
    }

    fun save(ids: List<String>) {
        BidaideSettings.getInstance().favoriteAdbActions = AdbFavorites.serialize(ids)
    }

    /** Every plain action of the ADB group, in popup order; groups, separators and the favourite entries themselves are skipped. */
    fun candidates(): List<FavoriteCandidate> {
        val manager = ActionManager.getInstance()
        val group = manager.getAction(AdbFavorites.OPERATIONS_GROUP_ID) as? DefaultActionGroup ?: return emptyList()
        return group.childActionsOrStubs.mapNotNull { child ->
            val id = manager.getId(child) ?: return@mapNotNull null
            if (id == AdbFavorites.GROUP_ID || id == AdbFavorites.MANAGE_ID) return@mapNotNull null
            val action = manager.getAction(id) ?: return@mapNotNull null
            if (action is ActionGroup || action is Separator) return@mapNotNull null
            FavoriteCandidate(id, action.templatePresentation.text ?: id)
        }
    }
}

/** The favourite operations, first in the ADB popup under a "Favorites" header; empty when there are none. */
class AdbFavoritesGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val actions = AdbFavoritesStore.ids().mapNotNull { manager.getAction(it) }.filter { it !is ActionGroup }
        if (actions.isEmpty()) return AnAction.EMPTY_ARRAY
        return (listOf(Separator(BidaideBundle.message("adb.favorites.separator"))) + actions + Separator.getInstance()).toTypedArray()
    }
}

/** Chooses which ADB operations are pinned at the top of the popup. */
class AdbManageFavoritesAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        AdbFavoritesDialog.edit(e.project)
    }
}

/** A filterable checkbox list of every ADB operation; saves straight to the settings on OK. */
internal class AdbFavoritesDialog private constructor(project: Project?, candidates: List<FavoriteCandidate>, selected: Set<String>) :
    DialogWrapper(project) {

    private val boxes = candidates.map { it to JBCheckBox(it.text.removePrefix(AndroidCli.TITLE + ": "), it.id in selected) }
    private val filter = SearchTextField(false)

    init {
        title = BidaideBundle.message("adb.favorites.title")
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                boxes.forEach { (candidate, box) -> box.isVisible = AdbFavorites.matches(candidate, filter.text) }
            }
        })
        init()
    }

    val selectedIds: List<String>
        get() = AdbFavorites.ordered(boxes.filter { it.second.isSelected }.map { it.first.id }.toSet(), boxes.map { it.first })

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            boxes.forEach { add(it.second) }
        }
        return panel {
            row { comment(BidaideBundle.message("adb.favorites.comment")) }
            row { cell(filter).align(AlignX.FILL) }
            row {
                cell(JBScrollPane(list).apply { preferredSize = JBUI.size(460, 420) }).align(Align.FILL)
            }.resizableRow()
        }
    }

    override fun getPreferredFocusedComponent() = filter.textEditor

    companion object {
        /** Shows the dialog and saves the choice; returns true when the user pressed OK. */
        fun edit(project: Project?): Boolean {
            val dialog = AdbFavoritesDialog(project, AdbFavoritesStore.candidates(), AdbFavoritesStore.ids().toSet())
            if (!dialog.showAndGet()) return false
            AdbFavoritesStore.save(dialog.selectedIds)
            return true
        }
    }
}
