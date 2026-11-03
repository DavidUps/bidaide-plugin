package com.bidaide.settings

import com.bidaide.BidaideBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.Locale

@Service(Service.Level.APP)
@State(name = "BidaideSettings", storages = [Storage("bidaide.xml")])
class BidaideSettings : PersistentStateComponent<BidaideSettings.State> {

    class State {
        /** Explicit path to the `gh` binary; blank means auto-detect. */
        var ghPath: String = ""

        /** Explicit path to the Android CLI `android` binary; blank means auto-detect. */
        var androidCliPath: String = ""

        /** Notify about review requests and about checks/reviews of the user's own pull requests. */
        var notificationsEnabled: Boolean = true
        var pollMinutes: Int = 5

        /** Where device screenshots and recordings are saved; blank means the Desktop. */
        var captureDir: String = ""

        /** User-defined ADB shell commands, one per line as `Name = command`; `{package}` is the app id. */
        var customAdbCommands: String = ""

        /** Device setting presets, one per line as `Name = key=value, key=value`. See DeviceProfile. */
        var deviceProfiles: String = ""

        /** Action ids pinned at the top of the ADB popup, one per line, in display order. See AdbFavorites. */
        var favoriteAdbActions: String = ""

        /** [BidaideLanguage] name of the language of the plugin's texts; blank means the IDE's. */
        var language: String = ""

        /** Canned review comments, one per line as `Name = text`; `\n` in the text is a line break. */
        var savedReplies: String = ""
    }

    private var state = State()

    var ghPath: String
        get() = state.ghPath
        set(value) {
            state.ghPath = value.trim()
        }

    var androidCliPath: String
        get() = state.androidCliPath
        set(value) {
            state.androidCliPath = value.trim()
        }

    var notificationsEnabled: Boolean
        get() = state.notificationsEnabled
        set(value) {
            state.notificationsEnabled = value
        }

    var pollMinutes: Int
        get() = state.pollMinutes.coerceIn(1, 120)
        set(value) {
            state.pollMinutes = value.coerceIn(1, 120)
        }

    var captureDir: String
        get() = state.captureDir
        set(value) {
            state.captureDir = value.trim()
        }

    var customAdbCommands: String
        get() = state.customAdbCommands
        set(value) {
            state.customAdbCommands = value
        }

    var deviceProfiles: String
        get() = state.deviceProfiles
        set(value) {
            state.deviceProfiles = value
        }

    var favoriteAdbActions: String
        get() = state.favoriteAdbActions
        set(value) {
            state.favoriteAdbActions = value
        }

    var savedReplies: String
        get() = state.savedReplies
        set(value) {
            state.savedReplies = value
        }

    var language: BidaideLanguage
        get() = BidaideLanguage.entries.firstOrNull { it.name == state.language } ?: BidaideLanguage.AUTO
        set(value) {
            state.language = if (value == BidaideLanguage.AUTO) "" else value.name
        }

    override fun getState() = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): BidaideSettings = service()

        /** Parses `Name = value` lines; blank lines, lines starting with # and lines without `=` are skipped. */
        fun namedLines(text: String): List<Pair<String, String>> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toList()
    }
}

/** Language of the plugin's texts. [AUTO] follows the IDE, and falls back to English when there is no translation. */
enum class BidaideLanguage(val locale: Locale?, private val nativeName: String?) {
    AUTO(null, null),
    ENGLISH(Locale.ENGLISH, "English"),
    SPANISH(Locale.forLanguageTag("es"), "Español"),
    FRENCH(Locale.FRENCH, "Français"),
    ITALIAN(Locale.ITALIAN, "Italiano");

    /** Each language in its own words, so it can be found whatever language the settings are shown in. */
    val displayName: String get() = nativeName ?: BidaideBundle.message("settings.language.auto")
}
