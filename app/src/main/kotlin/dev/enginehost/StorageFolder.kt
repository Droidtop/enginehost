package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/** Common system-file-manager folder selection and external-storage path mapping. */
object StorageFolder {
    fun pickerIntent() = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    /**
     * Native plugins still require a real directory path. The platform's
     * ExternalStorageProvider exposes a stable tree ID that can be mapped for
     * primary shared storage. Other document providers remain valid for config
     * editing, but cannot be handed to a path-based native engine.
     */
    fun absolutePath(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (!parts[0].equals("primary", ignoreCase = true)) return null
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val relative = parts.getOrNull(1).orEmpty()
        val candidate = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        return candidate.takeIf { it == root || it.toPath().startsWith(root.toPath()) }
    }

    fun hasNativePathAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Opens Android's per-app grant screen; the caller should retry afterward. */
    fun requestNativePathAccess(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appPage = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { activity.startActivityForResult(appPage, requestCode) }
            .getOrElse { activity.startActivityForResult(fallback, requestCode) }
    }
}
