package dev.enginehost

import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared screen furniture.
 *
 * Every screen below Home carries a back button in its title row. The system
 * gesture and a pad's cancel button already go back; a person holding the
 * device with one hand, or one who has never met a gesture bar, needs
 * something to press. Screens that have no back button simply have no view
 * with this id, so calling this is always safe.
 */
fun AppCompatActivity.wireBackButton() {
    findViewById<View>(R.id.backButton)?.setOnClickListener { finish() }
}
