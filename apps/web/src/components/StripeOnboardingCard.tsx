"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { OrganizerStripeAccount } from "@/lib/api/types";

type Props = {
  classroomId: string;
  /** Called once onboarding reaches ACTIVE (whether that happens via
   *  `complete` here or was already ACTIVE on load) — lets the pool page
   *  tell `GeneratePaymentsAction` its "Stripe isn't ACTIVE yet"
   *  precondition may now be stale. */
  onActive?: (account: OrganizerStripeAccount) => void;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "not-started" }
  | { status: "ready"; account: OrganizerStripeAccount };

/**
 * Organizer's Stripe Express onboarding card (PRD §8.4: "lightweight Stripe
 * Express onboarding … the first time they open a pool for payment") — GET
 * `/classrooms/{classroomId}/stripe-onboarding/status`. Mounted on the pool
 * detail page for an organizer once payment collection is imminent (once a
 * purchase plan exists — see `hasPurchasePlan` in `pool-labels.ts`), keyed
 * by the *classroom*, not the pool, since one Stripe account serves every
 * pool that classroom ever runs.
 *
 * A 404 ("onboarding has not been started for this classroom") is treated
 * as a normal "not started yet" state, not an error — same "absence is a
 * valid state" pattern as `MyAllocationPanel`'s empty array.
 *
 * There's no real Stripe hosted-onboarding redirect in this environment, so
 * after starting onboarding this card is explicit that the "Simulate
 * returning from Stripe" button is a placeholder for what would otherwise
 * be Stripe's own redirect flow (`POST .../complete`, which in production is
 * normally driven by a webhook once the organizer finishes on Stripe's
 * side) — never presented as if a real bank-account connection just
 * happened.
 */
export function StripeOnboardingCard({ classroomId, onActive }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [starting, setStarting] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api
      .GET("/classrooms/{classroomId}/stripe-onboarding/status", {
        params: { path: { classroomId } },
      })
      .then(({ data, error, response }) => {
        if (cancelled) return;
        if (response.status === 404) {
          setState({ status: "not-started" });
          return;
        }
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", account: data as OrganizerStripeAccount });
      });

    return () => {
      cancelled = true;
    };
  }, [classroomId]);

  async function handleStart() {
    setStarting(true);
    setErrorMessage(null);

    const { data, error } = await api.POST(
      "/classrooms/{classroomId}/stripe-onboarding",
      { params: { path: { classroomId } } }
    );

    setStarting(false);

    if (error || !data) {
      setErrorMessage(
        "We couldn't start this just now. Please check your connection and try again."
      );
      return;
    }

    setState({ status: "ready", account: data as OrganizerStripeAccount });
  }

  async function handleComplete() {
    setCompleting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/classrooms/{classroomId}/stripe-onboarding/complete",
      { params: { path: { classroomId } } }
    );

    setCompleting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "There's no onboarding in progress to complete — try starting it again."
          : "We couldn't confirm this just now. Please try again."
      );
      return;
    }

    const activeAccount = data as OrganizerStripeAccount;
    setState({ status: "ready", account: activeAccount });
    onActive?.(activeAccount);
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading payment setup…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load payment setup just now.
      </div>
    );
  }

  const account = state.status === "ready" ? state.account : null;

  if (account?.status === "ACTIVE") {
    return (
      <div className="rounded-lg border border-green-200 bg-green-50 p-4">
        <h2 className="text-base font-semibold text-green-900">
          Bank account connected
        </h2>
        <p className="mt-1 text-sm text-green-800">
          This classroom is ready to collect payments — families pay you
          directly through Stripe.
        </p>
      </div>
    );
  }

  if (account?.status === "RESTRICTED") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Stripe has restricted this account
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          There's an issue with this classroom's Stripe account that needs
          attention before payments can be collected. Check the onboarding
          link below, or contact Stripe support.
        </p>
        {account.onboardingUrl && (
          <a
            href={account.onboardingUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-2 inline-block text-sm font-medium text-amber-900 underline"
          >
            Open Stripe
          </a>
        )}
      </div>
    );
  }

  // PENDING or not started yet.
  return (
    <div className="rounded-lg border-2 border-brand-200 bg-brand-50 p-4">
      <h2 className="text-base font-semibold text-brand-900">
        Connect your bank account to collect payments
      </h2>
      <p className="mt-1 text-sm text-brand-800">
        Before families can pay you for this pool's purchase, this classroom
        needs a connected Stripe account. Money goes straight from each
        family to you — ClassPool never holds it.
      </p>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}

      {account?.status === "PENDING" ? (
        <div className="mt-3 space-y-2">
          <p className="text-sm text-brand-800">
            Onboarding started — in production, clicking below would open
            Stripe's own onboarding flow in a new tab, and you'd land back
            here automatically once you finish there.
          </p>
          {account.onboardingUrl && (
            <a
              href={account.onboardingUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="block rounded-lg border border-brand-700 bg-white px-4 py-2.5 text-center text-sm font-semibold text-brand-800 hover:bg-brand-100"
            >
              Continue on Stripe
            </a>
          )}
          <div>
            <button
              type="button"
              onClick={handleComplete}
              disabled={completing}
              className="w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
            >
              {completing ? "Confirming…" : "Simulate returning from Stripe"}
            </button>
            <p className="mt-1 text-xs text-brand-700">
              This app has no real Stripe account to redirect to yet — this
              button stands in for finishing Stripe's hosted flow and coming
              back here.
            </p>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={handleStart}
          disabled={starting}
          className="mt-3 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          {starting ? "Starting…" : "Connect your bank account"}
        </button>
      )}
    </div>
  );
}
