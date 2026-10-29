package dev.enginehost

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.enginehost.runtime.ITextServices

/**
 * The runtime's one binding to Enginehost's text services in the main process
 * ([TextServicesService]; docs/text-services.md), shared by translation ([GameTranslation]) and the
 * voices on the device ([RemoteSpeech]). Bound once per launch. A call made before the service is
 * bound waits for it, the newest [MAX_PENDING] only; every call is oneway, so none stalls the game.
 */
class TextServicesClient(activity: Activity) {
    @Volatile private var services: ITextServices? = null
    private val pending = ArrayList<(ITextServices) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = ITextServices.Stub.asInterface(binder)
            val waiting = synchronized(pending) { services = bound; pending.toList().also { pending.clear() } }
            waiting.forEach { call -> runCatching { call(bound) } }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            services = null
        }
    }

    init {
        runCatching {
            activity.applicationContext.bindService(Intent(activity, TextServicesService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    /** Runs [call] on the service now, or once it is bound. Any thread. */
    fun call(call: (ITextServices) -> Unit) {
        val bound = synchronized(pending) {
            services.also {
                if (it == null) {
                    if (pending.size >= MAX_PENDING) pending.removeAt(0)
                    pending += call
                }
            }
        } ?: return
        runCatching { call(bound) }
    }

    private companion object {
        /** Calls kept for a service not bound yet. */
        const val MAX_PENDING = 50
    }
}
