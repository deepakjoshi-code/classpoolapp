"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import type { PoolDetail } from "@/lib/api/types";

type Props = {
  poolId: string;
  requirementCount: number;
  onConfirmed: (pool: PoolDetail) => void;
};

/**
 * The organizer-verification gate (PRD §3.3: "Nothing is financially
 * actionable until a human organizer verifies") — a one-way transition that
 * locks the requirement list, so it gets a two-step confirmation and
 * deliberately non-routine (amber/red, not brand-blue "Save") styling rather
 * than a single click, per the accessibility/seriousness bar in the task.
 */
export function ConfirmPoolAction({
  poolId,
  requirementCount,
  onConfirmed,
}: Props) {
  const [step, setStep] = useState<"review" | "confirming">("review");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleConfirm() {
    setSubmitting(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST("/pools/{poolId}/confirm", {
      params: { path: { poolId } },
    });

    setSubmitting(false);

    if (error || !data) {
      if (response.status === 409) {
        setErrorMessage(
          requirementCount === 0
            ? "You need at least one item on the list before you can confirm it."
            : "This pool has already been confirmed — its list is locked."
        );
      } else {
        setErrorMessage(
          "We couldn't confirm the list just now. Please check your connection and try again."
        );
      }
      setStep("review");
      return;
    }

    onConfirmed(data as PoolDetail);
  }

  if (step === "review") {
    return (
      <div className="rounded-lg border-2 border-amber-300 bg-amber-50 p-4">
        <h2 className="text-base font-semibold text-amber-900">
          Ready to confirm the list?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          Nothing on this list is financially actionable until you confirm
          it — this is the step that makes it real. Once confirmed, the list
          locks: no more adding, editing, or removing items.
        </p>
        {errorMessage && (
          <p role="alert" className="mt-2 text-sm font-medium text-red-700">
            {errorMessage}
          </p>
        )}
        <button
          type="button"
          onClick={() => setStep("confirming")}
          disabled={requirementCount === 0}
          className="mt-3 w-full rounded-lg border-2 border-amber-700 bg-white px-4 py-2.5 text-sm font-semibold text-amber-900 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-700"
        >
          Confirm list…
        </button>
        {requirementCount === 0 && (
          <p className="mt-2 text-xs text-amber-700">
            Add at least one item before you can confirm.
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="rounded-lg border-2 border-red-300 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">
        This can't be undone
      </h2>
      <p className="mt-1 text-sm text-red-800">
        Confirming locks in all {requirementCount} item
        {requirementCount === 1 ? "" : "s"} on this list. Every family's
        totals will be calculated from exactly this list, and you won't be
        able to add, edit, or remove items afterward.
      </p>
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={handleConfirm}
          disabled={submitting}
          className="flex-1 rounded-lg bg-red-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-900"
        >
          {submitting ? "Confirming…" : "Yes, confirm and lock the list"}
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
