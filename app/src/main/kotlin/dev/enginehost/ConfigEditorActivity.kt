package dev.enginehost

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * First-class editor for a game folder's authoritative enginehost.json.
 *
 * Menu-driven wherever the vocabulary already exists on the device: engine
 * and context come from installed plugins plus the origin directory,
 * runtime requirements from installed capabilities, plugin builds from
 * installed versions, and the entry file from the game folder itself. The
 * engine version is the game's own runtime target, so no plugin list can
 * supply it; it is detected from the game files, with a typed field as the
 * fallback. Free-text escapes stay available for every value nothing
 * installed declares yet -- declared vocabularies suggest, they never gate.
 */
class ConfigEditorActivity : AppCompatActivity() {
    private var folderUri: Uri? = null
    private var folderPath: File? = null
    private var loadedDocument = JSONObject()

    private var engine: String? = null
    private var engineContext: String? = null
    private var runtimeRequirements: JSONObject? = null
    private var pluginVersionConstraint: String? = null
    private var execFile: String? = null
    private var options = JSONObject()

    /** Which option key a SAF folder pick is currently answering, and how. */
    private var pendingOptionKey: String? = null
    private var pendingOptionAppends = false

    private lateinit var folderLabel: TextView
    private lateinit var detectionLabel: TextView
    private lateinit var engineButton: Button
    private lateinit var contextButton: Button
    private lateinit var versionField: EditText
    private lateinit var runtimesButton: Button
    private lateinit var pluginVersionButton: Button
    private lateinit var execFileButton: Button
    private lateinit var optionsList: LinearLayout
    private lateinit var editorFields: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        folderLabel = findViewById(R.id.configFolderLabel)
        detectionLabel = findViewById(R.id.detectionLabel)
        engineButton = findViewById(R.id.engineButton)
        contextButton = findViewById(R.id.contextButton)
        versionField = findViewById(R.id.engineVersionField)
        runtimesButton = findViewById(R.id.runtimesButton)
        pluginVersionButton = findViewById(R.id.pluginVersionButton)
        execFileButton = findViewById(R.id.execFileButton)
        optionsList = findViewById(R.id.optionsList)
        editorFields = findViewById(R.id.editorFields)

        engineButton.setOnClickListener { pickEngine() }
        contextButton.setOnClickListener { pickContext() }
        runtimesButton.setOnClickListener { pickRuntimes() }
        pluginVersionButton.setOnClickListener { pickPluginVersion() }
        execFileButton.setOnClickListener { pickExecFile() }
        findViewById<Button>(R.id.addOptionButton).setOnClickListener { addOption() }

        intent.getStringExtra(EXTRA_PATH)?.let(::openCallerPath)

