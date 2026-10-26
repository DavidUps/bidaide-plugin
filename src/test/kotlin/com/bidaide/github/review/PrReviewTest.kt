package com.bidaide.github.review

import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhReaction
import com.bidaide.gh.GhReviewCommands
import com.bidaide.gh.ReactionContent
import com.bidaide.gh.ReviewEvent
import com.intellij.diff.util.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrReviewTest {
    @Test
    fun `the file filter needs every word somewhere in the path, ignoring case`() {
        val path = "app/src/main/java/com/acme/ui/login/LoginView.kt"
        for (query in listOf("", "  ", "loginview", "login view", "UI  LOGIN", ".kt")) assertTrue(query, PrReviewWindow.FileFilter.matches(path, query))
        for (query in listOf("register", "login register", "view.java")) assertFalse(query, PrReviewWindow.FileFilter.matches(path, query))
    }

    @Test
    fun `commentable lines follow the hunks`() {
        val patch = """
            @@ -2,4 +2,5 @@ package a
             ctx2
            -old3
            +new3
            +new4
             ctx4
             ctx5
            \ No newline at end of file
            @@ -20 +21,2 @@
            -old20
            +new21
            +new22
        """.trimIndent()
        val lines = CommentableLines.fromPatch(patch)
        assertTrue((2..5).all { lines.contains(Side.LEFT, it) })
        assertTrue((2..6).all { lines.contains(Side.RIGHT, it) })
        assertFalse(lines.contains(Side.LEFT, 6))
        assertFalse(lines.contains(Side.RIGHT, 7))
        assertTrue(lines.contains(Side.LEFT, 20))
        assertFalse(lines.contains(Side.LEFT, 21))
        assertTrue(lines.contains(Side.RIGHT, 21) && lines.contains(Side.RIGHT, 22))
        assertFalse(CommentableLines.fromPatch(null).contains(Side.RIGHT, 1))
    }

    @Test
    fun `slurped pages are flattened`() {
        val files = GhJson.prFiles(
            """[[{"filename":"a.kt","status":"renamed","previous_filename":"b.kt","additions":1,"deletions":2,"patch":"@@ -1 +1 @@"}],
                [{"filename":"img.png","status":"added"}]]"""
        )
        assertEquals(listOf("a.kt", "img.png"), files.map { it.filename })
        assertEquals("b.kt", files[0].previousFilename)
        assertNull(files[1].patch)

    }

    @Test
    fun `review comment commands`() {
        assertEquals(
            listOf(
                "api", "--method", "POST", "repos/{owner}/{repo}/pulls/9/comments",
                "-f", "body=hola", "-f", "commit_id=abc", "-f", "path=a/B.kt", "-f", "side=RIGHT", "-F", "line=12",
            ),
            GhCommands.prCreateReviewComment(9, "hola", "abc", "a/B.kt", "RIGHT", 12, 12),
        )
        assertEquals(
            listOf("-F", "start_line=10", "-f", "start_side=LEFT"),
            GhCommands.prCreateReviewComment(9, "x", "abc", "a", "LEFT", 12, 10).takeLast(4),
        )
        assertEquals("a%20b/c%23d.kt", GhCommands.encodePath("a b/c#d.kt"))
    }

    @Test
    fun `viewed states are read across pages`() {
        fun page(vararg files: Pair<String, String>) = """{"data":{"repository":{"pullRequest":{"id":"PR_x","files":{"nodes":[""" +
            files.joinToString(",") { """{"path":"${it.first}","viewerViewedState":"${it.second}"}""" } +
            """],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}"""
        val states = GhJson.viewedStates("[${page("a.kt" to "VIEWED", "b.kt" to "UNVIEWED")},${page("c.kt" to "DISMISSED", "d.kt" to "VIEWED")}]")
        assertEquals("PR_x", states.pullRequestId)
        assertEquals(setOf("a.kt", "d.kt"), states.viewedPaths)
    }

    @Test
    fun `viewed mutation picks mark or unmark`() {
        assertTrue(GhCommands.prSetFileViewed("PR_x", "a.kt", true).last().contains("{markFileAsViewed("))
        val unmark = GhCommands.prSetFileViewed("PR_x", "a.kt", false)
        assertTrue(unmark.last().contains("{unmarkFileAsViewed("))
        assertTrue(unmark.containsAll(listOf("id=PR_x", "path=a.kt")))
    }

    @Test
    fun `review state is parsed from graphql pages`() {
        fun page(threads: String, pending: String) = """{"data":{"repository":{"pullRequest":{"id":"PR_1","headRefName":"feature/x",
            "headRepository":{"nameWithOwner":"o/r"},"reviews":{"nodes":[$pending]},
            "reviewThreads":{"nodes":[$threads],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}"""
        val live = """{"id":"T1","isResolved":false,"isOutdated":false,"path":"a.kt","line":268,"startLine":256,"originalLine":268,
            "diffSide":"RIGHT","viewerCanResolve":true,"viewerCanUnresolve":false,"viewerCanReply":true,"comments":{"nodes":[
            {"id":"C1","databaseId":3435459193,"body":"root","createdAt":"2026-06-18T11:38:37Z","state":"SUBMITTED",
             "author":{"login":"rev"},"viewerCanUpdate":false,"viewerCanDelete":false,
             "reactionGroups":[{"content":"THUMBS_UP","viewerHasReacted":true,"reactors":{"totalCount":2}},
                               {"content":"EYES","viewerHasReacted":false,"reactors":{"totalCount":0}}]},
            {"id":"C2","databaseId":2,"body":"mine","createdAt":null,"state":"PENDING","author":null,
             "viewerCanUpdate":true,"viewerCanDelete":true,"reactionGroups":[]}]}}"""
        val outdated = """{"id":"T2","isResolved":true,"isOutdated":true,"path":"b.kt","line":null,"startLine":null,"originalLine":7,
            "diffSide":"LEFT","viewerCanResolve":false,"viewerCanUnresolve":true,"viewerCanReply":true,"comments":{"nodes":[]}}"""
        val state = GhReviewCommands.parseState("[${page(live, """{"id":"REV1"}""")},${page(outdated, """{"id":"REV1"}""")}]")

        assertEquals("PR_1", state.pullRequestId)
        assertEquals("feature/x", state.headRefName)
        assertEquals("o/r", state.headRepository)
        assertEquals("REV1", state.pendingReviewId)
        val (t1, t2) = state.threads
        assertEquals(268, t1.line)
        assertEquals(256, t1.startLine)
        assertTrue(t1.hasPending)
        assertEquals(listOf(GhReaction("THUMBS_UP", 2, true)), t1.root!!.reactions)
        assertEquals("ghost", t1.comments[1].author)
        assertTrue(t1.comments[1].pending && t1.comments[1].canDelete)
        assertNull(t2.line)
        assertTrue(t2.isOutdated && t2.isResolved && t2.canUnresolve)
        assertEquals("LEFT", t2.side)
        assertNull(GhReviewCommands.parseState("[${page("", "")}]").pendingReviewId)
    }

    @Test
    fun `mutations only declare the variables they send`() {
        val single = GhReviewCommands.addPendingThread("PR_1", "a.kt", "hola", "RIGHT", 12, null)
        assertTrue(single.containsAll(listOf("pullRequestId=PR_1", "path=a.kt", "body=hola", "line=12", "side=RIGHT")))
        assertEquals("-F", single[single.indexOf("line=12") - 1])
        assertEquals("-f", single[single.indexOf("body=hola") - 1])
        assertFalse(single.last().contains("startLine"))
        assertTrue(single.last().startsWith("query=mutation(\$pullRequestId:ID!,"))
        assertTrue(single.last().contains("addPullRequestReviewThread(input:{pullRequestId:\$pullRequestId,"))

        val range = GhReviewCommands.addPendingThread("PR_1", "a.kt", "x", "LEFT", 12, 10)
        assertTrue(range.containsAll(listOf("startLine=10", "startSide=LEFT")))
        assertTrue(range.last().contains("\$startLine:Int,\$startSide:DiffSide"))

        assertFalse(GhReviewCommands.submitPending("PR_1", ReviewEvent.APPROVE, " ").any { it.startsWith("body=") })
        assertTrue(GhReviewCommands.submitPending("PR_1", ReviewEvent.REQUEST_CHANGES, "no").contains("event=REQUEST_CHANGES"))
        assertTrue(GhReviewCommands.setResolved("T", false).last().contains("{unresolveReviewThread("))
        assertTrue(GhReviewCommands.setReaction("C", ReactionContent.ROCKET, false).last().contains("{removeReaction("))
    }

    @Test
    fun `suggestions are extracted and applied`() {
        assertEquals("val a = 1\nval b = 2", Suggestions.extract("Mejor así:\n```suggestion\nval a = 1\nval b = 2\n```\ngracias"))
        assertEquals("", Suggestions.extract("```suggestion\n```"))
        assertNull(Suggestions.extract("```kotlin\nval a = 1\n```"))

        assertEquals("1\nX\nY\n4\n", Suggestions.replaceLines("1\n2\n3\n4\n", 2, 3, "X\nY"))
        assertEquals("1\n3", Suggestions.replaceLines("1\n2\n3", 2, 2, ""))
        assertEquals("1\r\nX\r\n3", Suggestions.replaceLines("1\r\n2\r\n3", 2, 2, "X"))
    }
}
