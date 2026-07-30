package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.AuthFlowDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.DeviceLinkPreviewDto
import com.buco7854.opentv.contract.DeviceLinkStartDto
import com.buco7854.opentv.contract.DeviceLinkStatusDto
import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HubBrowserSignInTest {
    @Test
    fun startPollAndApprovalKeepSecretsInsideTheFlow() = runTest {
        val transport = QueueTransport(
            body(
                DeviceLinkStartDto.serializer(),
                start(),
            ),
            body(
                DeviceLinkStatusDto.serializer(),
                DeviceLinkStatusDto(
                    status = "SCANNED",
                    preview = DeviceLinkPreviewDto("Alex", "alex"),
                    intervalMs = 1_000,
                    expiresAtMs = 50_000,
                ),
            ),
            body(
                DeviceLinkStatusDto.serializer(),
                DeviceLinkStatusDto(
                    status = "APPROVED",
                    flow = AuthFlowDto(
                        status = "AUTHENTICATED",
                        user = user(),
                        sessionToken = SESSION_TOKEN,
                    ),
                    intervalMs = 1_000,
                    expiresAtMs = 50_000,
                ),
            ),
        )
        val flow = HubBrowserSignIn(HubApi(transport))

        val request = flow.start(BASE, "Pixel")

        assertEquals("$BASE/link#t=link-secret&mode=sign-in", request.browserUrl)
        assertEquals(2_000, request.retryAfterMs)
        assertTrue(transport.seen.first().body.orEmpty().contains("\"browserSignIn\":true"))
        assertTrue(request.handle.toString().contains("redacted"))
        assertTrue(!request.handle.toString().contains("poll-secret"))
        assertTrue(!request.toString().contains("link-secret"))

        val pending = assertIs<HubBrowserSignInState.Pending>(flow.poll(request.handle))
        assertEquals("alex", pending.account?.username)
        assertEquals(1_000, pending.retryAfterMs)

        val approved = assertIs<HubBrowserSignInState.Approved>(flow.poll(request.handle))
        assertEquals(BASE, approved.session.baseUrl)
        assertEquals(SESSION_TOKEN, approved.session.token)
        assertEquals("alex", approved.session.user.username)
        assertTrue(!approved.toString().contains(SESSION_TOKEN))

        assertEquals(HubBrowserSignInState.Expired, flow.poll(request.handle))
        assertEquals(3, transport.seen.size)
    }

    @Test
    fun deniedAndExpiredAreTerminalStates() = runTest {
        val deniedTransport = QueueTransport(
            body(DeviceLinkStartDto.serializer(), start()),
            body(
                DeviceLinkStatusDto.serializer(),
                DeviceLinkStatusDto(
                    status = "DENIED",
                    intervalMs = 1_000,
                    expiresAtMs = 50_000,
                ),
            ),
        )
        val deniedFlow = HubBrowserSignIn(HubApi(deniedTransport))
        val denied = deniedFlow.start(BASE)
        assertEquals(HubBrowserSignInState.Denied, deniedFlow.poll(denied.handle))
        assertEquals(HubBrowserSignInState.Expired, deniedFlow.poll(denied.handle))

        val expiredTransport = QueueTransport(
            body(DeviceLinkStartDto.serializer(), start()),
            body(
                DeviceLinkStatusDto.serializer(),
                DeviceLinkStatusDto(
                    status = "EXPIRED",
                    intervalMs = 2_000,
                    expiresAtMs = 50_000,
                ),
            ),
        )
        val expiredFlow = HubBrowserSignIn(HubApi(expiredTransport))
        val expired = expiredFlow.start(BASE)
        assertEquals(HubBrowserSignInState.Expired, expiredFlow.poll(expired.handle))
    }

    @Test
    fun cancelForgetsTheHandleAndInvalidatesTheServerRequest() = runTest {
        val transport = QueueTransport(
            body(DeviceLinkStartDto.serializer(), start()),
            HttpResponseSpec(204, emptyMap(), ""),
        )
        val flow = HubBrowserSignIn(HubApi(transport))
        val request = flow.start(BASE)

        flow.cancel(request.handle)

        assertEquals("$BASE/api/v1/auth/link/cancel", transport.seen.last().url)
        assertEquals("""{"pollToken":"poll-secret"}""", transport.seen.last().body)
        assertEquals(HubBrowserSignInState.Expired, flow.poll(request.handle))
        assertEquals(2, transport.seen.size)
    }

    @Test
    fun anOffOriginOrQuerySecretBrowserUrlIsRejectedAndCancelled() = runTest {
        val unsafe = start().copy(
            verificationUriComplete = "https://evil.example/link?t=link-secret#mode=sign-in",
        )
        val transport = QueueTransport(
            body(DeviceLinkStartDto.serializer(), unsafe),
            HttpResponseSpec(204, emptyMap(), ""),
        )
        val flow = HubBrowserSignIn(HubApi(transport))

        assertFailsWith<HubProtocolException> {
            flow.start(BASE)
        }
        assertEquals("$BASE/api/v1/auth/link/cancel", transport.seen.last().url)
    }

    private class QueueTransport(vararg replies: HttpResponseSpec) : HttpTransport {
        private val responses = ArrayDeque(replies.toList())
        val seen = mutableListOf<HttpRequestSpec>()

        override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
            seen += request
            return responses.removeFirst()
        }
    }

    private companion object {
        const val BASE = "https://tv.example"
        const val SESSION_TOKEN = "session-secret"
        val json = Json { encodeDefaults = true }

        fun <T> body(serializer: KSerializer<T>, value: T) =
            HttpResponseSpec(200, emptyMap(), json.encodeToString(serializer, value))

        fun start() = DeviceLinkStartDto(
            pollToken = "poll-secret",
            linkToken = "link-secret",
            verificationUriComplete = "$BASE/link#t=link-secret&mode=sign-in",
            expiresAtMs = 50_000,
            intervalMs = 2_000,
        )

        fun user() = CurrentUserDto(
            id = "user-1",
            username = "alex",
            displayName = "Alex",
            role = "USER",
            authMethod = "PASSWORD",
            clientKind = "LINKED_DEVICE",
            authSessionId = "session-1",
            playlistIds = emptyList(),
            hasPassword = true,
        )
    }
}
