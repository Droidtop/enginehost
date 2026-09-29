package dev.enginehost

/**
 * A ring buffer that tracks the last host events for debugging reports.
 * Events are recorded automatically and included in problem reports.
 */
class HostEventTracker private constructor() {
    data class Event(val timestamp: Long, val type: String, val detail: String)

    private val events = ArrayDeque<Event>(CAPACITY)

    fun record(type: String, detail: String = "") {
        val event = Event(System.currentTimeMillis(), type, detail)
        if (events.size >= CAPACITY) {
            events.removeFirst()
        }
        events.addLast(event)
    }

    fun dump(): String = events.joinToString(sep = System.lineSeparator()) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(it.timestamp))
        "$time: ${it.type}${if (it.detail.isNotBlank()) " - ${it.detail}" else ""}"
    }

    companion object {
        private const val CAPACITY = 30
        private val instance = HostEventTracker()

        fun get(): HostEventTracker = instance
    }
}