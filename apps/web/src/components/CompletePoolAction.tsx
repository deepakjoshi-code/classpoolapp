"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import type { Pool, PoolDetail } from "@/lib/api/types";

type Props = {
  poolId: string;
  poolState: Pool["state"];
  onCompleted: (pool: PoolDetail) => void;
};

/**
 * Organizer's one-way "close out this pool" action (PRD's V1 flow: "pool
 * completed → savings shown") — `POST /pools/{poolId}/complete`. Mounted
 * once `hasDistribution(pool.state)` (`DISTRIBUTING` or later), same shape
 * as `PaymentsThresholdPanel` folding its own "already finalized" message
 * into the same component rather than a separate read-only view: while
 * `DISTRIBUTING`, this shows the action; once `COMPLETED`, it shows a warm
 * closing message instead.
 *
 * Same two-step amber-then-red confirm as `ReconcileAction`/`GenerateDistri
 * butionAction` (this locks in `DISTRIBUTING -> COMPLETED` and can't be
 * re-run), but this is the last step in a pool's life — the PRD explicitly
 * wants a moment of warmth here ("savings shown"), so the copy leans into
 * that instead of reading like every other stern one-way warning. The
 * actual savings figure is a later phase's computation (out of scope here);
 * this only sets the closing tone, it doesn't fabricate a number.
 */
export function CompletePoolAction({ poolId, poolState, onCompleted }: Props) {
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleRun() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST("/pools/{poolId}/complete", {
      params: { path: { poolId } },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This pool may already be complete — someone may have just finished it."
          : "We couldn't do this just now. Please check your connection and try again."
      );
      setStep("review");
      return;
    }

    onCompleted(data as PoolDetail);
  }

  if (poolState === "COMPLETED") {
    return (
      <div className="rounded-lg border-2 border-green-200 bg-green-50 p-4">
        <h2 className="text-base font-semibold text-green-900">This pool is complete</h2>
        <p className="mt-1 text-sm text-green-800">
          Thank you for organizing this — every family got what they needed,
          together, for less than buying alone. Nice work.
        </p>
      </div>
    );
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">Ready to wrap up this pool?</h2>
        <p className="mt-1 text-sm text-amber-800">
          This marks the pool finished. You don't need to wait for every item
          to be marked delivered first — this is the last step. This is a
          one-way step — once it's done, this can't be run again.
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
          Finish this pool…
        </button>
      </div>
    );
  }

  return (
    <div className="rounded-lg border-2 border-red-300 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">This can't be undone</h2>
      <p className="mt-1 text-sm text-red-800">
        Once you continue, this pool is closed out for good.
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
          {submitting ? "Finishing…" : "Yes, finish this pool"}
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
