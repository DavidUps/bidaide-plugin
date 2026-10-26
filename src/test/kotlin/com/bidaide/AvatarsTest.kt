package com.bidaide

import com.bidaide.gh.GhJson
import com.bidaide.gh.GhPeople
import com.bidaide.gh.GhPrPeople
import com.bidaide.gh.GhReviewer
import com.bidaide.gh.GitHubUser
import com.bidaide.gh.GitHubUserResolver
import com.bidaide.gh.ReviewerStatus
import com.bidaide.github.pr.GitHubUsers
import com.bidaide.github.pr.PullRequestPeoplePanel
import com.bidaide.github.ui.AvatarIcon
import com.bidaide.github.ui.AvatarImages
import com.bidaide.github.ui.UserChip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AvatarsTest {
    private val avatar = "https://avatars.githubusercontent.com/u/1?v=4"

    // --- Parsing what `gh pr view` returns ---

    private fun people(json: String) = GhPeople.of(GhJson.pullRequest(json))

    @Test
    fun `author and requested reviewers come from gh pr view`() {
        val people = people(
            """{"number":7,"author":{"id":"x","is_bot":false,"login":"octocat","name":"The Octocat"},
                "reviewRequests":[{"__typename":"User","login":"alice"},{"__typename":"Team","name":"Mobile","slug":"mobile"}],
                "latestReviews":[{"author":{"login":"bob"},"state":"APPROVED"},{"author":{"login":"carol"},"state":"CHANGES_REQUESTED"}]}"""
        )
        assertEquals(GitHubUser("octocat"), people.author)
        assertEquals(listOf("bob", "carol", "alice", "mobile"), people.reviewers.map { it.user.login })
        assertEquals(
            listOf(ReviewerStatus.APPROVED, ReviewerStatus.CHANGES_REQUESTED, ReviewerStatus.REQUESTED, ReviewerStatus.REQUESTED),
            people.reviewers.map { it.status },
        )
        assertEquals(listOf(false, false, false, true), people.reviewers.map { it.isTeam })
        // Teams have no user profile to look up.
        assertEquals(listOf("octocat", "bob", "carol", "alice"), people.users.map { it.login })
    }

    @Test
    fun `reviewers are listed once and never include the author`() {
        val people = people(
            """{"author":{"login":"octocat"},
                "reviewRequests":[{"__typename":"User","login":"Alice"},{"__typename":"User","login":"octocat"}],
                "latestReviews":[{"author":{"login":"alice"},"state":"COMMENTED"},{"author":{"login":"OctoCat"},"state":"COMMENTED"},
                                 {"author":{"login":"bob"},"state":"PENDING"},{"author":null,"state":"APPROVED"}]}"""
        )
        // alice reviewed and was asked again: one entry, waiting on her; bob's draft review does not count.
        assertEquals(listOf(GhReviewer(GitHubUser("Alice"), ReviewerStatus.REQUESTED)), people.reviewers)
        assertEquals("octocat", people.author?.login)
    }

    @Test
    fun `a pull request without reviewers or author still parses`() {
        val none = people("""{"number":1,"author":{"login":"octocat"},"reviewRequests":[],"latestReviews":[]}""")
        assertTrue(none.reviewers.isEmpty())
        assertFalse(none.isEmpty)
        // A deleted account, and a gh too old to know the review fields.
        val ghost = people("""{"number":1,"author":null}""")
        assertNull(ghost.author)
        assertTrue(ghost.isEmpty)
    }

    @Test
    fun `avatars of the whole pull request come from one query`() {
        val json = """{"data":{"repository":{"pullRequest":{"author":{"login":"octocat","avatarUrl":"$avatar"},
            "reviewRequests":{"nodes":[{"requestedReviewer":{"__typename":"User","login":"alice","avatarUrl":"https://avatars.githubusercontent.com/u/2?v=4"}},
                                       {"requestedReviewer":{"__typename":"Team","name":"Mobile","slug":"mobile","avatarUrl":"https://avatars.githubusercontent.com/t/3?v=4"}},
                                       {"requestedReviewer":null}]},
            "latestReviews":{"nodes":[{"author":{"login":"octocat","avatarUrl":"$avatar"}},{"author":{"login":"noavatar","avatarUrl":""}},{"author":null}]}}}}}"""
        assertEquals(listOf("octocat", "alice", "mobile"), GhPeople.parseAvatars(json).map { it.login })
        assertEquals(avatar, GhPeople.parseAvatars(json).first().avatarUrl)
        assertTrue(GhPeople.parseAvatars("""{"data":{"repository":{"pullRequest":null}}}""").isEmpty())
        assertEquals("number=7", GhPeople.avatarsQuery(7)[7])
    }

    @Test
    fun `the users api gives login and avatar url`() {
        assertEquals(GitHubUser("octocat", avatar), GhPeople.parseUser("""{"login":"octocat","id":1,"avatar_url":"$avatar","type":"User"}"""))
        assertEquals(GitHubUser("octocat", null), GhPeople.parseUser("""{"login":"octocat","avatar_url":null}"""))
        assertEquals(GitHubUser("octocat", null), GhPeople.parseUser("""{"login":"octocat"}"""))
        assertTrue(runCatching { GhPeople.parseUser("""{"message":"Not Found"}""") }.isFailure)
        assertTrue(runCatching { GhPeople.parseUser("not json") }.isFailure)
        assertTrue(runCatching { GhPeople.parseUser("[]") }.isFailure)
        assertEquals(listOf("api", "users/dependabot%5Bbot%5D"), GhPeople.userArgs("dependabot[bot]"))
    }

    // --- Resolver and cache ---

    private class FakeGh(private val answer: (String) -> String) {
        val calls = ArrayList<String>()
        val resolver = GitHubUserResolver { login ->
            calls += login
            answer(login)
        }
    }

    @Test
    fun `returns cached user without executing gh again`() {
        val gh = FakeGh { """{"login":"$it","avatar_url":"$avatar"}""" }
        val first = gh.resolver.resolve(GitHubUser("octocat"))
        val second = gh.resolver.resolve(GitHubUser("OctoCat"))
        assertEquals(avatar, first.avatarUrl)
        assertEquals(avatar, second.avatarUrl)
        assertEquals(listOf("octocat"), gh.calls)
    }

    @Test
    fun `a user that already has an avatar never costs a request`() {
        val gh = FakeGh { error("gh must not run") }
        assertEquals(avatar, gh.resolver.resolve(GitHubUser("octocat", avatar)).avatarUrl)
        // ...and is remembered for the next pull request that only knows the login.
        assertEquals(avatar, gh.resolver.resolve(GitHubUser("octocat")).avatarUrl)
        assertTrue(gh.calls.isEmpty())
    }

    @Test
    fun `a failing gh leaves the user without avatar and is retried later`() {
        var broken = true
        val gh = FakeGh { if (broken) throw IllegalStateException("gh: command not found") else """{"login":"$it","avatar_url":"$avatar"}""" }
        assertEquals(GitHubUser("octocat", null), gh.resolver.resolve(GitHubUser("octocat")))
        assertNull(gh.resolver.cached("octocat"))
        broken = false
        assertEquals(avatar, gh.resolver.resolve(GitHubUser("octocat")).avatarUrl)
        assertEquals(2, gh.calls.size)
    }

    @Test
    fun `an invalid answer from GitHub is treated like a failure`() {
        val gh = FakeGh { """{"message":"API rate limit exceeded"}""" }
        assertEquals(GitHubUser("octocat", null), gh.resolver.resolve(GitHubUser("octocat")))
        assertNull(gh.resolver.cached("octocat"))
    }

    @Test
    fun `a user without avatar is remembered as such`() {
        val gh = FakeGh { """{"login":"$it","avatar_url":null}""" }
        gh.resolver.resolve(GitHubUser("octocat"))
        assertNull(gh.resolver.resolve(GitHubUser("octocat")).avatarUrl)
        assertEquals(1, gh.calls.size)
    }

    @Test
    fun `logins that are not logins never reach an api path`() {
        val gh = FakeGh { error("gh must not run") }
        for (login in listOf("../orgs/acme", "a b", "", "user?x=1", "-leading")) {
            assertNull(gh.resolver.resolve(GitHubUser(login)).avatarUrl)
        }
        assertTrue(gh.calls.isEmpty())
        assertTrue(GhPeople.isValidLogin("copilot-pull-request-reviewer"))
        assertTrue(GhPeople.isValidLogin("dependabot[bot]"))
    }

    private val team = GhReviewer(GitHubUser("mobile"), ReviewerStatus.REQUESTED, isTeam = true)
    private val somePeople = GhPrPeople(GitHubUser("octocat"), listOf(GhReviewer(GitHubUser("alice"), ReviewerStatus.APPROVED), team))

    @Test
    fun `one query serves the pull request and only missing users are asked for individually`() {
        val gh = FakeGh { """{"login":"$it","avatar_url":"https://avatars.githubusercontent.com/u/9?v=4"}""" }
        var queries = 0
        val query = {
            queries++
            """{"data":{"repository":{"pullRequest":{"author":{"login":"octocat","avatarUrl":"$avatar"},
                "reviewRequests":{"nodes":[{"requestedReviewer":{"__typename":"Team","slug":"mobile","name":"Mobile","avatarUrl":"https://avatars.githubusercontent.com/t/3?v=4"}}]},
                "latestReviews":{"nodes":[]}}}}}"""
        }
        val resolved = GitHubUsers.resolve(gh.resolver, somePeople, query)
        assertEquals(listOf("octocat", "alice", "mobile"), resolved.map { it.login })
        assertTrue(resolved.all { it.avatarUrl != null })
        // alice was missing from the query; the team is never looked up as a user.
        assertEquals(listOf("alice"), gh.calls)
        // Next refresh: everything is cached, nothing runs.
        GitHubUsers.resolve(gh.resolver, somePeople, query)
        assertEquals(1, queries)
        assertEquals(listOf("alice"), gh.calls)
    }

    @Test
    fun `without gh everybody is still returned, just without avatar`() {
        val gh = FakeGh { throw IllegalStateException("gh not installed") }
        val resolved = GitHubUsers.resolve(gh.resolver, somePeople) { throw IllegalStateException("gh not installed") }
        assertEquals(listOf("octocat", "alice", "mobile"), resolved.map { it.login })
        assertTrue(resolved.all { it.avatarUrl == null })
    }

    // --- Images ---

    @Test
    fun `only https addresses on github hosts are trusted`() {
        assertTrue(AvatarImages.isTrusted(avatar))
        assertTrue(AvatarImages.isTrusted("https://github.com/octocat.png"))
        for (url in listOf(
            "http://avatars.githubusercontent.com/u/1", "https://evil.example/u/1", "https://githubusercontent.com.evil.example/u/1",
            "file:///etc/passwd", "https://user:pass@avatars.githubusercontent.com/u/1", "not a url", "",
        )) assertFalse(url, AvatarImages.isTrusted(url))
    }

    @Test
    fun `list avatars come straight from the image host, which honours the size`() {
        assertEquals("https://avatars.githubusercontent.com/octocat?s=40", AvatarImages.sizedUrl(AvatarImages.profilePicture("octocat")!!, 40))
        assertEquals("https://avatars.githubusercontent.com/dependabot", AvatarImages.profilePicture("dependabot[bot]"))
        assertTrue(AvatarImages.isTrusted(AvatarImages.profilePicture("octocat")!!))
        assertNull(AvatarImages.profilePicture("not a login"))
    }

    @Test
    fun `avatars are requested at the size shown`() {
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4&s=40", AvatarImages.sizedUrl(avatar, 40))
        assertEquals("https://avatars.githubusercontent.com/u/1?s=40", AvatarImages.sizedUrl("https://avatars.githubusercontent.com/u/1", 40))
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4&s=40", AvatarImages.sizedUrl("https://avatars.githubusercontent.com/u/1?s=460&v=4", 40))
    }

    private var downloads = ArrayList<String>()
    private var failDownloads = false

    private fun png(color: Color): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            this.color = color
            fillRect(0, 0, 8, 8)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Before
    fun fakeNetwork() {
        AvatarImages.reset()
        downloads = ArrayList()
        failDownloads = false
        AvatarImages.download = { url ->
            downloads += url
            if (failDownloads) throw java.io.IOException("HTTP 503") else png(Color.RED)
        }
        // No IDE application in unit tests: run "background" and "EDT" work inline.
        AvatarImages.background = { it() }
        AvatarImages.onEdt = { it() }
        AvatarImages.clock = { 0L }
    }

    @After
    fun forget() = AvatarImages.reset()

    @Test
    fun `an avatar is downloaded once`() {
        var first: Any? = null
        var second: Any? = null
        AvatarImages.load(avatar) { first = it }
        AvatarImages.load(avatar) { second = it }
        assertTrue(first is BufferedImage)
        assertSame(first, second)
        assertEquals(listOf(avatar), downloads)
        assertSame(first, AvatarImages.cached(avatar))
    }

    @Test
    fun `a failed or bogus download gives no image and is not hammered`() {
        failDownloads = true
        var result: Any? = "untouched"
        AvatarImages.load(avatar) { result = it }
        assertNull(result)
        AvatarImages.load(avatar) { result = it }
        assertEquals("the failure is remembered for a while", 1, downloads.size)
        // Later on it is tried again.
        failDownloads = false
        AvatarImages.clock = { 10 * 60 * 1000L }
        AvatarImages.load(avatar) { result = it }
        assertTrue(result is BufferedImage)

        AvatarImages.download = { "this is not an image".toByteArray() }
        AvatarImages.load("https://avatars.githubusercontent.com/u/2") { result = it }
        assertNull(result)
    }

    @Test
    fun `untrusted addresses are never requested`() {
        var result: Any? = "untouched"
        AvatarImages.load("https://evil.example/avatar.png") { result = it }
        assertNull(result)
        assertTrue(downloads.isEmpty())
    }

    // --- Components: what the preview actually shows ---

    private fun paint(icon: AvatarIcon): BufferedImage {
        val canvas = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        return canvas
    }

    private fun alpha(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) ushr 24

    @Test
    fun `the default avatar is a filled circle`() {
        val painted = paint(AvatarIcon("octocat", 40))
        assertEquals("corners stay transparent", 0, alpha(painted, 0, 0))
        assertEquals(0, alpha(painted, 39, 39))
        assertEquals("the middle is painted", 255, alpha(painted, 20, 4))
        // Two users get their own colour, the same user always the same.
        assertEquals(paint(AvatarIcon("octocat", 40)).getRGB(20, 4), painted.getRGB(20, 4))
    }

    @Test
    fun `a loaded avatar is drawn round`() {
        val icon = AvatarIcon("octocat", 40)
        icon.image = ImageIO.read(png(Color.RED).inputStream())
        val painted = paint(icon)
        assertEquals(0, alpha(painted, 0, 0))
        assertEquals(Color.RED.rgb, painted.getRGB(20, 20))
    }

    @Test
    fun `the preview shows author and reviewers with avatars`() {
        val people = GhPrPeople(
            GitHubUser("octocat"),
            listOf(GhReviewer(GitHubUser("alice"), ReviewerStatus.APPROVED), GhReviewer(GitHubUser("bob"), ReviewerStatus.REQUESTED)),
        )
        var pending: ((List<GitHubUser>) -> Unit)? = null
        val panel = PullRequestPeoplePanel(people, resolve = { _, onResolved -> pending = onResolved }, cached = { null })

        // Before any avatar is known the pull request is fully usable: everybody is listed, with placeholders.
        assertEquals(listOf("octocat", "alice", "bob"), panel.chips.map { it.login })
        assertTrue(panel.chips.none { it.hasAvatarImage })
        assertTrue(panel.chips[0].text.contains("octocat"))
        assertTrue(panel.chips[1].text.contains("approved"))
        assertTrue(panel.chips[2].text.contains("pending"))
        assertTrue(downloads.isEmpty())

        // Avatars arrive: octocat and alice have one, bob has none.
        pending!!(listOf(GitHubUser("octocat", avatar), GitHubUser("alice", "https://avatars.githubusercontent.com/u/2?v=4"), GitHubUser("bob")))
        assertEquals(listOf(true, true, false), panel.chips.map { it.hasAvatarImage })
        assertEquals(2, downloads.size)
    }

    @Test
    fun `an avatar that fails to load leaves the default one and the preview intact`() {
        failDownloads = true
        val people = GhPrPeople(GitHubUser("octocat"), listOf(GhReviewer(GitHubUser("alice"), ReviewerStatus.COMMENTED)))
        val panel = PullRequestPeoplePanel(
            people,
            resolve = { _, onResolved -> onResolved(listOf(GitHubUser("octocat", avatar), GitHubUser("alice", "https://evil.example/a.png"))) },
            cached = { null },
        )
        assertEquals(listOf("octocat", "alice"), panel.chips.map { it.login })
        assertTrue(panel.chips.none { it.hasAvatarImage })
        // The placeholder still paints.
        assertEquals(255, alpha(paint(AvatarIcon("octocat", 40)), 20, 4))
    }

    @Test
    fun `users seen before show their avatar straight away`() {
        AvatarImages.load(AvatarImages.sizedUrl(avatar, UserChip.DEFAULT_SIZE * 2)) {}
        val panel = PullRequestPeoplePanel(
            GhPrPeople(GitHubUser("octocat"), emptyList()), resolve = { _, _ -> }, cached = { GitHubUser(it, avatar) },
        )
        assertTrue(panel.chips.single().hasAvatarImage)
        assertEquals(1, downloads.size)
    }

    @Test
    fun `nobody to show means no strip content`() {
        var asked = false
        val panel = PullRequestPeoplePanel(GhPrPeople(null, emptyList()), resolve = { _, _ -> asked = true }, cached = { null })
        assertTrue(panel.chips.isEmpty())
        assertEquals(0, panel.componentCount)
        assertFalse(asked)
    }
}
