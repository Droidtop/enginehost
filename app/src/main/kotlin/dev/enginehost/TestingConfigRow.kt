package dev.enginehost

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The row Game setup and a game's own screen show while that game has a
 * pending testing configuration (see [TestingConfigStore]): it says so, and
 * one press offers both choices, Keep and Discard.
 */
object TestingConfigRow {
    private const val TAG = "enginehost.testing-config-row"

    /** Puts the row right after [after], or removes it when nothing is pending. [onChanged] runs after Keep or Discard. */
    fun show(activity: Activity, after: View, folder: File, onChanged: () -> Unit) {
        val parent = after.parent as? ViewGroup ?: return
        parent.findViewWithTag<View>(TAG)?.let(parent::removeView)
        val store = TestingConfigStore.of(activity)
        val pending = store.get(folder) ?: return
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(pending.savedAt))
        val row = LayoutInflater.from(activity).inflate(R.layout.item_action_button, parent, false) as Button
        row.tag = TAG
        row.text = activity.getString(R.string.testing_pending, stamp)
        row.setOnClickListener {
            Sheet(activity)
                .title(R.string.testing_pending_title)
                .message(activity.getString(R.string.testing_pending_message, stamp))
                .choice(R.string.testing_keep) {
                    val message = runCatching { store.keep(folder) }.fold(
                        onSuccess = { activity.getString(R.string.testing_kept, CONFIG_FILE_NAME) },
                        onFailure = { it.message ?: activity.getString(R.string.could_not_save) },
                    )
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    onChanged()
                }
                .choice(R.string.testing_discard) {
                    store.discard(folder)
                    Toast.makeText(activity, R.string.testing_discarded, Toast.LENGTH_LONG).show()
                    onChanged()
                }
                .show()
        }
        parent.addView(row, parent.indexOfChild(after) + 1)
    }
}
