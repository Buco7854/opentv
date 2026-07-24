package com.buco7854.opentv.server

/**
 * Client-neutral local authentication flow boundary.
 *
 * Browser routes use it today; a future native session issuer can drive the same
 * password/MFA challenges without depending on cookies or Ktor calls.
 */
internal class AuthFlowService(
    private val auth: AuthService,
) {
    suspend fun capabilities() = auth.capabilities()

    suspend fun bootstrap(request: BootstrapRequestDto, clientIp: String) =
        auth.bootstrap(request, clientIp)

    suspend fun password(request: PasswordLoginRequestDto, clientIp: String) =
        auth.passwordLogin(request, clientIp)

    suspend fun activate(request: ActivationRequestDto, clientIp: String) =
        auth.activate(request, clientIp)

    suspend fun startTotpEnrollment(challenge: String, clientIp: String) =
        auth.startTotpEnrollment(challenge, clientIp)

    suspend fun completeTotpEnrollment(request: TotpCompleteRequestDto, clientIp: String) =
        auth.completeTotpEnrollment(request, clientIp)

    suspend fun completeTotp(request: TotpCompleteRequestDto, clientIp: String) =
        auth.completeTotp(request, clientIp)

    suspend fun completeRecovery(request: RecoveryCompleteRequestDto, clientIp: String) =
        auth.completeRecovery(request, clientIp)
}
