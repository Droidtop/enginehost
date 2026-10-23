package dev.enginehost

import android.view.View

/**
 * Adds a list's rows a few per main-thread message, so a long list never holds
 * the main thread for seconds while its rows inflate. Cores built about sixty
 * cards inside one Handler message, 4.77 s of CPU, and Android answered
 * "isn't responding" on opening it, scrolling it and on Add a core
 * (Droidtop/tracker#291, #620 E1). Between batches the screen draws and the pad
 * and touch work. A newer [add] on the same batches, or [cancel], stops what an
 * older one had left.
 */
internal class RowBatches(private val host: View, private val perBatch: Int = 4) {
    private var generation = 0

    /** Adds [items] with [addRow], [perBatch] per message, then runs [done]. */
    fun <T> add(items: List<T>, addRow: (T) -> Unit, done: () -> Unit = {}) {
        val mine = ++generation
        fun step(from: Int) {
            if (mine != generation) return
            val until = minOf(from + perBatch, items.size)
            for (index in from until until) addRow(items[index])
            if (until < items.size) host.post { step(until) } else done()
        }
        step(0)
    }

    fun cancel() {
        generation++
    }
}
