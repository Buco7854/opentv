package com.buco7854.opentv.ui.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.buco7854.opentv.ui.theme.ViewingAccent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.hub.ApiHubAuthGateway
import com.buco7854.opentv.hub.HubEndpoints
import com.buco7854.opentv.hub.DeviceLinkMode
import com.buco7854.opentv.hub.DeviceLinkPhase
import com.buco7854.opentv.hub.HubSignInFailure
import com.buco7854.opentv.hub.HubSignInState
import com.buco7854.opentv.hub.HubSignInViewModel
import com.buco7854.opentv.hub.RegistryHubSignInSink
import com.buco7854.opentv.ui.components.OtvButton
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.QrCode
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.focusHighlight

/**
 * Connecting to an OpenTV server. One screen per step of
 * [HubSignInState]; the state machine owns the flow, this only renders it.
 *
 * Everything here must work with a D-pad: the same tree serves phones and TVs,
 * and a TV cannot scan a QR code, so device linking shows the code for another
 * device to scan rather than asking for a camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubSignInScreen(hubId: Long?, onDone: (Long) -> Unit, onBack: () -> Unit) {
    val viewModel: HubSignInViewModel = viewModel(
        key = "HubSignIn-${hubId ?: "new"}",
        factory = viewModelFactory {
            initializer {
                val graph = OpenTvApp.graph
                HubSignInViewModel(
                    gateway = ApiHubAuthGateway(graph.hubApi),
                    sink = RegistryHubSignInSink(graph.hubs),
                    pendingLinkStore = graph.pendingDeviceLink,
                    reauthenticateHubId = hubId,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backFocusRequester = remember { FocusRequester() }
    // Password and MFA states intentionally carry no URL; retain the server context for the user.
    var serverAddress by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state) {
        val address = when (val current = state) {
            is HubSignInState.MethodChooser -> current.baseUrl
            is HubSignInState.DeviceLink -> current.baseUrl
            is HubSignInState.Probing -> current.url
            else -> null
        }
        if (!address.isNullOrBlank()) serverAddress = address
    }

    LaunchedEffect(state) {
        (state as? HubSignInState.Done)?.let { onDone(it.hubId) }
    }
    RequestInitialFocusOnTv(backFocusRequester, state::class)

    fun handleBack() {
        if (isHubSignInRoot(state, hubId != null)) {
            viewModel.cancel()
            onBack()
        } else viewModel.back()
    }
    BackHandler(onBack = ::handleBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenTV", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(
                        onClick = ::handleBack,
                        modifier = Modifier
                            .focusRequester(backFocusRequester)
                            .focusHighlight(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val serverStep = state is HubSignInState.UrlEntry || state is HubSignInState.Probing ||
                    state is HubSignInState.ProbeFailed || state is HubSignInState.UpdateRequired
                val browserSignIn = when (val current = state) {
                    is HubSignInState.MethodChooser -> current.browserSignInAvailable
                    is HubSignInState.DeviceLink -> current.mode == DeviceLinkMode.BROWSER_SIGN_IN
                    is HubSignInState.DeviceLinkDenied -> current.mode == DeviceLinkMode.BROWSER_SIGN_IN
                    is HubSignInState.DeviceLinkExpired -> current.mode == DeviceLinkMode.BROWSER_SIGN_IN
                    else -> false
                }
                SignInIntroduction(serverStep, hubId != null, browserSignIn)
                if (!serverStep && serverAddress.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = ViewingAccent)
                        Text(serverAddress, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
                when (val current = state) {
                    is HubSignInState.UrlEntry -> UrlEntryStep(current, viewModel::connect)
                    is HubSignInState.Probing -> BusyStep(stringResource(R.string.hub_checking))
                    is HubSignInState.ProbeFailed ->
                        if (hubId == null) {
                            UrlEntryStep(
                                HubSignInState.UrlEntry(current.url, current.error),
                                viewModel::connect,
                            )
                        } else {
                            RetryStep(failureMessage(current.error), viewModel::retryKnownHub)
                        }

                    is HubSignInState.UpdateRequired -> MessageStep(stringResource(R.string.hub_app_too_old))
                    is HubSignInState.MethodChooser -> MethodChooserStep(current, viewModel)
                    is HubSignInState.Password -> PasswordStep(current, viewModel::submitPassword)
                    is HubSignInState.MfaChallenge -> MfaStep(current, viewModel)
                    is HubSignInState.TotpEnrollment -> EnrollmentStep(current, viewModel::completeTotpEnrollment)
                    is HubSignInState.DeviceLink -> DeviceLinkStep(current, viewModel::claimBrowserLaunch)
                    is HubSignInState.DeviceLinkDenied -> RestartStep(
                        stringResource(R.string.hub_link_denied),
                        viewModel::restartDeviceLink,
                    )

                    is HubSignInState.DeviceLinkExpired -> RestartStep(
                        stringResource(R.string.hub_link_expired),
                        viewModel::restartDeviceLink,
                    )

                    HubSignInState.PendingApproval -> MessageStep(stringResource(R.string.hub_pending_approval))
                    HubSignInState.Finishing -> BusyStep(stringResource(R.string.hub_signing_in))
                    is HubSignInState.Done -> BusyStep(stringResource(R.string.hub_signing_in))
                }
            }
        }
    }
}

@Composable
internal fun SignInIntroduction(serverStep: Boolean, reauthenticating: Boolean, browserSignIn: Boolean = false) {
    Text(
        stringResource(if (serverStep) R.string.hub_step_server else R.string.hub_step_account),
        style = MaterialTheme.typography.labelLarge,
        color = ViewingAccent,
    )
    Text(
        stringResource(
            if (serverStep) R.string.hub_intro_connect
            else if (reauthenticating) R.string.hub_intro_return
            else R.string.hub_intro_sign_in,
        ),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(
            if (serverStep) R.string.hub_intro_description
            else if (browserSignIn) R.string.hub_browser_account_description
            else R.string.hub_account_description,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun UrlEntryStep(state: HubSignInState.UrlEntry, onSubmit: (String) -> Unit) {
    var url by rememberSaveable(state.url) { mutableStateOf(state.url) }
    val context = LocalContext.current
    val hasBrowser = remember(context) { HubBrowserHandoff(context).hasBrowser() }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.hub_field_url)) },
        placeholder = { Text(stringResource(R.string.hub_field_url_hint)) },
        singleLine = true,
        isError = state.error != null,
        supportingText = state.error?.let { { Text(failureMessage(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = { if (url.isNotBlank()) onSubmit(url) },
        ),
        modifier = Modifier.fillMaxWidth().focusHighlight(),
    )
    OtvButton(
        onClick = { onSubmit(url) },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (hasBrowser) R.string.hub_browser_continue else R.string.hub_browser_continue_phone)) }
}

@Composable
private fun MethodChooserStep(state: HubSignInState.MethodChooser, viewModel: HubSignInViewModel) {
    val context = LocalContext.current
    val handoff = remember(context) { HubBrowserHandoff(context) }
    SignInContinuation(
        state = state,
        hasBrowser = handoff.hasBrowser(),
        onBrowserSignIn = { viewModel.startDeviceLink(DeviceLinkMode.BROWSER_SIGN_IN) },
        onPasswordSignIn = viewModel::selectPassword,
    )
}

/** One continuation, with authentication choices delegated to the server's sign-in page. */
@Composable
internal fun SignInContinuation(
    state: HubSignInState.MethodChooser,
    hasBrowser: Boolean,
    onBrowserSignIn: () -> Unit,
    onPasswordSignIn: () -> Unit,
) {
    state.error?.let { ErrorText(failureMessage(it)) }
    if (state.browserSignInAvailable) {
        OtvButton(onClick = onBrowserSignIn, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.size(8.dp))
            }
            Text(stringResource(
                if (state.busy) R.string.hub_browser_preparing
                else if (hasBrowser) R.string.hub_browser_continue
                else R.string.hub_method_phone,
            ))
        }
        Text(
            stringResource(if (hasBrowser) R.string.hub_browser_continue_description else R.string.hub_method_phone_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.passwordAvailable) {
        OtvButton(onClick = onPasswordSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hub_method_password))
        }
    } else {
        MessageStep(stringResource(R.string.hub_browser_unavailable))
    }
}

