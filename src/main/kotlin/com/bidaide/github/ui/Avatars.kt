package com.bidaide.github.ui

import com.bidaide.gh.GitHubUser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.util.io.HttpRequests
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JLabel

/**
 * Downloads avatar images, once each, off the EDT. Avatars are public files on GitHub's image host, so they are
 * fetched without credentials: no token ever leaves `gh`.
 */
object AvatarImages {
    private val LOG = logger<AvatarImages>()
    private const val RETRY_AFTER_MILLIS = 5 * 60 * 1000L

    private val images = ConcurrentHashMap<String, Image>()

    /** When a download last failed, so a broken avatar is not requested again on every render. */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Callbacks waiting for a download already under way; only touched on the EDT. */
    private val waiting = HashMap<String, MutableList<(Image?) -> Unit>>()

    // Seams for tests, which have neither a network nor an IDE application.
    internal var download: (String) -> ByteArray = { url -> HttpRequests.request(url).connectTimeout(5_000).readTimeout(10_000).readBytes(null) }
    internal var background: (() -> Unit) -> Unit = { work -> ApplicationManager.getApplication().executeOnPooledThread(work) }
    internal var onEdt: (() -> Unit) -> Unit = { work -> ApplicationManager.getApplication().invokeLater(work, ModalityState.any()) }
    internal var clock: () -> Long = System::currentTimeMillis

    internal fun reset() {
        images.clear()
        failures.clear()
        waiting.clear()
    }

    /**
     * The URL comes from GitHub's API but is still external data: only HTTPS addresses on GitHub's own hosts are
     * ever requested.
     */
    fun isTrusted(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.userInfo == null &&
            (host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
    }

    /** GitHub scales avatars on request with `s=`; asking for the size shown keeps downloads small. */
    fun sizedUrl(url: String, pixels: Int): String {
        val base = url.substringBefore('#')
        val query = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("s=") && !it.startsWith("size=") }
        return base.substringBefore('?') + "?" + (query + "s=$pixels").joinToString("&")
    }

    /**
     * Where GitHub serves the picture of any account, with no API call: enough for lists, where only the login is
     * known. Null for names that are not logins. The image host answers it directly and honours `s=`, unlike
     * `github.com/<login>.png`, whose redirect drops the size.
     */
    fun profilePicture(login: String): String? =
        if (com.bidaide.gh.GhPeople.isValidLogin(login)) "https://avatars.githubusercontent.com/${login.replace("[bot]", "")}" else null

    /** The image if it has been downloaded already. */
    fun cached(url: String): Image? = images[url]

    /**
     * Must be called on the EDT. [onDone] runs on the EDT with the image, or null when it could not be had
     * (untrusted address, network error, not an image); the caller then keeps its placeholder.
     */
    fun load(url: String, onDone: (Image?) -> Unit) {
        images[url]?.let { return onDone(it) }
        val failedAt = failures[url]
        if (!isTrusted(url) || (failedAt != null && clock() - failedAt < RETRY_AFTER_MILLIS)) return onDone(null)
        waiting[url]?.let {
            it += onDone
            return
        }
        waiting[url] = mutableListOf(onDone)
        background {
            val image = try {
                ImageIO.read(ByteArrayInputStream(download(url)))
            } catch (e: Exception) {
                // The URL can carry nothing secret, but it is external data: keep it out of the log.
                LOG.debug("Avatar download failed: ${e.javaClass.simpleName}")
                null
            }
            if (image != null) images[url] = image else failures[url] = clock()
            onEdt { waiting.remove(url)?.forEach { callback -> callback(image) } }
        }
    }
}

/** A round avatar. Until (or unless) its image arrives it shows the initial of the login on a colour of its own. */
class AvatarIcon(private val login: String, private val size: Int) : Icon {
    var image: Image? = null

    val hasImage: Boolean get() = image != null

    override fun getIconWidth() = size
    override fun getIconHeight() = size

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val circle = Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())
            val picture = image
            if (picture != null) {
                g.clip(circle)
                g.drawImage(picture, x, y, size, size, null)
            } else {
                g.color = PALETTE[Math.floorMod(login.lowercase().hashCode(), PALETTE.size)]
                g.fill(circle)
                g.color = Color.WHITE
                g.font = (component?.font ?: g.font).deriveFont(Font.BOLD, size * 0.52f)
                val initial = login.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                val metrics = g.fontMetrics
                g.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2f, y + (size - metrics.height) / 2f + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
    }

    private companion object {
        val PALETTE = listOf(
            JBColor(0x3574F0, 0x548AF7), JBColor(0x208A3C, 0x57965C), JBColor(0xC77D0A, 0xD6AE58),
            JBColor(0x834DF0, 0xA571E6), JBColor(0xDB3B4B, 0xDB5C5C), JBColor(0x0A7B83, 0x24A394),
        )
    }
}

/**
 * `[avatar] login suffix`. Shows the placeholder at once and swaps in the picture when it arrives; a picture that
 * never arrives just leaves the placeholder.
 */
class UserChip(user: GitHubUser, private val suffix: String = "", size: Int = DEFAULT_SIZE, showLogin: Boolean = true) : JLabel() {
    private val avatar = AvatarIcon(user.login, size)
    private val pixels = size * 2 // enough for HiDPI screens
    private var shownUrl: String? = null

    val login: String = user.login
    val hasAvatarImage: Boolean get() = avatar.hasImage

    init {
        icon = avatar
        text = if (!showLogin) null else if (suffix.isEmpty()) user.login else "<html>${Html.esc(user.login)} <font color=\"gray\">${Html.esc(suffix)}</font></html>"
        iconTextGap = size / 3
        toolTipText = user.login
        show(user)
    }

    /** Call on the EDT, again whenever more becomes known about the user (its avatar URL, typically). */
    fun show(user: GitHubUser) {
        val url = user.avatarUrl?.let { AvatarImages.sizedUrl(it, pixels) } ?: return
        if (url == shownUrl) return
        shownUrl = url
        AvatarImages.cached(url)?.let {
            avatar.image = it
            return
        }
        AvatarImages.load(url) { image ->
            // The chip may have been replaced or re-pointed while the download ran.
            if (image != null && shownUrl == url) {
                avatar.image = image
                if (isDisplayable) repaint()
            }
        }
    }

    companion object {
        const val DEFAULT_SIZE = 20
    }
}
