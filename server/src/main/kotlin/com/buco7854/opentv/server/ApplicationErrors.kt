package com.buco7854.opentv.server

/** Framework-independent failures raised by application services. */
sealed class ApplicationError(message: String) : RuntimeException(message)

class ResourceNotFound(
    val resource: String,
    message: String = "No such $resource",
) : ApplicationError(message)

class SameContentAlreadyPlayingException : ApplicationError(
    "This account is already playing this content on another device. " +
        "Join that watch-together session or stop playback there.",
)

class LastFactorException : ApplicationError("The last usable sign-in or MFA factor cannot be removed")

class LastAdminException(action: String) :
    ApplicationError("Cannot $action the final manually managed administrator")

class SelfLockoutForbiddenException(
    val field: String,
    message: String,
) : ApplicationError(message)

class UsernameTakenException : ApplicationError("Username is already in use")

class UnknownPlaylistException(playlistId: Long) : ApplicationError("Unknown playlist: $playlistId")

class TotpExistsException : ApplicationError("A TOTP authenticator is already enrolled")

class PasswordCredentialRequiredException(action: String) :
    ApplicationError("Add a password before $action")

class PasswordAuthenticationDisabledException :
    ApplicationError("Cannot set a password because password authentication is disabled on this server")

class LocalAccountProvisioningDisabledException :
    ApplicationError(
        "Local account creation and credential reset require password authentication to be enabled",
    )

class UserStatusNotSettableException(status: String, reason: String) :
    ApplicationError("Administrators cannot set status $status: $reason")
