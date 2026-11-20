package com.salvia.salviabrowxer.core

/**
 * Lightweight, dependency-free diagnostic logger.
 *
 * Every call is appended to an in-memory ring buffer (the last
 * [MAX_ENTRIES] events) so that formerly silent catch blocks leave a visible
 * trace instead of disappearing. A sink hook lets other components (for
 * example the diagnostics screen) mirror or display the entries.
 */
object SalviaLog {

    const val MAX_ENTRIES = 250

    data class Entry(
        val time: Long = System.currentTimeMillis(),
        val level: String,
        val source: String,
        val detail: String
    )

    @Volatile
    var sink: ((Entry) -> Unit)? = null

    fun w(source: String, error: Throwable) {
        add("WARN", source, (error.javaClass.name + ": " + (error.message ?: "")).take(600))
    }

    fun w(source: String, detail: String, error: Throwable? = null) {
        val d = if (error != null) {
            detail + " :: " + error.javaClass.name + ": " + (error.message ?: "")
        } else detail
        add("WARN", source, d.take(600))
    }

    fun i(source: String, detail: String) = add("INFO", source, detail.take(600))

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    private fun add(level: String, source: String, detail: String) {
        val e = Entry(level = level, source = source, detail = detail)
        synchronized(buffer) {
            buffer.addLast(e)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        println("Salvia[$level][$source] $detail")
        try {
            sink?.invoke(e)
        } catch (_: Throwable) {
            // A broken sink must never crash the caller that tried to log.
        }
    }
}
