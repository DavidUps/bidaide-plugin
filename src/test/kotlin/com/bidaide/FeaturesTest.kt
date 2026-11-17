package com.bidaide

import com.bidaide.adb.AdbShell
import com.bidaide.adb.CustomAdbCommand
import com.bidaide.gh.GhCheck
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhCommands.MergeMethod
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPullRequest
import com.bidaide.github.actions.IssueDrafts
import com.bidaide.github.current.PrStatusWidget
import com.bidaide.github.current.PrWatch
import com.bidaide.github.pr.PullRequestChecks
import com.bidaide.github.pr.PullRequestGit
import com.bidaide.github.runs.CiPathFilter
import com.bidaide.github.runs.WorkflowDispatch
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeaturesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val passed = GhCheck(name = "build", status = "COMPLETED", conclusion = "SUCCESS")
    private val failed = GhCheck(name = "lint", status = "COMPLETED", conclusion = "FAILURE")
    private val running = GhCheck(name = "test", status = "IN_PROGRESS")

    private fun pr(number: Int, decision: String? = null, vararg checks: GhCheck) =
        GhPullRequest(number = number, title = "T$number", state = "OPEN", reviewDecision = decision, statusCheckRollup = checks.toList())

    @Test
    fun `pr commands carry the new options`() {
        assertEquals(
            listOf("--reviewer", "a,b", "--assignee", "@me", "--label", "bug"),
            GhCommands.prCreate("T", "B", null, false, " a , b ", "@me", "bug,").takeLast(6),
        )
        assertEquals("--auto", GhCommands.prMerge(1, MergeMethod.SQUASH, false, auto = true).last())
        assertEquals(listOf("pr", "update-branch", "1", "--rebase"), GhCommands.prUpdateBranch(1, true))
        assertEquals("author:@me fix login", GhCommands.combineSearch("author:@me", " fix login "))
        assertEquals("", GhCommands.combineSearch("", " "))
    }

    @Test
    fun `metadata edit only sends what changed`() {
        assertEquals(listOf("issue", "edit", "4"), GhCommands.editMetadata("issue", 4, emptySet(), emptySet(), emptySet(), emptySet(), null))
        assertEquals(
            listOf("pr", "edit", "4", "--add-label", "bug,ui", "--remove-assignee", "bob", "--add-reviewer", "ann", "--remove-milestone"),
            GhCommands.editMetadata("pr", 4, listOf("bug", "ui"), emptySet(), emptySet(), listOf("bob"), "", listOf("ann")),
        )
        assertEquals(listOf("--milestone", "v2"), GhCommands.editMetadata("issue", 4, emptySet(), emptySet(), emptySet(), emptySet(), "v2").takeLast(2))
    }

    @Test
    fun `run commands and parsing`() {
        assertEquals(listOf("--branch", "main", "--workflow", "CI"), GhCommands.runList("main", " CI ").takeLast(4))
        assertFalse("--branch" in GhCommands.runList(null, ""))
        assertEquals(listOf("run", "rerun", "9", "--failed"), GhCommands.runRerun(9, true))
        assertEquals("--log-failed", GhCommands.runLog(9, true).last())
        assertEquals(listOf("workflow", "run", "7", "--ref", "dev", "-f", "env=prod"), GhCommands.workflowRun(7, "dev", mapOf("env" to "prod")))

        val run = GhJson.run(
            """{"databaseId":27828399996,"displayTitle":"fix","workflowName":"CI","status":"completed","conclusion":"failure",
                "headBranch":"main","jobs":[{"databaseId":1,"name":"Lint","status":"completed","conclusion":"failure",
                "steps":[{"name":"Set up job","status":"completed","conclusion":"success","number":1}]}]}"""
        )
        assertEquals(27828399996L, run.databaseId)
        assertTrue(run.isFinished && run.failed)
        assertEquals("failure", run.result)
        assertEquals("Set up job", run.jobs!!.single().steps!!.single().name)
        assertEquals("in_progress", GhJson.runs("""[{"databaseId":2,"status":"in_progress","conclusion":""}]""").single().result)
    }

    @Test
    fun `workflow inputs are read from yaml`() {
        val yaml = """
            name: Deploy
            on:
              push:
                branches: [main]
              workflow_dispatch:
                inputs:
                  environment:
                    description: Where to deploy
                    required: true
                    type: choice
                    default: staging
                    options: [staging, production]
                  dry_run:
                    type: boolean
                    default: false
                  tag:
                    description: Version tag
            jobs: {}
        """.trimIndent()
        val inputs = WorkflowDispatch.parseInputs(yaml)!!
        assertEquals(listOf("environment", "dry_run", "tag"), inputs.map { it.name })
        assertEquals(listOf("staging", "production"), inputs[0].options)
        assertTrue(inputs[0].required)
        assertEquals("boolean", inputs[1].type)
        assertEquals("false", inputs[1].default)
        assertEquals("string", inputs[2].type)

        assertEquals(emptyList<Any>(), WorkflowDispatch.parseInputs("on: [push, workflow_dispatch]"))
        assertEquals(emptyList<Any>(), WorkflowDispatch.parseInputs("on:\n  workflow_dispatch:\n"))
        assertNull(WorkflowDispatch.parseInputs("on: push"))
        assertNull(WorkflowDispatch.parseInputs("on:\n  push:\n    branches: [main]"))
    }

    @Test
    fun `ci log paths resolve to the longest existing suffix`() {
        val root = temp.root
        File(root, "app/src/main/Foo.kt").apply { parentFile.mkdirs() }.writeText("x")
        assertEquals(File(root, "app/src/main/Foo.kt"), CiPathFilter.resolve(root, "home/runner/work/repo/repo/app/src/main/Foo.kt"))
        assertEquals(File(root, "app/src/main/Foo.kt"), CiPathFilter.resolve(root, "app/src/main/Foo.kt"))
        assertNull(CiPathFilter.resolve(root, "app/src/main/Missing.kt"))
        // A bare file name is too ambiguous to link.
        assertNull(CiPathFilter.resolve(root, "Foo.kt"))

        val match = CiPathFilter.PATH.find("e: file:///home/runner/work/r/r/app/src/main/Foo.kt:12:5 Unresolved reference")!!
        assertEquals("home/runner/work/r/r/app/src/main/Foo.kt", match.groupValues[1])
        assertEquals("12", match.groupValues[2])
        assertEquals("5", match.groupValues[3])
    }

    @Test
    fun `status widget text summarises checks and review`() {
        assertEquals("#1 ✓ checks · approved", PrStatusWidget.text(pr(1, "APPROVED", passed), 0))
        assertEquals("#2 ✗ checks · changes requested · 💬 3", PrStatusWidget.text(pr(2, "CHANGES_REQUESTED", passed, failed), 3))
        assertEquals("#3 … checks · review required", PrStatusWidget.text(pr(3, "REVIEW_REQUIRED", passed, running), 0))
        assertEquals("#4", PrStatusWidget.text(pr(4), 0))
        assertEquals("#5 merged", PrStatusWidget.text(pr(5).copy(state = "MERGED"), 2))
    }

    @Test
    fun `status widget spins only while checks of an open pull request run`() {
        assertTrue(pr(1, null, passed, running).checksRunning)
        assertFalse(pr(2, null, passed, failed).checksRunning)
        // Final results that are neither a pass nor a failure must not keep the spinner going.
        assertFalse(GhCheck(name = "old", status = "COMPLETED", conclusion = "STALE").running)
        assertTrue(GhCheck(context = "ci/external", state = "PENDING").running)
        assertSame(AnimatedIcon.Default.INSTANCE, PrStatusWidget.icon(pr(3, null, failed, running)))
        assertSame(AllIcons.Vcs.Vendors.Github, PrStatusWidget.icon(pr(4, null, passed)))
        assertSame(AllIcons.Vcs.Vendors.Github, PrStatusWidget.icon(pr(5, null, running).copy(state = "CLOSED")))
    }

    @Test
    fun `pull request checks spin while they run`() {
        assertSame(AnimatedIcon.Default.INSTANCE, PullRequestChecks.iconOf(running))
        assertSame(AllIcons.RunConfigurations.TestPassed, PullRequestChecks.iconOf(passed))
        assertSame(AllIcons.RunConfigurations.TestFailed, PullRequestChecks.iconOf(failed))
        assertSame(AllIcons.RunConfigurations.TestIgnored, PullRequestChecks.iconOf(GhCheck(name = "docs", status = "COMPLETED", conclusion = "SKIPPED")))
    }

    @Test
    fun `watcher reports only what is new`() {
        val before = listOf(pr(1, null, running), pr(2, "REVIEW_REQUIRED", passed)).map(PrWatch::snapshot)
        val after = listOf(pr(1, "APPROVED", failed), pr(2, "REVIEW_REQUIRED", passed), pr(3, "CHANGES_REQUESTED", failed)).map(PrWatch::snapshot)

        // The first poll only records the state.
        assertTrue(PrWatch.ownPrChanges(null, after).isEmpty())
        assertTrue(PrWatch.newReviewRequests(null, after).isEmpty())

        val changes = PrWatch.ownPrChanges(before.associateBy { it.number }, after).map { it.message }
        assertEquals(listOf("Approved: #1 T1", "Checks failing on #1 T1"), changes)
        assertEquals(listOf(3), PrWatch.newReviewRequests(setOf(1, 2), after).map { it.number })

        val green = PrWatch.ownPrChanges(mapOf(1 to PrWatch.snapshot(pr(1, null, running))), listOf(PrWatch.snapshot(pr(1, null, passed))))
        assertEquals("Checks green on #1 T1", green.single().message)
    }

    @Test
    fun `pull request drafts`() {
        assertEquals("Add host or cocker", PullRequestGit.humanize("feature/add-host_or-cocker"))
        assertNull(PullRequestGit.template(temp.root))
        File(temp.root, ".github").mkdirs()
        File(temp.root, ".github/PULL_REQUEST_TEMPLATE.md").writeText("## Qué cambia")
        assertEquals("## Qué cambia", PullRequestGit.template(temp.root))
    }

    @Test
    fun `issue drafts strip comment and todo markers`() {
        assertEquals("handle empty list", IssueDrafts.title("    // TODO: handle empty list\n    val a = 1"))
        assertEquals("fix crash", IssueDrafts.title("# FIXME(david) fix crash"))
        assertEquals("old api", IssueDrafts.title("/* HACK old api */"))
        assertEquals("val a = 1", IssueDrafts.title("\n\nval a = 1"))
        assertEquals("https://x/y#L1\n\n```kt\nval a = 1\n```\n", IssueDrafts.body("https://x/y#L1", "val a = 1\n", "kt"))
        assertEquals("https://x/y\n", IssueDrafts.body("https://x/y", " ", "kt"))
    }

    @Test
    fun `adb helpers`() {
        assertEquals("'it'\\''s'", AdbShell.quote("it's"))
        val commands = CustomAdbCommand.parse(
            """
            # comentario
            Ver actividad = dumpsys activity {package} | grep mResumed
            Hora = date
            sin separador
             = vacío
            """.trimIndent()
        )
        assertEquals(listOf("Ver actividad", "Hora"), commands.map { it.name })
        assertTrue(commands[0].needsPackage)
        assertFalse(commands[1].needsPackage)
        assertEquals("dumpsys activity com.app | grep mResumed", commands[0].resolve("com.app"))
    }

    @Test
    fun `custom command parameters`() {
        val command = CustomAdbCommand(
            "Login",
            """am start -n {package}/.Main --es nif "{nif}" --es birthdate "{birthdate}" --es again "{nif}" -e home ${'$'}{HOME}""",
        )
        assertEquals(listOf("nif", "birthdate"), command.parameters)
        assertTrue(command.needsPackage)
        assertEquals(
            """am start -n com.app/.Main --es nif "1" --es birthdate "2" --es again "1" -e home ${'$'}{HOME}""",
            command.resolve("com.app", mapOf("nif" to "1", "birthdate" to "2")),
        )
        assertTrue(CustomAdbCommand.isSensitive("userPassword"))
        assertFalse(CustomAdbCommand.isSensitive("nif"))
    }
}
