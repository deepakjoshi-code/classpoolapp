/**
 * Small sessionStorage helper for carrying a "come back here after auth"
 * destination across the sign-in / magic-link / Google OAuth round trip.
 *
 * Used by the invite landing page (PRD §2.2: show context pre-auth, THEN
 * route into sign-in) so that after the parent authenticates they land back
 * on /join/[token] instead of the generic household dashboard.
 */
const KEY = "classpool:redirectAfterAuth";

export function setPendingRedirect(path: string) {
  try {
    window.sessionStorage.setItem(KEY, path);
  } catch {
    // sessionStorage unavailable (private mode, etc) — non-fatal, the user
    // just lands on the default post-auth destination instead.
  }
}

export function consumePendingRedirect(): string | null {
  try {
    const value = window.sessionStorage.getItem(KEY);
    if (value) window.sessionStorage.removeItem(KEY);
    return value;
  } catch {
    return null;
  }
}
