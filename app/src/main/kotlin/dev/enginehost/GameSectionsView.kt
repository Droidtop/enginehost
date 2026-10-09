package dev.enginehost

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Draws a game's [GameSections] as rows under its status and answers their taps
 * (Droidtop/tracker#235, #237): the core and its builds, an update to it, the
 * core's options, the saves, and the play history. Every choice a person makes
 * here is stored as a per-game override in the library database
 * ([GameOverrides]); nothing is written into the game folder.
 */
class GameSectionsView(
    private val activity: EnginehostActivity,
    private val container: LinearLayout,
    private val folder: File,
    /** Called after something changed that the page must read again. */
    private val onChanged: () -> Unit,
) {
    private val library = GameLibraryStore(activity.applicationContext)

    fun show(sections: GameSections) {
        container.removeAllViews()
        sections.core?.let { addCore(sections, it) }
        sections.update?.let { addUpdate(it, sections.core) }
        sections.options.forEach { addOption(it) }
        if (sections.core != null) addControls(sections)
        sections.saves?.let { addSaves(it) }
        addHistory(sections.history)
    }

    private fun row(title: CharSequence, value: CharSequence, chevron: Boolean = true, onClick: (() -> Unit)? = null): View {
        val view = LayoutInflater.from(activity).inflate(R.layout.item_game_section, container, false)
        view.findViewById<TextView>(R.id.sectionTitle).text = title
        view.findViewById<TextView>(R.id.sectionValue).text = value
        view.findViewById<View>(R.id.sectionChevron).visibility = if (chevron) View.VISIBLE else View.GONE
        if (onClick == null) {
            view.isFocusable = false
            view.isClickable = false
        } else {
            view.setOnClickListener { onClick() }
        }
        container.addView(view)
        return view
    }

    // ---- Core -------------------------------------------------------------------

    private fun addCore(sections: GameSections, core: InstalledPlugin) {
        val version = PluginVersions.display(core.info.pluginVersion)
        val which = activity.getString(if (sections.pinned == null) R.string.core_newest else R.string.core_fixed)
        row(
            activity.getString(R.string.section_core),
            activity.getString(R.string.core_value, version, which),
        ) { changeCore(sections) }
    }

    private fun changeCore(sections: GameSections) {
        val sheet = Sheet(activity).title(R.string.section_core)
        sheet.choice(
            activity.getString(R.string.core_use_newest),
            activity.getString(R.string.core_use_newest_detail),
            current = sections.pinned == null,
        ) { setOverride("pluginVersion", null) }
        sections.compatible.forEach { plugin ->
            val version = plugin.info.pluginVersion.toString()
            sheet.choice(
                PluginVersions.display(plugin.info.pluginVersion),
                activity.getString(if (plugin.isolatable) R.string.badge_sandboxed else R.string.badge_unsandboxed),
                current = sections.pinned == version,
            ) { setOverride("pluginVersion", version) }
        }
        sheet.show()
    }

    private fun addUpdate(update: AvailablePlugin, core: InstalledPlugin?) {
        row(
            activity.getString(R.string.section_core_update),
            activity.getString(R.string.core_update_value, PluginVersions.display(update.info.pluginVersion)),
        ) { installUpdate(update, core) }
    }

    /** Installs the newer build through the quiet path Plugins' Update uses; approval carries over from the same key. */
    private fun installUpdate(update: AvailablePlugin, core: InstalledPlugin?) {
        Toast.makeText(activity, R.string.updating, Toast.LENGTH_SHORT).show()
        Thread {
            val failure = runCatching { PluginInstaller.installQuietly(activity, update) }.exceptionOrNull()
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (failure != null) {
                    Toast.makeText(activity, failure.message ?: core?.bundleId.orEmpty(), Toast.LENGTH_LONG).show()
                }
                onChanged()
            }
        }.start()
    }

    // ---- Options ----------------------------------------------------------------

    private fun addOption(entry: OptionRow) {
        val source = activity.getString(
            when (entry.source) {
                OptionSource.ENGINE_DEFAULT -> R.string.option_source_default
                OptionSource.GAME_FOLDER -> R.string.option_source_folder
                OptionSource.THIS_GAME -> R.string.option_source_game
            },
        )
        row(entry.option.label, activity.getString(R.string.option_value, valueText(entry), source)) { editOption(entry) }
    }

    private fun valueText(entry: OptionRow): String {
        val value = entry.value ?: return activity.getString(R.string.option_not_set)
        return when {
            entry.option.type == "boolean" && value is Boolean ->
                activity.getString(if (value) R.string.option_on else R.string.option_off)
            entry.option.type == "choice" ->
                entry.option.choices.firstOrNull { it.first == value.toString() }?.second ?: value.toString()
            else -> value.toString()
        }
    }

    private fun editOption(entry: OptionRow) {
        val key = "options.${entry.option.key}"
        val sheet = Sheet(activity).title(entry.option.label)
        if (entry.option.description.isNotBlank()) sheet.message(entry.option.description)
        val editable = !entry.option.repeats
        when {
            editable && entry.option.type == "boolean" -> {
                sheet.choice(activity.getString(R.string.option_on), current = entry.value == true) { setOverride(key, true) }
                sheet.choice(activity.getString(R.string.option_off), current = entry.value == false) { setOverride(key, false) }
            }
            editable && entry.option.type == "choice" -> entry.option.choices.forEach { (value, label) ->
                sheet.choice(label, current = entry.value?.toString() == value) { setOverride(key, value) }
            }
            editable && (entry.option.type == "number" || entry.option.type == "string") -> {
                val input = EditText(activity).apply {
                    setSingleLine()
                    setText(entry.value?.toString().orEmpty())
                    setSelection(text.length)
                }
                sheet.content(input)
                sheet.choice(activity.getString(R.string.filter_apply)) {
                    val text = input.text.toString().trim()
                    val parsed: Any? = when {
                        text.isEmpty() -> null
                        entry.option.type == "number" -> text.toLongOrNull() ?: text.toDoubleOrNull()
                        else -> text
                    }
                    if (text.isNotEmpty() && parsed == null) {
                        Toast.makeText(activity, R.string.option_not_a_number, Toast.LENGTH_LONG).show()
                    } else {
                        setOverride(key, parsed)
                    }
                }
            }
            else -> sheet.message(activity.getString(R.string.option_set_in_setup))
        }
        if (entry.source == OptionSource.THIS_GAME) {
            sheet.choice(activity.getString(R.string.option_reset), tone = Sheet.Tone.DANGER) { setOverride(key, null) }
        }
        sheet.show()
    }

    // ---- Controls ---------------------------------------------------------------

    private fun addControls(sections: GameSections) {
        row(
            activity.getString(R.string.section_controls),
            activity.getString(if (sections.ownControls) R.string.controls_own else R.string.controls_follow),
        ) {
            activity.startActivity(
                ControllerConfigActivity.intent(activity, sections.controlsScope, folder.absolutePath, editGame = true),
            )
        }
    }

    // ---- Saves and history ------------------------------------------------------

    private fun addSaves(saves: SavesInfo) {
        val folderPath = saves.folder?.absolutePath.orEmpty()
        val value = if (saves.besideGame) {
            activity.getString(R.string.saves_beside_game)
        } else if (saves.lastWrittenAt > 0) {
            activity.getString(
                R.string.saves_value,
                DateUtils.getRelativeTimeSpanString(saves.lastWrittenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                Formatter.formatShortFileSize(activity, saves.bytes),
            )
        } else {
            activity.getString(R.string.saves_none_yet)
        }
        row(activity.getString(R.string.section_saves), value) {
            Sheet(activity).title(R.string.section_saves).message(folderPath).choice(R.string.ok) {}.show()
        }
    }

    private fun addHistory(history: PlayHistory) {
        val value = if (history.launches == 0 && history.playedMs == 0L) {
            activity.getString(R.string.history_none)
        } else {
            listOfNotNull(
                activity.getString(R.string.history_played, playTime(history.playedMs)),
                activity.resources.getQuantityString(R.plurals.history_starts, history.launches, history.launches),
                history.lastPlayedAt.takeIf { it > 0 }?.let {
                    activity.getString(
                        R.string.history_last,
                        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    )
                },
                when (history.lastExit) {
                    PlayHistory.EXIT_CLEAN -> activity.getString(R.string.history_exit_clean)
                    PlayHistory.EXIT_CRASHED -> activity.getString(R.string.history_exit_crashed)
                    PlayHistory.EXIT_FAILED -> activity.getString(R.string.history_exit_failed)
                    else -> null
                },
            ).joinToString(" · ")
        }
        row(activity.getString(R.string.section_history), value, chevron = false)
    }

    private fun playTime(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) activity.getString(R.string.play_time_m, minutes.toInt())
        else activity.getString(R.string.play_time_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
    }

    private fun setOverride(key: String, value: Any?) {
        Thread {
            runCatching { library.setOverride(folder, key, value) }
            activity.runOnUiThread { if (!activity.isDestroyed) onChanged() }
        }.apply { isDaemon = true }.start()
    }
}
