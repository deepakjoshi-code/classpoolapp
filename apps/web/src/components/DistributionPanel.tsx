"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { DistributionItem, DistributionSummary } from "@/lib/api/types";
import { distributionItemStatusLabel, distributionModeLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "not-generated" }
  | { status: "ready"; summary: DistributionSummary };

/**
 * Organizer's distribution view (PRD §9.2/§9.3) — `GET
 * /pools/{poolId}/distribution`. Mounted once `hasDistribution(pool.state)`
 * (`DISTRIBUTING` or later), so the not-generated 409 is handled gracefully
 * the same "trust the mount point, but don't crash on a stale one" way as
 * `PurchasePlanPanel`'s not-generated 409 — reachable only via a second
 * organizer tab or a stale page state, not normal use.
 *
 * Two sections, deliberately not two separate components: the per-household
 * pick lists (PRD §9.2 update's printable "Family A: 12 pencils, 2
 * notebooks…" artifact — the actual point of this feature, so it's shown
 * first and given the most visual weight) and, below it, the raw per-item
 * delivery tracker, grouped by student rather than left as one flat table so
 * marking something delivered doesn't require hunting through an
 * unstructured wall of rows. One panel because both views are read from the
 * same `GET` call and act on the same underlying items — a second component
 * would just be a second fetch of the same data.
 *
 * The pick lists are wrapped in a dedicated `#pick-lists-printable` region
 * with a scoped `@media print` rule hiding everything else on the page, so
 * "Print pick lists" (`window.print()`) produces a clean, page-ready
 * hand-out — not a screenshot of the whole app chrome.
 */
export function DistributionPanel({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [deliveringId, setDeliveringId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/distribution", { params: { path: { poolId } } }).then(
      ({ data, error, response }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: response.status === 409 ? "not-generated" : "error" });
          return;
        }
        setState({ status: "ready", summary: data as DistributionSummary });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  async function handleDeliver(itemId: string) {
    setDeliveringId(itemId);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/distribution/items/{itemId}/deliver",
      { params: { path: { poolId, itemId } } }
    );

    setDeliveringId(null);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "That item is already marked delivered."
          : "We couldn't do that just now. Please try again."
      );
      return;
    }

    const updated = data as DistributionItem;
    setState((prev) =>
      prev.status === "ready"
        ? {
            status: "ready",
            summary: {
              ...prev.summary,
              items: prev.summary.items.map((i) => (i.id === updated.id ? updated : i)),
            },
          }
        : prev
    );
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading distribution…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load distribution just now.
      </div>
    );
  }

  if (state.status === "not-generated") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Distribution hasn't been set up yet.
      </div>
    );
  }

  const { summary } = state;

  const itemsByStudent = new Map<
    string,
    { studentFirstName: string | null; items: DistributionItem[] }
  >();
  for (const item of summary.items) {
    const existing = itemsByStudent.get(item.studentId);
    if (existing) {
      existing.items.push(item);
    } else {
      itemsByStudent.set(item.studentId, {
        studentFirstName: item.studentFirstName,
        items: [item],
      });
    }
  }

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <style>{`
        @media print {
          body * { visibility: hidden; }
          #pick-lists-printable, #pick-lists-printable * { visibility: visible; }
          #pick-lists-printable { position: absolute; left: 0; top: 0; width: 100%; }
        }
      `}</style>

      <div className="flex items-center justify-between gap-3">
        <h2 className="text-base font-semibold text-slate-900">Distribution</h2>
        <span className="text-sm font-medium text-brand-800">
          {distributionModeLabel(summary.mode)}
        </span>
      </div>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm font-medium text-red-700">
          {errorMessage}
        </p>
      )}

      <div id="pick-lists-printable" className="mt-3">
        <div className="flex items-center justify-between gap-3 print:hidden">
          <p className="text-sm font-semibold text-slate-800">Pick lists</p>
          <button
            type="button"
            onClick={() => window.print()}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            Print pick lists
          </button>
        </div>

        {summary.pickLists.length === 0 ? (
          <p className="mt-2 text-sm text-slate-500">No households to hand items to.</p>
        ) : (
          <ul className="mt-2 space-y-3 print:space-y-6">
            {summary.pickLists.map((pickList) => (
              <li
                key={pickList.householdId}
                className="rounded-md border border-slate-200 p-3 print:break-inside-avoid print:border-black print:p-0"
              >
                <p className="text-base font-semibold text-slate-900 print:text-lg">
                  {pickList.householdDisplayName ?? "An unnamed household"}
                </p>
                <ul className="mt-1.5 space-y-1 text-sm text-slate-700 print:text-base">
                  {pickList.lines.map((line, i) => (
                    <li key={i}>
                      {line.quantity} {line.requirementName}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="mt-4 border-t border-slate-100 pt-3 print:hidden">
        <p className="text-sm font-semibold text-slate-800">Mark delivered</p>
        {itemsByStudent.size === 0 ? (
          <p className="mt-2 text-sm text-slate-500">Nothing to deliver.</p>
        ) : (
          <ul className="mt-2 space-y-3">
            {[...itemsByStudent.entries()].map(([studentId, group]) => (
              <li key={studentId} className="rounded-md border border-slate-100 p-2.5">
                <p className="text-sm font-medium text-slate-900">
                  {group.studentFirstName ?? "A student"}
                </p>
                <ul className="mt-1.5 space-y-1.5">
                  {group.items.map((item) => {
                    const delivered = item.deliveredAt !== null;
                    return (
                      <li
                        key={item.id}
                        className="flex items-center justify-between gap-3 text-sm"
                      >
                        <span className="text-slate-700">
                          {item.quantity} {item.requirementName}
                          {" · "}
                          <span className={delivered ? "text-green-800" : "text-slate-500"}>
                            {distributionItemStatusLabel(item)}
                          </span>
                        </span>
                        {!delivered && (
                          <button
                            type="button"
                            onClick={() => handleDeliver(item.id)}
                            disabled={deliveringId === item.id}
                            aria-label={`Mark ${item.requirementName} delivered for ${group.studentFirstName ?? "this student"}`}
                            className="shrink-0 rounded-lg border border-brand-300 px-2.5 py-1 text-xs font-medium text-brand-800 hover:bg-brand-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                          >
                            {deliveringId === item.id ? "Marking…" : "Mark delivered"}
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
