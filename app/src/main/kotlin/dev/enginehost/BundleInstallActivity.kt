package dev.enginehost

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Installs a bundle from a file already on the device, for development.
 *
 * The catalog fetches releases from GitHub without credentials, and GitHub
 * allows sixty such requests an hour per address; a day of reinstalling
 * runs out of them. A developer with `gh` can download the same bundle
 * authenticated, push it with adb, and fire
 * `am start -a dev.enginehost.INSTALL_BUNDLE --es path /sdcard/Download/x.enginehost.tar.xz`.
 *
 * Only a debuggable build answers. The file goes through exactly the same
 * signature verification and the same trust prompt as a downloaded one, so
 * this path grants nothing the catalog does not.
 */
class BundleInstallActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val path = intent.getStringExtra(EXTRA_PATH)
        if (!debuggable || path == null || !File(path).isFile) {
            Log.w(TAG, "Bundle install request refused: debuggable=$debuggable path=$path")
            finish()
            return
        }
        PluginInstaller.installFromFile(this, Uri.fromFile(File(path))) { message ->
            Log.e(TAG, message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
        // The installer opens the trust screen itself when the bundle verifies;
        // this activity has nothing to show meanwhile.
        finish()
    }

    companion object {
        private const val TAG = "enginehost"
        const val EXTRA_PATH = "path"
    }
}
