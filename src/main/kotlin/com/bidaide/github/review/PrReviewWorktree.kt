package com.bidaide.github.review

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhException
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.pr.PrWorktreeGit
import com.bidaide.github.pr.PrWorktrees
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * Review with code navigation without leaving the current branch: the pull request's head goes into its own worktree
 * (the one "Test on Device" builds in), which opens as a project of its own, and the review opens there. Its diff
 * then shows the real files, so ⌘-click, Find Usages and the rest work on the code of the pull request.
 */
object PrReviewWorktree {
    /** Must be called on the EDT. */
    fun open(project: Project, pr: GhPullRequest) {
        val repository = GhRepoContext.repository(project)
            ?: return GhCli.report(project, BidaideBundle.message("github.review.worktree.task", pr.number), GhException(BidaideBundle.message("gh.repo.noGit")))
        GhCli.async(
            project, BidaideBundle.message("github.review.worktree.preparing", pr.number),
            work = {
                val repoRoot = VfsUtilCore.virtualToIoFile(repository.root)
                val (dir, _) = PrWorktreeGit.prepare(project, repository, repoRoot, pr.number)
                dir to projectDir(project, repoRoot, dir)
            },
        ) { (worktree, projectDir) ->
            // Already open from an earlier review: openOrImport just brings that window to the front.
            val opened = ProjectUtil.openOrImport(projectDir.toPath(), null, true) ?: return@async
            ApplicationManager.getApplication().invokeLater({ PrReviewDiff.reopen(opened, pr.number, worktree) }, opened.disposed)
        }
    }

    /**
     * Where the project sits inside the worktree: the same folder as the current project inside its repository, so a
     * Gradle build in a subfolder opens as such. `local.properties` (the SDK path, never committed) is copied along,
     * or the Gradle sync of the new window could not find the Android SDK.
     */
    private fun projectDir(project: Project, repoRoot: File, worktree: File): File {
        val base = project.basePath?.let(::File) ?: return worktree
        val inRepo = PrWorktrees.gradleRootInRepo(repoRoot, base) ?: return worktree
        val dir = if (inRepo.isEmpty()) worktree else File(worktree, inRepo)
        val localProperties = File(base, "local.properties")
        val target = File(dir, "local.properties")
        if (localProperties.isFile && dir.isDirectory && !target.exists()) localProperties.copyTo(target)
        return dir
    }
}
