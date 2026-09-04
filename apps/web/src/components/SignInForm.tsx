"use client";

import { useState, type FormEvent } from "react";
import { api, API_ORIGIN } from "@/lib/api/client";
import { setPendingRedirect } from "@/lib/pending-redirect";

type Mode = "form" | "sending" | "sent" | "error";

/**
 * Sign-in form: Google button + email magic-link, no password anywhere
 * (PRD §2.2). `redirectTo` is where the parent should land after they
 * finish authenticating (e.g. back on an invite landing page).
 */
export function SignInForm({ redirectTo }: { redirectTo?: string }) {
  const [email, setEmail] = useState("");
  const [mode, setMode] = useState<Mode>("form");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  function rememberRedirect() {
    if (redirectTo) setPendingRedirect(redirectTo);
  }

  function handleGoogleSignIn() {
    rememberRedirect();
    // The contract (contracts/openapi.yaml) documents the OAuth2 *callback*
    // (`/auth/google/callback`) but not an initiation route — that's
    // expected, since Spring Security OAuth2 Client serves the login-kickoff
    // redirect itself, by convention, at `/oauth2/authorization/{registrationId}`
    // rather than as a JSON API operation. Uses API_ORIGIN, not API_BASE_URL —
    // this route lives at the API's root, not under /api/v1.
    window.location.href = `${API_ORIGIN}/oauth2/authorization/google`;
  }

  async function handleMagicLinkSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setMode("sending");
    rememberRedirect();

    const { error } = await api.POST("/auth/magic-link/request", {
      body: { email },
    });

    if (error) {
      setMode("error");
      setErrorMessage("Something went wrong sending that link. Please try again.");
      return;
    }

    setMode("sent");
  }

  if (mode === "sent") {
    return (
      <div
        role="status"
        className="rounded-lg border border-brand-200 bg-brand-50 p-4 text-brand-900"
      >
        <h2 className="text-lg font-semibold">Check your email</h2>
        <p className="mt-1 text-sm">
          We sent a sign-in link to <strong>{email}</strong>. Open it on this
          device to finish signing in. The link expires in 15 minutes.
        </p>
        <button
          type="button"
          onClick={() => setMode("form")}
          className="mt-3 text-sm font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800"
        >
          Use a different email
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <button
        type="button"
        onClick={handleGoogleSignIn}
        className="flex w-full items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-3 text-sm font-medium text-slate-800 shadow-sm hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
      >
        <GoogleIcon />
        Continue with Google
      </button>

      <div className="flex items-center gap-3 text-xs text-slate-500">
        <div className="h-px flex-1 bg-slate-200" aria-hidden="true" />
        or
        <div className="h-px flex-1 bg-slate-200" aria-hidden="true" />
      </div>

      <form onSubmit={handleMagicLinkSubmit} className="space-y-3" noValidate>
        <div>
          <label
            htmlFor="email"
            className="block text-sm font-medium text-slate-700"
          >
            Email address
          </label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>

        {mode === "error" && errorMessage && (
          <p role="alert" className="text-sm text-red-700">
            {errorMessage}
          </p>
        )}

        <button
          type="submit"
          disabled={mode === "sending"}
          className="w-full rounded-lg bg-brand-700 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          {mode === "sending" ? "Sending link…" : "Email me a sign-in link"}
        </button>
      </form>

      <p className="text-xs text-slate-500">
        No password needed. We'll email you a one-tap link instead.
      </p>
    </div>
  );
}

function GoogleIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 18 18"
      aria-hidden="true"
      focusable="false"
    >
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.81.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"
      />
      <path
        fill="#FBBC05"
        d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.05l3.01-2.33z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.32 0 2.51.45 3.44 1.35l2.59-2.59C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"
      />
    </svg>
  );
}
