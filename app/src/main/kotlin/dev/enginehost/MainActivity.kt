package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import java.io.File

/**
 * The basic, secondary UI -- for picking a game folder by hand and
 * launching it, or getting to controller config. [LaunchActivity]'s Intent
 * contract is the primary/intended way in (a caller passing a real path);
 * this exists for the case where there's no caller, just a person holding
 * the device. Deliberately plain -- a path field, not a real folder
 * browser yet (a real one is follow-up work, not this pass).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pathField = findViewById<EditText>(R.id.pathField)
        findViewById<Button>(R.id.launchButton).setOnClickListener {
            val path = pathField.text.toString().trim()
            if (path.isNotEmpty()) {
                GameRunner.run(this, File(path))
            }
        }
        findViewById<Button>(R.id.controllerConfigButton).setOnClickListener {
            startActivity(Intent(this, ControllerConfigActivity::class.java))
        }
    }
}
