package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Below API 30, stands in once for a bundled Activity whose Context Android
 * built before the bundle's resources were attached (see PluginResources):
 * it starts the same intent again, passing on the result the launch screen
 * waits for, and goes. The second start is built with the bundle's APK in
 * its Resources, and EnginehostComponentFactory then constructs the plugin's
 * own Activity.
 */
class BundleRelaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
