/** Fixed, signal-only return target shared with the Android app. */
export const BROWSER_SIGN_IN_RETURN_URL = 'opentv://sign-in';

const ANDROID_USER_AGENT = /\bAndroid\b/iu;

export function supportsBrowserSignInReturn(
  userAgent = window.navigator.userAgent,
): boolean {
  return ANDROID_USER_AGENT.test(userAgent);
}

/**
 * Best-effort handoff after the server has approved a browser sign-in.
 *
 * Desktop browsers stay on the completed web page instead of navigating to an
 * unsupported custom scheme. Android navigation can still be refused by the
 * browser or intercepted by another app, so failure is deliberately harmless:
 * the waiting app retains its poll token and polling remains authoritative.
 */
export function attemptBrowserSignInReturn(
  userAgent = window.navigator.userAgent,
  navigate: (url: string) => void = (url) => window.location.assign(url),
): boolean {
  if (!supportsBrowserSignInReturn(userAgent)) return false;
  try {
    navigate(BROWSER_SIGN_IN_RETURN_URL);
    return true;
  } catch {
    return false;
  }
}
