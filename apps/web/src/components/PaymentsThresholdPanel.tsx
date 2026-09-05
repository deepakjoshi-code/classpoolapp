"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { PaymentsSummary, Pool, PoolDetail } from "@/lib/api/types";
import { formatCents } from "@/lib/money";

type Props = {
  poolId: string;
  poolState: Pool["state"];
  onFinalized: (pool: PoolDetail) => void;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; summary: PaymentsSummary };

/**
 * Organizer's payment-threshold view and finalize action (PRD §8.4 update)
 * — `GET /pools/{poolId}/payments/summary`, then `POST
 * /pools/{poolId}/payments/finalize`. Shows percent collected against the
 * platform-set threshold (currently 90%, `thresholdPercent` — never
 * organizer-editable) with a simple progress bar, and — the PRD's explicit
 * risk banner — every outstanding household once below that threshold.
 *
 * Finalizing locks the pool `PAYMENT_OPEN → ORDERED`, the hand-off into
 * ordering, so committing to buy without full payment collected gets the
 * same weight as `PurchasePlanPanel`'s approve-with-money-at-stake pattern:
 * at/above threshold, a single deliberate confirm step (not a bare click);
 * below threshold, an explicit acknowledgment (a checkbox that must be
 * ticked, not just a second click) before `acknowledgeBelowThreshold: true`
 * is sent — this is committing to buy while some families haven't paid.
 */
export function PaymentsThresholdPanel({ poolId, poolState, onFinalized }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [confirming, setConfirming] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [finalizing, setFinalizing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/payments/summary", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", summary: data as PaymentsSummary });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  async function handleFinalize(acknowledgeBelowThreshold: boolean) {
    setFinalizing(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/payments/finalize",
      { params: { path: { poolId } }, body: { acknowledgeBelowThreshold } }
    );

    setFinalizing(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This can't be finalized right now — it may already have moved on, or need that below-threshold acknowledgment."
          : "We couldn't finalize this just now. Please check your connection and try again."
      );
      return;
    }

    onFinalized(data as PoolDetail);
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading payment progress…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load payment progress just now.
      </div>
    );
  }

  const { summary } = state;
  const percent = Math.max(0, Math.min(100, summary.percentCollected));

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">
        Payment progress
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        {formatCents(summary.totalCollectedCents)} collected of{" "}
        {formatCents(summary.totalOwedCents)} owed.
      </p>

      <div className="mt-2">
        <div
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Percent of payments collected"
          className="h-2 w-full overflow-hidden rounded-full bg-slate-200"
        >
          <div
            className={
              summary.meetsThreshold ? "h-2 rounded-full bg-green-600" : "h-2 rounded-full bg-amber-500"
            }
            style={{ width: `${percent}%` }}
          />
        </div>
        <p className="mt-1 text-xs text-slate-600">
          {percent}% collected · needs {summary.thresholdPercent}% to proceed
          normally
        </p>
      </div>

      {!summary.meetsThreshold && (
        <div className="mt-3 rounded-lg border-2 border-amber-300 bg-amber-50 p-3">
          <p className="text-sm font-medium text-amber-900">
            Below the {summary.thresholdPercent}% collection target —
            proceeding now means buying before everyone has paid.
          </p>
          {summary.outstandingHouseholds.length > 0 && (
            <ul className="mt-2 space-y-1 text-sm text-amber-800">
              {summary.outstandingHouseholds.map((h) => (
                <li key={h.householdId}>
                  {h.householdDisplayName ?? "An unnamed household"} still
                  owes {formatCents(h.amountCents)}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}

      {poolState !== "PAYMENT_OPEN" && (
        <p className="mt-3 text-sm text-green-800">
          Payment has been finalized — this pool has moved on to ordering.
        </p>
      )}

      {poolState === "PAYMENT_OPEN" && !confirming && (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="mt-3 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Finalize payment and proceed to ordering…
        </button>
      )}

      {poolState === "PAYMENT_OPEN" && confirming && summary.meetsThreshold && (
        <div className="mt-3 rounded-lg border-2 border-brand-300 bg-brand-50 p-3">
          <p className="text-sm font-medium text-brand-900">
            This locks in payment collection and moves this pool on to
            ordering. This can't be undone.
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => handleFinalize(false)}
              disabled={finalizing}
              className="flex-1 rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
            >
              {finalizing ? "Finalizing…" : "Yes, finalize"}
            </button>
            <button
              type="button"
              onClick={() => setConfirming(false)}
              disabled={finalizing}
              className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {poolState === "PAYMENT_OPEN" && confirming && !summary.meetsThreshold && (
        <div className="mt-3 rounded-lg border-2 border-red-300 bg-red-50 p-3">
          <p className="text-sm font-medium text-red-900">
            You're about to commit this class to buying before every family
            has paid. Outstanding households may still need to be chased
            down or refunded later — this can't be undone.
          </p>
          <label className="mt-2 flex items-start gap-2 text-sm text-red-900">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => setAcknowledged(e.target.checked)}
              className="mt-0.5"
            />
            I understand not everyone has paid yet, and want to proceed
            anyway.
          </label>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={() => handleFinalize(true)}
              disabled={finalizing || !acknowledged}
              className="flex-1 rounded-lg bg-red-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-900"
            >
              {finalizing ? "Finalizing…" : "Yes, finalize below threshold"}
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirming(false);
                setAcknowledged(false);
              }}
              disabled={finalizing}
              className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
