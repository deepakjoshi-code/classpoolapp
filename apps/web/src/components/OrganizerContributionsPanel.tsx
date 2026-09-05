"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { Contribution } from "@/lib/api/types";
import { contributionStateLabel } from "@/lib/pool-labels";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; contributions: Contribution[] };

/**
 * Organizer confirmation view for pledged/received surplus (PRD §12.3:
 * "View unreceived contributions") — GET /pools/{poolId}/contributions.
 *
 * PRIVACY — read this before adding a call site. Per PRD §5.3's privacy
 * model ("aggregate contribution counts by default; organizer can see
 * contributor identity for drop-off coordination; no public household-level
 * inventory disclosure"), `offeringParentDisplayName` is the one piece of
 * data in the whole contribution feature that identifies a household to
 * anyone other than themselves, and this component is the ONLY place in the
 * app that renders it. It must only ever be mounted behind an
 * organizer/co-organizer check — same gating pattern as
 * InventorySummaryPanel (see PoolDetailPage, which renders this only inside
 * its `isOrganizer` branch). This component does not re-check the caller's
 * role itself: it trusts its mount point, exactly like InventorySummaryPanel
 * does, with the API's own 403 on GET /pools/{poolId}/contributions as the
 * real enforcement boundary (same "UX nicety, not a security boundary"
 * pattern documented in the README for every other client-side role check
 * in this app). A plain parent's own contribution view is a completely
 * separate component (ContributionOfferCard, fed only from
 * GET .../contributions/mine) that never receives or renders this field.
 */
export function OrganizerContributionsPanel({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [receivingId, setReceivingId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/contributions", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        setState({ status: "ready", contributions: data as Contribution[] });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  async function handleReceive(contributionId: string) {
    setReceivingId(contributionId);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/contributions/{contributionId}/receive",
      { params: { path: { poolId, contributionId } } }
    );

    setReceivingId(null);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "That contribution has already been marked received."
          : "We couldn't mark that received just now. Please try again."
      );
      return;
    }

    const updated = data as Contribution;
    setState((prev) =>
      prev.status === "ready"
        ? {
            ...prev,
            contributions: prev.contributions.map((c) =>
              c.id === updated.id ? updated : c
            ),
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
        Loading contributions…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load contributions just now.
      </div>
    );
  }

  const { contributions } = state;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-base font-semibold text-slate-900">Contributions</h2>
      <p className="mt-1 text-sm text-slate-600">
        Every family's pledged and received surplus. Mark an item received
        once it physically arrives — only received surplus counts toward the
        class's remaining need.
      </p>

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      {contributions.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">
          No one has offered anything yet.
        </p>
      ) : (
        <ul className="mt-3 space-y-2">
          {contributions.map((contribution) => {
            const isPledged = contribution.state === "PLEDGED";
            return (
              <li
                key={contribution.id}
                className="flex items-center justify-between gap-3 rounded-md border border-slate-100 p-3 text-sm"
              >
                <div>
                  <p className="font-medium text-slate-900">
                    {contribution.quantity} × {contribution.requirementName}
                    <span className="font-normal text-slate-500">
                      {" "}
                      · {contribution.studentFirstName}
                    </span>
                  </p>
                  <p className="mt-0.5 text-slate-600">
                    From {contribution.offeringParentDisplayName ?? "an unnamed household"}{" "}
                    ·{" "}
                    <span
                      className={
                        isPledged
                          ? "font-medium text-brand-800"
                          : "font-medium text-green-800"
                      }
                    >
                      {contributionStateLabel(contribution.state)}
                    </span>
                  </p>
                </div>
                {isPledged && (
                  <button
                    type="button"
                    onClick={() => handleReceive(contribution.id)}
                    disabled={receivingId === contribution.id}
                    className="shrink-0 rounded-lg border border-brand-300 px-3 py-1.5 text-xs font-medium text-brand-800 hover:bg-brand-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
                  >
                    {receivingId === contribution.id
                      ? "Marking received…"
                      : "Mark received"}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
