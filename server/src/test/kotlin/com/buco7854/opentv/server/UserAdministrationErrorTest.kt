package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserAdministrationErrorTest {
    @Test
    fun anAdministratorCannotDemoteDisableOrDeleteThemselves() = runTest {
        withAdmin { service, actor, admin, _ ->
            service.adminCreateUser(
                actor,
                CreateUserRequestDto(
                    "backup",
                    "Backup administrator",
                    password = "another sufficiently long password",
                    role = UserRole.ADMIN,
                ),
            )

            val demotion = assertFailsWith<SelfLockoutForbiddenException> {
                service.adminUpdateUser(actor, admin, UpdateUserRequestDto(role = "USER"))
            }
            assertEquals("role", demotion.field)
            val disabling = assertFailsWith<SelfLockoutForbiddenException> {
                service.adminUpdateUser(actor, admin, UpdateUserRequestDto(status = "DISABLED"))
            }
            assertEquals("status", disabling.field)
            val deletion = assertFailsWith<SelfLockoutForbiddenException> {
                service.adminDeleteUser(actor, admin)
            }
            assertEquals("account", deletion.field)
        }
    }

    @Test
    fun anotherAdministratorCanDemoteDisableAndDeleteAccounts() = runTest {
        withAdmin { service, actor, _, _ ->
            val demoted = newAdmin(service, actor, "demoted")
            val disabled = newAdmin(service, actor, "disabled")
            val deleted = newAdmin(service, actor, "deleted")

            assertEquals(
                UserRole.USER,
                service.adminUpdateUser(
                    actor,
                    demoted,
                    UpdateUserRequestDto(role = UserRole.USER),
                ).manualRole,
            )
            assertEquals(
                UserStatus.DISABLED,
                service.adminUpdateUser(
                    actor,
                    disabled,
                    UpdateUserRequestDto(status = UserStatus.DISABLED),
                ).status,
            )
            service.adminDeleteUser(actor, deleted)
            assertFailsWith<ResourceNotFound> {
                service.adminUpdateUser(actor, deleted, UpdateUserRequestDto(displayName = "Gone"))
            }
        }
    }

    @Test
    fun renamingAndResettingYourOwnAccountRemainAllowedWhenAnotherAdminExists() = runTest {
        withAdmin { service, actor, admin, db ->
            newAdmin(service, actor, "backup")

            val renamed = service.adminUpdateUser(
                actor,
                admin,
                UpdateUserRequestDto(username = "renamed-admin", displayName = "Renamed Admin"),
            )
            assertEquals("renamed-admin", renamed.username)
            assertEquals("Renamed Admin", renamed.displayName)

            val reset = service.adminResetUser(actor, admin)
            assertTrue(reset.setupToken.isNotBlank())
            assertEquals(UserStatus.INVITED, assertNotNull(db.users().get(admin)).status)
        }
    }

    @Test
    fun theLastAdminGuardStillAppliesWhenAnAdministratorActsOnADifferentAccount() = runTest {
        withAdmin { service, actor, admin, db ->
            val finalManualAdmin = newAdmin(service, actor, "final")
            val actingUser = assertNotNull(db.users().get(admin))
            db.users().update(
                actingUser.copy(
                    manualRole = UserRole.USER,
                    oidcAdmin = true,
                ),
            )

            assertFailsWith<LastAdminException> {
                service.adminUpdateUser(
                    actor,
                    finalManualAdmin,
                    UpdateUserRequestDto(role = UserRole.USER),
                )
            }
            assertFailsWith<LastAdminException> {
                service.adminUpdateUser(
                    actor,
                    finalManualAdmin,
                    UpdateUserRequestDto(status = UserStatus.DISABLED),
                )
            }
            assertFailsWith<LastAdminException> {
                service.adminDeleteUser(actor, finalManualAdmin)
            }
            assertFailsWith<LastAdminException> {
                service.adminResetUser(actor, finalManualAdmin)
            }
        }
    }

    @Test
    fun aTakenUsernameIsReportedAsSuchOnCreateAndOnRename() = runTest {
        withAdmin { service, actor, _, _ ->
            val created = service.adminCreateUser(actor, CreateUserRequestDto("viewer", "Viewer"))
            assertFailsWith<UsernameTakenException> {
                service.adminCreateUser(actor, CreateUserRequestDto("Viewer", "Another"))
            }
            assertFailsWith<UsernameTakenException> {
                service.adminUpdateUser(actor, created.user.id, UpdateUserRequestDto(username = "admin"))
            }
        }
    }

    @Test
    fun grantingAnUnknownPlaylistNamesThePlaylist() = runTest {
        withAdmin(knownPlaylists = setOf(7L)) { service, actor, _, _ ->
            val created = service.adminCreateUser(actor, CreateUserRequestDto("viewer", "Viewer"))
            service.setUserPlaylists(actor, created.user.id, listOf(7L))
            val failure = assertFailsWith<UnknownPlaylistException> {
                service.setUserPlaylists(actor, created.user.id, listOf(7L, 4_711L))
            }
            assertEquals("Unknown playlist: 4711", failure.message)
        }
    }

    private suspend fun newAdmin(service: AuthService, actor: Actor, username: String): String =
        service.adminCreateUser(
            actor,
            CreateUserRequestDto(
                username,
                "$username administrator",
                password = "a sufficiently long password for $username",
                role = UserRole.ADMIN,
            ),
        ).user.id

    private suspend fun withAdmin(
        knownPlaylists: Set<Long> = emptySet(),
        block: suspend (AuthService, Actor, String, ServerUserDatabase) -> Unit,
    ) {
        val dir = Files.createTempDirectory("opentv-admin-error-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        val now = 1_700_000_000_000L
        val config = AuthConfig(
            publicUrl = URI("https://tv.example.com"),
            passwordEnabled = true,
            encryptionKey = ByteArray(32) { it.toByte() },
            initialAdmin = null,
            mfaRequiredRoles = emptySet(),
            oidc = null,
            secureCookies = true,
            webAuthnRpId = "tv.example.com",
            webAuthnOrigin = "https://tv.example.com",
            sessionIdleMs = 24 * 60 * 60_000L,
            sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
        )
        try {
            val service = AuthService(db, config, dir, { now }, { it in knownPlaylists })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val result = service.bootstrap(
                BootstrapRequestDto(bootstrapToken, "Admin", "a sufficiently long password", "Administrator"),
                "127.0.0.1",
            )
            val token = assertNotNull(result.sessionToken)
            val actor = assertNotNull(service.authenticate(token)).first
            val admin = assertNotNull(db.users().byNormalizedUsername("admin")).id
            block(service, actor, admin, db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