@Composable
internal fun PasswordStep(state: HubSignInState.Password, onSubmit: (String, String) -> Unit) {
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    // Deliberately not rememberSaveable: saved instance state can be written to
    // disk by the system, and a password must not outlive the composition.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Text(stringResource(R.string.hub_method_password), style = MaterialTheme.typography.titleMedium)
    state.error?.let { ErrorText(failureMessage(it)) }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.playlist_field_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        modifier = Modifier.fillMaxWidth().focusHighlight(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.playlist_field_password)) },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (passwordVisible) R.string.hub_hide_password else R.string.hub_show_password,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = {
                if (username.isNotBlank() && password.isNotEmpty()) onSubmit(username, password)
            },
        ),
        modifier = Modifier.fillMaxWidth().focusHighlight(),
    )
    OtvButton(
        onClick = { onSubmit(username, password) },
        enabled = username.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.hub_sign_in)) }
}

@Composable
private fun MfaStep(state: HubSignInState.MfaChallenge, viewModel: HubSignInViewModel) {
    var useRecovery by rememberSaveable { mutableStateOf(false) }
    var code by remember(useRecovery) { mutableStateOf("") }

    Text(
        stringResource(if (useRecovery) R.string.hub_recovery_title else R.string.hub_mfa_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(if (useRecovery) R.string.hub_recovery_subtitle else R.string.hub_mfa_subtitle),
        style = MaterialTheme.typography.bodyMedium,
    )
    state.error?.let { ErrorText(failureMessage(it)) }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(R.string.hub_mfa_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            // Recovery codes are alphanumeric with dashes; only TOTP is digits.
            keyboardType = if (useRecovery) KeyboardType.Text else KeyboardType.NumberPassword,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                if (code.isNotBlank()) {
                    if (useRecovery) viewModel.submitRecoveryCode(code) else viewModel.submitTotp(code)
                }
            },
        ),
        modifier = Modifier.fillMaxWidth().focusHighlight(),
    )
    OtvButton(
        onClick = { if (useRecovery) viewModel.submitRecoveryCode(code) else viewModel.submitTotp(code) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.hub_sign_in)) }
    if (state.recoveryAvailable) {
        OtvTextButton(onClick = { useRecovery = !useRecovery }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (useRecovery) R.string.hub_mfa_use_totp else R.string.hub_mfa_use_recovery,
                ),
            )
        }
    }
}

