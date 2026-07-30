package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeAuthFlowTest {
    private class Clock(var now: Long = 1_700_000_000_000L) {
        fun time() = now
    }

    @Test
    fun `all successful auth completions return bearer tokens and tag the client`() =
        testApplication {
            val dir = Files.createTempDirectory("opentv-native-auth")
            val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
            val clock = Clock()
            val config = authConfig()
            val auth = AuthService(db, config, dir, clock = clock::time)
            val links = DeviceLinkService(db, auth, config, clock::time)
            try {
                auth.initialize()
                val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
                application {
                    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                    installOpenTvErrorResponses()
                    routing {
                        route("/api/v1") {
                            publicAuthRoutes(
                                auth,
                                OidcService(auth, config),
                                WebAuthnService(db, auth, config, clock::time),
                                links,
                                config,
                                PublicOrigin(config) { false },
                            ) { "127.0.0.1" }
                        }
                    }
                }

                suspend fun authPost(
                    path: String,
                    body: String,
                    native: Boolean = false,
                ): HttpResponse = client.post("/api/v1/auth$path") {
                    contentType(ContentType.Application.Json)
                    if (native) header(CLIENT_KIND_HEADER, "native")
                    setBody(body)
                }.also {
                    assertNull(it.headers[HttpHeaders.SetCookie], "$path issued a session cookie")
                }

                val bootstrap = authPost(
                    "/bootstrap",
                    """
                    {"token":"$bootstrapToken","username":"admin",
                     "password":"a sufficiently long password","displayName":"Administrator"}
                    """.trimIndent(),
                    native = true,
                ).flow()
                assertEquals("ENROLLMENT_REQUIRED", bootstrap.status)
                assertNull(bootstrap.sessionToken)

                val enrollment = Json.decodeFromString<TotpEnrollmentDto>(
                    authPost(
                        "/totp/enroll/start",
                        """{"challenge":"${bootstrap.challenge}"}""",
                    ).bodyAsText(),
                )
                val enrollmentCode = AuthCrypto.totp(
                    AuthCrypto.decodeBase32(enrollment.secret),
                    clock.now / 30_000L,
                )
                val enrolled = authPost(
                    "/totp/enroll/complete",
                    """{"challenge":"${enrollment.challenge}","code":"$enrollmentCode"}""",
                    native = true,
                ).flow()
                val nativeToken = assertNotNull(enrolled.sessionToken)
                assertEquals(ClientKind.NATIVE, enrolled.user?.clientKind)
                assertEquals(
                    ClientKind.NATIVE,
                    db.sessions().byTokenHash(AuthCrypto.hashToken(nativeToken))?.clientKind,
                )
                val recoveryCode = enrolled.recoveryCodes.first()

                val totpChallenge = authPost(
                    "/password",
                    """{"username":"admin","password":"a sufficiently long password"}""",
                ).flow()
                assertEquals("MFA_REQUIRED", totpChallenge.status)
                assertNull(totpChallenge.sessionToken)
                clock.now += 30_000L
                val totpCode = AuthCrypto.totp(
                    AuthCrypto.decodeBase32(enrollment.secret),
                    clock.now / 30_000L,
                )
                val totp = authPost(
                    "/totp",
                    """{"challenge":"${totpChallenge.challenge}","code":"$totpCode"}""",
                    native = true,
                ).flow()
                assertNotNull(totp.sessionToken)
                assertEquals(ClientKind.NATIVE, totp.user?.clientKind)

                val recoveryChallenge = authPost(
                    "/password",
                    """{"username":"admin","password":"a sufficiently long password"}""",
                ).flow()
                val recovered = authPost(
                    "/recovery",
                    """{"challenge":"${recoveryChallenge.challenge}","code":"$recoveryCode"}""",
                ).flow()
                val browserToken = assertNotNull(recovered.sessionToken)
                assertEquals(ClientKind.BROWSER, recovered.user?.clientKind)
                assertEquals(
                    ClientKind.BROWSER,
                    db.sessions().byTokenHash(AuthCrypto.hashToken(browserToken))?.clientKind,
                )

                val actor = assertNotNull(auth.authenticate(assertNotNull(totp.sessionToken))).first
                val started = links.start(
                    DeviceLinkStartRequestDto("Living room"),
                    "OpenTV native",
                    "127.0.0.1",
                )
                links.lookup(actor, DeviceLinkTokenRequestDto(started.linkToken), "127.0.0.1")
                links.approve(actor, DeviceLinkTokenRequestDto(started.linkToken), "127.0.0.1")
                val linked = links.poll(DeviceLinkPollRequestDto(started.pollToken)).status
                assertEquals("APPROVED", linked.status)
                val linkedFlow = assertNotNull(linked.flow)
                assertNotNull(linkedFlow.sessionToken)
                assertEquals(ClientKind.LINKED_DEVICE, linkedFlow.user?.clientKind)
            } finally {
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

    private suspend fun HttpResponse.flow(): AuthFlowDto {
        val body = bodyAsText()
        return Json.decodeFromString<AuthFlowDto>(body).also {
            assertTrue(it.status.isNotBlank(), body)
        }
    }

    private fun authConfig() = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = setOf(UserRole.ADMIN),
        oidc = null,
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
