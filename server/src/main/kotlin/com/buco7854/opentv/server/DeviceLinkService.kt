package com.buco7854.opentv.server

import androidx.sqlite.SQLiteException
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.net.URI
import java.util.Base64
import java.util.UUID

@Serializable
private data class DeviceLinkPayload(
    val linkTokenHash: String,
    val deviceName: String?,
    val userAgent: String?,
    val ip: String?,
    val scannedAtMs: Long? = null,
    val approvedAtMs: Long? = null,
    val deniedAtMs: Long? = null,
    val approvedAuthMethod: String? = null,
)

internal data class DeviceLinkPollResult(
    val status: DeviceLinkStatusDto,
    val sessionToken: String? = null,
)

/** Device authorization flow independent from HTTP origin, cookie, and header handling. */
class DeviceLinkService(
    private val db: ServerUserDatabase,
    private val auth: AuthService,
    private val config: AuthConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class ResolvedLink(
        val row: AuthChallengeRow,
        val payload: DeviceLinkPayload,
        val limitKeys: Array<String>,
    )

    private val mutation = Mutex()
    private val limiter = AuthRateLimiter(clock)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /**
     * [base] is the address the waiting device reached us on: the QR has to send the phone
     * somewhere it can actually open, which the configured default rarely is.
     */
    suspend fun start(
        request: DeviceLinkStartRequestDto,
        userAgent: String?,
        clientIp: String,
        base: URI = config.publicUrl,
    ): DeviceLinkStartDto = mutation.withLock {
        val now = clock()
        limiter.consume("device-link-start:global", limit = 200, windowMs = 60_000)
        limiter.consume("device-link-start:ip:$clientIp", limit = 10, windowMs = 60_000)
        db.challenges().prune(now)
        if (db.challenges().activeCount(ChallengeKind.DEVICE_LINK, now) >= MAX_ACTIVE_LINKS) {
            throw AuthRateLimitedException(now + 60_000)
        }
        val deviceName = sanitizeDisplayValue(request.deviceName, MAX_DEVICE_NAME_CODE_POINTS)
        val cleanAgent = sanitizeDisplayValue(userAgent, MAX_USER_AGENT_CODE_POINTS)
        var collision: SQLiteException? = null
        repeat(INSERT_ATTEMPTS) {
            val pollToken = AuthCrypto.token()
            val linkToken = AuthCrypto.token()
            val expiresAtMs = now + LINK_TTL_MS
            val payload = DeviceLinkPayload(
                linkTokenHash = b64.encodeToString(AuthCrypto.hashToken(linkToken)),
                deviceName = deviceName,
                userAgent = cleanAgent,
                ip = clientIp.takeIf(String::isNotBlank),
            )
            try {
                db.challenges().insert(
                    AuthChallengeRow(
                        id = UUID.randomUUID().toString(),
                        userId = null,
                        kind = ChallengeKind.DEVICE_LINK,
                        tokenHash = AuthCrypto.hashToken(pollToken),
                        payloadJson = Json.encodeToString(payload),
                        attempts = 0,
                        createdAtMs = now,
                        expiresAtMs = expiresAtMs,
                        consumedAtMs = null,
                    ),
                )
                val verificationUri = base.toString().trimEnd('/') + "/link"
                return@withLock DeviceLinkStartDto(
                    pollToken = pollToken,
                    linkToken = linkToken,
                    verificationUriComplete = "$verificationUri#t=$linkToken",
                    expiresAtMs = expiresAtMs,
                    intervalMs = PENDING_POLL_INTERVAL_MS,
                )
            } catch (error: SQLiteException) {
                collision = error
            }
        }
        throw AuthRateLimitedException(now + 1_000L).also { failure ->
            collision?.let(failure::addSuppressed)
        }
    }

    internal suspend fun poll(request: DeviceLinkPollRequestDto): DeviceLinkPollResult {
        val now = clock()
        if (request.pollToken.isBlank() || request.pollToken.length > MAX_TOKEN_LENGTH) {
            return expired(now)
        }
        val tokenHash = AuthCrypto.hashToken(request.pollToken)
        val row = db.challenges().byToken(ChallengeKind.DEVICE_LINK, tokenHash)
        val payload = row?.let(::decodePayload)
        val user = row?.userId?.let { db.users().get(it) }
        val preview = if (payload?.scannedAtMs != null && user != null) {
            DeviceLinkPreviewDto(user.displayName, user.username)
        } else {
            null
        }
        val pollInterval = if (preview == null) {
            PENDING_POLL_INTERVAL_MS
        } else {
            SCANNED_POLL_INTERVAL_MS
        }
        limiter.consume(
            "device-link-poll:${b64.encodeToString(tokenHash)}",
            limit = 1,
            windowMs = pollInterval,
        )
        row ?: return expired(now)
        if (row.consumedAtMs != null || row.expiresAtMs <= now || payload == null) {
            return expired(row.expiresAtMs, preview)
        }
        if (payload.scannedAtMs == null || user == null) {
            return status("PENDING", row.expiresAtMs, PENDING_POLL_INTERVAL_MS)
        }
        if (payload.deniedAtMs != null) {
            return status(
                "DENIED",
                row.expiresAtMs,
                SCANNED_POLL_INTERVAL_MS,
                preview,
            )
        }
        if (payload.approvedAtMs == null) {
            return status(
                "SCANNED",
                row.expiresAtMs,
                SCANNED_POLL_INTERVAL_MS,
                preview,
            )
        }
        val method = payload.approvedAuthMethod
            ?: return expired(row.expiresAtMs, preview)
        if (user.status != UserStatus.ACTIVE) {
            return status(
                "DENIED",
                row.expiresAtMs,
                SCANNED_POLL_INTERVAL_MS,
                preview,
            )
        }
        val result = try {
            auth.claimDeviceLink(
                challengeId = row.id,
                userId = user.id,
                method = method,
                deviceName = sanitizeDisplayValue(
                    payload.deviceName,
                    MAX_DEVICE_NAME_CODE_POINTS,
                ),
            )
        } catch (_: InvalidChallengeException) {
            return expired(row.expiresAtMs, preview)
        }
        return DeviceLinkPollResult(
            DeviceLinkStatusDto(
                status = "APPROVED",
                preview = preview,
                flow = result.flow,
                intervalMs = SCANNED_POLL_INTERVAL_MS,
                expiresAtMs = row.expiresAtMs,
            ),
            result.sessionToken,
        )
    }

    suspend fun lookup(
        actor: Actor,
        request: DeviceLinkTokenRequestDto,
        clientIp: String,
    ): DeviceLinkLookupDto {
        val resolved = resolve(request, clientIp)
        val now = clock()
        val updated = resolved.payload.copy(
            scannedAtMs = resolved.payload.scannedAtMs ?: now,
        )
        if (db.challenges().claimDeviceLink(
                resolved.row.id,
                actor.userId,
                Json.encodeToString(updated),
                now,
            ) != 1
        ) {
            fail(resolved)
        }
        limiter.success(*resolved.limitKeys)
        return DeviceLinkLookupDto(
            deviceName = updated.deviceName,
            userAgent = updated.userAgent,
            ip = updated.ip,
            requestedAtMs = resolved.row.createdAtMs,
            expiresAtMs = resolved.row.expiresAtMs,
        )
    }

    suspend fun approve(
        actor: Actor,
        request: DeviceLinkTokenRequestDto,
        clientIp: String,
    ) {
        auth.requireMfaSatisfied(actor)
        val resolved = resolve(request, clientIp)
        if (resolved.row.userId != actor.userId ||
            resolved.payload.scannedAtMs == null ||
            resolved.payload.approvedAtMs != null ||
            resolved.payload.deniedAtMs != null
        ) {
            fail(resolved)
        }
        val now = clock()
        val updated = resolved.payload.copy(
            approvedAtMs = now,
            approvedAuthMethod = actor.authMethod,
        )
        decide(resolved, actor.userId, updated, now)
    }

    suspend fun deny(
        actor: Actor,
        request: DeviceLinkTokenRequestDto,
        clientIp: String,
    ) {
        val resolved = resolve(request, clientIp)
        if (resolved.row.userId != actor.userId ||
            resolved.payload.scannedAtMs == null ||
            resolved.payload.approvedAtMs != null ||
            resolved.payload.deniedAtMs != null
        ) {
            fail(resolved)
        }
        val now = clock()
        decide(resolved, actor.userId, resolved.payload.copy(deniedAtMs = now), now)
    }

    private suspend fun decide(
        resolved: ResolvedLink,
        userId: String,
        payload: DeviceLinkPayload,
        now: Long,
    ) {
        if (db.challenges().completeDeviceLinkDecision(
                resolved.row.id,
                userId,
                Json.encodeToString(payload),
                now,
            ) != 1
        ) {
            fail(resolved)
        }
        limiter.success(*resolved.limitKeys)
    }

    private suspend fun resolve(
        request: DeviceLinkTokenRequestDto,
        clientIp: String,
    ): ResolvedLink {
        val linkToken = request.linkToken.trim()
        require(linkToken.isNotEmpty()) { "A link token is required" }
        require(linkToken.length <= MAX_TOKEN_LENGTH) { "Device link token is too large" }
        val tokenHash = AuthCrypto.hashToken(linkToken)
        val keys = arrayOf(
            "device-link-action:ip:$clientIp",
            "device-link-action:${b64.encodeToString(tokenHash)}",
        )
        limiter.check(*keys)
        val row = findByLinkToken(tokenHash)
        if (row == null) {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        val payload = decodePayload(row)
        if (payload == null || row.attempts >= MAX_FAILED_ATTEMPTS) {
            db.challenges().incrementAttempts(row.id)
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        return ResolvedLink(row, payload, keys)
    }

    private suspend fun findByLinkToken(expected: ByteArray): AuthChallengeRow? {
        var matched: AuthChallengeRow? = null
        db.challenges().active(ChallengeKind.DEVICE_LINK, clock()).forEach { row ->
            val actual = decodePayload(row)?.linkTokenHash
                ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
                ?: ByteArray(expected.size)
            if (MessageDigest.isEqual(expected, actual)) matched = row
        }
        return matched
    }

    private suspend fun fail(resolved: ResolvedLink): Nothing {
        db.challenges().incrementAttempts(resolved.row.id)
        limiter.fail(*resolved.limitKeys)
        throw InvalidChallengeException()
    }

    private fun decodePayload(row: AuthChallengeRow): DeviceLinkPayload? =
        runCatching { Json.decodeFromString<DeviceLinkPayload>(row.payloadJson) }.getOrNull()

    private fun status(
        value: String,
        expiresAtMs: Long,
        intervalMs: Long,
        preview: DeviceLinkPreviewDto? = null,
        flow: AuthFlowDto? = null,
    ) = DeviceLinkPollResult(
        DeviceLinkStatusDto(
            status = value,
            preview = preview,
            flow = flow,
            intervalMs = intervalMs,
            expiresAtMs = expiresAtMs,
        ),
    )

    private fun expired(
        expiresAtMs: Long,
        preview: DeviceLinkPreviewDto? = null,
    ) = status(
        "EXPIRED",
        expiresAtMs,
        if (preview == null) PENDING_POLL_INTERVAL_MS else SCANNED_POLL_INTERVAL_MS,
        preview,
    )

    private fun sanitizeDisplayValue(value: String?, maxCodePoints: Int): String? {
        if (value == null) return null
        val result = StringBuilder()
        var index = 0
        var count = 0
        while (index < value.length && count < maxCodePoints) {
            val codePoint = value.codePointAt(index)
            if (!Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint)
                count += 1
            }
            index += Character.charCount(codePoint)
        }
        return result.toString().trim().ifBlank { null }
    }

    private companion object {
        const val LINK_TTL_MS = 5 * 60_000L
        const val PENDING_POLL_INTERVAL_MS = 2_000L
        const val SCANNED_POLL_INTERVAL_MS = 1_000L
        const val MAX_ACTIVE_LINKS = 4_096
        const val MAX_FAILED_ATTEMPTS = 5
        const val INSERT_ATTEMPTS = 3
        const val MAX_TOKEN_LENGTH = 512
        const val MAX_DEVICE_NAME_CODE_POINTS = 64
        const val MAX_USER_AGENT_CODE_POINTS = 256
    }
}
