"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import type { PurchasePlan } from "@/lib/api/types";

type Props = {
  poolId: string;
  onGenerated: (plan: PurchasePlan) => void;
};

/**
 * Organizer's "work out the cheapest way to buy what's left" action (PRD
 * §7.1's bulk-pack optimizer, described here without that internal name) —
 * `POST /pools/{poolId}/purchase-plan/generate`. Same one-way, two-step
 * confirmation shape as `ReconcileAction`: this is also a locking transition
 * (`RECONCILING -> PURCHASE_PROPOSED`, no re-run in V1 — the contract 409s
 * if a plan already exists), so it gets the same amber "review" step then
 * red "confirming" step rather than a single click.
 *
 * The contract's 409 for this endpoint actually covers three cases (pool
 * isn't RECONCILING, a plan already exists, or a requirement that still
 * needs buying has no price option yet) with no distinguishing field on the
 * response, but the pool detail page only mounts this action while
 * `pool.state === "RECONCILING"` and no plan exists yet — same "trust the
 * mount point" reasoning used throughout this app (see `OrganizerAllocation
 * Panel`'s 409 handling) — so in normal use the only 409 an organizer can
 * actually trigger by clicking this is the missing-price-option one. That's
 * the message shown here, distinct from the generic retry message used for
 * everything else (network errors, 500s).
 */
export function GeneratePurchasePlanAction({ poolId, onGenerated }: Props) {
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleRun() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/purchase-plan/generate",
      { params: { path: { poolId } } }
    );

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "Every item that still needs buying needs at least one price option first — add one above."
          : "We couldn't do this just now. Please check your connection and try again."
      );
      setStep("review");
      return;
    }

    onGenerated(data as PurchasePlan);
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Ready to work out the cheapest way to buy what's left?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          This looks at the price options entered above and works out the
          cheapest combination that covers everything still needed. This is
          a one-way step — once it's done, this can't be run again for this
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
          Work out the purchase plan…
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
        Once you continue, we'll lock in exactly what to buy, from where, and
        how much it'll cost — using the price options entered so far. You
        won't be able to redo this for this pool.
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
          {submitting ? "Working it out…" : "Yes, work it out"}
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
