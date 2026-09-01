package dev.enginehost

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Optional initial location for game/config folder navigation. */
class GameBrowserStartStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun treeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun initialUri(): Uri? = treeUri()?.let { tree ->
        runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrDefault(tree)
    }

    fun select(uri: Uri) {
        require(uri.authority == "com.android.externalstorage.documents") {
            "Choose a folder on internal or removable shared storage"
        }
        DocumentsContract.getTreeDocumentId(uri)
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    companion object {
        private const val PREFERENCES = "game-browser-start-v1"
        private const val KEY_TREE_URI = "tree-uri"
    }
}
