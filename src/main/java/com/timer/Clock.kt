package com.timer

/** Time source in milliseconds, injected so time-based logic can run on virtual time in tests. */
fun interface Clock {
    fun nowMillis(): Long
}
