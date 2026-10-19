package com.maynutlab.astralpatcher.ui

/** Coalesces refreshes while preserving a request received during inspection or file work. */
internal class LatestRequestGate {
    var generation: Long = 0
        private set
    private var active: Long? = null
    private var pending = false

    fun request() {
        generation += 1
        pending = true
    }

    fun startIfIdle(blocked: Boolean): Long? {
        if (blocked || active != null || !pending) return null
        pending = false
        return generation.also { active = it }
    }

    fun isCurrent(ticket: Long): Boolean = ticket == generation

    fun finish(ticket: Long) {
        check(active == ticket) { "Refresh completion does not match its request" }
        active = null
    }
}
