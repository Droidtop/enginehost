package dev.enginehost

import dalvik.system.DexClassLoader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The application class loader of every Enginehost process, installed by
 * [EnginehostComponentFactory.instantiateClassLoader].
 *
 * Android resolves a great deal through the Context's class loader rather
 * than through whoever asked: LayoutInflater turns the class names in a
 * layout XML into views with `context.getClassLoader()`, and so do
 * Fragment, View and Animator inflation. A bundled activity is constructed
 * from its plugin's dex, but its Context is still Enginehost's, so a layout
 * naming a class the plugin carries (EasyRPG's Material drawer was the
 * first) failed with ClassNotFoundException on the host's own dex path.
 *
 * This loader delegates to the host first, exactly as before, and then to
 * every plugin loader [attach]ed in this process. Plugin loaders have it as
 * their parent, so a plugin class is defined once, by its own loader, and
 * found from either side. The plugin side must search only its own dex here
 * (see [PluginDexLoader.findOwn]), or the parent-first walk would recurse.
 */
class RuntimeClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val plugins = CopyOnWriteArrayList<PluginDexLoader>()

    fun attach(loader: PluginDexLoader) {
        if (loader !in plugins) plugins += loader
    }

    override fun findClass(name: String): Class<*> {
        for (plugin in plugins) {
            try {
                return plugin.findOwn(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        throw ClassNotFoundException(name)
    }

    companion object {
        /** Makes [loader]'s classes visible through the process's application class loader. */
        fun attach(appClassLoader: ClassLoader, loader: PluginDexLoader) {
            (appClassLoader as? RuntimeClassLoader)?.attach(loader)
        }
    }
}

/** A plugin's dex loader, able to answer for its own dex alone. */
class PluginDexLoader(
    dexPath: String,
    optimizedDirectory: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
    /** This loader's own dex files only, without asking the parent. */
    fun findOwn(name: String): Class<*> = findClass(name)
}
