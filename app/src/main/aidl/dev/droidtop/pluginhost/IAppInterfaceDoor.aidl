package dev.droidtop.pluginhost;

/**
 * droidtop's door for app interface plugins (docs/plugin-api.md 1.3): an installed, approved, verified plugin's app
 * calls knock() when it needs droidtop and has no broker yet (it started a game on its own). droidtop checks the
 * caller, binds the app's plugin service and hands it its broker in loadContained. It carries no data. Methods are
 * only ever appended.
 */
interface IAppInterfaceDoor {
    /** True when droidtop will connect the calling app's plugin; false for any other caller, or a plugin not approved. */
    boolean knock();
}
