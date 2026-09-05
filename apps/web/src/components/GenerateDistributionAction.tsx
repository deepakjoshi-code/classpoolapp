"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { DistributionSummary } from "@/lib/api/types";
import { DISTRIBUTION_MODE_LABELS } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  onGenerated: (summary: DistributionSummary) => void;
  /** Bump this whenever `RecordOrderAction` (a separate, self-contained
   *  sibling with no other link back to this one) records an order — this
   *  component's own "order recorded yet?" precondition check is fetched
   *  once on mount and would otherwise never learn it's stale. Same shape as
   *  `GeneratePaymentsAction`'s `refreshKey`. */
  refreshKey?: number;
};

type PreconditionState =
  | { status: "checking" }
  | { status: "blocked"; reason: string }
  | { status: "ready" };

type DistributionMode = DistributionSummary["mode"];

const MODE_OPTIONS: DistributionMode[] = ["CLASSROOM_DESK", "LOBBY_PICKUP", "HOUSEHOLD_BAG"];

/**
 * Organizer's one-way "set up distribution" action (PRD §9.2/§9.3) — `POST
 * /pools/{poolId}/distribution/generate`. Same two-step amber-then-red
 * confirm shape as `ReconcileAction`/`GeneratePurchasePlanAction`/
 * `GeneratePaymentsAction`: this locks in `ORDERED -> DISTRIBUTING` and
 * can't be re-run (the contract 409s if a batch already exists).
 *
 * Mounted only while `pool.state === "ORDERED"`. Like `GeneratePayments
 * Action`, this endpoint's real precondition ("an order has been recorded")
 * isn't captured by pool state alone — a pool stays `ORDERED` whether or not
 * `RecordOrderAction` has run yet — so this checks `GET .../order` itself on
 * mount (and whenever `refreshKey` bumps) and shows a specific, named reason
 * instead of a dead button when no order exists yet.
 *
 * Adds a plain-language mode picker (PRD §9.2's three hand-off styles) to
 * the review step, sent as the POST body once confirmed.
 */
export function GenerateDistributionAction({ poolId, onGenerated, refreshKey }: Props) {
  const [precondition, setPrecondition] = useState<PreconditionState>({ status: "checking" });
  const [mode, setMode] = useState<DistributionMode>("CLASSROOM_DESK");
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPrecondition({ status: "checking" });

    api.GET("/pools/{poolId}/order", { params: { path: { poolId } } }).then(
      ({ data, error, response }) => {
        if (cancelled) return;

        if (data) {
          setPrecondition({ status: "ready" });
          return;
        }

        if (response.status === 409 || error) {
          setPrecondition({
            status: "blocked",
            reason: "Record the order first (see above).",
          });
          return;
        }

        setPrecondition({ status: "ready" });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId, refreshKey]);

  async function handleRun() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST("/pools/{poolId}/distribution/generate", {
      params: { path: { poolId } },
      body: { mode },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "Distribution may already be set up for this pool — someone may have just run this."
          : "We couldn't do this just now. Please check your connection and try again."
      );
      setStep("review");
      return;
    }

    onGenerated(data as DistributionSummary);
  }

  if (precondition.status === "checking") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Checking whether distribution can be set up yet…
      </div>
    );
  }

  if (precondition.status === "blocked") {
    return (
      <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
        <p className="font-medium text-slate-700">Not ready to set up distribution yet</p>
        <p className="mt-1">{precondition.reason}</p>
      </div>
    );
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Ready to set up how families will get their items?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          This works out exactly who gets what from the order, and builds a
          pick list for each household. This is a one-way step — once it's
          done, this can't be run again for this pool.
        </p>

        <fieldset className="mt-3">
          <legend className="text-sm font-medium text-amber-900">
            How will items be handed off?
          </legend>
          <div className="mt-2 space-y-2">
            {MODE_OPTIONS.map((option) => (
              <label
                key={option}
                className="flex items-center gap-2 text-sm text-amber-900"
              >
                <input
                  type="radio"
                  name="distribution-mode"
                  value={option}
                  checked={mode === option}
                  onChange={() => setMode(option)}
                />
                {DISTRIBUTION_MODE_LABELS[option]}
              </label>
            ))}
          </div>
        </fieldset>

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
          Set up distribution…
        </button>
      </div>
    );
  }

  return (
    <div className="rounded-lg border-2 border-red-300 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">This can't be undone</h2>
      <p className="mt-1 text-sm text-red-800">
        Once you continue, we'll build pick lists for every household using{" "}
        {DISTRIBUTION_MODE_LABELS[mode].toLowerCase()}. You won't be able to
        redo this for this pool.
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
          {submitting ? "Setting up…" : "Yes, set up distribution"}
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
