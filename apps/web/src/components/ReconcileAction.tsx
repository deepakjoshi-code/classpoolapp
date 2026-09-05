"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import type { AllocationSummary } from "@/lib/api/types";

type Props = {
  poolId: string;
  onReconciled: (summary: AllocationSummary) => void;
};

/**
 * The organizer's "work out what still needs buying" action (PRD §6's
 * allocation & residual-demand engine, described here in plain language —
 * "residual demand" and "allocation engine" are internal PRD terms, never
 * shown to a parent or organizer) — `POST /pools/{poolId}/reconcile`.
 *
 * Same one-way, two-step confirmation shape as `ConfirmPoolAction`: this is
 * also a locking transition (`OPEN_FOR_INVENTORY → RECONCILING`, and V1
 * doesn't support re-running it — the contract 409s if the pool has already
 * left `OPEN_FOR_INVENTORY`), so it gets the same amber "review" step then
 * red "confirming" step rather than a single click.
 *
 * Unlike `ConfirmPoolAction`, the endpoint doesn't return the updated pool —
 * it returns the `AllocationSummary` itself (the whole point of running
 * this). The pool detail page is responsible for flipping its own local
 * `pool.state` to `RECONCILING` in its `onReconciled` callback, same
 * "update local state so the UI reflects the transition without a full
 * reload" idea as `onConfirmed`, just carrying a different payload.
 */
export function ReconcileAction({ poolId, onReconciled }: Props) {
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleRun() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST("/pools/{poolId}/reconcile", {
      params: { path: { poolId } },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This has already been done for this pool — someone may have just run it."
          : "We couldn't do this just now. Please check your connection and try again."
      );
      setStep("review");
      return;
    }

    onReconciled(data as AllocationSummary);
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Ready to work out what still needs buying?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          This looks at what everyone already owns and what's been donated,
          and works out exactly what still needs to be bought for the class.
          This is a one-way step — once it's done, those numbers are locked
          in and this can't be run again.
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
          Work out what's needed…
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
        Once you continue, we'll lock in exactly what each family already
        has, what's been donated, and what's left to buy — using this
        moment's numbers. You won't be able to redo this for this pool.
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
