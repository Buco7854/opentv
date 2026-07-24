package com.buco7854.opentv.server

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class IssuedMediaGrant(val token: String, val expiresAtMs: Long)

/**
 * A media grant narrows an authenticated browser session to one live playback
 * lease. Tokens are short lived, stored only as hashes, and become useless as
 * soon as the lease is tombstoned.
 */
class PlaybackMediaGrants(
    private val sessions: PlaybackSessionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60_000L,
) {
    private data class Grant(
        val leaseId: String,
        val authSessionId: String,
        val expiresAtMs: Long,
    )

    private val grants = ConcurrentHashMap<String, Grant>()
    private val resources = ConcurrentHashMap<String, MutableSet<String>>()
    private val resourceLock = Any()

    fun issue(actor: Actor, leaseId: String): IssuedMediaGrant {
        sessions.owned(actor, leaseId)
        val raw = AuthCrypto.token()
        val expires = clock() + ttlMs
        grants[key(raw)] = Grant(leaseId, actor.authSessionId, expires)
        prune()
        return IssuedMediaGrant(raw, expires)
    }

    fun validate(actor: Actor, leaseId: String?, rawGrant: String?) {
        if (leaseId.isNullOrBlank() || rawGrant.isNullOrBlank()) throw PlaybackRevokedException()
        sessions.owned(actor, leaseId)
        val grant = grants[key(rawGrant)] ?: throw PlaybackRevokedException()
        if (grant.leaseId != leaseId ||
            grant.authSessionId != actor.authSessionId ||
            grant.expiresAtMs <= clock()
        ) {
            grants.remove(key(rawGrant))
            throw PlaybackRevokedException()
        }
    }

    fun validateSource(actor: Actor, leaseId: String?, rawGrant: String?, source: String) {
        validate(actor, leaseId, rawGrant)
        if (sessions.owned(actor, requireNotNull(leaseId)).sourceUrl != source) {
            throw PlaybackRevokedException()
        }
    }

    fun bindResource(actor: Actor, leaseId: String, resourceId: String) {
        sessions.owned(actor, leaseId)
        synchronized(resourceLock) {
            resources.computeIfAbsent(resourceId) { ConcurrentHashMap.newKeySet() }.add(leaseId)
        }
    }

    fun validateResource(actor: Actor, leaseId: String?, rawGrant: String?, resourceId: String) {
        validate(actor, leaseId, rawGrant)
        if (resources[resourceId]?.contains(leaseId) != true) throw PlaybackRevokedException()
    }

    /**
     * Releases only this lease's viewer attachment.
     *
     * @return true when the caller released the final attachment and the physical resource may stop.
     */
    fun releaseResource(
        actor: Actor,
        leaseId: String?,
        rawGrant: String?,
        resourceId: String,
    ): Boolean {
        validateResource(actor, leaseId, rawGrant, resourceId)
        return synchronized(resourceLock) {
            var finalAttachment = false
            resources.computeIfPresent(resourceId) { _, leases ->
                leases.remove(requireNotNull(leaseId))
                if (leases.isEmpty()) {
                    finalAttachment = true
                    null
                } else {
                    leases
                }
            }
            finalAttachment
        }
    }

    fun hasAttachments(resourceId: String): Boolean = resources[resourceId]?.isNotEmpty() == true

    fun revokeLease(leaseId: String): Set<String> {
        grants.entries.removeIf { it.value.leaseId == leaseId }
        return detachResources(leaseId)
    }

    fun detachResources(leaseId: String): Set<String> {
        return synchronized(resourceLock) {
            val finalResources = mutableSetOf<String>()
            resources.entries.removeIf { (resourceId, leases) ->
                leases.remove(leaseId)
                leases.isEmpty().also { empty -> if (empty) finalResources += resourceId }
            }
            finalResources
        }
    }

    private fun prune() {
        val now = clock()
        grants.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun key(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(AuthCrypto.hashToken(raw))
}

internal fun mediaUrl(path: String, encryptedSource: String, leaseId: String, grant: String): String {
    return "$path?u=${urlEncode(encryptedSource)}&sid=${urlEncode(leaseId)}&g=${urlEncode(grant)}"
}

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
