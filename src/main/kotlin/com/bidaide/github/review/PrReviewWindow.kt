package com.bidaide.github.review

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommit
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhCommands.ReviewKind
import com.bidaide.gh.GhPrFile
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.GhReviewThread
import com.bidaide.gh.ReviewEvent
import com.bidaide.github.pr.PrWorktrees
import com.bidaide.github.ui.GhLoadingPanel
import com.bidaide.github.ui.Html
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import javax.swing.BoxLayout
import javax.swing.SwingConstants
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FrameWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Stand-alone review window: changed files and conversations on the left, the IDE diff viewer on the right,
 * review actions on top.
 */
class PrReviewWindow(private val session: PrReviewSession) {
    private val project = session.project

    private class FileNode(val index: Int, val file: GhPrFile)

    /** What the diff shows: the whole pull request, or one of its commits ([commit] non-null, read-only). */
    private class Scope(val commit: GhCommit?, val files: List<GhPrFile>, val baseSha: String?, val headSha: String) {
        val interactive: Boolean get() = commit == null
    }

    private val wholePr = Scope(null, session.files, session.mergeBaseSha, session.headSha)
    private var scope = wholePr
    private var processor = newProcessor()
    private var currentIndex = 0

    /** Bumped on every scope change so file-shown callbacks of a replaced diff are ignored. */
    private var scopeGeneration = 0

    /** Tree node of each file shown, by its index in [scope]; files hidden by the filter have none. */
    private val fileNodes = LinkedHashMap<Int, DefaultMutableTreeNode>()
    private val fileFilter = SearchTextField(false)
    private val tree = Tree(buildTreeModel())
    private val main = OnePixelSplitter(false, "Bidaide.PrReview.main", 0.24f)
    /** Combo entry standing for "the whole pull request". */
    private val allChanges = GhCommit()
    private val scopeCombo = ComboBox((listOf(allChanges) + session.pr.commits.orEmpty()).toTypedArray())
    private val threadsModel = CollectionListModel<GhReviewThread>()
    private val threadsList = JBList(threadsModel)

    private val submitButton = JButton()
    private val summary = JBLabel()

    private val frame = FrameWrapper(project, "Bidaide.PrReview", false, "PR #${session.number} — ${session.pr.title}")

    /** The diff side, under a spinner while the files of another commit are fetched. */
    private val diffHolder = JPanel(BorderLayout())
    private val diffLoading = GhLoadingPanel(diffHolder, frame)

    /** The side panel (files and conversations) and the stripe that brings it back once hidden, as with a tool window. */
    private val sidePanel = JPanel(BorderLayout())
    private val stripe = JPanel(BorderLayout())

