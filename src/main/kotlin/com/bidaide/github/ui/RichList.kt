package com.bidaide.github.ui

import com.bidaide.BidaideBundle
import com.bidaide.gh.GitHubUser
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** "8 min ago", "2 days ago", "on Aug 28": how GitHub dates things in lists, in the language of the texts. */
object TimeAgo {

    fun format(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val then = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val elapsed = Duration.between(then, now)
        return when {
            elapsed.isNegative || elapsed.toMinutes() < 1 -> BidaideBundle.message("github.time.now")
            elapsed.toMinutes() < 60 -> BidaideBundle.message("github.time.minutes", elapsed.toMinutes())
            elapsed.toHours() < 24 -> BidaideBundle.message("github.time.hours", elapsed.toHours())
            elapsed.toDays() == 1L -> BidaideBundle.message("github.time.yesterday")
            elapsed.toDays() < 30 -> BidaideBundle.message("github.time.days", elapsed.toDays())
            else -> {
                val date = then.atZone(zone)
                val pattern = if (date.year == now.atZone(zone).year) "github.time.datePattern" else "github.time.datePatternWithYear"
                val text = DateTimeFormatter.ofPattern(BidaideBundle.message(pattern), BidaideBundle.locale()).format(date).replace(".", "")
                BidaideBundle.message("github.time.date", text)
            }
        }
    }
}

/** Colours with a meaning, readable on light and dark themes. */
object Tones {
    val GREEN: Color = JBColor(0x1A7F37, 0x57AB5A)
    val RED: Color = JBColor(0xCF222E, 0xE5534B)
    val AMBER: Color = JBColor(0x9A6700, 0xC69026)
    val PURPLE: Color = JBColor(0x8250DF, 0x986EE2)
    val BLUE: Color = JBColor(0x0969DA, 0x539BF5)
    val GRAY: Color = JBColor(0x6E7781, 0x8B949E)

    /** A GitHub label colour ("d73a4a"), or grey when it is missing or malformed. */
    fun ofLabel(hex: String?): Color =
        hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { Color(it) } ?: GRAY
}

/** A small rounded tag: a tinted fill, an outline in the full colour and normal text, as GitHub draws labels in dark mode. */
class Pill(text: String, private val tone: Color, private val solid: Boolean = false) : JLabel(text, SwingConstants.CENTER) {
    init {
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(1, 7)
        isOpaque = false
        if (solid) foreground = Color.WHITE
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            g2.color = if (solid) tone else Color(tone.red, tone.green, tone.blue, 46)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (!solid) {
                g2.color = Color(tone.red, tone.green, tone.blue, 170)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getMaximumSize(): Dimension = preferredSize
}

/** Up to [MAX] round avatars, overlapping like the participants of a GitHub notification. */
class AvatarStack(private val size: Int = 20) : JComponent() {
    var icons: List<AvatarIcon> = emptyList()
        set(value) {
            field = value.take(MAX)
            revalidate()
        }

    private val step get() = size - size / 4

    override fun getPreferredSize() = Dimension(if (icons.isEmpty()) 0 else size + step * (icons.size - 1), size)
    override fun getMaximumSize(): Dimension = preferredSize
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        // Right to left, so the first avatar ends up on top.
        for (index in icons.indices.reversed()) icons[index].paintIcon(this, g, index * step, (height - size) / 2)
    }

    private companion object {
        const val MAX = 3
    }
}

/** What one row of a [RichRowRenderer] list shows. Only [title] is required. */
class RichRow(
    val title: String,
    val icon: Icon? = null,
    /** A blue dot before the icon: something new the user has not seen. */
    val unread: Boolean = false,
    val bold: Boolean = true,
    /** Small grey line above the title, e.g. "owner/repo #12". */
    val overline: String = "",
    /** Small grey line below the title. */
    val subtitle: String = "",
    val labels: List<Tag> = emptyList(),
    /** Status tags on the right-hand side. */
    val badges: List<Tag> = emptyList(),
    /** Plain grey text on the right, e.g. why a notification arrived. */
    val note: String = "",
    val people: List<GitHubUser> = emptyList(),
    val time: String = "",
) {
    class Tag(val text: String, val tone: Color)
}

/**
 * Two-line list rows in the style of GitHub's own lists: state icon, title with labels, a line of details, and on
 * the right status, people and time. Avatars are fetched in the background and the list repaints as they arrive.
 */
class RichRowRenderer<T>(private val describe: (T) -> RichRow) : ListCellRenderer<T> {
    private val dot = object : JComponent() {
        var on = false
        override fun getPreferredSize() = Dimension(JBUI.scale(14), JBUI.scale(18))
        override fun paintComponent(g: Graphics) {
            if (!on) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Tones.BLUE
            val d = JBUI.scale(8)
            g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
            g2.dispose()
        }
    }
    private val iconLabel = JLabel()
    private val overline = JLabel().apply { font = JBUI.Fonts.smallFont() }
    private val title = JLabel()
    private val labels = Box.createHorizontalBox()
    private val subtitle = JLabel().apply { font = JBUI.Fonts.smallFont() }
    private val badges = Box.createHorizontalBox()
    private val note = JLabel().apply { font = JBUI.Fonts.smallFont() }
    private val avatars = AvatarStack()
    private val time = JLabel().apply {
        font = JBUI.Fonts.smallFont()
        horizontalAlignment = SwingConstants.RIGHT
    }

