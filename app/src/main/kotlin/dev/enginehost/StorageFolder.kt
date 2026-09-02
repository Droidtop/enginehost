package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/** Common system-file-manager selection and external-storage path mapping. */
object StorageFolder {
    fun pickerIntent(initialUri: Uri? = null) =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    /**
     * A single file, for the options that take one -- a SoundFont, a
     * replacement script -- rather than a directory.
     *
     * [mimeTypes] is the declaring plugin's advisory hint and is allowed to
     * be empty, in which case everything is offered. It is the only filter
     * ACTION_OPEN_DOCUMENT actually applies: the system picker has no notion
     * of a file extension, and asks each provider for its own MIME type.
     */
    fun filePickerIntent(mimeTypes: List<String> = emptyList(), initialUri: Uri? = null) =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    /**
     * Native plugins still require a real directory path. The platform's
     * ExternalStorageProvider exposes a stable tree ID that can be mapped for
     * primary or removable shared storage. Other document providers remain
     * valid for config editing, but cannot be handed to a path-based engine.
     */
    fun absolutePath(uri: Uri): File? =
        resolveDocument(uri) { DocumentsContract.getTreeDocumentId(it) }

    /**
     * The same mapping for a [filePickerIntent] result. A single picked
     * document has no tree behind it, so its ID is read the other way.
     */
    fun absoluteFilePath(uri: Uri): File? =
        resolveDocument(uri) { DocumentsContract.getDocumentId(it) }

    private fun resolveDocument(uri: Uri, documentId: (Uri) -> String): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { documentId(uri) }.getOrNull() ?: return null
        val parts = id.split(':', limit = 2)
        val volume = parts[0]
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().canonicalFile
        } else {
            File("/storage", volume).canonicalFile.takeIf { it.isDirectory } ?: return null
        }
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
