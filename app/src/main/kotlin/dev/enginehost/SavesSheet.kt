package dev.enginehost

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * The game page's Saves row (Droidtop/tracker#238): where the game's saves are, then
 * Back up now, Restore a backup and Copy to a folder ([SaveBackups]). Every copy runs off
 * the main thread with a running count; a restore asks first and backs the current saves up
 * before it. A game whose saves sit among its own files with no folder the core declares
 * for them says so and offers nothing: the game's own files are never copied.
 */
class SavesSheet(
    private val activity: EnginehostActivity,
    private val game: File,
    private val saves: SavesInfo,
    /** Called after a restore, so the page reads the saves again. */
    private val onChanged: () -> Unit,
) {
    fun show() {
        val sheet = Sheet(activity).title(R.string.section_saves).message(where())
        val backups = saves.backups
        if (saves.sources.isEmpty() || backups == null) {
            sheet.choice(R.string.ok) {}.show()
            return
        }
        sheet.choice(activity.getString(R.string.saves_back_up)) { backUp(backups) }
            .choice(activity.getString(R.string.saves_restore)) { chooseBackup(backups) }
            .choice(activity.getString(R.string.saves_copy), activity.getString(R.string.saves_copy_detail)) { pickDestination() }
            .show()
    }

    /** Where the saves are, in the words of the three placements; the full path is the next line. */
    private fun where(): String {
        val path = saves.sources.joinToString("\n") { it.absolutePath }.ifBlank { saves.folder?.absolutePath.orEmpty() }
        return when {
            saves.besideGame && saves.sources.isEmpty() -> activity.getString(R.string.saves_beside_no_folder, path)
            saves.besideGame -> activity.getString(R.string.saves_beside_folders, path)
            else -> path
        }
    }

    private fun backUp(backups: File) = run(R.string.saves_backing_up) { count ->
        val (_, copied) = SaveBackups.backUp(saves.sources, backups, System.currentTimeMillis(), count)
        activity.resources.getQuantityString(R.plurals.saves_backed_up, copied.files, copied.files) + skippedNote(copied)
    }

    /** The backups, newest first, read off the main thread; picking one asks before anything changes. */
    private fun chooseBackup(backups: File) {
        Thread {
            val list = runCatching { SaveBackups.list(backups) }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (list.isEmpty()) {
                    Sheet(activity).title(R.string.saves_restore).message(R.string.saves_no_backups).choice(R.string.ok) {}.show()
                    return@runOnUiThread
                }
                Sheet(activity).title(R.string.saves_restore).apply {
                    list.forEach { backup ->
                        choice(
                            DateUtils.formatDateTime(activity, backup.takenAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                            Formatter.formatShortFileSize(activity, backup.bytes),
                        ) { confirmRestore(backup, backups) }
                    }
                }.show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun confirmRestore(backup: SaveBackups.Backup, backups: File) {
        Sheet(activity)
            .title(R.string.saves_restore_title)
            .message(R.string.saves_restore_message)
            .choice(R.string.saves_restore_confirm) {
                run(R.string.saves_restoring) { count ->
                    val copied = SaveBackups.restore(backup.folder, saves.sources, backups, System.currentTimeMillis(), count)
                    activity.runOnUiThread(onChanged)
                    activity.resources.getQuantityString(R.plurals.saves_restored, copied.files, copied.files) + skippedNote(copied)
                }
            }
            .choice(android.R.string.cancel) {}
            .show()
    }

    /** The folder picker; the copy goes into a new folder named for the game inside the one chosen. */
    private fun pickDestination() {
        val key = "saves-copy-${System.nanoTime()}"
        var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
        launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
            launcher?.unregister()
            val uri = result.data?.data ?: return@register
            val destination = StorageFolder.absolutePath(uri)
            if (destination == null) {
                Toast.makeText(activity, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
                return@register
            }
            run(R.string.saves_copying) { count ->
                val name = activity.getString(R.string.saves_copy_folder, game.name)
                val (target, copied) = SaveBackups.copyTo(saves.sources, destination, name, count)
                activity.getString(R.string.saves_copied, target.absolutePath) + skippedNote(copied)
            }
        }
        launcher.launch(StorageFolder.pickerIntent())
    }

    /** Runs [work] off the main thread under a sheet that counts the files done, then says what happened. */
    private fun run(title: Int, work: ((Int) -> Unit) -> String) {
        val counter = TextView(activity).apply { setTextAppearance(R.style.TextAppearance_Enginehost_Body) }
        val progress = Sheet(activity).title(title).content(counter).show()
        Thread {
            val outcome = runCatching {
                work { files ->
                    activity.runOnUiThread { counter.text = activity.resources.getQuantityString(R.plurals.saves_files_done, files, files) }
                }
            }.getOrElse { ErrorText.of(activity, it, R.string.saves_failed) }
            activity.runOnUiThread {
                progress.dismiss()
                if (!activity.isDestroyed) Sheet(activity).title(title).message(outcome).choice(R.string.ok) {}.show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun skippedNote(copied: SaveBackups.Copied): String =
        if (copied.skipped == 0) "" else "\n" + activity.resources.getQuantityString(R.plurals.saves_skipped, copied.skipped, copied.skipped)
}
