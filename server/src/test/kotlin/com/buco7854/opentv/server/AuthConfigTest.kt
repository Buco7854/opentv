package com.buco7854.opentv.server

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun encryptionKeyAcceptsBase64AndHexEncoding() {
        val hex = "2982fa1bb58891822a5fe78ca8c442d03806394991c35c8683d28192d4f11a3b"
        val expected = Base64.getDecoder().decode("KYL6G7WIkYIqX+eMqMRC0DgGOUmRw1yGg9KBktTxGjs=")

        listOf(
            Base64.getEncoder().encodeToString(expected),
            hex,
            hex.uppercase(),
        ).forEach { encoded ->
            val actual = AuthConfig.fromEnv(
                env("OPENTV_AUTH_ENCRYPTION_KEY" to "  $encoded  "),
            ).encryptionKey
            assertContentEquals(expected, actual, encoded)
        }
    }

    @Test
    fun passwordAuthenticationRequiresAValid32ByteEncryptionKey() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.fromEnv(mapOf("OPENTV_PUBLIC_URL" to "https://tv.example.com"))
        }

        val invalidValues = listOf(
            "a".repeat(62),
            "a".repeat(66),
            "g".repeat(64),
            "not base64!!",
            Base64.getEncoder().encodeToString(ByteArray(16)),
        )
        invalidValues.forEach { value ->
            val error = assertFailsWith<IllegalArgumentException>(value) {
                AuthConfig.fromEnv(
                    mapOf(
                        "OPENTV_PUBLIC_URL" to "https://tv.example.com",
                        "OPENTV_AUTH_ENCRYPTION_KEY" to value,
                    ),
                )
            }
            assertEquals(
                "OPENTV_AUTH_ENCRYPTION_KEY must be a 32-byte key encoded as base64 or 64-character hex",
                error.message,
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
