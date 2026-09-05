"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Contribution } from "@/lib/api/types";
import { contributionStateLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  requirementId: string;
  requirementName: string;
  quantityPerStudent: number;
  studentId: string;
  studentFirstName: string | null;
  /**
   * This household's own pledges (any state) for this requirement + student.
   * Scoped by the page to GET /pools/{poolId}/contributions/mine — never
   * another household's, and this component never fetches or is handed
   * anyone else's contributions (PRD §5.3 privacy model).
   */
  contributions: Contribution[];
  onOffered: (contribution: Contribution) => void;
  onWithdrawn: (contributionId: string) => void;
};

/**
 * "Offer surplus" (PRD §5.1) — one card per (requirement, student the caller
 * has in this classroom), letting a parent pledge donated surplus and see/
 * withdraw their own pledge. A related but distinct action from Phase 4's
 * "Shop Your Home First" stepper: that records what a household already
 * owns and keeps, this records what they're willing to give away — the
 * contract is explicit that `quantity` here is an independent declaration,
 * not derived from or clamped by the inventory number.
 *
 * V1 only supports `mode: DONATE` (Give) per PRD §5.1 — Lend/Sell are
 * explicitly "later," so this only ever offers a `mode: "DONATE"` body and
 * never renders a mode picker.
 *
 * Framed as low-pressure/optional (PRD §5.1's "proactively ask whether the
 * surplus can help the class") — no required-step styling, no red/amber
 * urgency treatment like ConfirmPoolAction; a plain brand-blue "offer"
 * action a parent can ignore entirely.
 *
 * Privacy: this card only ever shows the caller's OWN pledges. It never
 * receives or renders `offeringParentDisplayName` (irrelevant to your own
 * pledge) or any other household's contributions — that identity-carrying
 * view is OrganizerContributionsPanel, a completely separate component
 * rendered only for organizers.
 */
export function ContributionOfferCard({
  poolId,
  requirementId,
  requirementName,
  quantityPerStudent,
  studentId,
  studentFirstName,
  contributions,
  onOffered,
  onWithdrawn,
}: Props) {
  const [quantity, setQuantity] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [withdrawingId, setWithdrawingId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const idSuffix = `${requirementId}-${studentId}`;
  const studentSuffix = studentFirstName ? ` for ${studentFirstName}` : "";
  const quantityNumber = Number(quantity);
  const canSubmit =
    quantity.trim().length > 0 &&
    Number.isInteger(quantityNumber) &&
    quantityNumber >= 1;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const { data, error } = await api.POST(
      "/pools/{poolId}/requirements/{requirementId}/contributions",
      {
        params: { path: { poolId, requirementId } },
        body: { studentId, quantity: quantityNumber, mode: "DONATE" },
      }
    );

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        "We couldn't record that offer just now. Please check your connection and try again."
      );
      return;
    }

    setQuantity("");
    onOffered(data as Contribution);
  }

  async function handleWithdraw(contributionId: string) {
    setWithdrawingId(contributionId);
    setErrorMessage(null);

    const { error, response } = await api.DELETE(
      "/pools/{poolId}/contributions/{contributionId}",
      { params: { path: { poolId, contributionId } } }
    );

    setWithdrawingId(null);

    if (error) {
      setErrorMessage(
        response.status === 409
          ? "That offer has already been received, so it can no longer be withdrawn."
          : "We couldn't withdraw that offer just now. Please try again."
      );
      return;
    }

    onWithdrawn(contributionId);
  }

  return (
    <li className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="font-medium text-slate-900">
        {requirementName}
        {studentFirstName && (
          <span className="font-normal text-slate-500"> · {studentFirstName}</span>
        )}
      </p>
      <p className="mt-1 text-sm text-slate-600">
        {quantityPerStudent} needed per student
      </p>

      {contributions.length > 0 && (
        <ul className="mt-3 space-y-2">
          {contributions.map((contribution) => {
            const isPledged = contribution.state === "PLEDGED";
            return (
              <li
                key={contribution.id}
                className="flex items-center justify-between gap-3 rounded-md border border-slate-100 bg-slate-50 p-2.5 text-sm"
              >
                <span
                  className={
                    isPledged
                      ? "font-medium text-brand-800"
                      : "font-medium text-green-800"
                  }
                >
                  You offered {contribution.quantity} ·{" "}
                  {contributionStateLabel(contribution.state)}
                </span>
                {isPledged && (
                  <button
                    type="button"
                    onClick={() => handleWithdraw(contribution.id)}
                    disabled={withdrawingId === contribution.id}
                    aria-label={`Withdraw your offer of ${contribution.quantity} ${requirementName}${studentSuffix}`}
                    className="shrink-0 rounded-lg border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                  >
                    {withdrawingId === contribution.id ? "Withdrawing…" : "Withdraw"}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <form onSubmit={handleSubmit} className="mt-3 flex items-end gap-2" noValidate>
        <div className="flex-1">
          <label
            htmlFor={`offer-qty-${idSuffix}`}
            className="block text-xs font-medium text-slate-700"
          >
            Extra you can give{studentSuffix}
          </label>
          <input
            id={`offer-qty-${idSuffix}`}
            type="number"
            inputMode="numeric"
            min={1}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="0"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>
        <button
          type="submit"
          disabled={!canSubmit || submitting}
          className="rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          {submitting ? "Offering…" : "Offer to donate"}
        </button>
      </form>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {errorMessage}
        </p>
      )}
    </li>
  );
}
