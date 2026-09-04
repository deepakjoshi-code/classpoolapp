"use client";

import { useEffect, useState } from "react";
import { api } from "./api/client";
import type { CurrentUser } from "./api/types";

export type AuthState =
  | { status: "loading" }
  | { status: "authenticated"; user: CurrentUser }
  | { status: "anonymous" };

/**
 * Client-side auth check against GET /me. There's no server-rendered auth
 * here (the session cookie is httpOnly and set by apps/api, so the RSC layer
 * can't read it without a shared-origin proxy) — every page that needs to
 * know "is someone signed in" checks this on mount and renders a loading
 * state first, per the offline/PWA-shell approach in PRD §11.4.
 */
export function useCurrentUser(): AuthState {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    api.GET("/me").then(({ data, error }) => {
      if (cancelled) return;
      if (data && !error) {
        // See src/lib/api/types.ts DeepRequired comment: the contract's
        // response schemas don't declare `required`, so we narrow here.
        setState({ status: "authenticated", user: data as CurrentUser });
      } else {
        setState({ status: "anonymous" });
      }
    });

    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
