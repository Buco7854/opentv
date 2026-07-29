package com.buco7854.opentv.core.log

/**
 * Removes provider credentials from text that reaches a person.
 *
 * Playlist and stream URLs carry the account's username and password - in the query for
 * `get.php`/`xmltv.php`, and in the *path* for every Xtream stream (`/live/USER/PASS/1.ts`).
 * Anything derived from a provider URL - an ffmpeg log line, a transfer failure, a message
 * echoed from a panel - can therefore contain them, and none of it may be shown to a viewer,
 * pasted into a bug report, or rendered on an administrator's dashboard.
 */
object ProviderSecrets {

    private const val MASK = "•••"

    // Credentials and capabilities in URLs: provider queries, device-link
    // fragments, media grants, WebSocket tickets and download-file tokens.
    private val URL_SECRET =
        Regex("""(?i)\b(username|password|token|pass|api_key|ws_token|u|g|sid|t)=[^&\s"'<>]+""")

    // The same values after a request/DTO has been rendered as JSON or a map.
    private val STRUCTURED_SECRET =
        Regex(
            """(?i)(["']?\b(?:username|password|pass|api_key|token|sessionToken|pollToken|linkToken|ws_token)["']?\s*[:=]\s*[\[{"']*\s*)[^&\s"'<> ,;}\]]+""",
        )

    // Xtream path credentials: /live|movie(s)|series|timeshift/USER/PASS/...
    private val KIND_PATH =
        Regex("""(?i)/(live|movies?|series|timeshift)/[^/\s"'<>]+/[^/\s"'<>]+/""")

    // Bare Xtream stream paths: http://host/USER/PASS/1234.ts
    private val BARE_PATH =
        Regex("""(://[^/\s"'<>]+)/[^/\s"'<>]+/[^/\s"'<>]+/(\d+(?:\.\w{1,5})?)(?=[:;.!?#\s"'<>,)\]}]|$)""")

    // Hub bearer sessions in an Authorization header echoed into an error/log line.
    private val AUTHORIZATION =
        Regex("""(?i)\bauthorization["']?\s*[:=]\s*[\[{"']*\s*(?:Bearer\s+)?[^\s"'<> ,;}\]]+""")

    fun redact(text: String): String {
        var result = URL_SECRET.replace(text) { "${it.groupValues[1]}=$MASK" }
        result = STRUCTURED_SECRET.replace(result) { "${it.groupValues[1]}$MASK" }
        result = AUTHORIZATION.replace(result) { "authorization: $MASK" }
        result = BARE_PATH.replace(result) { "${it.groupValues[1]}/$MASK/$MASK/${it.groupValues[2]}" }
        result = KIND_PATH.replace(result) { "/${it.groupValues[1]}/$MASK/$MASK/" }
        return result
    }

    /** Convenience for the common "show this failure to someone" case. */
    fun redact(error: Throwable): String =
        redact(error.message ?: error::class.simpleName ?: "Unknown error")
}
