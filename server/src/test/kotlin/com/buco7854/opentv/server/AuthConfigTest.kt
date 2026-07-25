package com.buco7854.opentv.server

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthConfigTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun env(vararg extra: Pair<String, String>) =
        mapOf("OPENTV_AUTH_ENCRYPTION_KEY" to key) + extra

    @Test
    fun publicUrlMustBeHttpsUnlessLoopbackOrExplicitlyAllowed() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(env("OPENTV_PUBLIC_URL" to "http://tv.example.com"))
        }
        assertEquals(
            "http",
            AuthConfig.fromEnv(env("OPENTV_PUBLIC_URL" to "http://localhost:8080")).publicUrl.scheme,
        )
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "http://tv.example.com",
                    "OPENTV_ALLOW_INSECURE_HTTP" to "true",
                ),
            )
        }
        assertEquals(
            "tv.example.com",
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "http://tv.example.com",
                    "OPENTV_ALLOW_INSECURE_HTTP" to "true",
                    "OPENTV_WEBAUTHN_ORIGIN" to "https://tv.example.com",
                ),
            ).publicUrl.host,
        )
    }

    @Test
    fun publicUrlCarriesOnlySchemeAndAuthority() {
        listOf(
            "https://tv.example.com/app",
            "https://user:pw@tv.example.com",
            "https://tv.example.com?a=1",
            "not-a-url",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                AuthConfig.fromEnv(env("OPENTV_PUBLIC_URL" to value))
            }
        }
    }

    @Test
    fun passwordAuthenticationRequiresA32ByteEncryptionKey() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(mapOf("OPENTV_PUBLIC_URL" to "https://tv.example.com"))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                mapOf(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_AUTH_ENCRYPTION_KEY" to Base64.getEncoder()
                        .encodeToString(ByteArray(16)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                mapOf(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_AUTH_ENCRYPTION_KEY" to "not base64!!",
                ),
            )
        }
    }

    @Test
    fun webAuthnRelyingPartyMustCoverItsOrigin() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_WEBAUTHN_RP_ID" to "unrelated.test",
                ),
            )
        }
        val suffix = AuthConfig.fromEnv(
            env(
                "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                "OPENTV_WEBAUTHN_RP_ID" to "example.com",
            ),
        )
        assertEquals("example.com", suffix.webAuthnRpId)
        assertEquals(
            "tv.example.com",
            AuthConfig.fromEnv(env("OPENTV_PUBLIC_URL" to "https://tv.example.com")).webAuthnRpId,
        )
    }

    @Test
    fun oidcSettingsAreAllOrNothingAndTheIssuerMustBeHttps() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_OIDC_ISSUER" to "https://idp.example.com",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_OIDC_ISSUER" to "http://idp.example.com",
                    "OPENTV_OIDC_CLIENT_ID" to "id",
                    "OPENTV_OIDC_CLIENT_SECRET" to "secret",
                ),
            )
        }
        val complete = AuthConfig.fromEnv(
            env(
                "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                "OPENTV_OIDC_ISSUER" to "https://idp.example.com",
                "OPENTV_OIDC_CLIENT_ID" to "id",
                "OPENTV_OIDC_CLIENT_SECRET" to "secret",
            ),
        )
        assertEquals("id", complete.oidc?.clientId)
    }

    @Test
    fun passwordAuthenticationCannotBeDisabledWithoutOidcToReplaceIt() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_PASSWORD_AUTH_ENABLED" to "false",
                ),
            )
        }
    }

    @Test
    fun mfaRolesAreValidatedAndSessionLifetimesAreBounded() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_MFA_REQUIRED_ROLES" to "USER,ROOT",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(
                env(
                    "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                    "OPENTV_SESSION_IDLE_HOURS" to "0",
                ),
            )
        }
        val config = AuthConfig.fromEnv(
            env(
                "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                "OPENTV_MFA_REQUIRED_ROLES" to "admin",
            ),
        )
        assertEquals(setOf("ADMIN"), config.mfaRequiredRoles)
        assertNull(config.initialAdmin)
    }
}
