package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap

internal class AuthRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Bucket(var failures: Int, var blockedUntilMs: Long, var lastMs: Long)
    private data class Window(val attempts: ArrayDeque<Long> = ArrayDeque())
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val windows = ConcurrentHashMap<String, Window>()

    @Synchronized
    fun check(vararg keys: String) {
        val now = clock()
        prune(now)
        keys.forEach { key ->
            val bucket = buckets[key] ?: return@forEach
            if (bucket.blockedUntilMs > now) throw AuthRateLimitedException(bucket.blockedUntilMs)
        }
    }

    @Synchronized
    fun fail(vararg keys: String) {
        val now = clock()
        prune(now)
        keys.forEach { key ->
            val bucket = buckets.getOrPut(key) { Bucket(0, 0, now) }
            if (now - bucket.lastMs > 15 * 60_000) bucket.failures = 0
            bucket.failures += 1
            bucket.lastMs = now
            if (bucket.failures >= 5) {
                val exponent = (bucket.failures - 5).coerceAtMost(6)
                bucket.blockedUntilMs = now + (2_000L shl exponent)
            }
        }
    }

    @Synchronized
    fun success(vararg keys: String) = keys.forEach(buckets::remove)

    @Synchronized
    fun consume(key: String, limit: Int, windowMs: Long) {
        require(limit > 0 && windowMs > 0)
        val now = clock()
        val attempts = windows.getOrPut(key, ::Window).attempts
        while (attempts.firstOrNull()?.let { now - it >= windowMs } == true) {
            attempts.removeFirst()
        }
        if (attempts.size >= limit) {
            throw AuthRateLimitedException(attempts.first() + windowMs)
        }
        attempts.addLast(now)
        if (windows.size >= 1_024) {
            windows.entries.removeIf { (_, window) ->
                window.attempts.lastOrNull()?.let { now - it >= windowMs } != false
            }
        }
    }

    private fun prune(now: Long) {
        if (buckets.size < 1_024) return
        buckets.entries.removeIf { now - it.value.lastMs > 60 * 60_000L }
    }
}

internal class AuthRateLimitedException(val retryAtMs: Long) : RuntimeException()
internal class InvalidCredentialsException : RuntimeException()
internal class InvalidChallengeException : RuntimeException()
internal class CsrfException : RuntimeException()
