package com.bidaide.android

import com.android.ddmlib.IDevice
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.projectsystem.getAndroidFacets
import com.android.tools.idea.projectsystem.gradle.getGradleProjectPath
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.adb.AdbShell
import com.bidaide.adb.AdbTargetResolver
import com.bidaide.adb.AdbTasks
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhException
import com.bidaide.gh.GhPullRequest
import com.bidaide.gh.GhRepoContext
import com.bidaide.github.actions.GitHubProjectAction
import com.bidaide.github.pr.PrAppModule
import com.bidaide.github.pr.PrWorktreeGit
import com.bidaide.github.pr.PrWorktrees
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File

/**
 * "Test on Device" without touching the user's checkout: the pull request's head is fetched into a detached
 * worktree under the IDE system directory, built there with the project's Gradle wrapper and JDK, and started on
 * the device chosen through [AdbTargetResolver].
 */
object PrWorktreeRun {

    /** What the background preparation hands to the EDT: the worktree and the Gradle build ready to start. */
    private class Prepared(val dir: File, val handler: ColoredProcessHandler, val header: String, val copiedLocalProperties: Boolean)

    /** Must be called on the EDT. */
    fun start(project: Project, pr: GhPullRequest) {
        val repository = GhRepoContext.repository(project)
            ?: return GhCli.report(project, BidaideBundle.message("github.pr.test.task", pr.number), GhException(BidaideBundle.message("gh.repo.noGit")))
        val apps = appModules(project)
        if (apps.isEmpty()) {
            Notifier.warn(
                project, "PR #${pr.number}",
                BidaideBundle.message("github.pr.worktree.noModule", PrWorktrees.installTask(PrWorktrees.FALLBACK_MODULE)),
            )
        }
        // The app is asked for (when there are several) the same way as for any ADB operation, and names the module to build.
        AdbTargetResolver.resolve(project, needsPackage = apps.any { it.applicationId != null }) { devices, packageName ->
            val module = apps.firstOrNull { it.applicationId == packageName } ?: apps.firstOrNull() ?: PrWorktrees.FALLBACK_MODULE
            // With one device Gradle must install only there; "all devices" is what the install task does anyway.
            val serial = devices.singleOrNull()?.serialNumber
            GhCli.async(
                project, BidaideBundle.message("github.pr.worktree.preparing", pr.number),
                work = { prepare(project, repository, pr.number, module, serial) },
            ) { prepared -> showBuild(project, pr, prepared, devices, module.applicationId) }
        }
    }

    /** The Android application modules with their Gradle path and the build variant selected in the IDE. */
    private fun appModules(project: Project): List<PrAppModule> =
        project.getAndroidFacets().filter { it.configuration.isAppProject }.mapNotNull { facet ->
            val model = GradleAndroidModel.get(facet) ?: return@mapNotNull null
            val path = facet.module.getGradleProjectPath()?.path ?: return@mapNotNull null
            val id = AndroidModel.get(facet)?.applicationId?.takeIf { it.isNotBlank() && it != AndroidModel.UNINITIALIZED_APPLICATION_ID }
            PrAppModule(path, model.selectedVariantName, id)
        }.distinctBy { it.gradlePath }

    /** Blocking: fetches the head, creates or updates the worktree and starts (but does not yet read) the Gradle build. */
    private fun prepare(project: Project, repository: GitRepository, number: Int, module: PrAppModule, serial: String?): Prepared {
        val repoRoot = VfsUtilCore.virtualToIoFile(repository.root)
        val (dir, commit) = PrWorktreeGit.prepare(project, repository, repoRoot, number)

        val linkedPath = GradleSettings.getInstance(project).linkedProjectsSettings.firstOrNull()?.externalProjectPath ?: project.basePath
        val userGradleRoot = linkedPath?.let(::File) ?: repoRoot
        val inRepo = PrWorktrees.gradleRootInRepo(repoRoot, userGradleRoot)
            ?: throw GhException(BidaideBundle.message("github.pr.worktree.outsideRepo", userGradleRoot))
        val gradleRoot = if (inRepo.isEmpty()) dir else File(dir, inRepo)

        // sdk.dir and friends are never committed; without them the build cannot find the Android SDK.
        val localProperties = File(userGradleRoot, "local.properties")
        val target = File(gradleRoot, "local.properties")
        if (localProperties.isFile && !target.exists()) localProperties.copyTo(target)

        val wrapper = File(gradleRoot, PrWorktrees.wrapperName(SystemInfo.isWindows))
        if (!wrapper.isFile) throw GhException(BidaideBundle.message("github.pr.worktree.noWrapper", wrapper.name, gradleRoot.path))
        // The JDK the IDE gives Gradle for this project (Settings → Build Tools → Gradle), so the build behaves the same.
        val jdk = linkedPath?.let {
            ApplicationManager.getApplication().runReadAction(Computable { GradleInstallationManager.getInstance().getGradleJvmPath(project, it) })
        }
        val task = PrWorktrees.installTask(module)

        val commandLine = GeneralCommandLine(PrWorktrees.buildCommand(wrapper, task)).withWorkDirectory(gradleRoot).withCharset(Charsets.UTF_8)
        jdk?.let { commandLine.withEnvironment("JAVA_HOME", it) }
        serial?.let { commandLine.withEnvironment("ANDROID_SERIAL", it) }
        val header = buildString {
            appendLine(BidaideBundle.message("github.pr.worktree.header", number, commit.take(10), dir.path))
            appendLine("JDK: ${jdk ?: BidaideBundle.message("github.pr.worktree.javaHome")}${serial?.let { " · " + BidaideBundle.message("github.pr.worktree.device", it) }.orEmpty()}")
            if (!localProperties.isFile) appendLine(BidaideBundle.message("github.pr.worktree.noLocalProperties"))
            appendLine("> ${wrapper.name} $task")
        }
        return Prepared(dir, ColoredProcessHandler(commandLine), header, localProperties.isFile)
    }

