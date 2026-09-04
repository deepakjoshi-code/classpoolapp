"use client";

import { useEffect } from "react";

/** Registers /sw.js on mount. Client component so it never runs during SSR. */
export function ServiceWorkerRegister() {
  useEffect(() => {
    if (typeof window === "undefined") return;
    if (!("serviceWorker" in navigator)) return;

    navigator.serviceWorker.register("/sw.js").catch((err) => {
      // Non-fatal: the app still works without offline caching.
      console.error("Service worker registration failed", err);
    });
  }, []);

  return null;
}
