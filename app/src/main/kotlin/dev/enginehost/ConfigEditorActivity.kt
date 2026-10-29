package dev.enginehost

import dev.droidtop.shared.launchcontract.EnginehostIntents
import dev.droidtop.shared.version.Version
import dev.droidtop.shared.version.VersionConstraint
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
import androidx.annotation.StringRes
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
class ConfigEditorActivity : EnginehostActivity() {
    /**
     * The first field once the folder is open, never Save: Save was the first
     * focus, so a mashed A saved (#620 E3). Choosing the folder before that.
     */
    override fun primaryAction(): View? =
        findViewById<View>(R.id.engineButton)?.takeIf { it.isShown }
            ?: findViewById<View>(R.id.detectedToggle)?.takeIf { it.isShown }
            ?: findViewById(R.id.chooseConfigFolderButton)

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
    private lateinit var advancedIds: TextView
    private lateinit var execFileButton: Button
    private lateinit var optionsList: LinearLayout
    private lateinit var editorFields: View
    /** Engine, version, core builds and entry file: folded away when detection filled them in (#620 E13). */
    private lateinit var detectedFields: View
    private lateinit var detectedToggle: TextView
    private lateinit var saveToFolderSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var configSourceNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)
        wireBackButton()

        folderLabel = findViewById(R.id.configFolderLabel)
        detectionLabel = findViewById(R.id.detectionLabel)
        engineButton = findViewById(R.id.engineButton)
        contextButton = findViewById(R.id.contextButton)
        versionField = findViewById(R.id.engineVersionField)
        runtimesButton = findViewById(R.id.runtimesButton)
        pluginVersionButton = findViewById(R.id.pluginVersionButton)
        advancedIds = findViewById(R.id.advancedIds)
        findViewById<TextView>(R.id.advancedToggle).setOnClickListener { toggle ->
            val open = advancedIds.visibility != View.VISIBLE
            advancedIds.visibility = if (open) View.VISIBLE else View.GONE
            (toggle as TextView).setText(if (open) R.string.advanced_hide else R.string.advanced_show)
        }
        execFileButton = findViewById(R.id.execFileButton)
        optionsList = findViewById(R.id.optionsList)
        editorFields = findViewById(R.id.editorFields)
        detectedFields = findViewById(R.id.detectedFields)
        detectedToggle = findViewById(R.id.detectedToggle)
        detectedToggle.setOnClickListener { showDetectedFields(true) }
        // Before openCallerPath below, which shows where the setup comes from.
        saveToFolderSwitch = findViewById(R.id.saveToFolderSwitch)
        configSourceNote = findViewById(R.id.configSourceNote)

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
            showDetectedFields(true)
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
        pickFromList(
            R.string.pick_engine_title,
            engineChoices(),
            otherLabel = getString(R.string.other_custom),
            labelOf = EngineNames::family,
        ) {
            engine = it.ifBlank { null }
            refreshEditors()
        }
    }

    /** A variant as a person reads it ("VX Ace"); the raw id stays in enginehost.json. */
    private fun variantLabel(context: String): String =
        EngineNames.variant(engine.orEmpty(), context) ?: getString(R.string.variant_standard)

    /** An embedded runtime map as a person reads it: "Ruby 1.9.2". */
    private fun runtimeLabel(components: Map<String, Any?>): String =
        components.entries.joinToString(" · ") { (name, version) -> "${EngineNames.componentName(name)} $version" }

    private fun pickContext() {
        pickFromList(
            R.string.pick_context_title,
            engine?.let(::contextChoices).orEmpty(),
            clearLabel = getString(R.string.value_not_set),
            otherLabel = getString(R.string.other_custom),
            labelOf = this::variantLabel,
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
                val label = "${variantLabel(capability.engineContext)} · ${runtimeLabel(capability.runtimeComponents)}"
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
        DeclaredOptionsReader.forResolvedBundle(this, resolvedBundle(), engine)

    /**
     * The one bundle this config would actually launch, when the editor
     * holds enough to say. Resolution is the host's own, so the options
     * offered are the options of the bundle that will run the game.
     */
    private fun resolvedBundle(): InstalledPlugin? {
        val engineName = engine?.takeIf { it.isNotBlank() } ?: return null
        val version = runCatching { Version.parse(versionField.text.toString().trim()) }.getOrNull()
            ?: return null
        val requirements = runCatching {
            runtimeRequirements?.let { json ->
                json.keys().asSequence().associateWith { Version.parse(json.getString(it)) }
            }.orEmpty()
        }.getOrNull() ?: return null
        val constraint = pluginVersionConstraint?.let {
            runCatching { VersionConstraint.parse(it) }.getOrNull() ?: return null
        }
        return runCatching {
            PluginRegistry.resolve(this, engineName, engineContext, version, requirements, constraint)?.plugin
        }.getOrNull()
    }

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
        /** How a stored id reads in the list; two ids that read the same are told apart by the id in brackets. */
        labelOf: (String) -> String = { it },
        onPick: (String) -> Unit,
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        clearLabel?.let {
            labels += it
            actions += { onPick("") }
        }
        val shown = choices.associateWith(labelOf)
        choices.forEach { choice ->
            val label = shown.getValue(choice)
            labels += if (shown.values.count { it == label } > 1) "$label ($choice)" else label
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
        Sheet(this).title(titleRes).apply {
            labels.forEachIndexed { index, label -> choice(label, pick = actions[index]) }
        }.show()
    }

    private fun promptText(titleRes: Int, initial: String?, title: String? = null, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial ?: "")
            setSelection(text.length)
            isSingleLine = true
        }
        Sheet(this)
            .title(title ?: getString(titleRes))
            .content(input)
            .choice(R.string.ok) { onDone(input.text.toString().trim()) }
            .show()
        input.requestFocus()
    }

    private fun refreshEditors() {
        val notSet = getString(R.string.value_not_set)
        engineButton.text = engine?.let(EngineNames::family) ?: notSet
        contextButton.text = engineContext?.let(this::variantLabel) ?: notSet
        runtimesButton.text = runtimeRequirements
            ?.takeIf { it.length() > 0 }
            ?.let { json -> runtimeLabel(json.keys().asSequence().associateWith { json.optString(it) }) }
            ?: getString(R.string.runtime_none)
        pluginVersionButton.text = pluginVersionConstraint ?: getString(R.string.any_version)
        execFileButton.text = execFile ?: getString(R.string.exec_clear)
        // The raw ids, for someone who needs to match them against enginehost.json or a report.
        advancedIds.text = listOfNotNull(
            engine?.let { "engine: $it" },
            engineContext?.let { "variant: $it" },
            runtimeRequirements?.takeIf { it.length() > 0 }?.let { "runtimes: $it" },
            pluginVersionConstraint?.let { "plugin versions: $it" },
        ).ifEmpty { listOf(getString(R.string.value_not_set)) }.joinToString("\n")
        renderOptions()
    }

    // --- Loading, detection, saving --------------------------------------

    private fun load(treeUri: Uri) {
        try {
            val configUri = findChild(treeUri, CONFIG_FILE_NAME)
            if (configUri == null) {
                val stored = StorageFolder.absolutePath(treeUri)?.let { GameConfigStore.of(this).get(it) }
                loadedDocument = stored ?: JSONObject()
                populate(loadedDocument)
                showConfigSource(folderHasConfig = false)
                if (stored == null) toast(getString(R.string.new_configuration))
                return
            }
            showConfigSource(folderHasConfig = true)
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
        // The game's own config: its folder's enginehost.json, else the setup kept in Enginehost.
        val current = runCatching { GameConfigs.document(this, folder) }.getOrElse {
            toast(getString(R.string.could_not_open_config, CONFIG_FILE_NAME, it.message))
            null
        }
        loadedDocument = current?.json?.let { JSONObject(it.toString()) } ?: JSONObject()
        showConfigSource(folderHasConfig = current?.source == GameConfigs.Source.GAME_FOLDER)
        intent.getStringExtra(EXTRA_CONFIG)?.takeIf(String::isNotBlank)?.let { callerConfig ->
            runCatching { mergeMissing(loadedDocument, JSONObject(callerConfig)) }
                .onFailure { toast(getString(R.string.ignored_invalid_caller, it.message)) }
        }
        // A pending testing configuration is what the person last ran, so
        // the fields show it (the row above says so); Save keeps it.
        TestingConfigStore.of(this).get(folder)?.let { loadedDocument = JSONObject(it.config.toString()) }
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
            val result = runCatching { EngineDetector.detect(EngineRegistryStore.rows(this), contentResolver, treeUri) }
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
            val result = runCatching { EngineDetector.detect(EngineRegistryStore.rows(this), folder) }
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
        if (!detection.hosted) {
            // Nothing to prefill: no engine here runs it, and the editor says
            // why instead of offering fields that could not make it start.
            detectionLabel.text = UnhostedEngine.explain(this, detection)
            findViewById<Button>(R.id.browseAllPluginsButton).visibility = View.GONE
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
        // The title and save folder ride along in the document: they are the
        // game's own facts, not choices, and the editor preserves them.
        if (!loadedDocument.has("title")) detection.title?.let { loadedDocument.put("title", it) }
        // The folder the game was found in, inside a wrapper: where it runs (EngineConfig.root).
        if (!loadedDocument.has("root") && detection.root != null &&
            execFile.let { it == null || it.startsWith("${detection.root}/") }
        ) {
            loadedDocument.put("root", detection.root)
        }
        if (!loadedDocument.has("saveFolder")) {
            SaveFolders.defaultFor(engine ?: detection.engine, engineContext, detection.saveFolder, currentFolderName())
                ?.let { loadedDocument.put("saveFolder", it) }
        }
        refreshEditors()
        val found = getString(R.string.detected_engine, EngineNames.line(detection.engine, detection.engineContext), detection.evidence)
        detectionLabel.text = if (versionField.text.isBlank()) {
            "$found\n${getString(R.string.engine_version_not_found)}"
        } else {
            "$found\n${getString(R.string.detected_nothing_to_change)}"
        }
        // Detection filled engine and version: the game is set up, and the fields that hold it fold
        // away behind one link (#620 E13). A missing version keeps them open, since one is needed.
        showDetectedFields(engine == null || versionField.text.isBlank())
    }

    /** Shows the detected fields, or folds them behind "Change what was detected". */
    private fun showDetectedFields(open: Boolean) {
        // Focus follows what is shown: a folded field cannot keep it, and an opened fold starts on its first.
        val moveTo = when {
            open && detectedToggle.hasFocus() -> findViewById<View>(R.id.engineButton)
            !open && detectedFields.hasFocus() -> detectedToggle
            else -> null
        }
        detectedFields.visibility = if (open) View.VISIBLE else View.GONE
        detectedToggle.visibility = if (open) View.GONE else View.VISIBLE
        moveTo?.requestFocus()
    }

    /** The chosen folder's own name, whichever way it was chosen. */
    private fun currentFolderName(): String =
        folderPath?.name
            ?: folderUri?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: ""

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
        if (version.isEmpty()) throw IllegalArgumentException(getString(R.string.engine_version_missing))
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

    /**
     * Save keeps the setup in Enginehost's own storage ([GameConfigStore]); it
     * goes into the game folder only when the person turns on "Also save to
     * the game folder", and replacing an enginehost.json already there asks
     * first. The owner, 2026-10-23: the creation UI for configs is a helper,
     * and a save "will not overwrite the one in the game folder unless the
     * user explicitly flags to do so, and with a warning" ([GameConfigs]).
     */
    private fun save() {
        if (folderPath == null && folderUri == null) return report(R.string.could_not_save, getString(R.string.choose_folder_first))
        val document = try {
            buildDocument().also { EngineConfigReader.parseDocument(it.toString()) }
        } catch (error: Exception) {
            showDetectedFields(true)
            return report(R.string.could_not_save, error.message ?: getString(R.string.config_needs_attention))
        }
        val folder = currentFolder()
        val toFolder = saveToFolderSwitch.isChecked
        if (folder == null && !toFolder) return report(R.string.could_not_save, getString(R.string.no_path_for_enginehost))
        val folderHasConfig = folderPath?.let { File(it, CONFIG_FILE_NAME).isFile }
            ?: folderUri?.let { findChild(it, CONFIG_FILE_NAME) != null } ?: false
        if (toFolder && folderHasConfig) {
            Sheet(this).warning()
                .title(R.string.replace_folder_config_title)
                .message(R.string.replace_folder_config_message)
                .choice(R.string.replace_folder_config_confirm, Sheet.Tone.DANGER) { commitSave(document, folder, toFolder = true) }
                .choice(R.string.cancel) {}
                .show()
            return
        }
        commitSave(document, folder, toFolder)
    }

    private fun commitSave(document: JSONObject, folder: File?, toFolder: Boolean) {
        try {
            folder?.let { GameConfigStore.of(this).set(it, document) }
            if (toFolder) {
                val path = folderPath
                if (path != null) FolderConfigFile.write(path, document) else folderUri?.let { writeDocument(it, document) }
            }
            loadedDocument = document
            // Saved is the working config now; a pending test is settled by it.
            folder?.let { TestingConfigStore.of(this).discard(it) }
            showTestingRow()
            val folderStillApplies = !toFolder && folder != null && File(folder, CONFIG_FILE_NAME).isFile
            showConfigSource(folderHasConfig = toFolder || folderStillApplies)
            if (folderStillApplies) {
                report(R.string.saved_in_enginehost, getString(R.string.folder_config_still_applies))
            } else {
                toast(getString(if (toFolder) R.string.saved_in_enginehost_and_folder else R.string.saved_in_enginehost))
            }
        } catch (error: Exception) {
            report(R.string.could_not_save, error.message ?: getString(R.string.config_needs_attention))
        }
    }

    /** Says where this game's setup comes from, so a save that does not apply is never a surprise. */
    private fun showConfigSource(folderHasConfig: Boolean) {
        configSourceNote.visibility = View.VISIBLE
        configSourceNote.setText(if (folderHasConfig) R.string.config_source_folder else R.string.config_source_enginehost)
    }

    /**
     * Test runs the game with what is on screen as a TESTING configuration
     * (see [TestingConfigStore]): stored in Enginehost's own storage, never
     * in the game folder, so a test that does not work cannot replace a
     * working enginehost.json (the user, 2026-09-25). Afterwards Keep makes
     * it the folder's config and Discard rolls back; a new Test replaces it.
     *
     * Test always ends somewhere the person can see: the launch screen,
     * which says how the start went, or a sheet saying why it did not
     * begin (a toast was missed entirely on the rig, dq-coordinator-23 F15).
     */
    private fun testRun() {
        val folder = folderPath ?: run {
            val uri = folderUri ?: return report(R.string.test_not_started, getString(R.string.choose_folder_first))
            if (!StorageFolder.hasNativePathAccess()) {
                StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
                return toast(getString(R.string.grant_native_then_test))
            }
            StorageFolder.absolutePath(uri)
                ?: return report(R.string.test_not_started, getString(R.string.provider_needs_primary))
        }
        try {
            TestingConfigStore.of(this).set(folder, buildDocument())
            GameRunner.run(this, folder, testing = true)
        } catch (error: Exception) {
            showDetectedFields(true)
            report(R.string.test_not_started, error.message ?: getString(R.string.config_not_ready))
        }
    }

    /** The game folder as a path, however it was chosen, or null when the provider has none. */
    private fun currentFolder(): File? = folderPath ?: folderUri?.let { StorageFolder.absolutePath(it) }

    /** Game setup's own answer to a pending testing configuration: the row, and the fields show it. */
    private fun showTestingRow() {
        val folder = currentFolder() ?: return
        TestingConfigRow.show(this, detectionLabel, folder) {
            // Kept or discarded: the folder's config is the working one again.
            folderPath?.let { openCallerPath(it.absolutePath) } ?: folderUri?.let { load(it) }
            showTestingRow()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::detectionLabel.isInitialized) showTestingRow()
    }

    /** A result that must be read: a sheet, which stays until it is dismissed. */
    private fun report(@StringRes title: Int, message: String) {
        Sheet(this).title(title).message(message).show()
    }

    private fun writeDocument(treeUri: Uri, document: JSONObject) {
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
        const val ACTION_CONFIGURE = EnginehostIntents.ACTION_CONFIGURE
        const val EXTRA_PATH = EnginehostIntents.EXTRA_PATH
        const val EXTRA_CONFIG = EnginehostIntents.EXTRA_CONFIG
        private const val REQUEST_FOLDER = 20
        private const val REQUEST_NATIVE_FILES = 21
        private const val REQUEST_PLUGIN_SELECTION = 22
        private const val REQUEST_OPTION_FOLDER = 23
        private const val REQUEST_OPTION_FILE = 24
    }
}
