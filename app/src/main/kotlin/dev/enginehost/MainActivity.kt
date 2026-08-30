package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

/**
 * Configuration-first home screen with a deliberately minimal test launcher.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.createConfigButton).setOnClickListener {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
        findViewById<Button>(R.id.pickAndLaunchButton).setOnClickListener {
            openTestPickerWhenAllowed()
        }
        findViewById<Button>(R.id.controllerConfigButton).setOnClickListener {
            startActivity(Intent(this, ControllerConfigActivity::class.java))
        }
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_NATIVE_FILES) {
            if (StorageFolder.hasNativePathAccess()) {
                startActivityForResult(StorageFolder.pickerIntent(), REQUEST_TEST_GAME)
            } else {
                Toast.makeText(
                    this,
                    "Native file access is required only for test launching; config editing still works without it",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        if (requestCode != REQUEST_TEST_GAME || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        val folder = StorageFolder.absolutePath(uri)
        if (folder == null) {
            Toast.makeText(
                this,
                "Native test launch needs a folder on primary shared storage",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        GameRunner.run(this, folder)
    }

    private fun openTestPickerWhenAllowed() {
        if (StorageFolder.hasNativePathAccess()) {
            startActivityForResult(StorageFolder.pickerIntent(), REQUEST_TEST_GAME)
        } else {
            StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
        }
    }

    companion object {
        private const val REQUEST_TEST_GAME = 10
        private const val REQUEST_NATIVE_FILES = 11
    }
}
