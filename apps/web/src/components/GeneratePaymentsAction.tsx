"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { Payment } from "@/lib/api/types";

type Props = {
  poolId: string;
  classroomId: string;
  onGenerated: (payments: Payment[]) => void;
  /**
   * Bump this (e.g. a counter incremented by the parent page) whenever
   * something that could change the precondition check's answer happens
   * elsewhere on the page — approving the purchase plan in
   * `PurchasePlanPanel`, or completing Stripe onboarding in
   * `StripeOnboardingCard`. Both are separate, self-contained components
   * with no other link back to this one, so without this the precondition
   * check (which only runs once on mount) would never learn that its
   * "blocked" answer is stale.
   */
  refreshKey?: number;
};

type PreconditionState =
  | { status: "checking" }
  | { status: "blocked"; reason: string }
  | { status: "ready" };

/**
 * Organizer's one-way "generate payments" action (PRD §8.1-8.3) — `POST
 * /pools/{poolId}/payments/generate`. Same two-step amber-then-red confirm
 * shape as `GeneratePurchasePlanAction`/`ReconcileAction`: this locks in
 * `PURCHASE_PROPOSED → PAYMENT_OPEN` and can't be re-run (the contract 409s
 * if payments already exist).
 *
 * Unlike those two, this endpoint's own preconditions (an *approved*
 * purchase plan, and an *ACTIVE* Stripe account) aren't fully captured by
 * pool state alone — `PURCHASE_PROPOSED` covers both a still-PROPOSED and
 * an already-APPROVED plan. Rather than show a dead button that 409s with a
 * collapsed, undifferentiated message, this component checks both
 * preconditions itself on mount (`GET .../purchase-plan`, `GET
 * .../stripe-onboarding/status`) and renders a specific, named reason
 * instead of the action when either isn't met — the one 409 case left
 * reachable through this button (payments already exist) is handled the
 * same "trust the mount point, but don't crash" way as elsewhere in this
 * app, since the pool detail page stops mounting this once
 * `hasPayments(pool.state)`.
 */
export function GeneratePaymentsAction({
  poolId,
  classroomId,
  onGenerated,
  refreshKey,
}: Props) {
  const [precondition, setPrecondition] = useState<PreconditionState>({
    status: "checking",
  });
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPrecondition({ status: "checking" });

    Promise.all([
      api.GET("/pools/{poolId}/purchase-plan", { params: { path: { poolId } } }),
      api.GET("/classrooms/{classroomId}/stripe-onboarding/status", {
        params: { path: { classroomId } },
      }),
    ]).then(([planResult, stripeResult]) => {
      if (cancelled) return;

      if (planResult.error || !planResult.data || planResult.data.state !== "APPROVED") {
        setPrecondition({
          status: "blocked",
          reason: "The purchase plan needs to be approved first.",
        });
        return;
      }

      if (stripeResult.response.status === 404 || stripeResult.error || !stripeResult.data) {
        setPrecondition({
          status: "blocked",
          reason:
            "Connect this classroom's bank account with Stripe first (see above).",
        });
        return;
      }

      if (stripeResult.data.status !== "ACTIVE") {
        setPrecondition({
          status: "blocked",
          reason:
            "This classroom's Stripe account isn't fully connected yet (see above).",
        });
        return;
      }

      setPrecondition({ status: "ready" });
    });

    return () => {
      cancelled = true;
    };
  }, [poolId, classroomId, refreshKey]);

  async function handleRun() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/payments/generate",
      { params: { path: { poolId } } }
    );

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "Payments may already have been generated for this pool — someone may have just run this."
          : "We couldn't do this just now. Please check your connection and try again."
      );
      setStep("review");
      return;
    }

    onGenerated(data as Payment[]);
  }

  if (precondition.status === "checking") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Checking whether payments can be generated yet…
      </div>
    );
  }

  if (precondition.status === "blocked") {
    return (
      <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
        <p className="font-medium text-slate-700">
          Not ready to collect payments yet
        </p>
        <p className="mt-1">{precondition.reason}</p>
      </div>
    );
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Ready to collect payments from families?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          This works out exactly what each family owes for their own
          residual purchase and opens payment for the whole class. This is a
          one-way step — once it's done, this can't be run again for this
          pool.
        </p>
        {errorMessage && (
          <p role="alert" className="mt-2 text-sm font-medium text-red-700">
            {errorMessage}
          </p>
        )}
        <button
          type="button"
          onClick={() => setStep("confirming")}
          className="mt-3 w-full rounded-lg border-2 border-amber-700 bg-white px-4 py-2.5 text-sm font-semibold text-amber-900 hover:bg-amber-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-700"
        >
          Open payment for this pool…
        </button>
      </div>
    );
  }

  return (
    <div className="rounded-lg border-2 border-red-300 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">
        This can't be undone
      </h2>
      <p className="mt-1 text-sm text-red-800">
        Once you continue, every family with a residual purchase will owe
        their share and be asked to pay. You won't be able to redo this for
        this pool.
      </p>
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={handleRun}
          disabled={submitting}
          className="flex-1 rounded-lg bg-red-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-900"
        >
          {submitting ? "Opening payment…" : "Yes, open payment"}
        </button>
        <button
          type="button"
          onClick={() => setStep("review")}
          disabled={submitting}
          className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
