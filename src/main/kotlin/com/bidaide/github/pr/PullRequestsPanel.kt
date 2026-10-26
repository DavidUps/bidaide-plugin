package com.bidaide.github.pr

import com.bidaide.AndroidBridge
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommands.MergeMethod
import com.bidaide.gh.GhCommands.ReviewKind
import com.bidaide.gh.GhCommands.StateFilter
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhMergeRules
import com.bidaide.gh.GhPeople
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhTimeline
import com.bidaide.gh.display
import com.bidaide.github.review.PrReviewDiff
import com.bidaide.github.review.PrReviewWorktree
import com.bidaide.github.runs.CiArtifacts
import com.bidaide.github.ui.GhListDetailPanel
import com.bidaide.github.ui.Html
import com.bidaide.github.ui.RichRow
import com.bidaide.github.ui.MetadataEditor
import com.bidaide.github.ui.TextPromptDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent

class PullRequestsPanel(project: Project) : GhListDetailPanel<GhPullRequest>(
    project, "pull request",
    createText = BidaideBundle.message("github.pr.new"),
    filters = StateFilter.entries.map { FilterOption(it.label, it.name) },
    scopes = GhCommands.PR_SCOPES.map { FilterOption(it.first, it.second) },
) {
    override fun listArgs(filter: String, scope: String, search: String) =
        GhCommands.prList(StateFilter.valueOf(filter), GhCommands.combineSearch(scope, search))

    override fun parseList(json: String) = GhJson.pullRequests(json)
    override fun detailArgs(item: GhPullRequest) = GhCommands.prView(item.number)
    override fun parseDetail(json: String) = GhJson.pullRequest(json)
    override fun id(item: GhPullRequest) = item.number.toLong()

    override fun fetchDetail(workDir: File, item: GhPullRequest): GhPullRequest {
        val pr = super.fetchDetail(workDir, item)
        // The feed is a nicety: without it reviews, comments and commits are listed separately.
        return pr.copy(
            timeline = runCatching { GhTimeline.parse(GhCli.run(workDir, GhTimeline.args(pr.number))) }.getOrNull(),
            mergeStatus = if (pr.isOpen) runCatching { GhMergeRules.load(workDir, pr.number) }.getOrNull() else null,
        )
    }

    override fun renderCell(renderer: ColoredListCellRenderer<GhPullRequest>, item: GhPullRequest) {
        renderer.icon = when {
            item.state.equals("MERGED", true) -> AllIcons.Vcs.Merge
            !item.isOpen -> AllIcons.Actions.Cancel
            item.isDraft -> AllIcons.Actions.Edit
            else -> AllIcons.Vcs.Branch
        }
        renderer.append("#${item.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        renderer.append(item.title)
        renderer.append("  ${item.author.display()} · ${item.headRefName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    override fun detailHtml(item: GhPullRequest): String = PullRequestHtml.render(item)

    override fun richRow(): (GhPullRequest) -> RichRow = { PullRequestRows.row(it) }

    override val visibleButtons get() = 4

    /** Title, state, and author and reviewers with their avatars. The avatars arrive on their own, after the rest. */
    override fun detailHeader(item: GhPullRequest): JComponent {
        val people = GhPeople.of(item)
        val workDir = GhRepoContext.workDir(project)
        val strip = if (people.isEmpty) null else {
            PullRequestPeoplePanel(people, resolve = { who, onResolved -> GitHubUsers.resolveAsync(workDir, item.number, who, onResolved) })
        }
        return PullRequestHeader(item, strip)
    }

    /** The first four stay visible (the first one highlighted); the rest live under "More". */
    override fun detailButtons(item: GhPullRequest): List<JButton> = buildList {
        val n = item.number
        add(button(BidaideBundle.message("github.pr.button.review")) { PrReviewDiff.open(project, item) })
        add(button(BidaideBundle.message("github.pr.button.checkout")) { PullRequestActions.checkout(project, item) })
        if (item.isOpen) {
            add(button(BidaideBundle.message("github.pr.button.approve")) { review(item, ReviewKind.APPROVE) })
            add(button(BidaideBundle.message("github.pr.button.merge")) { merge(item) })
            add(button(BidaideBundle.message("github.pr.button.requestChanges")) { review(item, ReviewKind.REQUEST_CHANGES) })
            add(button(BidaideBundle.message("github.pr.button.comment")) { comment(item) })
        }
        add(button(BidaideBundle.message("github.pr.button.reviewInWorktree")) { PrReviewWorktree.open(project, item) }.apply {
            toolTipText = BidaideBundle.message("github.review.worktree.tooltip")
        })
        // Building, installing and starting the app need Android Studio (see AndroidBridge).
        if (AndroidBridge.isAvailable) {
            add(button(BidaideBundle.message("github.pr.button.testOnDevice")) { PullRequestActions.testOnDevice(project, item) })
            add(button(BidaideBundle.message("github.pr.button.ciApk")) { CiArtifacts.forBranch(project, item.headRefName) })
        }
        if (item.isOpen) {
            if (item.isDraft) {
                add(button(BidaideBundle.message("github.pr.button.ready")) { runAndReload(BidaideBundle.message("github.pr.ready.task", n), GhCommands.prReady(n), BidaideBundle.message("github.pr.ready.done", n)) })
            }
            add(button(BidaideBundle.message("github.pr.button.updateBranch")) { updateBranch(item) })
            if (item.autoMergeRequest != null) {
                add(button(BidaideBundle.message("github.pr.button.disableAutoMerge")) {
                    runAndReload(BidaideBundle.message("github.pr.autoMergeOff.task", n), GhCommands.prDisableAutoMerge(n), BidaideBundle.message("github.pr.autoMergeOff.done", n))
                })
            }
            add(button(BidaideBundle.message("github.pr.button.edit")) { editMetadata(item) })
            add(button(BidaideBundle.message("github.pr.button.close")) { runAndReload(BidaideBundle.message("github.pr.close.task", n), GhCommands.prSetOpen(n, false), BidaideBundle.message("github.pr.close.done", n)) })
        } else if (item.state.equals("CLOSED", true)) {
            add(button(BidaideBundle.message("github.pr.button.reopen")) { runAndReload(BidaideBundle.message("github.pr.reopen.task", n), GhCommands.prSetOpen(n, true), BidaideBundle.message("github.pr.reopen.done", n)) })
        }
        add(button(BidaideBundle.message("github.pr.button.browse")) { BrowserUtil.browse(item.url) })
    }

    override fun create() {
        val repository = GhRepoContext.repository(project)
        val branch = repository?.currentBranchName
        if (repository == null || branch == null) {
            Notifier.warn(project, "GitHub", BidaideBundle.message("github.pr.create.noBranch"))
            return
        }
        GhCli.async(
            project, BidaideBundle.message("github.pr.create.preparing"),
            work = {
                val base = GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.defaultBranch()).trim()
                base to PullRequestGit.draft(project, repository, branch, base)
            },
        ) { (defaultBranch, draft) ->
            val dialog = CreatePullRequestDialog(project, branch, defaultBranch, draft)
            if (!dialog.showAndGet()) return@async
            GhCli.async(
                project, BidaideBundle.message("github.pr.create.creating"),
                work = {
                    if (dialog.pushFirst) PullRequestGit.pushCurrentBranch(project, repository, branch)
                    val args = GhCommands.prCreate(
                        dialog.itemTitle, dialog.body, dialog.base, dialog.draft, dialog.reviewers, dialog.assignees, dialog.labels,
                    )
                    GhCli.run(GhRepoContext.requireWorkDir(project), args).trim()
                },
            ) { output ->
                val url = output.lines().lastOrNull().orEmpty()
                Notifier.info(project, BidaideBundle.message("github.pr.create.done"), Html.link(url, url))
                reload()
            }
        }
    }

    private fun review(pr: GhPullRequest, kind: ReviewKind) {
        // GitHub rejects "request changes" and "comment" reviews without a body.
        val dialog = TextPromptDialog(project, "${kind.label} — PR #${pr.number}", kind.label, required = kind != ReviewKind.APPROVE)
        if (!dialog.showAndGet()) return
        runAndReload(BidaideBundle.message("github.pr.review.task", pr.number), GhCommands.prReview(pr.number, kind, dialog.text), BidaideBundle.message("github.pr.review.done", pr.number))
    }

    private fun comment(pr: GhPullRequest) {
        val dialog = TextPromptDialog(project, BidaideBundle.message("github.pr.comment.title", pr.number), BidaideBundle.message("github.pr.button.comment"), required = true)
        if (!dialog.showAndGet()) return
        runAndReload(BidaideBundle.message("github.pr.comment.task", pr.number), GhCommands.prComment(pr.number, dialog.text), BidaideBundle.message("github.pr.comment.done", pr.number))
    }

    private fun merge(pr: GhPullRequest) {
        val dialog = MergeDialog(project, pr)
        if (!dialog.showAndGet()) return
        runAndReload(
            if (dialog.auto) BidaideBundle.message("github.pr.merge.autoTask", pr.number) else BidaideBundle.message("github.pr.merge.task", pr.number),
            GhCommands.prMerge(pr.number, dialog.method, dialog.deleteBranch, dialog.auto),
            if (dialog.auto) BidaideBundle.message("github.pr.merge.autoDone", pr.number) else BidaideBundle.message("github.pr.merge.done", pr.number),
        )
    }

    private fun updateBranch(pr: GhPullRequest) {
        val options = arrayOf("Merge", "Rebase", BidaideBundle.message("common.cancel"))
        val choice = Messages.showDialog(
            project, BidaideBundle.message("github.pr.updateBranch.question", pr.headRefName, pr.baseRefName),
            BidaideBundle.message("github.pr.updateBranch.title", pr.number), options, 0, Messages.getQuestionIcon(),
        )
        if (choice != 0 && choice != 1) return
        runAndReload(
            BidaideBundle.message("github.pr.updateBranch.task", pr.number), GhCommands.prUpdateBranch(pr.number, rebase = choice == 1),
            BidaideBundle.message("github.pr.updateBranch.done", pr.number),
        )
    }

    private fun editMetadata(pr: GhPullRequest) {
        MetadataEditor.edit(project, "pr", pr.number, pr.labels, pr.assignees, pr.milestone?.title) { command ->
            runAndReload(BidaideBundle.message("github.pr.edit.task", pr.number), command, BidaideBundle.message("github.pr.edit.done", pr.number))
        }
    }
}

private class MergeDialog(project: Project, private val pr: GhPullRequest) : DialogWrapper(project) {
    private val methodCombo = ComboBox(MergeMethod.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val deleteBranchBox = JBCheckBox(BidaideBundle.message("github.pr.merge.deleteBranch"))
    private val autoBox = JBCheckBox(BidaideBundle.message("github.pr.merge.auto"))

    val method: MergeMethod get() = methodCombo.item
    val deleteBranch: Boolean get() = deleteBranchBox.isSelected
    val auto: Boolean get() = autoBox.isSelected

    init {
        title = "Merge PR #${pr.number}"
        setOKButtonText("Merge")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("${pr.headRefName} → ${pr.baseRefName}") }
        row(BidaideBundle.message("github.pr.merge.method")) { cell(methodCombo) }
        row { cell(deleteBranchBox) }
        row { cell(autoBox) }
    }
}
