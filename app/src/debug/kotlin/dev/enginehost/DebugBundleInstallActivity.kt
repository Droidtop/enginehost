package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/** ADB/CI harness; absent from release builds and still uses the production verifier. */
class DebugBundleInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val archive = intent.getStringExtra(EXTRA_ARCHIVE)?.let(::File)
        if (archive == null) return finishWithError("Missing $EXTRA_ARCHIVE")
        Thread {
            runCatching {
                val installed = EngineBundleInstaller.install(this, archive)
                require(PluginTrustStore(this).isOfficial(installed)) {
                    "Debug harness only auto-approves an official built-in signer"
                }
                PluginTrustStore(this).approve(installed)
                Log.i(TAG, "Installed and approved ${installed.bundleId} from ${archive.absolutePath}")
            }.onSuccess {
                setResult(RESULT_OK)
                finish()
            }.onFailure { error -> finishWithError(error.message ?: error.javaClass.simpleName, error) }
        }.start()
    }

    private fun finishWithError(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "enginehost-debug-install"
        const val EXTRA_ARCHIVE = "dev.enginehost.debug.ARCHIVE"
    }
}
