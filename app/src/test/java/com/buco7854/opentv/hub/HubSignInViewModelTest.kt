package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.AuthCapabilitiesDto
import com.buco7854.opentv.contract.AuthFlowDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.DeviceLinkStartDto
import com.buco7854.opentv.contract.DeviceLinkStatusDto
import com.buco7854.opentv.contract.ServerInfoDto
import com.buco7854.opentv.contract.TotpEnrollmentDto
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubSignInViewModelTest {
    @Test
    fun `re-authentication updates the requested hub instead of adding another`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> authenticated() }
        }
        val sink = FakeSink()
        val viewModel = newViewModel(gateway, sink, reauthenticateHubId = HUB_ID)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is HubSignInState.MethodChooser)
        viewModel.selectPassword()
        viewModel.submitPassword("alice", "new password")
        advanceUntilIdle()

        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
        assertEquals(listOf(HUB_ID), sink.reauthenticatedIds)
        assertTrue(sink.addedHubs.isEmpty())
        assertEquals(listOf(HUB_ID), sink.refreshedIds)
    }

    @Test
    fun `a removed re-authentication target falls back to the add flow`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> authenticated() }
        }
        val sink = FakeSink().apply { storedSource = null }
        val viewModel = newViewModel(gateway, sink, reauthenticateHubId = HUB_ID)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is HubSignInState.UrlEntry)
        viewModel.probe(BASE_URL)
        advanceUntilIdle()
        viewModel.selectPassword()
        viewModel.submitPassword("alice", "password")
        advanceUntilIdle()

        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
        assertEquals(listOf("hub.example" to BASE_URL), sink.addedHubs)
        assertTrue(sink.reauthenticatedIds.isEmpty())
    }


    @Test
    fun `password authentication finishes and caches identity`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> authenticated() }
        }
        val sink = FakeSink()
        val viewModel = probedViewModel(gateway, sink)

        viewModel.selectPassword()
        viewModel.submitPassword("alice", "correct horse")
        advanceUntilIdle()

        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
        assertEquals(listOf("hub.example" to BASE_URL), sink.addedHubs)
        assertEquals(listOf(HUB_ID), sink.refreshedIds)
    }

    @Test
    fun `identity refresh failure does not report an already saved hub as unsaved`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> authenticated() }
        }
        val sink = FakeSink().apply {
            refreshFailure = IllegalStateException("identity endpoint unavailable")
        }
        val viewModel = probedViewModel(gateway, sink)

        viewModel.selectPassword()
        viewModel.submitPassword("alice", "correct horse")
        advanceUntilIdle()

        assertEquals(listOf("hub.example" to BASE_URL), sink.addedHubs)
        assertEquals(listOf(HUB_ID), sink.refreshedIds)
        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
    }

    @Test
    fun `totp and recovery both complete an MFA challenge`() = runTest {
        val totpGateway = FakeGateway().apply {
            passwordCall = { _, _ -> mfaRequired() }
            totpCall = { _, _ -> authenticated() }
        }
        val totpSink = FakeSink()
        val totpViewModel = probedViewModel(totpGateway, totpSink)

        totpViewModel.selectPassword()
        totpViewModel.submitPassword("alice", "password")
        advanceUntilIdle()
        assertEquals(
            HubSignInState.MfaChallenge(CHALLENGE, listOf("totp", "recovery")),
            totpViewModel.state.value,
        )

        totpViewModel.submitTotp("123456")
        advanceUntilIdle()
        assertEquals(HubSignInState.Done(HUB_ID), totpViewModel.state.value)
        assertEquals(listOf(CHALLENGE to "123456"), totpGateway.totpSubmissions)

        val recoveryGateway = FakeGateway().apply {
            passwordCall = { _, _ -> mfaRequired() }
            recoveryCall = { _, _ -> authenticated() }
        }
        val recoverySink = FakeSink()
        val recoveryViewModel = probedViewModel(recoveryGateway, recoverySink)
        recoveryViewModel.selectPassword()
        recoveryViewModel.submitPassword("bob", "password")
        advanceUntilIdle()

        recoveryViewModel.submitRecoveryCode("recovery-code")
        advanceUntilIdle()

        assertEquals(HubSignInState.Done(HUB_ID), recoveryViewModel.state.value)
        assertEquals(listOf(CHALLENGE to "recovery-code"), recoveryGateway.recoverySubmissions)
    }

    @Test
    fun `required TOTP enrollment starts and completes before finishing`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ ->
                AuthFlowDto(status = "ENROLLMENT_REQUIRED", challenge = ENROLLMENT_CHALLENGE)
            }
            enrollment = TotpEnrollmentDto(
                challenge = ENROLLMENT_CHALLENGE,
                secret = "SECRET",
                uri = "otpauth://totp/OpenTV",
                expiresAtMs = 60_000,
            )
            enrollmentCompleteCall = { _, _ -> authenticated() }
        }
        val viewModel = probedViewModel(gateway, FakeSink())
        viewModel.selectPassword()

        viewModel.submitPassword("alice", "password")
        advanceUntilIdle()

        assertEquals(
            HubSignInState.TotpEnrollment(
                secret = "SECRET",
                otpauthUri = "otpauth://totp/OpenTV",
                challenge = ENROLLMENT_CHALLENGE,
                expiresAtMs = 60_000,
            ),
            viewModel.state.value,
        )

        viewModel.completeTotpEnrollment("654321")
        advanceUntilIdle()

        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
        assertEquals(
            listOf(ENROLLMENT_CHALLENGE to "654321"),
            gateway.enrollmentSubmissions,
        )
    }

    @Test
    fun `wrong password keeps username and returns to password entry`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ ->
                throw HubUnauthorizedException(
                    "invalid_credentials",
                    "request contained $SESSION_TOKEN",
                )
            }
        }
        val viewModel = probedViewModel(gateway, FakeSink())
        viewModel.selectPassword()

        viewModel.submitPassword("typed-user", "wrong")
        advanceUntilIdle()

        val state = viewModel.state.value as HubSignInState.Password
        assertEquals("typed-user", state.username)
        assertEquals(HubSignInFailure.BAD_CREDENTIALS, state.error)
        assertFalse(state.toString().contains(SESSION_TOKEN))
    }

    @Test
    fun `a protocol violation reads as one failure and keeps its detail in the log`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> AuthFlowDto(status = "AUTHENTICATED", sessionToken = null) }
        }
        val viewModel = probedViewModel(gateway, FakeSink())
        viewModel.selectPassword()
        ErrorLog.clear()

        viewModel.submitPassword("alice", "password")
        advanceUntilIdle()

        // The user cannot act on which part of the response was wrong...
        val state = viewModel.state.value as HubSignInState.Password
        assertEquals(HubSignInFailure.UNEXPECTED_RESPONSE, state.error)
        // ...but the shape of it stays debuggable.
        val logged = ErrorLog.entries.value.single()
        assertEquals("Hub sign-in", logged.tag)
        assertTrue(logged.message.contains("session token"))
    }

    @Test
    fun `probe rejects other products and newer APIs without trapping navigation`() = runTest {
        val otherGateway = FakeGateway().apply {
            info = ServerInfoDto(product = "something-else", apiVersion = 1, version = "1")
        }
        val other = newViewModel(otherGateway, FakeSink())
        other.probe(BASE_URL)
        advanceUntilIdle()

        assertTrue(other.state.value is HubSignInState.ProbeFailed)
        other.back()
        assertTrue(other.state.value is HubSignInState.UrlEntry)

        val newerGateway = FakeGateway().apply {
            info = ServerInfoDto(
                product = "opentv",
                apiVersion = HubSignInViewModel.SUPPORTED_API_VERSION + 1,
                version = "future",
            )
        }
        val newer = newViewModel(newerGateway, FakeSink())
        newer.probe(BASE_URL)
        advanceUntilIdle()

        assertEquals(
            HubSignInState.UpdateRequired(
                BASE_URL,
                HubSignInViewModel.SUPPORTED_API_VERSION + 1,
                HubSignInViewModel.SUPPORTED_API_VERSION,
            ),
            newer.state.value,
        )
        newer.back()
        assertTrue(newer.state.value is HubSignInState.UrlEntry)
    }

    @Test
    fun `device link honors interval changes and capacity retry-after`() = runTest {
        val pollTimes = mutableListOf<Long>()
        val gateway = FakeGateway().apply {
            link = linkStart(intervalMs = 1_000)
            pollResults.addAll(
                listOf(
                    { linkStatus("PENDING", intervalMs = 2_000) },
                    { linkStatus("PENDING", intervalMs = 3_000) },
                    { linkStatus("SCANNED", intervalMs = 4_000) },
                    {
                        throw HubCapacityException(
                            "rate_limited",
                            "wait",
                            retryAfterMs = 5_000,
                        )
                    },
                    {
                        linkStatus(
                            "APPROVED",
                            intervalMs = 4_000,
                            flow = authenticated(),
                        )
                    },
                ),
            )
            onPoll = { pollTimes += testScheduler.currentTime }
        }
        val viewModel = probedViewModel(gateway, FakeSink())

        viewModel.startDeviceLink()
        runCurrent()
        assertTrue(viewModel.state.value is HubSignInState.DeviceLink)
        assertTrue(pollTimes.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1_000L), pollTimes)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(1_000L, 3_000L), pollTimes)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(DeviceLinkPhase.SCANNED, (viewModel.state.value as HubSignInState.DeviceLink).phase)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(1_000L, 3_000L, 6_000L, 10_000L), pollTimes)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(4, pollTimes.size)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(1_000L, 3_000L, 6_000L, 10_000L, 15_000L), pollTimes)
        assertEquals(HubSignInState.Done(HUB_ID), viewModel.state.value)
    }

    @Test
    fun `device link denied and expired can restart`() = runTest {
        val deniedGateway = FakeGateway().apply {
            pollResults += { linkStatus("DENIED") }
        }
        val denied = probedViewModel(deniedGateway, FakeSink())
        denied.startDeviceLink(DeviceLinkMode.BROWSER_SIGN_IN)
        advanceUntilIdle()

        assertEquals(
            HubSignInState.DeviceLinkDenied(DeviceLinkMode.BROWSER_SIGN_IN),
            denied.state.value,
        )
        deniedGateway.pollResults += { linkStatus("APPROVED", flow = authenticated()) }
        denied.restartDeviceLink()
        advanceUntilIdle()
        assertEquals(HubSignInState.Done(HUB_ID), denied.state.value)

        val expiredGateway = FakeGateway().apply {
            pollResults += { linkStatus("EXPIRED") }
        }
        val expired = probedViewModel(expiredGateway, FakeSink())
        expired.startDeviceLink()
        advanceUntilIdle()

        assertEquals(
            HubSignInState.DeviceLinkExpired(DeviceLinkMode.LINK_THIS_DEVICE),
            expired.state.value,
        )
        expiredGateway.pollResults += { linkStatus("APPROVED", flow = authenticated()) }
        expired.restartDeviceLink()
        advanceUntilIdle()
        assertEquals(HubSignInState.Done(HUB_ID), expired.state.value)
    }

    @Test
    fun `cancel stops the device-link poller`() = runTest {
        val gateway = FakeGateway().apply {
            link = linkStart(intervalMs = 1_000)
            pollResults += { linkStatus("PENDING", intervalMs = 1_000) }
            pollResults += { linkStatus("PENDING", intervalMs = 1_000) }
        }
        val viewModel = probedViewModel(gateway, FakeSink())
        viewModel.startDeviceLink()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, gateway.pollCalls)

        viewModel.cancel()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(1, gateway.pollCalls)
        assertTrue(viewModel.state.value is HubSignInState.UrlEntry)
    }

    @Test
    fun `session token never enters observable state or error copy`() = runTest {
        val gateway = FakeGateway().apply {
            passwordCall = { _, _ -> authenticated() }
        }
        val sink = FakeSink().apply {
            refreshFailure = IllegalStateException("failed while using $SESSION_TOKEN")
        }
        val viewModel = probedViewModel(gateway, sink)
        val observed = mutableListOf<HubSignInState>()
        observed += viewModel.state.value
        viewModel.selectPassword()
        observed += viewModel.state.value
        viewModel.submitPassword("alice", "password")
        runCurrent()
        observed += viewModel.state.value

        assertFalse(observed.any { it.toString().contains(SESSION_TOKEN) })
        assertFalse(viewModel.state.value.toString().contains(SESSION_TOKEN))
    }

    private suspend fun TestScope.probedViewModel(
        gateway: FakeGateway,
        sink: FakeSink,
    ): HubSignInViewModel {
        val viewModel = newViewModel(gateway, sink)
        viewModel.probe("$BASE_URL/api/v1/")
        advanceUntilIdle()
        assertTrue(viewModel.state.value is HubSignInState.MethodChooser)
        return viewModel
    }

    private fun TestScope.newViewModel(
        gateway: FakeGateway,
        sink: FakeSink,
        reauthenticateHubId: Long? = null,
    ) = HubSignInViewModel(
        gateway = gateway,
        sink = sink,
        coroutineScope = this,
        nowMs = { testScheduler.currentTime },
        deviceName = "Test device",
        reauthenticateHubId = reauthenticateHubId,
    )

    private class FakeGateway : HubAuthGateway {
        var info = ServerInfoDto(product = "opentv", apiVersion = 1, version = "test")
        var capabilities = CAPABILITIES
        var enrollment = TotpEnrollmentDto(
            challenge = ENROLLMENT_CHALLENGE,
            secret = "secret",
            uri = "otpauth://totp/OpenTV",
            expiresAtMs = Long.MAX_VALUE,
        )
        var link = linkStart()
        var passwordCall: suspend (String, String) -> AuthFlowDto = { _, _ -> authenticated() }
        var totpCall: suspend (String, String) -> AuthFlowDto = { _, _ -> authenticated() }
        var recoveryCall: suspend (String, String) -> AuthFlowDto = { _, _ -> authenticated() }
        var enrollmentCompleteCall: suspend (String, String) -> AuthFlowDto = { _, _ -> authenticated() }
        val pollResults = ArrayDeque<suspend () -> DeviceLinkStatusDto>()
        val totpSubmissions = mutableListOf<Pair<String, String>>()
        val recoverySubmissions = mutableListOf<Pair<String, String>>()
        val enrollmentSubmissions = mutableListOf<Pair<String, String>>()
        var pollCalls = 0
        var onPoll: () -> Unit = {}

        override suspend fun serverInfo(baseUrl: String) = info

        override suspend fun authCapabilities(baseUrl: String) = capabilities

        override suspend fun password(baseUrl: String, username: String, password: String) =
            passwordCall(username, password)

        override suspend fun totp(baseUrl: String, challenge: String, code: String): AuthFlowDto {
            totpSubmissions += challenge to code
            return totpCall(challenge, code)
        }

        override suspend fun recovery(baseUrl: String, challenge: String, code: String): AuthFlowDto {
            recoverySubmissions += challenge to code
            return recoveryCall(challenge, code)
        }

        override suspend fun totpEnrollmentStart(baseUrl: String, challenge: String) = enrollment

        override suspend fun totpEnrollmentComplete(
            baseUrl: String,
            challenge: String,
            code: String,
        ): AuthFlowDto {
            enrollmentSubmissions += challenge to code
            return enrollmentCompleteCall(challenge, code)
        }

        override suspend fun linkStart(baseUrl: String, deviceName: String) = link

        override suspend fun linkPoll(baseUrl: String, pollToken: String): DeviceLinkStatusDto {
            pollCalls++
            onPoll()
            return pollResults.removeFirst().invoke()
        }
    }

    private class FakeSink : HubSignInSink {
        val addedHubs = mutableListOf<Pair<String, String>>()
        val reauthenticatedIds = mutableListOf<Long>()
        val refreshedIds = mutableListOf<Long>()
        var refreshFailure: Throwable? = null
        var storedSource: HubSource? = HubSource(
            id = HUB_ID,
            name = "Home",
            baseUrl = BASE_URL,
            userId = "old-user",
            username = "old",
            role = "USER",
            addedMs = 1,
        )

        override suspend fun source(id: Long): HubSource? =
            storedSource?.takeIf { it.id == id }

        override suspend fun add(name: String, baseUrl: String, token: String): Long {
            assertEquals(SESSION_TOKEN, token)
            addedHubs += name to baseUrl
            return HUB_ID
        }

        override suspend fun reauthenticate(id: Long, token: String): Long {
            assertEquals(SESSION_TOKEN, token)
            reauthenticatedIds += id
            return id
        }

        override suspend fun refreshIdentity(id: Long): CurrentUserDto? {
            refreshedIds += id
            refreshFailure?.let { throw it }
            return USER
        }
    }

    private companion object {
        const val BASE_URL = "https://hub.example"
        const val HUB_ID = 42L
        const val SESSION_TOKEN = "never-show-this-session-token"
        const val CHALLENGE = "mfa-challenge"
        const val ENROLLMENT_CHALLENGE = "enrollment-challenge"

        val CAPABILITIES = AuthCapabilitiesDto(
            passwordEnabled = true,
            oidcEnabled = true,
            passkeyLoginEnabled = true,
            deviceLinkEnabled = true,
            bootstrapRequired = false,
            webAuthnRpId = "hub.example",
        )

        val USER = CurrentUserDto(
            id = "user-id",
            username = "alice",
            displayName = "Alice",
            role = "ADMIN",
            authMethod = "PASSWORD",
            clientKind = "NATIVE",
            authSessionId = "session-id",
            playlistIds = emptyList(),
            hasPassword = true,
        )

        fun authenticated() = AuthFlowDto(
            status = "AUTHENTICATED",
            user = USER,
            sessionToken = SESSION_TOKEN,
        )

        fun mfaRequired() = AuthFlowDto(
            status = "MFA_REQUIRED",
            challenge = CHALLENGE,
            methods = listOf("totp", "recovery"),
        )

        fun linkStart(intervalMs: Long = 1) = DeviceLinkStartDto(
            pollToken = "poll-token",
            linkToken = "link-token",
            verificationUriComplete = "$BASE_URL/link#token",
            expiresAtMs = Long.MAX_VALUE,
            intervalMs = intervalMs,
        )

        fun linkStatus(
            status: String,
            intervalMs: Long = 1,
            flow: AuthFlowDto? = null,
        ) = DeviceLinkStatusDto(
            status = status,
            flow = flow,
            intervalMs = intervalMs,
            expiresAtMs = Long.MAX_VALUE,
        )
    }
}
