"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { Order, PurchasePlan } from "@/lib/api/types";
import { dollarsToCents, formatCents } from "@/lib/money";
import { orderLineSubstitutionMessage } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  /** Called once an order is recorded (whether just now, or already recorded
   *  on mount) — lets the pool page tell `GenerateDistributionAction` (a
   *  separate, self-contained sibling with its own one-time precondition
   *  check) that its "order recorded yet?" answer may now be stale. Same
   *  `refreshKey`-bump shape as `PurchasePlanPanel`'s `onApproved`/
   *  `StripeOnboardingCard`'s `onActive` feeding `GeneratePaymentsAction`. */
  onRecorded?: (order: Order) => void;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "to-record"; plan: PurchasePlan }
  | { status: "recorded"; order: Order };

type LineOverride = { actualCost: string; actualDescription: string };

/**
 * Organizer's "record the order" action (PRD §9.1) — `GET`/`POST
 * /pools/{poolId}/order`. Mounted only while `pool.state === "ORDERED"`
 * (see `hasEnteredOrdering`/`hasDistribution` in `pool-labels.ts`); unlike
 * every earlier one-way action in this app, recording an order does NOT
 * change `pool.state` itself, so this component checks on mount whether one
 * has already been recorded (`GET .../order`, 409 = not yet) and doubles as
 * both the action (not yet recorded) and the read view of what happened
 * (already recorded) — same "one panel, both a read view and a commit
 * action" shape as `PurchasePlanPanel`, just with the precondition self-check
 * `GeneratePaymentsAction` uses layered on top, since state alone can't tell
 * these two sub-states apart.
 *
 * Recording with no line overrides at all is the common, fully-valid case
 * (PRD's flow assumes most orders go exactly as planned) — the primary
 * button posts with no `lines` override. The secondary, optional per-line
 * editor is for genuine substitutions (a different pack/brand/price than
 * priced): each edited line's actual cost and/or description is sent, and
 * the server works out per-line whether the resulting delta is small enough
 * to `ABSORBED` or big enough to `TOP_UP_CHARGED` an extra `Payment` per
 * affected household — `orderLineSubstitutionMessage` turns that outcome
 * into plain language once recorded, never the raw enum value.
 *
 * Recording doesn't move real money by itself in the common case (no
 * overrides), and never changes `pool.state`, so this uses the same
 * single-step deliberate confirm `PurchasePlanPanel`'s approve action uses
 * rather than the full two-screen amber-then-red treatment reserved for
 * locking pool-state transitions (`ReconcileAction`, `GeneratePurchasePlan
 * Action`, `GenerateDistributionAction`) — though the override path can
 * still trigger real top-up charges, so it isn't a bare button either.
 *
 * KNOWN CONTRACT GAP: `recordOrder`'s request body needs a
 * `purchasePlanLineId` per overridden line, but `PurchasePlanLine` (the
 * shape `GET .../purchase-plan` actually returns) has no `id` field of its
 * own anywhere in the contract — only `requirementId`/`productOfferId`.
 * Rather than invent a client-side id or edit the contract unilaterally,
 * this sends that line's `requirementId` as the `purchasePlanLineId` value,
 * which is correct in the common case (one plan line per requirement) but
 * ambiguous if a single requirement's plan ever spans more than one
 * offer/line (the same rare edge case `PurchasePlanLine.wasteQuantity`'s own
 * "designated line" doc comment already calls out) — only the last edited
 * line for a given requirement would end up applied in that case. Flagged in
 * README's "Known discrepancies" rather than silently working around it.
 */