@Composable
private fun ColumnScope.EnrollmentStep(state: HubSignInState.TotpEnrollment, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.hub_enroll_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.hub_enroll_subtitle), style = MaterialTheme.typography.bodyMedium)
    QrCode(
        content = state.otpauthUri,
        contentDescription = null,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        stringResource(R.string.hub_enroll_secret, state.secret),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.error?.let { ErrorText(failureMessage(it)) }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(R.string.hub_mfa_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onSubmit(code) }),
        modifier = Modifier.fillMaxWidth().focusHighlight(),
    )
    OtvButton(
        onClick = { onSubmit(code) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.hub_sign_in)) }
}

@Composable
private fun ColumnScope.DeviceLinkStep(
    state: HubSignInState.DeviceLink,
    claimBrowserLaunch: (String) -> Boolean,
) {
    val hubBaseUrl = state.baseUrl
    val context = LocalContext.current
    val handoff = remember(context) { HubBrowserHandoff(context) }
    var rejected by rememberSaveable(state.verificationUri) { mutableStateOf(false) }
    // A link pointing off the hub's own origin is not shown at all: a QR code
    // is scanned by another device, which would follow it without question.
    val trusted = remember(hubBaseUrl, state.verificationUri) {
        HubEndpoints.isSameOrigin(hubBaseUrl, state.verificationUri)
    }

    if (!trusted) {
        ErrorText(stringResource(R.string.hub_handoff_rejected))
        return
    }

    // Browser sign-in means this device's own browser, so open it rather than showing a
    // QR code for a second device to scan. The QR is still the answer where opening
    // fails or makes no sense — a television, or a device with no browser at all.
    val browserMode = state.mode == DeviceLinkMode.BROWSER_SIGN_IN
    var showQr by rememberSaveable(state.verificationUri) { mutableStateOf(!browserMode || !handoff.hasBrowser()) }
    // The ViewModel owns the one automatic handoff for this explicit attempt. Rotation,
    // returning from the browser and restoration must never open another tab.
    LaunchedEffect(state.verificationUri) {
        if (!browserMode || showQr || !claimBrowserLaunch(state.verificationUri)) return@LaunchedEffect
        when (handoff.open(hubBaseUrl, state.verificationUri)) {
            HandoffResult.Opened -> Unit
            is HandoffResult.ShowQrInstead -> showQr = true
            HandoffResult.Rejected -> rejected = true
        }
    }

    DeviceLinkWaiting(
        state = state,
        showQr = showQr,
        rejected = rejected,
        onReopen = if (handoff.hasBrowser()) {
            {
                when (handoff.open(hubBaseUrl, state.verificationUri)) {
                    HandoffResult.Opened -> { rejected = false; showQr = false }
                    is HandoffResult.ShowQrInstead -> showQr = true
                    HandoffResult.Rejected -> rejected = true
                }
            }
        } else null,
    )
}

