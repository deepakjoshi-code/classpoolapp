"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api/client";
import { consumePendingRedirect } from "@/lib/pending-redirect";

type Status = "verifying" | "error";

/**
 * Landing target for the magic-link email (PRD §2.2). The emailed link is
 * expected to point here with ?token=..., which we exchange via
 * GET /auth/magic-link/verify. The contract doesn't specify the emailed
 * link's destination URL (that's assembled server-side by apps/api) — this
 * route is our assumption of what that destination should be, flagged in
 * the PR description.
 */
function VerifyInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = searchParams.get("token");
  const [status, setStatus] = useState<Status>("verifying");

  useEffect(() => {
    if (!token) {
      setStatus("error");
      return;
    }

    let cancelled = false;

    api
      .GET("/auth/magic-link/verify", { params: { query: { token } } })
      .then(({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setStatus("error");
          return;
        }
        const redirectTo = consumePendingRedirect() ?? "/";
        router.replace(redirectTo);
      });

    return () => {
      cancelled = true;
    };
  }, [token, router]);

  if (status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          That link didn't work
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          It may have expired or already been used. Sign-in links are valid
          for 15 minutes and can only be used once.
        </p>
        <a
          href="/sign-in"
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Request a new link
        </a>
      </div>
    );
  }

  return (
    <div role="status" className="px-4 py-16 text-center text-slate-700">
      Signing you in…
    </div>
  );
}

export default function VerifyPage() {
  return (
    <Suspense fallback={null}>
      <VerifyInner />
    </Suspense>
  );
}