    fun show() {
        configureTree()
        configureThreads()
        configureFilter()

        val left = OnePixelSplitter(true, "Bidaide.PrReview.left", 0.6f).apply {
            firstComponent = titled(BidaideBundle.message("github.review.files"), JPanel(BorderLayout()).apply {
                add(fileFilter.apply { border = JBUI.Borders.empty(0, 6, 4, 6) }, BorderLayout.NORTH)
                add(JBScrollPane(tree), BorderLayout.CENTER)
            })
            secondComponent = titled(BidaideBundle.message("gh.merge.conversations"), JBScrollPane(threadsList))
        }
        sidePanel.add(header(), BorderLayout.NORTH)
        sidePanel.add(left, BorderLayout.CENTER)
        main.firstComponent = sidePanel
        diffHolder.add(processor.component, BorderLayout.CENTER)
        main.secondComponent = diffLoading.component
        configureStripe()
        val root = JPanel(BorderLayout()).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(stripe, BorderLayout.WEST)
            add(main, BorderLayout.CENTER)
        }
        frame.component = root
        // The window is no editor tab, so the IDE's "Close" (⌘W, Ctrl+F4…) has to be wired to it by hand.
        DumbAwareAction.create { frame.close() }
            .registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_CLOSE), root, frame)
        // The Project tool window's own shortcut (⌘1, Alt+1) shows and hides the files, where hands expect it.
        DumbAwareAction.create { setSideVisible(!sidePanel.isVisible) }
            .registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts("ActivateProjectToolWindow"), root, frame)
        setSideVisible(!PropertiesComponent.getInstance().getBoolean(SIDE_HIDDEN_KEY))
        frame.preferredFocusedComponent = tree
        Disposer.register(frame, processor)
        session.addListener(frame) { refresh() }
        refresh()
        processor.updateRequest()
        fileNodes.values.firstOrNull()?.let { tree.selectionPath = TreePath(it.path) }
        frame.show()
    }

    private fun titled(title: String, content: JComponent) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    // --- Hiding the side panel ---

    private fun configureStripe() {
        val show = InplaceButton(BidaideBundle.message("github.review.files.show"), AllIcons.Toolwindows.ToolWindowChanges) { setSideVisible(true) }
        stripe.border = JBUI.Borders.compound(JBUI.Borders.customLineRight(JBColor.border()), JBUI.Borders.empty(6, 4))
        stripe.add(show, BorderLayout.NORTH)
    }

    /** Hidden, the diff takes the whole window; the choice is remembered for the next review. */
    private fun setSideVisible(visible: Boolean) {
        sidePanel.isVisible = visible
        stripe.isVisible = !visible
        PropertiesComponent.getInstance().setValue(SIDE_HIDDEN_KEY, !visible)
        main.revalidate()
        main.repaint()
        if (visible) tree.requestFocusInWindow()
    }

    /** Title and branches of the pull request above the file tree, as in the IDE's own pull request view. */
    private fun header(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 10, 6, 10)
        val title = JBLabel("<html><b>${Html.esc(session.pr.title)}</b> <font color=\"gray\">#${session.number}</font></html>").apply {
            font = JBUI.Fonts.label().biggerOn(2f)
        }
        // The "—" of a tool window header.
        val hide = InplaceButton(BidaideBundle.message("github.review.files.hide"), AllIcons.General.HideToolWindow) { setSideVisible(false) }
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(title, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(hide, BorderLayout.NORTH) }, BorderLayout.EAST)
        }.leftAligned())
        add(JBLabel("${session.pr.headRefName} → ${session.pr.baseRefName}", AllIcons.Vcs.Branch, SwingConstants.LEFT).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyTop(4)
        }.leftAligned())
    }

    private fun toolbar(): JComponent {
        submitButton.addActionListener { submitReview() }
        val refresh = InplaceButton(BidaideBundle.message("github.list.refresh"), AllIcons.Actions.Refresh) { session.refresh() }
        val reload = InplaceButton(BidaideBundle.message("github.review.reload.tooltip"), AllIcons.Actions.ForceRefresh) {
            frame.close()
            PrReviewDiff.reopen(project, session.number)
        }
        // Not needed when this review already runs in the PR's worktree.
        val inWorktree = PrWorktrees.isInside(PrWorktrees.root(File(PathManager.getSystemPath())), session.workDir)
        val navigate = JButton(BidaideBundle.message("github.pr.button.reviewInWorktree"), AllIcons.Actions.MoveToWindow).apply {
            toolTipText = BidaideBundle.message("github.review.worktree.tooltip")
            isVisible = !inWorktree
            addActionListener { PrReviewWorktree.open(project, session.pr) }
        }
        scopeCombo.renderer = SimpleListCellRenderer.create("") { commit ->
            if (commit === allChanges) BidaideBundle.message("github.review.allChanges") else "${commit.oid.take(7)}  ${commit.messageHeadline.take(70)}"
        }
        scopeCombo.toolTipText = BidaideBundle.message("github.review.scope.tooltip")
        scopeCombo.isVisible = scopeCombo.itemCount > 2
        scopeCombo.addActionListener { chooseScope(scopeCombo.item?.takeIf { it !== allChanges }) }
        summary.foreground = UIUtil.getContextHelpForeground()
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground()), JBUI.Borders.empty(2, 4))
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
                add(scopeCombo)
                add(summary)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
                add(refresh)
                add(reload)
                add(navigate)
                add(submitButton)
            }, BorderLayout.EAST)
        }
    }

    // --- Whole pull request or a single commit ---

    private fun newProcessor(): CacheDiffRequestChainProcessor {
        val shown = scope
        val generation = scopeGeneration
        val producers = shown.files.mapIndexed { index, file ->
            PrFileDiffProducer(PrFileReview(session, file), shown.baseSha, shown.headSha, shown.interactive) {
                if (generation == scopeGeneration) fileShown(index)
            }
        }
        return CacheDiffRequestChainProcessor(project, SimpleDiffRequestChain.fromProducers(producers))
    }

    private fun chooseScope(commit: GhCommit?) {
        if (commit?.oid == scope.commit?.oid) return
        if (commit == null) {
            show(wholePr)
            return
        }
        diffLoading.async(
            project, BidaideBundle.message("github.review.loadingCommit", commit.oid.take(7)),
            onFailure = { scopeCombo.selectedItem = scope.commit ?: allChanges },
            work = { GhJson.commitDetail(GhCli.run(session.workDir, GhCommands.commit(commit.oid))) },
        ) { detail ->
            // The user may have picked something else while this one loaded.
            if (scopeCombo.item === commit) show(Scope(commit, detail.files, detail.parent, commit.oid))
        }
    }

    private fun show(next: Scope) {
        scope = next
        scopeGeneration++
        val previous = processor
        processor = newProcessor()
        Disposer.register(frame, processor)
        diffHolder.removeAll()
        diffHolder.add(processor.component, BorderLayout.CENTER)
        diffHolder.revalidate()
        diffHolder.repaint()
        Disposer.dispose(previous)
        currentIndex = 0
        rebuildTree()
        processor.updateRequest()
        fileNodes.values.firstOrNull()?.let { tree.selectionPath = TreePath(it.path) }
        refresh()
    }

    /** The diff viewer moved to another file by itself (next/previous change across files): follow it in the tree. */
    private fun fileShown(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        fileNodes[index]?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun refresh() {
        val pending = session.pendingCount
        submitButton.text = if (session.hasPendingReview) BidaideBundle.message("github.review.submitPending", pending) else BidaideBundle.message("github.review.submit")
        // Blue while there is something waiting to be sent, like the IDE's own “Submit (1)…”.
        submitButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, session.hasPendingReview)
        submitButton.repaint()
        val open = session.threads.count { !it.isResolved }
        val reviewed = BidaideBundle.message("github.review.summary", session.viewedCount, session.files.size, open, session.threads.size)
        summary.text = scope.commit?.let { BidaideBundle.message("github.review.commitSummary", it.oid.take(7), scope.files.size, reviewed) } ?: reviewed
        threadsModel.replaceAll(session.threads.sortedWith(compareBy({ it.isResolved }, { it.path }, { it.line ?: it.originalLine ?: 0 })))
        tree.repaint()
    }

    // --- Files ---

    private fun buildTreeModel(): DefaultTreeModel {
        val root = DefaultMutableTreeNode("root")
        val directories = LinkedHashMap<String, DefaultMutableTreeNode>()
        fileNodes.clear()
        val query = fileFilter.text
        scope.files.forEachIndexed { index, file ->
            if (!FileFilter.matches(file.filename, query)) return@forEachIndexed
            val directory = file.filename.substringBeforeLast('/', "")
            val parent = if (directory.isEmpty()) root else directories.getOrPut(directory) {
                DefaultMutableTreeNode(directory).also(root::add)
            }
            DefaultMutableTreeNode(FileNode(index, file)).also {
                parent.add(it)
                fileNodes[index] = it
            }
        }
        return DefaultTreeModel(root)
    }

    /** Re-applies the filter, keeping the file on screen selected when it is still listed. */
    private fun rebuildTree() {
        tree.model = buildTreeModel()
        TreeUtil.expandAll(tree)
        fileNodes[currentIndex]?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun configureFilter() {
        fileFilter.textEditor.emptyText.text = BidaideBundle.message("github.review.files.filter")
        fileFilter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        tree.emptyText.text = BidaideBundle.message("github.review.files.noMatch")
        // ⌘F (the IDE's Find) over the files jumps to the filter; Enter or ↓ from there goes on to the tree.
        DumbAwareAction.create { fileFilter.requestFocusInWindow() }
            .registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_FIND), sidePanel, frame)
        DumbAwareAction.create {
            if (tree.selectionPath == null) fileNodes.values.firstOrNull()?.let { tree.selectionPath = TreePath(it.path) }
            tree.requestFocusInWindow()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyEvent.VK_ENTER, 0), fileFilter.textEditor)
        DumbAwareAction.create { tree.requestFocusInWindow() }
            .registerCustomShortcutSet(CustomShortcutSet(KeyEvent.VK_DOWN, 0), fileFilter.textEditor)
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is FileNode -> {
                        val file = item.file
                        val name = file.filename.substringAfterLast('/')
                        icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                        val status = when (file.status) {
                            "added" -> FileStatus.ADDED
                            "removed" -> FileStatus.DELETED
                            else -> FileStatus.MODIFIED
                        }
                        append(name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                        append("  +${file.additions} −${file.deletions}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        if (scope.interactive) {
                            // Partly reviewed: how many of its changes are ticked off.
                            session.hunkProgress(file.filename)?.let { append("  ◐ $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                            val count = session.threadCount(file.filename)
                            if (count > 0) append("  💬 $count", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            // Not viewed yet: the blue dot of the IDE's own pull request view.
                            val viewed = session.isViewed(file.filename)
                            if (!viewed) append("  ●", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBUI.CurrentTheme.Link.Foreground.ENABLED))
                            toolTipText = BidaideBundle.message(if (viewed) "github.review.file.viewed" else "github.review.file.notViewed")
                        }
                    }
                    is String -> {
                        icon = AllIcons.Nodes.Folder
                        append(item, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }
        tree.addTreeSelectionListener {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode ?: return@addTreeSelectionListener
            if (node.index != currentIndex) {
                currentIndex = node.index
                processor.setCurrentRequest(node.index)
            }
        }
        TreeUtil.expandAll(tree)
    }

    private fun selectFile(path: String): Boolean {
        // A conversation on a file the filter hides: drop the filter so it can be shown.
        if (fileNodes.values.none { (it.userObject as FileNode).file.filename == path } && fileFilter.text.isNotEmpty()) fileFilter.text = ""
        val node = fileNodes.values.firstOrNull { (it.userObject as FileNode).file.filename == path } ?: return false
        val treePath = TreePath(node.path)
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
        return true
    }

    // --- Conversations ---

    private fun configureThreads() {
        threadsList.emptyText.text = BidaideBundle.message("github.review.noThreads")
        threadsList.cellRenderer = object : ColoredListCellRenderer<GhReviewThread>() {
            override fun customizeCellRenderer(list: JList<out GhReviewThread>, value: GhReviewThread?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val thread = value ?: return
                icon = if (thread.isResolved) AllIcons.Actions.Checked else AllIcons.General.Balloon
                append("${thread.path.substringAfterLast('/')}:${thread.line ?: thread.originalLine ?: "?"}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val root = thread.root
                append(root?.let { "${it.author}: ${it.body.lineSequence().firstOrNull().orEmpty().take(80)}" }.orEmpty())
                val tags = listOfNotNull(
                    "pendiente".takeIf { thread.hasPending }, "obsoleto".takeIf { thread.isOutdated },
                    "${thread.comments.size} mensajes".takeIf { thread.comments.size > 1 },
                )
                if (tags.isNotEmpty()) append("  ${tags.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                threadsList.selectedValue?.let(::openThread)
                return true
            }
        }.installOn(threadsList)
    }

    private fun openThread(thread: GhReviewThread) {
        if (thread.line == null || thread.isOutdated) {
            // The code under the thread is gone from the diff, so it cannot be shown inline.
            ThreadPopup.show(session, thread.id, threadsList)
            return
        }
        // Threads are only drawn on the diff of the whole pull request.
        if (!scope.interactive) scopeCombo.selectedItem = allChanges
        session.scrollToThreadId = thread.id
        val alreadyShown = scope.files.getOrNull(currentIndex)?.filename == thread.path
        if (!selectFile(thread.path)) return
        if (alreadyShown) session.requestRender()
    }

    // --- Review ---

    private fun submitReview() {
        SubmitReviewPopup(session, ::discardReview) { event, body, done ->
            val args = if (session.hasPendingReview) {
                GhReviewCommands.submitPending(session.state.pullRequestId, event, body)
            } else {
                val kind = when (event) {
                    ReviewEvent.APPROVE -> ReviewKind.APPROVE
                    ReviewEvent.REQUEST_CHANGES -> ReviewKind.REQUEST_CHANGES
                    ReviewEvent.COMMENT -> ReviewKind.COMMENT
                }
                GhCommands.prReview(session.number, kind, body)
            }
            session.mutate(BidaideBundle.message("github.pr.review.task", session.number), args) { ok ->
                if (ok) Notifier.info(project, "GitHub", BidaideBundle.message("github.review.sent", session.number, event.label.lowercase()))
                done(ok)
            }
        }.show(submitButton)
    }

    private fun discardReview() {
        val reviewId = session.state.pendingReviewId ?: return
        val answer = Messages.showYesNoDialog(
            project, BidaideBundle.message("github.review.discard.confirm", session.pendingCount),
            BidaideBundle.message("github.review.discard"), BidaideBundle.message("github.review.discard.ok"),
            BidaideBundle.message("common.cancel"), Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) session.mutate(BidaideBundle.message("github.review.discarding"), GhReviewCommands.discardPending(reviewId))
    }

    /** Matches a path when it contains every word of [query], ignoring case: "login view" finds `ui/login/LoginView.kt`. */
    internal object FileFilter {
        fun matches(path: String, query: String): Boolean =
            query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { path.contains(it, ignoreCase = true) }
    }

    private companion object {
        const val SIDE_HIDDEN_KEY = "Bidaide.PrReview.sideHidden"
    }
}

/** The review form hanging from the submit button: a summary and one button per verdict, as in the IDE's own review. */
private class SubmitReviewPopup(
    private val session: PrReviewSession,
    private val onDiscard: () -> Unit,
    private val onSubmit: (event: ReviewEvent, body: String, done: (Boolean) -> Unit) -> Unit,
) {
    private val area = JBTextArea(5, 44).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = BidaideBundle.message("github.review.summaryPlaceholder")
        border = JBUI.Borders.empty(4, 6)
    }
    private val error = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        isVisible = false
    }
    private val buttons = ArrayList<JButton>()
    private var popup: JBPopup? = null

    fun show(anchor: JComponent) {
        // GitHub only lets the author of a pull request comment on it, not approve it or ask for changes.
        val own = session.viewerLogin != null && session.viewerLogin.equals(session.pr.author?.login, ignoreCase = true)
        val events = if (own) listOf(ReviewEvent.COMMENT) else listOf(ReviewEvent.APPROVE, ReviewEvent.REQUEST_CHANGES, ReviewEvent.COMMENT)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            for (event in events) add(JButton(event.label).also { button ->
                if (event == events.first()) button.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                button.addActionListener { send(event) }
                buttons += button
            })
        }
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(bar, BorderLayout.WEST)
            if (session.hasPendingReview) add(ActionLink(BidaideBundle.message("github.review.discard")) {
                popup?.cancel()
                onDiscard()
            }, BorderLayout.EAST)
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12)
            add(JBLabel("<html><b>${Html.esc(BidaideBundle.message("github.review.submitTitle", session.number))}</b></html>").leftAligned())
            if (session.hasPendingReview) add(JBLabel(BidaideBundle.message("github.review.willPublish", session.pendingCount)).apply {
                foreground = UIUtil.getContextHelpForeground()
            }.leftAligned())
            add(JBScrollPane(area).apply { border = JBUI.Borders.compound(JBUI.Borders.emptyTop(8), JBUI.Borders.customLine(JBColor.border(), 1)) }.leftAligned())
            add(error.apply { border = JBUI.Borders.emptyTop(4) }.leftAligned())
            add(footer.leftAligned())
        }
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, area)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup = created
        created.showUnderneathOf(anchor)
    }

    private fun send(event: ReviewEvent) {
        val body = area.text.trim()
        val problem = when {
            event == ReviewEvent.REQUEST_CHANGES && body.isEmpty() -> BidaideBundle.message("github.review.changesNeedComment")
            // A bare "comment" review only makes sense when it publishes pending inline comments.
            event == ReviewEvent.COMMENT && body.isEmpty() && session.pendingCount == 0 -> BidaideBundle.message("github.review.writeComment")
            else -> null
        }
        error.text = problem.orEmpty()
        error.isVisible = problem != null
        if (problem != null) {
            popup?.pack(true, true)
            return
        }
        setBusy(true)
        onSubmit(event, body) { ok -> if (ok) popup?.cancel() else setBusy(false) }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
        area.isEnabled = !busy
    }
}

/** Shows one thread in a floating popup; used for threads that no longer map to a line of the diff. */
object ThreadPopup {
    fun show(session: PrReviewSession, threadId: String, anchor: JComponent) {
        val holder = JPanel(BorderLayout())
        val ui = ThreadUiState().apply { expandedResolved += threadId }
        var popup: JBPopup? = null

        fun rebuild() {
            val thread = session.threads.firstOrNull { it.id == threadId }
            if (thread == null) {
                popup?.cancel()
                return
            }
            holder.removeAll()
            holder.add(ThreadPanels.build(session, thread, ui, JBUI.scale(560), ::rebuild), BorderLayout.CENTER)
            holder.revalidate()
            popup?.takeIf { it.isVisible }?.pack(true, true)
        }
        rebuild()
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(holder), holder)
            .setTitle(BidaideBundle.message("github.review.conversation"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnWindowDeactivation(false)
            .createPopup()
        popup = created
        session.addListener(created) { rebuild() }
        created.showInCenterOf(anchor)
    }
}
