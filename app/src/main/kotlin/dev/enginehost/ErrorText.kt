package dev.enginehost

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes

/**
 * The sentence a person sees for a failure. Enginehost's own checks
 * (`require`, `check`) are written as sentences for people and are shown
 * as they are; a network failure is said plainly; anything else (an I/O
 * error's errno text, a parser's message) is logged and replaced by the
 * caller's own sentence. Screens used to show `error.message` whatever it
 * was (review, 2026-10-16).
 */
object ErrorText {
    /** What [of] shows, decided without a Context so it can be tested. */
    internal sealed class Shown {
        class Own(val text: String) : Shown()
        object Network : Shown()
        object Fallback : Shown()
    }

    internal fun classify(error: Throwable): Shown = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.SocketTimeoutException, is java.net.NoRouteToHostException,
        -> Shown.Network
        // A number that would not parse says so in the parser's words, not ours.
        is NumberFormatException -> Shown.Fallback
        is IllegalArgumentException, is IllegalStateException ->
            error.message?.takeIf { it.isNotBlank() }?.let { Shown.Own(it) } ?: Shown.Fallback
        else -> Shown.Fallback
    }

    fun of(context: Context, error: Throwable, @StringRes fallback: Int): String {
        Log.w(TAG, "Shown to the person as a sentence", error)
        return when (val shown = classify(error)) {
            is Shown.Own -> shown.text
            Shown.Network -> context.getString(R.string.error_network)
            Shown.Fallback -> context.getString(fallback)
        }
    }

    private const val TAG = "enginehost-error"
}
