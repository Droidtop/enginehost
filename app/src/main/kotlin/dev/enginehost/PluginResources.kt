package dev.enginehost

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Attaches a bundle's compiled resources so the engine inside it can
 * actually find them.
 *
 * A `ResourcesLoader` is attached to one `Resources` object, not to the
 * app. That is the whole subtlety here. An engine that resolves through
 * the activity it was handed sees a loader added to the activity's
 * `Resources`; an engine that keeps `context.applicationContext` -- Godot
 * does, through its own singleton -- resolves against the application's
 * `Resources`, a different object entirely. Attaching to only one of them
 * leaves the other answering the plugin's lookups out of the host's own
 * table, so every plugin whose engine holds the application context had to
 * rediscover the same hole and patch around it on its own side. Attaching
 * to both is what makes the bundle's resources reachable from anywhere a
 * plugin can reasonably ask.
 *
 * Neither of those is the Resources of an Activity created afterwards. A
 * bundle that carries its own Activity is instantiated by
 * EnginehostComponentFactory after Android has already built that
 * Activity's Context and Resources, and those, with the AssetManager behind
 * the Activity's getAssets() (KiriKiri's locale/en_us.xml, EasyRPG's
 * layouts), hold only the host's own paths. So the APKs are also attached
 * to every Activity this process creates from now on, from an activity
 * lifecycle callback: before its onCreate from API 29, and at the end of
 * Activity.onCreate below that (the first moment Android offers; a plugin
 * Activity calls super.onCreate before it inflates or reads anything).
 * Without it, Android 9 found none of a bundled Activity's resources (rig,
 * dq-ehfix-01: "No package ID 80 found", "locale/en_us.xml not found").
 */
object PluginResources {
    /**
     * Returns handles the caller must keep alive for as long as the
     * resources are in use, and close when they are not: a garbage-collected
     * [ParcelFileDescriptor] closes the underlying file.
     */
    fun attach(context: Context, apks: List<File>): List<AutoCloseable> {
        val targets = attachTargets(context)
        val handles = mutableListOf<AutoCloseable>()
        val loaders = mutableListOf<ResourcesLoader>()
        val paths = mutableListOf<String>()
        apks.forEach { apk ->
            require(apk.isFile) { "A signed plugin resource APK is missing" }
            ResourceTable.requireDistinctPackageId(apk)
            if (Build.VERSION.SDK_INT >= 30) {
                val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                val provider = ResourcesProvider.loadFromApk(descriptor)
                // One loader, added to every target: a ResourcesLoader is
                // explicitly shareable across Resources objects, so this
                // keeps a single provider per APK rather than one per view
                // of it.
                val loader = ResourcesLoader().apply { addProvider(provider) }
                targets.forEach { it.addLoaders(loader) }
                loaders += loader
                handles += provider
                handles += descriptor
            } else {
                targets.forEach { addAssetPath(it, apk.absolutePath) }
                paths += apk.absolutePath
            }
        }
        handles += attachToLaterActivities(context) { resources ->
            if (loaders.isNotEmpty()) resources.addLoaders(*loaders.toTypedArray())
            paths.forEach { addAssetPath(resources, it) }
        }
        return handles
    }

    /** Below API 30: adds [path] to [resources]' AssetManager (a path already there keeps its cookie). */
    private fun addAssetPath(resources: Resources, path: String) {
        val method = resources.assets.javaClass.getMethod("addAssetPath", String::class.java)
        require((method.invoke(resources.assets, path) as Int) != 0) { "Could not attach plugin resources" }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(resources.configuration, resources.displayMetrics)
    }

    /**
     * Every Activity created in this process from now on gets [attachTo] run
     * on its own Resources: before its onCreate from API 29
     * (onActivityPreCreated), at the end of Activity.onCreate below that. A
     * loader or path already there is left as it is, so the two callbacks
     * both firing on API 29 and up is harmless. The handle stops it when the
     * resources are released.
     */
    private fun attachToLaterActivities(context: Context, attachTo: (Resources) -> Unit): AutoCloseable {
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = attachTo(activity.resources)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < 29) attachTo(activity.resources)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        return AutoCloseable { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    /** Every distinct Resources object a plugin can reasonably resolve from. */
    private fun attachTargets(context: Context): List<Resources> {
        val targets = mutableListOf<Resources>()
        fun consider(resources: Resources?) {
            if (resources != null && targets.none { it === resources }) targets += resources
        }
        consider(runCatching { context.resources }.getOrNull())
        consider(runCatching { context.applicationContext?.resources }.getOrNull())
        check(targets.isNotEmpty()) { "No Resources object to attach plugin resources to" }
        return targets
    }
}

/**
 * Reads the package id out of an APK's compiled resource table.
 *
 * Android resource IDs are `0xPPTTEEEE`: the high byte is the package id.
 * An ordinary application table is built at aapt2's default `0x7f`, so a
 * bundle whose resource APK also used the default collides with the host's
 * own table. The platform does not report that as an error -- it answers
 * the plugin's lookup from whichever table it finds first and hands back a
 * host string, drawable or layout. The plugin then fails somewhere else
 * entirely, with no trace back to the cause. Refusing the load names the
 * problem where it is.
 */
internal object ResourceTable {
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val FRAMEWORK_PACKAGE_ID = 0x01

    /** The host's own package id, read from its own compiled table. */
    val hostPackageId: Int get() = R.string.app_name ushr 24

    fun requireDistinctPackageId(apk: File) {
        val table = resourceTable(apk) ?: return
        packageIds(table).forEach { id ->
            // 0x00 is a shared-library table whose id is assigned at load.
            if (id == 0) return@forEach
            require(id != hostPackageId && id != FRAMEWORK_PACKAGE_ID) {
                "${apk.name} compiled its resources at package id ${hex(id)}, which is already taken by " +
                    (if (id == FRAMEWORK_PACKAGE_ID) "the Android framework" else "Enginehost") +
                    ". Rebuild the bundle's resources at a distinct id, conventionally 0x80 or above."
            }
        }
    }

    private fun hex(id: Int) = "0x" + id.toString(16).padStart(2, '0')

    /** Bounded read: an untrusted archive must not be able to demand arbitrary memory. */
    private const val MAX_TABLE_BYTES = 32 * 1024 * 1024

    private fun resourceTable(apk: File): ByteArray? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return@use null
            zip.getInputStream(entry).use { stream ->
                val buffer = ByteArray(64 * 1024)
                val collected = ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (collected.size() + read > MAX_TABLE_BYTES) return@use null
                    collected.write(buffer, 0, read)
                }
                collected.toByteArray()
            }
        }
    }.getOrNull()

    /**
     * Walks the chunk list of a `resources.arsc` and collects each package
     * chunk's id. Split out from the file reading above so the parse itself
     * is testable on a plain JVM with no APK and no Context.
     */
    internal fun packageIds(table: ByteArray): List<Int> {
        if (table.size < 12) return emptyList()
        val buffer = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(0).toInt() and 0xFFFF != RES_TABLE_TYPE) return emptyList()
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        val declared = buffer.getInt(4)
        val end = if (declared in 1..table.size) declared else table.size
        val ids = mutableListOf<Int>()
        var offset = headerSize
        while (offset >= 8 && offset + 8 <= end) {
            val type = buffer.getShort(offset).toInt() and 0xFFFF
            val size = buffer.getInt(offset + 4)
            if (size < 8 || offset + size > end) break
            if (type == RES_TABLE_PACKAGE_TYPE && offset + 12 <= end) ids += buffer.getInt(offset + 8)
            offset += size
        }
        return ids
    }
}