    /** Streams the build into a closable tab of the Bidaide tool window; on success, starts the app on the devices. */
    private fun showBuild(project: Project, pr: GhPullRequest, prepared: Prepared, devices: List<IDevice>, packageName: String?) {
        val handler = prepared.handler
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Bidaide")
        if (toolWindow == null) {
            handler.destroyProcess()
            Notifier.warn(project, "PR #${pr.number}", BidaideBundle.message("adb.logcat.noToolWindow"))
            return
        }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.print(prepared.header + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.attachToProcess(handler)
        val content = ContentFactory.getInstance().createContent(console.component, "PR #${pr.number}: build", false)
        content.isCloseable = true
        Disposer.register(content, console)
        var closed = false
        // Closing the tab stops the build.
        Disposer.register(content) {
            closed = true
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().invokeLater {
                    when {
                        project.isDisposed || closed -> Unit
                        event.exitCode != 0 -> Notifier.error(
                            project, BidaideBundle.message("github.pr.worktree.buildFailed", pr.number, event.exitCode),
                            PrWorktrees.failureHint(prepared.copiedLocalProperties) + "<br>Worktree: ${prepared.dir.path}",
                        )
                        packageName == null -> Notifier.info(project, "PR #${pr.number}", BidaideBundle.message("github.pr.worktree.installed"))
                        else -> AdbTasks.run(project, "PR #${pr.number}", devices, packageName) { context ->
                            AdbShell.start(context.device, packageName)
                            BidaideBundle.message("github.pr.worktree.started", pr.number)
                        }
                    }
                }
            }
        })
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        toolWindow.activate(null)
        handler.startNotify()
    }

    /** Deletes this repository's PR worktrees after asking; the user's checkout and other worktrees are never touched. */
    fun deleteWorktrees(project: Project) {
        val repository = GhRepoContext.repository(project)
            ?: return Notifier.warn(project, BidaideBundle.message("github.pr.worktrees.title"), BidaideBundle.message("gh.repo.noGit"))
        val repoRoot = VfsUtilCore.virtualToIoFile(repository.root)
        val systemDir = File(PathManager.getSystemPath())
        val root = PrWorktrees.root(systemDir)
        val repoDir = PrWorktrees.repoDir(systemDir, repoRoot.path)
        GhCli.async(
            project, BidaideBundle.message("github.pr.worktrees.searching"),
            work = { PrWorktrees.ownWorktrees(repoDir, PrWorktreeGit.listWorktrees(project, repoRoot)) },
        ) { worktrees ->
            val leftovers = repoDir.listFiles().orEmpty().count { dir -> worktrees.none { it.canonicalFile == dir.canonicalFile } }
            if (worktrees.isEmpty() && leftovers == 0) {
                Notifier.info(project, BidaideBundle.message("github.pr.worktrees.title"), BidaideBundle.message("github.pr.worktrees.none"))
                return@async
            }
            val answer = Messages.showOkCancelDialog(
                project,
                BidaideBundle.message("github.pr.worktrees.confirm", worktrees.size + leftovers, repoDir.path),
                BidaideBundle.message("github.pr.worktrees.deleteTitle"), BidaideBundle.message("github.pr.worktrees.delete"),
                BidaideBundle.message("common.cancel"), Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return@async
            GhCli.async(
                project, BidaideBundle.message("github.pr.worktrees.deleting"),
                work = {
                    worktrees.forEach { PrWorktreeGit.git(project, repoRoot, GitCommand.WORKTREE, PrWorktrees.removeArgs(it)) }
                    PrWorktreeGit.git(project, repoRoot, GitCommand.WORKTREE, listOf("prune"))
                    // Folders git had already forgotten; only ever inside bidaide-worktrees.
                    repoDir.listFiles().orEmpty().filter { PrWorktrees.isInside(root, it) }.forEach(FileUtil::delete)
                    if (PrWorktrees.isInside(root, repoDir) && repoDir.listFiles().isNullOrEmpty()) FileUtil.delete(repoDir)
                },
            ) { Notifier.info(project, BidaideBundle.message("github.pr.worktrees.title"), BidaideBundle.message("github.pr.worktrees.deleted")) }
        }
    }
}

/** Tools → Bidaide → GitHub → "Delete Pull Request Worktrees...". Android Studio only, like the worktrees it deletes. */
class DeletePrWorktreesAction : GitHubProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        PrWorktreeRun.deleteWorktrees(e.project ?: return)
    }
}