        findViewById<Button>(R.id.chooseConfigFolderButton).setOnClickListener {
            startActivityForResult(
                StorageFolder.pickerIntent(GameBrowserStartStore(this).initialUri()),
                REQUEST_FOLDER,
            )
        }
        findViewById<Button>(R.id.saveConfigButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testConfigButton).setOnClickListener { testRun() }
        findViewById<Button>(R.id.browseAllPluginsButton).setOnClickListener {
            startActivityForResult(
                Intent(this, PluginCatalogActivity::class.java)
                    .putExtra(PluginCatalogActivity.EXTRA_SELECTION_ONLY, true),
                REQUEST_PLUGIN_SELECTION,
            )
        }
        refreshEditors()
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PLUGIN_SELECTION && resultCode == RESULT_OK) {
            data ?: return
            engine = data.getStringExtra("engine")
            engineContext = data.getStringExtra("engineContext")
            versionField.setText(data.getStringExtra("engineVersion"))
            refreshEditors()
            detectionLabel.setText(R.string.plugin_selected_manually)
            return
        }
        if (requestCode == REQUEST_OPTION_FOLDER && resultCode == RESULT_OK) {
            val key = pendingOptionKey ?: return
            pendingOptionKey = null
            val uri = data?.data ?: return
            val folder = StorageFolder.absolutePath(uri)
            if (folder == null) {
                toast(getString(R.string.choose_shared_storage_folder))
                return
            }
            if (pendingOptionAppends) {
                val array = options.optJSONArray(key) ?: JSONArray()
                array.put(folder.absolutePath)
                options.put(key, array)
            } else {
                options.put(key, folder.absolutePath)
            }
            refreshEditors()
            return
        }
        if (requestCode == REQUEST_OPTION_FILE && resultCode == RESULT_OK) {
            val key = pendingOptionKey ?: return
            pendingOptionKey = null
            val uri = data?.data ?: return
            val file = StorageFolder.absoluteFilePath(uri)
            if (file == null) {
                toast(getString(R.string.choose_shared_storage_file))
                return
            }
            if (pendingOptionAppends) {
                val array = options.optJSONArray(key) ?: JSONArray()
                array.put(file.absolutePath)
                options.put(key, array)
            } else {
                options.put(key, file.absolutePath)
            }
            refreshEditors()
            return
        }
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        folderPath = null
        folderUri = uri
        folderLabel.text = StorageFolder.absolutePath(uri)?.absolutePath ?: uri.toString()
        editorFields.visibility = View.VISIBLE
        load(uri)
        detect(uri)
    }

    // --- Menu-driven choices ---------------------------------------------

    private fun installedPlugins(): List<InstalledPlugin> =
        runCatching { PluginRegistry.discover(this) }.getOrDefault(emptyList())

    private fun originIdentities(): List<OriginIdentity> {
        val directory = OriginDirectory(this)
        return PluginOriginStore(this).all().mapNotNull { directory.describe(it) }
    }

    private fun engineChoices(): List<String> =
        (installedPlugins().map { it.info.engine } + originIdentities().map { it.engine })
            .filter { it.isNotBlank() }.distinct().sorted()

    private fun contextChoices(engineName: String): List<String> =
        (
            installedPlugins().filter { it.info.engine == engineName }
                .flatMap { plugin -> plugin.info.capabilities.map { it.engineContext } } +
                originIdentities().filter { it.engine == engineName }.flatMap { it.engineContexts }
            )
            .filter { it.isNotBlank() }.distinct().sorted()

    private fun pickEngine() {
        pickFromList(R.string.pick_engine_title, engineChoices(), otherLabel = getString(R.string.other_custom)) {
            engine = it.ifBlank { null }
            refreshEditors()
        }
    }

    private fun pickContext() {
        pickFromList(
            R.string.pick_context_title,
            engine?.let(::contextChoices).orEmpty(),
            clearLabel = getString(R.string.value_not_set),
            otherLabel = getString(R.string.other_custom),
        ) {
            engineContext = it.ifBlank { null }
            refreshEditors()
        }
    }

    /**
     * A capability is the real unit of choice here: mkxp-z ships each RGSS
     * context against both Ruby 3.1 and Ruby 1.9, and the difference is
     * exactly the runtimeComponents map. Offering the capability spares the
     * user from writing that map by hand.
     */
    private fun pickRuntimes() {
        val choices = installedPlugins()
            .filter { engine == null || it.info.engine == engine }
            .flatMap { plugin ->
                plugin.info.capabilities
                    .filter { engineContext == null || it.engineContext == engineContext }
                    .filter { it.runtimeComponents.isNotEmpty() }
            }
            .map { capability ->
                val label = buildString {
                    append(capability.engineContext)
                    append(" · runtime ").append(capability.runtimeVersion)
                    append(" · ")
                    append(
                        capability.runtimeComponents.entries
                            .joinToString { (name, version) -> "$name $version" },
                    )
                }
                label to capability.runtimeComponents
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
        val labels = mutableListOf(getString(R.string.runtime_none))
        val actions = mutableListOf<() -> Unit>({
            runtimeRequirements = null
            refreshEditors()
        })
        choices.forEach { (label, components) ->
            labels += label
            actions += {
                runtimeRequirements = JSONObject(components.mapValues { it.value.toString() })
                refreshEditors()
            }
        }
        labels += getString(R.string.custom_json)
        actions += {
            promptText(R.string.pick_runtimes_title, runtimeRequirements?.toString()) { raw ->
                runtimeRequirements = when {
                    raw.isBlank() -> null
                    else -> runCatching { JSONObject(raw) }.getOrElse {
                        toast(getString(R.string.json_object_error, "runtimeRequirements", it.message))
                        return@promptText
                    }
                }
                refreshEditors()
            }
        }
        showItems(R.string.pick_runtimes_title, labels, actions)
    }

    private fun pickPluginVersion() {
        val versions = installedPlugins()
            .filter { engine == null || it.info.engine == engine }
            .map { it.info.pluginVersion.toString() }
            .distinct().sortedDescending()
        val labels = mutableListOf(getString(R.string.any_version))
        val actions = mutableListOf<() -> Unit>({
            pluginVersionConstraint = null
            refreshEditors()
        })
        versions.forEach { version ->
            labels += version
            actions += {
                pluginVersionConstraint = version
                refreshEditors()
            }
        }
        labels += getString(R.string.custom_constraint)
        actions += {
            promptText(R.string.pick_plugin_version_title, pluginVersionConstraint) {
                pluginVersionConstraint = it.ifBlank { null }
                refreshEditors()
            }
        }
        showItems(R.string.pick_plugin_version_title, labels, actions)
    }

    private fun pickExecFile() {
        val root = folderPath ?: folderUri?.let { StorageFolder.absolutePath(it) }
        if (root == null || !root.isDirectory) {
            toast(getString(R.string.exec_unavailable))
            promptText(R.string.pick_exec_title, execFile) {
                execFile = it.ifBlank { null }
                refreshEditors()
            }
            return
        }
        browseExecFile(root, root)
    }

    private fun browseExecFile(root: File, current: File) {
        val entries = runCatching { current.listFiles() }.getOrNull().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val labels = mutableListOf(getString(R.string.exec_clear))
        val actions = mutableListOf<() -> Unit>({
            execFile = null
            refreshEditors()
        })
        val atRoot = runCatching { current.canonicalPath == root.canonicalPath }.getOrDefault(true)
        if (!atRoot) {
            labels += getString(R.string.exec_up)
            actions += { browseExecFile(root, current.parentFile ?: root) }
        }
        entries.forEach { entry ->
            if (entry.isDirectory) {
                labels += entry.name + "/"
                actions += { browseExecFile(root, entry) }
            } else {
                labels += entry.name
                actions += {
                    execFile = entry.relativeTo(root).invariantSeparatorsPath
                    refreshEditors()
                }
            }
        }
        showItems(R.string.pick_exec_title, labels, actions)
    }

    // --- Options: declared entries suggest, custom entries always work ----

    private fun declaredOptions(): List<DeclaredOption> =
        engine?.let { DeclaredOptionsReader.forEngine(this, it) }.orEmpty()

    private fun renderOptions() {
        optionsList.removeAllViews()
        val keys = options.keys().asSequence().toList().sorted()
        if (keys.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.item_hint, optionsList, false) as TextView
            empty.setText(R.string.options_empty)
            optionsList.addView(empty)
            return
        }
        val declared = declaredOptions().associateBy { it.key }
        keys.forEach { key ->
            val row = layoutInflater.inflate(R.layout.item_action_button, optionsList, false) as Button
            val label = declared[key]?.label ?: key
            val preview = options.opt(key).toString().let { if (it.length > 48) it.take(48) + "…" else it }
            row.text = "$label · $preview"
            row.setOnClickListener { editOption(key) }
            row.setOnLongClickListener {
                options.remove(key)
                refreshEditors()
                true
            }
            optionsList.addView(row)
        }
    }

    private fun addOption() {
        val existing = options.keys().asSequence().toSet()
        val declared = declaredOptions().filterNot { it.key in existing }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        declared.forEach { option ->
            labels += if (option.description.isBlank()) option.label else "${option.label} · ${option.description}"
            actions += { editDeclaredOption(option) }
        }
        labels += getString(R.string.option_custom)
        actions += {
            promptText(R.string.option_key_prompt, null) { key ->
                if (key.isNotBlank()) promptOptionValue(key)
            }
        }
        showItems(R.string.add_option, labels, actions)
    }

    private fun editOption(key: String) {
        val declared = declaredOptions().firstOrNull { it.key == key }
        if (declared != null) editDeclaredOption(declared) else promptOptionValue(key)
    }

    private fun editDeclaredOption(option: DeclaredOption) {
        if (option.repeats) {
            editListOption(option)
            return
        }
        when (option.type) {
            "boolean" -> showItems(
                R.string.add_option,
                listOf(getString(R.string.option_true), getString(R.string.option_false), getString(R.string.option_remove)),
                listOf(
                    { options.put(option.key, true); refreshEditors() },
                    { options.put(option.key, false); refreshEditors() },
                    { options.remove(option.key); refreshEditors() },
                ),
            )
            "choice" -> {
                val labels = option.choices.map { it.second } + getString(R.string.option_remove)
                val actions: List<() -> Unit> = option.choices.map { (value, _) ->
                    { options.put(option.key, value); refreshEditors(); Unit }
                } + listOf<() -> Unit>({ options.remove(option.key); refreshEditors() })
                showItems(R.string.add_option, labels, actions)
            }
            "path" -> pickOptionFolder(option.key, appends = false)
            "file" -> pickOptionFile(option, appends = false)
            else -> promptOptionValue(option.key)
        }
    }

    /** Repeating values: list current entries (select to remove) and append new ones. */
    private fun editListOption(option: DeclaredOption) {
        val array = options.optJSONArray(option.key) ?: JSONArray()
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        for (index in 0 until array.length()) {
            val value = array.opt(index).toString()
            labels += getString(R.string.option_remove_entry, value)
            actions += {
                val kept = JSONArray()
                for (keep in 0 until array.length()) if (keep != index) kept.put(array.opt(keep))
                if (kept.length() == 0) options.remove(option.key) else options.put(option.key, kept)
                refreshEditors()
            }
        }
        if (option.type == "path") {
            labels += getString(R.string.option_pick_folder)
            actions += { pickOptionFolder(option.key, appends = true) }
        } else if (option.type == "file") {
            labels += getString(R.string.option_pick_file)
            actions += { pickOptionFile(option, appends = true) }
        } else {
            labels += getString(R.string.option_add_entry)
            actions += {
                promptText(R.string.add_option, null) { raw ->
                    if (raw.isNotBlank()) {
                        val target = options.optJSONArray(option.key) ?: JSONArray()
                        target.put(parseJsonValue(raw))
                        options.put(option.key, target)
                        refreshEditors()
                    }
                }
            }
        }
        showItems(R.string.add_option, labels, actions)
    }

    private fun pickOptionFolder(key: String, appends: Boolean) {
        pendingOptionKey = key
        pendingOptionAppends = appends
        startActivityForResult(StorageFolder.pickerIntent(), REQUEST_OPTION_FOLDER)
    }

    private fun pickOptionFile(option: DeclaredOption, appends: Boolean) {
        pendingOptionKey = option.key
        pendingOptionAppends = appends
        startActivityForResult(StorageFolder.filePickerIntent(option.mimeTypes), REQUEST_OPTION_FILE)
    }

    private fun promptOptionValue(key: String) {
        val current = options.opt(key)?.toString()
        promptText(R.string.option_key_prompt, current, getString(R.string.option_value_prompt, key)) { raw ->
            if (raw.isBlank()) options.remove(key) else options.put(key, parseJsonValue(raw))
            refreshEditors()
        }
    }

    /** Store typed JSON when the text is valid JSON; otherwise store the string. */
    private fun parseJsonValue(raw: String): Any =
        runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: raw

    // --- Shared dialog helpers -------------------------------------------

    private fun pickFromList(
        titleRes: Int,
        choices: List<String>,
        clearLabel: String? = null,
        otherLabel: String? = null,
        onPick: (String) -> Unit,
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        clearLabel?.let {
            labels += it
            actions += { onPick("") }
        }
        choices.forEach { choice ->
            labels += choice
            actions += { onPick(choice) }
        }
        otherLabel?.let {
            labels += it
            actions += { promptText(titleRes, null, onDone = onPick) }
        }
        if (choices.isEmpty()) toast(getString(R.string.no_installed_choices))
        showItems(titleRes, labels, actions)
    }

    private fun showItems(titleRes: Int, labels: List<String>, actions: List<() -> Unit>) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptText(titleRes: Int, initial: String?, title: String? = null, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial ?: "")
        }
        AlertDialog.Builder(this)
            .apply { if (title != null) setTitle(title) else setTitle(titleRes) }
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ -> onDone(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshEditors() {
        val notSet = getString(R.string.value_not_set)
        engineButton.text = engine ?: notSet
        contextButton.text = engineContext ?: notSet
        runtimesButton.text = runtimeRequirements
            ?.takeIf { it.length() > 0 }
            ?.let { json ->
                json.keys().asSequence().joinToString { name -> "$name ${json.optString(name)}" }
            }
            ?: getString(R.string.runtime_none)
        pluginVersionButton.text = pluginVersionConstraint ?: getString(R.string.any_version)
        execFileButton.text = execFile ?: getString(R.string.exec_clear)
        renderOptions()
    }

    // --- Loading, detection, saving --------------------------------------

    private fun load(treeUri: Uri) {
        try {
            val configUri = findChild(treeUri, CONFIG_FILE_NAME)
            if (configUri == null) {
                loadedDocument = JSONObject()
                populate(loadedDocument)
                toast(getString(R.string.new_configuration))
                return
            }
            val raw = contentResolver.openInputStream(configUri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Unable to read $CONFIG_FILE_NAME")
            loadedDocument = JSONObject(raw)
            populate(loadedDocument)
            // Show malformed existing files for repair, but make the problem visible.
            runCatching { EngineConfigReader.parseDocument(raw) }
                .exceptionOrNull()?.let { toast(it.message ?: getString(R.string.config_needs_attention)) }
        } catch (error: Exception) {
            loadedDocument = JSONObject()
            populate(loadedDocument)
            toast(getString(R.string.could_not_open_config, CONFIG_FILE_NAME, error.message))
        }
    }

    private fun openCallerPath(rawPath: String) {
        val folder = File(rawPath).absoluteFile
        if (!folder.isDirectory) {
            toast(getString(R.string.supplied_path_invalid))
            return
        }
        folderPath = folder
        folderLabel.text = folder.absolutePath
        editorFields.visibility = View.VISIBLE
        val configFile = File(folder, CONFIG_FILE_NAME)
        loadedDocument = runCatching {
            if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
        }.getOrElse {
            toast(getString(R.string.could_not_open_config, CONFIG_FILE_NAME, it.message))
            JSONObject()
        }
        intent.getStringExtra(EXTRA_CONFIG)?.takeIf(String::isNotBlank)?.let { callerConfig ->
            runCatching { mergeMissing(loadedDocument, JSONObject(callerConfig)) }
                .onFailure { toast(getString(R.string.ignored_invalid_caller, it.message)) }
        }
        populate(loadedDocument)
        detect(folder)
    }

    private fun mergeMissing(authoritative: JSONObject, fallback: JSONObject) {
        fallback.keys().forEach { key ->
            if (!authoritative.has(key)) {
                authoritative.put(key, fallback.get(key))
            } else {
                val existing = authoritative.opt(key)
                val additional = fallback.opt(key)
                if (existing is JSONObject && additional is JSONObject) mergeMissing(existing, additional)
            }
        }
    }

    private fun populate(json: JSONObject) {
        engine = json.optString("engine").takeIf { it.isNotBlank() }
        engineContext = json.optString("engineContext").takeIf { it.isNotBlank() }
        versionField.setText(json.optString("engineVersion"))
        runtimeRequirements = json.optJSONObject("runtimeRequirements")
        pluginVersionConstraint = json.optString("pluginVersion").takeIf { it.isNotBlank() }
        execFile = json.optString("execFile").takeIf { it.isNotBlank() }
        options = json.optJSONObject("options")?.let { JSONObject(it.toString()) } ?: JSONObject()
        refreshEditors()
    }

    private fun detect(treeUri: Uri) {
        detectionLabel.setText(R.string.scanning_selected)
        Thread {
            val result = runCatching { EngineDetector.detect(contentResolver, treeUri) }
            runOnUiThread {
                if (folderUri != treeUri) return@runOnUiThread
                result.fold(
                    onSuccess = { detection -> applyDetection(detection) },
                    onFailure = {
                        detectionLabel.text = getString(R.string.detection_failed_choose, it.message)
                        findViewById<Button>(R.id.browseAllPluginsButton).visibility = View.VISIBLE
                    },
                )
            }
        }.start()
    }

    private fun detect(folder: File) {
        detectionLabel.setText(R.string.scanning_supplied)
        Thread {
            val result = runCatching { EngineDetector.detect(folder) }
            runOnUiThread {
                if (folderPath != folder) return@runOnUiThread
                result.fold(
                    onSuccess = { detection -> applyDetection(detection) },
                    onFailure = { detectionLabel.text = getString(R.string.detection_failed_manual, it.message) },
                )
            }
        }.start()
    }

    private fun applyDetection(detection: EngineDetection?) {
        if (detection == null) {
            detectionLabel.setText(R.string.engine_not_identified)
            findViewById<Button>(R.id.browseAllPluginsButton).visibility = View.VISIBLE
            return
        }
        findViewById<Button>(R.id.browseAllPluginsButton).visibility = View.GONE
        if (engine == null) engine = detection.engine
        if (engineContext == null) detection.engineContext?.let { engineContext = it }
        if (versionField.text.isBlank()) detection.engineVersion?.let(versionField::setText)
        if (execFile == null) detection.execFile?.let { execFile = it }
        if ((runtimeRequirements?.length() ?: 0) == 0 && detection.runtimeRequirements.isNotEmpty()) {
            runtimeRequirements = JSONObject(detection.runtimeRequirements)
        }
        refreshEditors()
        detectionLabel.text = getString(R.string.detected_engine, detection.engine, detection.evidence)
    }

    private fun buildDocument(): JSONObject {
        val result = JSONObject(loadedDocument.toString())
        result.put(
            "engine",
            engine?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    getString(R.string.field_required, getString(R.string.engine_family)),
                ),
        )
        val version = versionField.text.toString().trim()
        if (version.isEmpty()) {
            throw IllegalArgumentException(getString(R.string.field_required, getString(R.string.engine_version)))
        }
        result.put("engineVersion", version)
        putOptional(result, "engineContext", engineContext)
        putOptional(result, "pluginVersion", pluginVersionConstraint)
        putOptional(result, "execFile", execFile)
        val runtimes = runtimeRequirements
        if (runtimes == null || runtimes.length() == 0) result.remove("runtimeRequirements")
        else result.put("runtimeRequirements", runtimes)
        if (options.length() == 0) result.remove("options")
        else result.put("options", JSONObject(options.toString()))
        EngineConfigReader.parseDocument(result.toString())
        return result
    }

    private fun save() {
        try {
            val path = folderPath
            if (path != null) writeDocument(path)
            else writeDocument(folderUri ?: return toast(getString(R.string.choose_folder_first)))
            toast(getString(R.string.saved_config, CONFIG_FILE_NAME))
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.could_not_save))
        }
    }

    private fun testRun() {
        folderPath?.let { folder ->
            try {
                writeDocument(folder)
                GameRunner.run(this, folder)
            } catch (error: Exception) {
                toast(error.message ?: getString(R.string.config_not_ready))
            }
            return
        }
        val uri = folderUri ?: return toast(getString(R.string.choose_folder_first))
        if (!StorageFolder.hasNativePathAccess()) {
            StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
            return toast(getString(R.string.grant_native_then_test))
        }
        val folder = StorageFolder.absolutePath(uri)
            ?: return toast(getString(R.string.provider_needs_primary))
        try {
            // The folder file is the highest-priority configuration source.
            // Persist the visible editor state first so the test cannot launch
            // with a stale on-disk document overriding it.
            writeDocument(uri)
            GameRunner.run(this, folder)
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.config_not_ready))
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

    private fun writeDocument(folder: File): JSONObject {
        val document = buildDocument()
        val configFile = File(folder, CONFIG_FILE_NAME)
        require(!configFile.exists() || configFile.isFile) { "$CONFIG_FILE_NAME is not a file" }
        configFile.writeText(document.toString(2) + "\n")
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

    private fun putOptional(target: JSONObject, name: String, value: String?) {
        if (value.isNullOrBlank()) target.remove(name) else target.put(name, value)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_CONFIGURE = "dev.enginehost.CONFIGURE"
        const val EXTRA_PATH = "path"
        const val EXTRA_CONFIG = "config"
        private const val REQUEST_FOLDER = 20
        private const val REQUEST_NATIVE_FILES = 21
        private const val REQUEST_PLUGIN_SELECTION = 22
        private const val REQUEST_OPTION_FOLDER = 23
        private const val REQUEST_OPTION_FILE = 24
    }
}
