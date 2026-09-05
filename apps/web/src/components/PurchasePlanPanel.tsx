"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { PurchasePlan } from "@/lib/api/types";
import { formatCents } from "@/lib/money";
import { purchasePlanStateLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  /** Called after a successful approve — lets the pool page tell
   *  `GeneratePaymentsAction` (a separate, self-contained component with no
   *  other link back to this one) that its "plan needs to be approved
   *  first" precondition may now be stale. */
  onApproved?: (plan: PurchasePlan) => void;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "not-generated" }
  | { status: "ready"; plan: PurchasePlan };

/**
 * Organizer-only view of the generated purchase plan (PRD §7-8) — `GET
 * /pools/{poolId}/purchase-plan`. The pool detail page only mounts this once
 * `hasPurchasePlan(pool.state)` (`PURCHASE_PROPOSED` or later, per
 * `pool-labels.ts`), so the not-generated 409 is handled gracefully the same
 * "trust the mount point, but don't crash on a stale one" way as
 * `OrganizerAllocationPanel`'s not-reconciled 409 — reachable only via a
 * second organizer tab or a stale page state, not normal use.
 *
 * Shows, per line: which retailer/pack/quantity was chosen and its cost, a
 * running grand total, and the plan's own PROPOSED/APPROVED state in plain
 * language. Approving commits the class to real spending, so it gets a
 * single deliberate confirm step (not the full two-screen amber-then-red
 * treatment `ReconcileAction`/`GeneratePurchasePlanAction` use for locking
 * pool-state transitions, since this one's undo story — re-approving is a
 * no-op 409, and nothing about the pool's own state changes here — is lower
 * stakes than those, but still real money, hence more than a bare button).
 * On success, the returned (now-`APPROVED`) plan replaces local state
 * directly — no full reload.
 */
export function PurchasePlanPanel({ poolId, onApproved }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [confirmingApproval, setConfirmingApproval] = useState(false);
  const [approving, setApproving] = useState(false);
  const [approveError, setApproveError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/purchase-plan", { params: { path: { poolId } } }).then(
      ({ data, error, response }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({
            status: response.status === 409 ? "not-generated" : "error",
          });
          return;
        }
        setState({ status: "ready", plan: data as PurchasePlan });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  async function handleApprove() {
    setApproving(true);
    setApproveError(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/purchase-plan/approve",
      { params: { path: { poolId } } }
    );

    setApproving(false);

    if (error || !data) {
      setApproveError(
        response.status === 409
          ? "This plan has already been approved, or hasn't been generated yet."
          : "We couldn't approve this just now. Please check your connection and try again."
      );
      return;
    }

    setConfirmingApproval(false);
    const approvedPlan = data as PurchasePlan;
    setState({ status: "ready", plan: approvedPlan });
    onApproved?.(approvedPlan);
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading the purchase plan…
      </div>
    );
  }

  if (state.status === "not-generated") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        The purchase plan hasn't been worked out yet.
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load the purchase plan just now.
      </div>
    );
  }

  const { plan } = state;
  const isApproved = plan.state === "APPROVED";

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-base font-semibold text-slate-900">
          The purchase plan
        </h2>
        <span
          className={
            isApproved
              ? "text-sm font-semibold text-green-800"
              : "text-sm font-medium text-brand-800"
          }
        >
          {purchasePlanStateLabel(plan.state)}
        </span>
      </div>
      <p className="mt-1 text-sm text-slate-600">
        Exactly what to buy, from where, and how much it'll cost.
      </p>

      {plan.lines.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">
          Nothing to buy — everything's already covered.
        </p>
      ) : (
        <ul className="mt-3 space-y-2">
          {plan.lines.map((line) => (
            <li
              key={`${line.requirementId}:${line.productOfferId}`}
              className="rounded-md border border-slate-100 p-2.5 text-sm"
            >
              <p className="font-medium text-slate-900">
                {line.requirementName}
              </p>
              <p className="mt-0.5 text-slate-600">
                {line.packCount} pack{line.packCount === 1 ? "" : "s"} of{" "}
                {line.packQuantity} from {line.retailer}
              </p>
              <p className="mt-0.5 font-medium text-slate-800">
                {formatCents(line.totalCostCents)}
              </p>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3 flex items-center justify-between border-t border-slate-100 pt-3">
        <span className="text-sm font-medium text-slate-700">Total</span>
        <span className="text-base font-semibold text-slate-900">
          {formatCents(plan.totalCostCents)}
        </span>
      </div>

      {approveError && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {approveError}
        </p>
      )}

      {!isApproved && !confirmingApproval && (
        <button
          type="button"
          onClick={() => setConfirmingApproval(true)}
          className="mt-3 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Approve this plan…
        </button>
      )}

      {!isApproved && confirmingApproval && (
        <div className="mt-3 rounded-lg border-2 border-amber-300 bg-amber-50 p-3">
          <p className="text-sm font-medium text-amber-900">
            Approving commits the class to spending{" "}
            {formatCents(plan.totalCostCents)} on this plan. This can't be
            undone.
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={handleApprove}
              disabled={approving}
              className="flex-1 rounded-lg bg-red-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-900"
            >
              {approving ? "Approving…" : "Yes, approve this plan"}
            </button>
            <button
              type="button"
              onClick={() => setConfirmingApproval(false)}
              disabled={approving}
              className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {isApproved && (
        <p className="mt-3 text-sm text-green-800">
          Approved — thanks! The next step (payment) will follow.
        </p>
      )}
    </div>
  );
}
