package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject

/** First-class editor for a game folder's authoritative enginehost.json. */
class ConfigEditorActivity : Activity() {
    private var folderUri: Uri? = null
    private var loadedDocument = JSONObject()

    private lateinit var folderLabel: TextView
    private lateinit var detectionLabel: TextView
    private lateinit var engineField: EditText
    private lateinit var contextField: EditText
    private lateinit var versionField: EditText
    private lateinit var runtimesField: EditText
    private lateinit var pluginVersionField: EditText
    private lateinit var execFileField: EditText
    private lateinit var optionsField: EditText
    private lateinit var editorFields: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        folderLabel = findViewById(R.id.configFolderLabel)
        detectionLabel = findViewById(R.id.detectionLabel)
        engineField = findViewById(R.id.engineField)
        contextField = findViewById(R.id.engineContextField)
        versionField = findViewById(R.id.engineVersionField)
        runtimesField = findViewById(R.id.runtimeRequirementsField)
        pluginVersionField = findViewById(R.id.pluginVersionField)
        execFileField = findViewById(R.id.execFileField)
        optionsField = findViewById(R.id.optionsField)
        editorFields = findViewById(R.id.editorFields)

        findViewById<Button>(R.id.chooseConfigFolderButton).setOnClickListener {
            startActivityForResult(StorageFolder.pickerIntent(), REQUEST_FOLDER)
        }
        findViewById<Button>(R.id.saveConfigButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testConfigButton).setOnClickListener { testRun() }
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        folderUri = uri
        folderLabel.text = StorageFolder.absolutePath(uri)?.absolutePath ?: uri.toString()
        editorFields.visibility = View.VISIBLE
        load(uri)
        detect(uri)
    }

    private fun load(treeUri: Uri) {
        try {
            val configUri = findChild(treeUri, CONFIG_FILE_NAME)
            if (configUri == null) {
                loadedDocument = JSONObject()
                populate(loadedDocument)
                toast("New configuration")
                return
            }
            val raw = contentResolver.openInputStream(configUri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Unable to read $CONFIG_FILE_NAME")
            loadedDocument = JSONObject(raw)
            populate(loadedDocument)
            // Show malformed existing files for repair, but make the problem visible.
            runCatching { EngineConfigReader.parseDocument(raw) }
                .exceptionOrNull()?.let { toast(it.message ?: "Configuration needs attention") }
        } catch (error: Exception) {
            loadedDocument = JSONObject()
            populate(loadedDocument)
            toast("Could not open $CONFIG_FILE_NAME: ${error.message}")
        }
    }

    private fun populate(json: JSONObject) {
        engineField.setText(json.optString("engine"))
        contextField.setText(json.optString("engineContext"))
        versionField.setText(json.optString("engineVersion"))
        runtimesField.setText(json.optJSONObject("runtimeRequirements")?.toString(2) ?: "{}")
        pluginVersionField.setText(json.optString("pluginVersion"))
        execFileField.setText(json.optString("execFile"))
        optionsField.setText(json.optJSONObject("options")?.toString(2) ?: "{}")
    }

    private fun detect(treeUri: Uri) {
        detectionLabel.text = "Scanning the selected folder for engine metadata…"
        Thread {
            val result = runCatching { EngineDetector.detect(contentResolver, treeUri) }
            runOnUiThread {
                if (folderUri != treeUri) return@runOnUiThread
                result.fold(
                    onSuccess = { detection ->
                        if (detection == null) {
                            detectionLabel.text = "Engine not identified; enter the configuration manually."
                        } else {
                            if (engineField.text.isBlank()) engineField.setText(detection.engine)
                            if (contextField.text.isBlank()) detection.engineContext?.let(contextField::setText)
                            if (versionField.text.isBlank()) detection.engineVersion?.let(versionField::setText)
                            if (execFileField.text.isBlank()) detection.execFile?.let(execFileField::setText)
                            if (
                                (runtimesField.text.isBlank() || runtimesField.text.toString().trim() == "{}") &&
                                detection.runtimeRequirements.isNotEmpty()
                            ) {
                                runtimesField.setText(JSONObject(detection.runtimeRequirements).toString(2))
                            }
                            detectionLabel.text = "Detected ${detection.engine}: ${detection.evidence}"
                        }
                    },
                    onFailure = { detectionLabel.text = "Detection failed: ${it.message}" },
                )
            }
        }.start()
    }

    private fun buildDocument(): JSONObject {
        val result = JSONObject(loadedDocument.toString())
        result.put("engine", required(engineField, "Engine family"))
        result.put("engineVersion", required(versionField, "Engine version"))
        putOptional(result, "engineContext", contextField)
        putOptional(result, "pluginVersion", pluginVersionField)
        putOptional(result, "execFile", execFileField)
        putObject(result, "runtimeRequirements", runtimesField)
        putObject(result, "options", optionsField)
        EngineConfigReader.parseDocument(result.toString())
        return result
    }

    private fun save() {
        val treeUri = folderUri ?: return toast("Choose a game folder first")
        try {
            writeDocument(treeUri)
            toast("Saved $CONFIG_FILE_NAME")
        } catch (error: Exception) {
            toast(error.message ?: "Could not save configuration")
        }
    }

    private fun testRun() {
        val uri = folderUri ?: return toast("Choose a game folder first")
        if (!StorageFolder.hasNativePathAccess()) {
            StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
            return toast("Grant native file access, then tap Test again")
        }
        val folder = StorageFolder.absolutePath(uri)
            ?: return toast("This provider can edit the config, but native test launch needs a primary-storage folder")
        try {
            // The folder file is the highest-priority configuration source.
            // Persist the visible editor state first so the test cannot launch
            // with a stale on-disk document overriding it.
            writeDocument(uri)
            GameRunner.run(this, folder)
        } catch (error: Exception) {
            toast(error.message ?: "Configuration is not ready to test")
        }
    }

    private fun writeDocument(treeUri: Uri): JSONObject {
        val document = buildDocument()
        val configUri = findChild(treeUri, CONFIG_FILE_NAME)
            ?: DocumentsContract.createDocument(
                contentResolver,
                treeDocumentUri(treeUri),
                "application/json",
                CONFIG_FILE_NAME,
            )
            ?: throw IllegalStateException("The selected folder would not create $CONFIG_FILE_NAME")
        contentResolver.openOutputStream(configUri, "wt")?.bufferedWriter()?.use {
            it.write(document.toString(2))
            it.newLine()
        } ?: throw IllegalStateException("The selected folder is not writable")
        loadedDocument = document
        return document
    }

    private fun findChild(treeUri: Uri, displayName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun treeDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun required(field: EditText, label: String): String = field.text.toString().trim().also {
        if (it.isEmpty()) throw IllegalArgumentException("$label is required")
    }

    private fun putOptional(target: JSONObject, name: String, field: EditText) {
        val value = field.text.toString().trim()
        if (value.isEmpty()) target.remove(name) else target.put(name, value)
    }

    private fun putObject(target: JSONObject, name: String, field: EditText) {
        val raw = field.text.toString().trim()
        if (raw.isEmpty() || raw == "{}") target.remove(name) else {
            try {
                target.put(name, JSONObject(raw))
            } catch (error: JSONException) {
                throw IllegalArgumentException("$name must be a JSON object: ${error.message}")
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_FOLDER = 20
        private const val REQUEST_NATIVE_FILES = 21
    }
}