/** A waiting state, with QR only where a browser cannot be opened on this device. */
@Composable
internal fun ColumnScope.DeviceLinkWaiting(
    state: HubSignInState.DeviceLink,
    showQr: Boolean,
    rejected: Boolean = false,
    onReopen: (() -> Unit)? = null,
) {
    val browserMode = state.mode == DeviceLinkMode.BROWSER_SIGN_IN
    if (showQr) {
        Text(
            stringResource(if (browserMode) R.string.hub_method_phone else R.string.hub_link_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        stringResource(
            if (showQr && browserMode) R.string.hub_phone_scan
            else if (showQr) R.string.hub_link_scan
            else R.string.hub_browser_waiting_description,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (showQr) {
        QrCode(
            content = state.verificationUri,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    } else {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(28.dp), strokeWidth = 2.dp)
    }
    Text(
        state.scannedBy
            ?.takeIf { state.phase == DeviceLinkPhase.SCANNED }
            ?.let { stringResource(R.string.hub_link_scanned_by, it) }
            ?: stringResource(
                when (state.phase) {
                    DeviceLinkPhase.SCANNED -> R.string.hub_link_scanned
                    DeviceLinkPhase.PENDING -> if (browserMode) R.string.hub_browser_waiting else R.string.hub_link_waiting
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    state.error?.let { ErrorText(failureMessage(it)) }
    if (rejected) ErrorText(stringResource(R.string.hub_handoff_rejected))
    onReopen?.let { reopen ->
        OtvTextButton(onClick = reopen, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (browserMode) R.string.hub_browser_reopen else R.string.hub_link_open_here))
        }
    }
}

@Composable
private fun RestartStep(message: String, onRestart: () -> Unit) {
    MessageStep(message)
    OtvButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hub_link_restart))
    }
}

@Composable
private fun RetryStep(message: String, onRetry: () -> Unit) {
    MessageStep(message)
    OtvButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hub_retry))
    }
}

@Composable
private fun BusyStep(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(message, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MessageStep(message: String) {
    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun ErrorText(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/** The state machine names the failure; the words live here, in both locales. */
@Composable
private fun failureMessage(failure: HubSignInFailure): String = stringResource(
    when (failure) {
        HubSignInFailure.ADDRESS_REQUIRED -> R.string.hub_enter_address
        HubSignInFailure.NOT_OPENTV -> R.string.hub_not_opentv
        HubSignInFailure.UNREACHABLE -> R.string.hub_unreachable
        HubSignInFailure.BAD_CREDENTIALS -> R.string.hub_bad_credentials
        HubSignInFailure.BAD_CODE -> R.string.hub_mfa_rejected
        HubSignInFailure.TOO_MANY_ATTEMPTS -> R.string.hub_too_many_attempts
        HubSignInFailure.STEP_UNSUPPORTED -> R.string.hub_step_unsupported
        HubSignInFailure.REQUEST_FAILED -> R.string.hub_request_failed
        HubSignInFailure.UNEXPECTED_RESPONSE -> R.string.hub_server_unexpected_response
        HubSignInFailure.NOT_SAVED -> R.string.hub_sign_in_not_saved
        HubSignInFailure.GENERIC -> R.string.hub_generic_failure
    },
)

internal fun isHubSignInRoot(state: HubSignInState, reauthenticating: Boolean): Boolean =
    state is HubSignInState.UrlEntry ||
        reauthenticating && (
            state is HubSignInState.Probing ||
                state is HubSignInState.ProbeFailed ||
                state is HubSignInState.UpdateRequired ||
                state is HubSignInState.MethodChooser
            )
