"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { InventorySummary } from "@/lib/api/types";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; summary: InventorySummary };

/**
 * Organizer dashboard aggregate for household inventory (PRD §12.3:
 * "Inventory completed 19/25") — GET /pools/{poolId}/inventory/summary.
 * Organizer/co-organizer only; the pool detail page only renders this once
 * it has already confirmed the caller is an organizer and the pool is past
 * DRAFT (inventory has nothing to summarize before then).
 */
export function InventorySummaryPanel({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/inventory/summary", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", summary: data as InventorySummary });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  if (state.status === "loading") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600" role="status">
        Loading inventory summary…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load the inventory summary just now.
      </div>
    );
  }

  const { summary } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">
        Household inventory
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        Inventory completed {summary.studentsWithInventorySubmitted}/
        {summary.totalJoinedStudents} students
      </p>

      {summary.perRequirement.length > 0 && (
        <ul className="mt-3 space-y-2">
          {summary.perRequirement.map((row) => (
            <li
              key={row.requirementId}
              className="flex items-center justify-between gap-3 text-sm"
            >
              <span className="text-slate-700">{row.requirementName}</span>
              <span className="font-medium text-slate-900">
                {row.totalOwned} already owned
                {row.totalRequired != null && ` of ${row.totalRequired} needed`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