    private val titleLine = Box.createHorizontalBox().apply {
        add(title)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(labels)
        add(Box.createHorizontalGlue())
    }
    private val text = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        for (line in listOf(overline, titleLine, subtitle)) {
            line.alignmentX = Component.LEFT_ALIGNMENT
            add(line)
        }
    }
    private val leading = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(Box.createHorizontalBox().apply {
            add(dot)
            add(iconLabel)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
        }, BorderLayout.NORTH)
    }
    private val trailing = Box.createHorizontalBox().apply {
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(badges)
        add(note)
        add(Box.createHorizontalStrut(JBUI.scale(10)))
        add(avatars)
        add(Box.createHorizontalStrut(JBUI.scale(10)))
        add(time)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(leading, BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
        add(trailing, BorderLayout.EAST)
    }

    private val icons = HashMap<String, AvatarIcon>()
    private val requested = HashSet<String>()

    /** Lets a line give way sideways (with an ellipsis) instead of pushing the rest out of the row, but never vertically. */
    private fun shrinkable(label: JLabel) {
        label.minimumSize = null
        label.minimumSize = Dimension(0, label.preferredSize.height)
    }

    override fun getListCellRendererComponent(list: JList<out T>, value: T, index: Int, selected: Boolean, focused: Boolean): Component {
        val row = describe(value)
        val foreground = if (selected) UIUtil.getListSelectionForeground(list.hasFocus()) else UIUtil.getListForeground()
        val muted = if (selected) foreground else UIUtil.getContextHelpForeground()

        dot.on = row.unread
        iconLabel.icon = row.icon
        overline.text = row.overline
        overline.isVisible = row.overline.isNotEmpty()
        overline.foreground = muted
        title.text = row.title
        title.font = list.font.deriveFont(if (row.bold) Font.BOLD else Font.PLAIN)
        title.foreground = foreground
        subtitle.text = row.subtitle
        subtitle.isVisible = row.subtitle.isNotEmpty()
        subtitle.foreground = muted
        note.text = row.note
        note.foreground = muted
        time.text = row.time
        time.foreground = muted
        time.preferredSize = Dimension(JBUI.scale(TIME_WIDTH), time.preferredSize.height.coerceAtLeast(1))

        listOf(overline, title, subtitle).forEach(::shrinkable)

        fill(labels, row.labels)
        fill(badges, row.badges)
        avatars.icons = row.people.map { avatar(it, list) }

        panel.background = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else UIUtil.getListBackground()
        panel.border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(7, 6, 7, 10))
        return panel
    }

    private fun fill(box: Box, tags: List<RichRow.Tag>) {
        box.removeAll()
        for (tag in tags) {
            box.add(Pill(tag.text, tag.tone))
            box.add(Box.createHorizontalStrut(JBUI.scale(4)))
        }
    }

    /** The avatar for a user, asking for its picture the first time it is needed. */
    private fun avatar(user: GitHubUser, list: JList<*>): AvatarIcon {
        val icon = icons.getOrPut(user.login.lowercase()) { AvatarIcon(user.login, avatars.preferredSize.height.coerceAtLeast(20)) }
        if (icon.hasImage) return icon
        val url = AvatarImages.sizedUrl(user.avatarUrl ?: AvatarImages.profilePicture(user.login) ?: return icon, 40)
        AvatarImages.cached(url)?.let {
            icon.image = it
            return icon
        }
        // Once per address while it loads. A failure frees it again, so a later paint retries once AvatarImages
        // allows it (a network hiccup when the list first showed must not leave initials for good); the failed
        // callback repaints nothing, so this never loops.
        if (requested.add(url)) {
            AvatarImages.load(url) { image ->
                if (image != null) {
                    icon.image = image
                    list.repaint()
                } else {
                    requested.remove(url)
                }
            }
        }
        return icon
    }

    private companion object {
        const val TIME_WIDTH = 92
    }
}
