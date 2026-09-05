"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { AllocationLine } from "@/lib/api/types";
import { allocationStatusLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; lines: AllocationLine[] };

/**
 * A household's own "what's happening with my order" view (PRD §6's
 * allocation & residual-demand engine, described here without its internal
 * names) — `GET /pools/{poolId}/allocation/mine`. Any classroom member
 * (including an organizer's own household, if they have a student in the
 * class) — the pool detail page mounts this for everyone once the pool has
 * moved past reconcile's pre-state.
 *
 * PRIVACY: `.../allocation/mine` is already scoped server-side to the
 * caller's own students — unlike `Contribution.studentId` (Phase 5's
 * documented always-null limitation), `AllocationLine.studentId`/
 * `studentFirstName` here are real per-student values, but they're always
 * *this household's own* students, never anyone else's. This component adds
 * no further filtering of its own (there's nothing else to filter — the
 * whole response is already "mine") and never receives or renders any
 * other household's data.
 *
 * Empty array is a valid, non-error response (reconcile hasn't run yet, or
 * the household has no student in this classroom) — same "nothing to show,
 * not an error" pattern as Phase 4's `getMyInventory` on a still-DRAFT pool.
 */
export function MyAllocationPanel({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/allocation/mine", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", lines: data as AllocationLine[] });
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
        Loading your students' status…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load this just now.
      </div>
    );
  }

  const { lines } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">
        Where things stand for your student
        {lines.length !== 1 ? "s" : ""}
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        What's already covered, and what will be part of the class's
        purchase, for your own household.
      </p>

      {lines.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">
          Nothing to show here yet — check back once the organizer works out
          the class's needs.
        </p>
      ) : (
        <ul className="mt-3 space-y-2">
          {lines.map((line) => (
            <li
              key={`${line.requirementId}:${line.studentId}`}
              className="flex items-center justify-between gap-3 text-sm"
            >
              <span className="text-slate-700">
                <span className="font-medium text-slate-900">
                  {line.studentFirstName ?? "Your student"}
                </span>
                {" · "}
                <span>{line.requirementName}</span>
              </span>
              <span className="font-medium text-slate-900">
                {allocationStatusLabel(line.status, line.purchaseRequiredQuantity)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
