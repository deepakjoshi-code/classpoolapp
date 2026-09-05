"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { AllocationSummary } from "@/lib/api/types";
import { allocationStatusLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "not-reconciled" }
  | { status: "ready"; summary: AllocationSummary };

/**
 * Organizer view of the allocation & residual-demand engine's output (PRD
 * §6, described here without its internal names) — `GET
 * /pools/{poolId}/allocation`. Organizer/co-organizer only; the pool detail
 * page only mounts this once the pool has moved past `RECONCILING`'s
 * pre-state (see `hasReconciled` in `pool-labels.ts`), same "trust the mount
 * point, API 403 is the real boundary" pattern as `InventorySummaryPanel`
 * and `OrganizerContributionsPanel`.
 *
 * The 409 ("reconcile hasn't run yet") is handled gracefully even though the
 * page's own state gate should make it unreachable in normal use — a second
 * organizer tab, or a stale page state, could still hit it.
 */
export function OrganizerAllocationPanel({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/allocation", { params: { path: { poolId } } }).then(
      ({ data, error, response }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({
            status: response.status === 409 ? "not-reconciled" : "error",
          });
          return;
        }
        setState({ status: "ready", summary: data as AllocationSummary });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading the purchase breakdown…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load the purchase breakdown just now.
      </div>
    );
  }

  if (state.status === "not-reconciled") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        This hasn't been worked out yet. Once you run that step, the
        breakdown will show up here.
      </div>
    );
  }

  const { summary } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">
        What still needs to be purchased
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        This looks at what everyone already owns and what's been donated to
        work out exactly what's left to buy for the class, item by item.
      </p>

      {summary.residualDemand.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">No items on this list.</p>
      ) : (
        <ul className="mt-3 space-y-4">
          {summary.residualDemand.map((line) => {
            const students = summary.allocations.filter(
              (a) => a.requirementId === line.requirementId
            );
            const fullyCovered = line.residualDemand === 0;
            return (
              <li
                key={line.requirementId}
                className="rounded-md border border-slate-100 p-3"
              >
                <p className="font-medium text-slate-900">
                  {line.requirementName}
                </p>
                <p
                  className={
                    fullyCovered
                      ? "mt-0.5 text-sm font-semibold text-green-800"
                      : "mt-0.5 text-sm font-medium text-brand-800"
                  }
                >
                  {fullyCovered
                    ? "Fully covered!"
                    : `${line.residualDemand} still need${
                        line.residualDemand === 1 ? "s" : ""
                      } to be purchased`}
                </p>

                {students.length > 0 && (
                  <ul className="mt-2 space-y-1 border-t border-slate-100 pt-2 text-sm text-slate-600">
                    {students.map((a) => (
                      <li key={a.studentId}>
                        <span className="font-medium text-slate-800">
                          {a.studentFirstName ?? "A student"}
                        </span>
                        {": "}
                        {allocationStatusLabel(a.status, a.purchaseRequiredQuantity)}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