export function RecordOrderAction({ poolId, onRecorded }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [showEditor, setShowEditor] = useState(false);
  const [overrides, setOverrides] = useState<Record<number, LineOverride>>({});
  const [pendingAction, setPendingAction] = useState<"plain" | "overrides" | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setState({ status: "loading" });

    Promise.all([
      api.GET("/pools/{poolId}/purchase-plan", { params: { path: { poolId } } }),
      api.GET("/pools/{poolId}/order", { params: { path: { poolId } } }),
    ]).then(([planResult, orderResult]) => {
      if (cancelled) return;

      if (orderResult.data) {
        setState({ status: "recorded", order: orderResult.data as Order });
        return;
      }

      if (planResult.error || !planResult.data) {
        setState({ status: "error" });
        return;
      }

      setState({ status: "to-record", plan: planResult.data as PurchasePlan });
    });

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  function overrideFor(index: number): LineOverride {
    return overrides[index] ?? { actualCost: "", actualDescription: "" };
  }

  function updateOverride(index: number, patch: Partial<LineOverride>) {
    setOverrides((prev) => ({ ...prev, [index]: { ...overrideFor(index), ...patch } }));
  }

  async function handleSubmit() {
    if (state.status !== "to-record" || !pendingAction) return;
    setSubmitting(true);
    setErrorMessage(null);

    const lines =
      pendingAction === "overrides"
        ? state.plan.lines
            .map((line, index) => ({ line, override: overrides[index] }))
            .filter(
              ({ override }) =>
                override && (override.actualCost.trim() || override.actualDescription.trim())
            )
            .map(({ line, override }) => {
              const costCents = override!.actualCost.trim()
                ? dollarsToCents(override!.actualCost)
                : null;
              return {
                purchasePlanLineId: line.requirementId,
                actualCostCents: costCents !== null && Number.isFinite(costCents) ? costCents : null,
                actualDescription: override!.actualDescription.trim()
                  ? override!.actualDescription.trim()
                  : null,
              };
            })
        : undefined;

    const { data, error, response } = await api.POST("/pools/{poolId}/order", {
      params: { path: { poolId } },
      body: lines && lines.length > 0 ? { lines } : {},
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "An order may already have been recorded for this pool — someone may have just done this."
          : "We couldn't record this just now. Please check your connection and try again."
      );
      setPendingAction(null);
      return;
    }

    const order = data as Order;
    setState({ status: "recorded", order });
    onRecorded?.(order);
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Checking whether this order has been recorded yet…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load the order status just now.
      </div>
    );
  }

  if (state.status === "recorded") {
    const { order } = state;
    return (
      <div className="rounded-lg border border-green-200 bg-green-50 p-4">
        <h2 className="text-base font-semibold text-green-900">Order recorded</h2>
        <p className="mt-1 text-sm text-green-800">
          Ordered on {new Date(order.orderedAt).toLocaleDateString()}.
        </p>
        {order.lines.length === 0 ? (
          <p className="mt-3 text-sm text-green-800">Nothing needed to be purchased.</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {order.lines.map((line) => (
              <li
                key={line.id}
                className="rounded-md border border-green-100 bg-white p-2.5 text-sm"
              >
                <p className="font-medium text-slate-900">{line.requirementName}</p>
                <p className="mt-0.5 text-slate-700">
                  {formatCents(line.actualCostCents)}
                  {line.actualDescription ? ` — ${line.actualDescription}` : ""}
                </p>
                <p className="mt-0.5 text-slate-600">{orderLineSubstitutionMessage(line)}</p>
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  }

  const { plan } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">Record the order</h2>
      <p className="mt-1 text-sm text-slate-600">
        Tell us the purchase plan was actually bought, so distribution can be
        set up next.
      </p>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}

      {pendingAction === null && (
        <>
          <button
            type="button"
            onClick={() => setPendingAction("plain")}
            className="mt-3 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
          >
            Yes, I bought this — nothing was substituted
          </button>

          {plan.lines.length > 0 && (
            <div className="mt-3">
              <button
                type="button"
                onClick={() => setShowEditor((s) => !s)}
                className="text-sm font-medium text-brand-700 hover:underline"
              >
                {showEditor
                  ? "Hide item-by-item changes"
                  : "Something was different? Edit specific items first"}
              </button>

              {showEditor && (
                <div className="mt-3 space-y-3 border-t border-slate-100 pt-3">
                  {plan.lines.map((line, index) => {
                    const override = overrideFor(index);
                    return (
                      <div
                        key={`${line.requirementId}-${index}`}
                        className="rounded-md border border-slate-100 p-2.5"
                      >
                        <p className="text-sm font-medium text-slate-900">
                          {line.requirementName}
                        </p>
                        <p className="mt-0.5 text-xs text-slate-500">
                          Planned: {formatCents(line.totalCostCents)} from{" "}
                          {line.retailer}
                        </p>
                        <div className="mt-2 grid grid-cols-2 gap-2">
                          <div>
                            <label
                              htmlFor={`order-cost-${index}`}
                              className="block text-xs font-medium text-slate-700"
                            >
                              Actual cost{" "}
                              <span className="font-normal text-slate-500">
                                (optional)
                              </span>
                            </label>
                            <input
                              id={`order-cost-${index}`}
                              type="number"
                              inputMode="decimal"
                              step="0.01"
                              min="0"
                              value={override.actualCost}
                              onChange={(e) =>
                                updateOverride(index, { actualCost: e.target.value })
                              }
                              placeholder={(line.totalCostCents / 100).toFixed(2)}
                              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                            />
                          </div>
                          <div>
                            <label
                              htmlFor={`order-desc-${index}`}
                              className="block text-xs font-medium text-slate-700"
                            >
                              What you actually bought{" "}
                              <span className="font-normal text-slate-500">
                                (optional)
                              </span>
                            </label>
                            <input
                              id={`order-desc-${index}`}
                              type="text"
                              value={override.actualDescription}
                              onChange={(e) =>
                                updateOverride(index, { actualDescription: e.target.value })
                              }
                              placeholder="e.g. Store-brand marker"
                              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                            />
                          </div>
                        </div>
                      </div>
                    );
                  })}
                  <button
                    type="button"
                    onClick={() => setPendingAction("overrides")}
                    className="w-full rounded-lg border border-brand-700 bg-white px-4 py-2.5 text-sm font-semibold text-brand-800 hover:bg-brand-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                  >
                    Record with these changes
                  </button>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {pendingAction !== null && (
        <div className="mt-3 rounded-lg border-2 border-amber-300 bg-amber-50 p-3">
          <p className="text-sm font-medium text-amber-900">
            {pendingAction === "plain"
              ? "This records the order exactly as planned. This can't be undone."
              : "This records the order with the changes you entered above — any item priced enough over plan will trigger an extra charge for the families who need it. This can't be undone."}
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={handleSubmit}
              disabled={submitting}
              className="flex-1 rounded-lg bg-red-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-900"
            >
              {submitting ? "Recording…" : "Yes, record this order"}
            </button>
            <button
              type="button"
              onClick={() => setPendingAction(null)}
              disabled={submitting}
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
