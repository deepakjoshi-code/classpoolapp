"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { Payment, Pool } from "@/lib/api/types";
import { formatCents } from "@/lib/money";
import { paymentStateLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  poolState: Pool["state"];
  /** Called after a cash/refund action changes a payment's state — lets the
   *  pool page tell `PaymentsThresholdPanel` (a separate, self-contained
   *  sibling with its own one-time summary fetch) that its collected-total
   *  may now be stale. */
  onPaymentChanged?: (payment: Payment) => void;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; payments: Payment[] };

/**
 * Organizer's per-household payment list (PRD §8.4) — `GET
 * /pools/{poolId}/payments`. Same identity-visible-to-organizer posture as
 * `OrganizerContributionsPanel` (`Payment.householdDisplayName`, "same
 * privacy posture as Contribution.offeringParentDisplayName" per the
 * contract's own doc comment) — this is the ONLY component in the app that
 * should render it, mounted only inside the pool page's `isOrganizer`
 * branch, same "trust the mount point, API 403 is the real boundary"
 * pattern as every other client-side role gate here.
 *
 * Cash fallback (PRD §8.4 update): "Mark cash pending" only on a still-
 * `PENDING` row, "Mark cash received" only once it's `PENDING_CASH` — never
 * both at once, and neither once a row has moved on. Refund is only ever
 * actionable on a `PAID`/`PAID_CASH_RECEIVED` row, and only while the pool
 * hasn't reached `ORDERED` yet (the contract's own refund cutoff, PRD §8.4
 * update's "full refund before ORDERED; no refund after") — passed in via
 * `poolState` rather than re-deriving it, since the payments list itself
 * has no pool-state field of its own.
 */
export function OrganizerPaymentsPanel({
  poolId,
  poolState,
  onPaymentChanged,
}: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [actingId, setActingId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/payments", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", payments: data as Payment[] });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  function replacePayment(updated: Payment) {
    setState((prev) =>
      prev.status === "ready"
        ? {
            ...prev,
            payments: prev.payments.map((p) => (p.id === updated.id ? updated : p)),
          }
        : prev
    );
  }

  async function runAction(
    paymentId: string,
    path:
      | "/pools/{poolId}/payments/{paymentId}/mark-cash-pending"
      | "/pools/{poolId}/payments/{paymentId}/mark-cash-received"
      | "/pools/{poolId}/payments/{paymentId}/refund",
    failureMessage: string
  ) {
    setActingId(paymentId);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(path, {
      params: { path: { poolId, paymentId } },
    });

    setActingId(null);

    if (error || !data) {
      setErrorMessage(response.status === 409 ? failureMessage : "We couldn't do that just now. Please try again.");
      return;
    }

    const updated = data as Payment;
    replacePayment(updated);
    onPaymentChanged?.(updated);
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading payments…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load payments just now.
      </div>
    );
  }

  const { payments } = state;
  const canRefundByPoolState = poolState !== "ORDERED" &&
    poolState !== "DISTRIBUTING" &&
    poolState !== "COMPLETED";

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">Payments</h2>
      <p className="mt-1 text-sm text-slate-600">
        Every family's payment for this pool's purchase.
      </p>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      {payments.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">
          No payments yet — no family has a residual purchase to pay for.
        </p>
      ) : (
        <ul className="mt-3 space-y-2">
          {payments.map((payment) => {
            const isPending = payment.state === "PENDING";
            const isCashPending = payment.state === "PENDING_CASH";
            const canRefund =
              canRefundByPoolState &&
              (payment.state === "PAID" || payment.state === "PAID_CASH_RECEIVED");
            const acting = actingId === payment.id;

            return (
              <li
                key={payment.id}
                className="rounded-md border border-slate-100 p-3 text-sm"
              >
                <div className="flex items-center justify-between gap-3">
                  <p className="font-medium text-slate-900">
                    {payment.householdDisplayName ?? "An unnamed household"}
                  </p>
                  <p className="font-semibold text-slate-900">
                    {formatCents(payment.amountCents)}
                  </p>
                </div>
                <p className="mt-0.5 text-slate-600">
                  {paymentStateLabel(payment.state)}
                </p>

                <div className="mt-2 flex flex-wrap gap-2">
                  {isPending && (
                    <button
                      type="button"
                      onClick={() =>
                        runAction(
                          payment.id,
                          "/pools/{poolId}/payments/{paymentId}/mark-cash-pending",
                          "That payment isn't waiting on payment anymore."
                        )
                      }
                      disabled={acting}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                    >
                      {acting ? "Working…" : "Mark cash pending"}
                    </button>
                  )}
                  {isCashPending && (
                    <button
                      type="button"
                      onClick={() =>
                        runAction(
                          payment.id,
                          "/pools/{poolId}/payments/{paymentId}/mark-cash-received",
                          "That payment isn't marked as pending cash anymore."
                        )
                      }
                      disabled={acting}
                      className="rounded-lg border border-brand-300 px-3 py-1.5 text-xs font-medium text-brand-800 hover:bg-brand-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                    >
                      {acting ? "Working…" : "Mark cash received"}
                    </button>
                  )}
                  {canRefund && (
                    <button
                      type="button"
                      onClick={() =>
                        runAction(
                          payment.id,
                          "/pools/{poolId}/payments/{paymentId}/refund",
                          "That payment can no longer be refunded — the pool may have moved to ordering, or it isn't paid."
                        )
                      }
                      disabled={acting}
                      className="rounded-lg border border-red-300 px-3 py-1.5 text-xs font-medium text-red-800 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700"
                    >
                      {acting ? "Working…" : "Refund"}
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
