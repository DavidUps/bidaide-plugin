package com.bidaide.github.pr

import com.bidaide.BidaideBundle
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.File

/**
 * The git side of a pull request's worktree, shared by "Test on Device" and the review with code navigation: fetch
 * the head without moving any local branch, then create or update the detached worktree. All calls are blocking.
 */
object PrWorktreeGit {
    /** Fetches `pull/<n>/head` and checks it out in the PR's worktree, created if needed. Returns the commit. */
    fun prepare(project: Project, repository: GitRepository, repoRoot: File, number: Int): Pair<File, String> {
        val commit = fetchHead(project, repository, repoRoot, number)
        val dir = PrWorktrees.worktreeDir(File(PathManager.getSystemPath()), repoRoot.path, number)
        checkoutWorktree(project, repoRoot, dir, commit)
        return dir to commit
    }

    /** Fetches `pull/<n>/head` from the remote `gh` uses, without touching any local branch, and returns its commit. */
    fun fetchHead(project: Project, repository: GitRepository, repoRoot: File, number: Int): String {
        val nameWithOwner = GhCli.run(repoRoot, GhCommands.repoName()).trim()
        val remotes = repository.remotes.associate { it.name to it.urls }
        val remote = PrWorktrees.remoteFor(remotes, nameWithOwner)
        val source = remote ?: PrWorktrees.repoUrl(nameWithOwner)
        git(project, repoRoot, GitCommand.FETCH, PrWorktrees.fetchArgs(source, number), urls = remotes[remote] ?: listOf(source))
        return git(project, repoRoot, GitCommand.REV_PARSE, listOf("FETCH_HEAD")).firstOrNull { it.isNotBlank() }?.trim()
            ?: throw GhException(BidaideBundle.message("github.pr.worktree.noCommit", number))
    }

    /** Reuses the PR's worktree when git still knows it; otherwise (re)creates it detached at [commit]. */
    fun checkoutWorktree(project: Project, repoRoot: File, dir: File, commit: String) {
        if (dir.isDirectory && isRegistered(project, repoRoot, dir)) {
            git(project, dir, GitCommand.CHECKOUT, PrWorktrees.updateArgs(commit))
            return
        }
        // Forgets a worktree whose folder was deleted by hand, so the add below does not refuse the path.
        git(project, repoRoot, GitCommand.WORKTREE, listOf("prune"))
        val root = PrWorktrees.root(File(PathManager.getSystemPath()))
        // A folder git does not know as a worktree is a leftover of ours (it is inside bidaide-worktrees).
        if (dir.exists() && PrWorktrees.isInside(root, dir)) FileUtil.delete(dir)
        dir.parentFile.mkdirs()
        git(project, repoRoot, GitCommand.WORKTREE, PrWorktrees.addArgs(dir, commit))
    }

    private fun isRegistered(project: Project, repoRoot: File, dir: File): Boolean =
        listWorktrees(project, repoRoot).any { File(it).canonicalFile == dir.canonicalFile }

    fun listWorktrees(project: Project, repoRoot: File): List<String> =
        PrWorktrees.parseWorktreeList(git(project, repoRoot, GitCommand.WORKTREE, listOf("list", "--porcelain")).joinToString("\n"))

    /** Output lines; a failure becomes a [GhException] so [GhCli.async] reports it like any other. */
    fun git(project: Project, dir: File, command: GitCommand, args: List<String>, urls: List<String>? = null): List<String> {
        val handler = GitLineHandler(project, dir, command)
        urls?.let(handler::setUrls)
        handler.addParameters(args)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw GhException(BidaideBundle.message("github.pr.worktree.gitFailed", command.name(), result.errorOutputAsJoinedString))
        return result.output
    }
}
