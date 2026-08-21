package com.buco7854.opentv.server

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class IssuedMediaGrant(val token: String, val expiresAtMs: Long)

internal enum class PlaybackMediaTransport {
    SOLO,
    AUDIO_TRANSCODE,
    SHARED_HLS,
    RELAY,
    REMUX,
}

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
    private val resources = ConcurrentHashMap<
        String,
        MutableMap<String, PlaybackSessionRegistry.MediaScope>,
    >()
    private val resourceLock = Any()

    fun issue(actor: Actor, leaseId: String): IssuedMediaGrant {
        sessions.owned(actor, leaseId)
        val raw = AuthCrypto.token()
        val expires = clock() + ttlMs
        grants[key(raw)] = Grant(leaseId, actor.authSessionId, expires)
        prune()
        return IssuedMediaGrant(raw, expires)
    }

    fun validate(leaseId: String?, rawGrant: String?): PlaybackSessionRegistry.Live {
        if (leaseId.isNullOrBlank() || rawGrant.isNullOrBlank()) throw PlaybackRevokedException()
        val lease = sessions.lease(leaseId)
        if (!sessions.mediaAllowed(leaseId)) throw PlaybackRevokedException()
        val grant = grants[key(rawGrant)] ?: throw PlaybackRevokedException()
        if (grant.leaseId != leaseId ||
            grant.authSessionId != lease.authSessionId ||
            grant.expiresAtMs <= clock()
        ) {
            grants.remove(key(rawGrant))
            throw PlaybackRevokedException()
        }
        if (sessions.sameAccountConflict(lease.id) != null) {
            throw SameContentAlreadyPlayingException()
        }
        return lease
    }

    internal fun validateCapability(
        leaseId: String?,
        rawGrant: String?,
        capability: StreamCapability,
        transport: PlaybackMediaTransport = PlaybackMediaTransport.SOLO,
    ) {
        val lease = validate(leaseId, rawGrant)
        if (capability.leaseId != leaseId) throw PlaybackRevokedException()
        if (sessions.requiresSharedLiveTransport(lease.id)) {
            val required = if (capability.hlsResource) {
                PlaybackMediaTransport.SHARED_HLS
            } else {
                PlaybackMediaTransport.RELAY
            }
            if (transport != required) throw SameContentAlreadyPlayingException()
        } else if (
            sessions.requiresAudioRescue(lease.id) &&
            transport != PlaybackMediaTransport.AUDIO_TRANSCODE
        ) {
            // Once the solo player transfers its provider seat to AAC rescue, a late HLS
            // segment must not reopen the old physical read and steal that seat back.
            throw SameContentAlreadyPlayingException()
        }
    }

    fun validateSource(leaseId: String?, rawGrant: String?, source: String) {
        if (validate(leaseId, rawGrant).sourceUrl != source) {
            throw PlaybackRevokedException()
        }
    }

    fun bindResource(leaseId: String, resourceId: String) {
        sessions.withLiveLease(leaseId) {
            val scope = sessions.mediaScope(leaseId)
            synchronized(resourceLock) {
                resources.computeIfAbsent(resourceId) { ConcurrentHashMap() }[leaseId] = scope
            }
        }
    }

    fun validateResource(leaseId: String?, rawGrant: String?, resourceId: String) {
        val lease = validate(leaseId, rawGrant)
        val currentScope = sessions.mediaScope(lease.id)
        if (resources[resourceId]?.get(lease.id) != currentScope) throw PlaybackRevokedException()
    }

    /**
     * Releases only this lease's viewer attachment.
     *
     * @return true when the caller released the final attachment and the physical resource may stop.
     */
    fun releaseResource(
        leaseId: String?,
        rawGrant: String?,
        resourceId: String,
    ): Boolean {
        validateResource(leaseId, rawGrant, resourceId)
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
