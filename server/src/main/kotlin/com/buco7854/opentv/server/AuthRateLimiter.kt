package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap

internal class AuthRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Bucket(var failures: Int, var blockedUntilMs: Long, var lastMs: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()

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

    private fun prune(now: Long) {
        if (buckets.size < 1_024) return
        buckets.entries.removeIf { now - it.value.lastMs > 60 * 60_000L }
    }
}

internal class AuthRateLimitedException(val retryAtMs: Long) : RuntimeException()
internal class InvalidCredentialsException : RuntimeException()
internal class InvalidChallengeException : RuntimeException()
internal class CsrfException : RuntimeException()
