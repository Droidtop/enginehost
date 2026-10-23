package dev.droidtop.pluginhost;

import android.os.ParcelFileDescriptor;

/**
 * The plugin to host broker (docs/plugin-api.md 1.4). :app hands ONE object
 * of this interface to each plugin it loads; every call a plugin makes
 * beyond its own process goes through it, and the caller is known from which
 * object was called, never from anything in the request.
 *
 * requestJson is {"api", "version", "op", "args"}; the return value is
 * {"ok":true,"data":{...}} or {"ok":false,"error":{"code","message"}} with
 * one of the closed error codes. It never throws across the binder.
 */
interface IPluginHostBroker {
    String call(String requestJson);

    /**
     * The same request for an op that hands the plugin a file instead of JSON
     * (data.open, files.open, files.shared.open; docs/plugin-api.md 3 D4, D5, H1):
     * the descriptor, or null when the call was refused. reply[0] is the reply
     * JSON either way, so a refusal still says why. A contained plugin has no
     * file system of its own, so this is how a file reaches it at all.
     */
    ParcelFileDescriptor open(String requestJson, out String[] reply);
}
