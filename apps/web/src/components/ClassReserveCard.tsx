"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ClassReserveEntry } from "@/lib/api/types";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; entries: ClassReserveEntry[] };

/**
 * Organizer's class reserve list (PRD §9.4/§19) — `GET
 * /pools/{poolId}/class-reserve`. Any leftover stock from a bulk-pack buy
 * (`PurchasePlanLine.wasteQuantity`) becomes a `ClassReserveEntry` when
 * `GenerateDistributionAction` runs, so this is mounted alongside
 * `DistributionPanel` once `hasDistribution(pool.state)`. Small and
 * low-key by design — this phase's actual point is distribution, not this
 * ledger, which mostly matters to a *future* pool sizing its own purchase
 * against what's already banked.
 *
 * `custodianLocation` is nullable (nobody's noted where it's physically
 * kept yet) — rendered as "not yet noted" rather than a raw `null`/blank.
 */
export function ClassReserveCard({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/class-reserve", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", entries: data as ClassReserveEntry[] });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-3 text-sm text-slate-600"
        role="status"
      >
        Loading the class reserve…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-3 text-sm text-slate-600">
        Couldn't load the class reserve just now.
      </div>
    );
  }

  const { entries } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <h2 className="text-sm font-semibold text-slate-900">Class reserve</h2>
      <p className="mt-0.5 text-xs text-slate-500">
        Extra supplies bought in bulk, kept for next time.
      </p>

      {entries.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500">Nothing banked for this class yet.</p>
      ) : (
        <ul className="mt-2 space-y-1 text-sm text-slate-700">
          {entries.map((entry) => (
            <li key={entry.id}>
              {entry.quantity} {entry.itemName} — kept at:{" "}
              {entry.custodianLocation ?? "not yet noted"}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
