"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { SavingsSummary } from "@/lib/api/types";
import { formatCents } from "@/lib/money";

type Props = {
  poolId: string;
};

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "not-reconciled" }
  | { status: "ready"; summary: SavingsSummary };

/**
 * The shareable "how much this pool saved" viral-loop artifact (PRD §16.3 —
 * "Grade 1 saved $1,118 and reused 397 items with ClassPool") — `GET
 * /pools/{poolId}/savings-summary`. Shown to any member of the pool once
 * `hasReconciled(pool.state)` (same gate `OrganizerAllocationPanel`/
 * `MyAllocationPanel` already use), never organizer-gated, since the whole
 * point is that any family can share it.
 *
 * The 409 ("pool hasn't been reconciled yet") is handled with the same
 * quiet, non-alarming tone as `OrganizerAllocationPanel`'s `not-reconciled`
 * state, even though the page's own gate should make it unreachable in
 * normal use — a stale page state could still hit it.
 *
 * The dollar figure only renders when `estimatedSavingsCents > 0` — it's
 * `0` whenever no purchase plan exists yet for this pool (no price signal to
 * estimate from), and showing "$0.00 saved" would read as a failure rather
 * than "not applicable yet".
 *
 * Sharing prefers the Web Share API (`navigator.share`, per this app's own
 * mobile-first PWA precedent in `InviteShare`) and falls back to copying
 * `shareableMessage` to the clipboard with a brief "Copied!" confirmation —
 * same pattern, same 2-second reset — for browsers without it (desktop,
 * mostly).
 */
export function SavingsSummaryCard({ poolId }: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}/savings-summary", { params: { path: { poolId } } }).then(
      ({ data, error, response }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({
            status: response.status === 409 ? "not-reconciled" : "error",
          });
          return;
        }
        setState({ status: "ready", summary: data as SavingsSummary });
      }
    );

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  async function handleShare(summary: SavingsSummary) {
    if (navigator.share) {
      try {
        await navigator.share({
          title: "ClassPool savings",
          text: summary.shareableMessage,
        });
        return;
      } catch {
        // User cancelled the share sheet — no action needed.
        return;
      }
    }
    try {
      await navigator.clipboard.writeText(summary.shareableMessage);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API unavailable — the message is still visible on screen.
    }
  }

  if (state.status === "loading") {
    return (
      <div
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600"
        role="status"
      >
        Loading your savings summary…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        Couldn't load your savings summary just now.
      </div>
    );
  }

  if (state.status === "not-reconciled") {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-600">
        This hasn't been worked out yet. Once that step runs, your savings
        summary will show up here.
      </div>
    );
  }

  const { summary } = state;

  return (
    <div className="rounded-lg border-2 border-brand-200 bg-brand-50 p-4">
      <h2 className="text-base font-semibold text-brand-900">
        {summary.poolName} savings
      </h2>

      <dl className="mt-3 grid grid-cols-2 gap-3">
        <div className="rounded-md bg-white p-3 text-center">
          <dt className="text-xs font-medium text-slate-500">Items reused</dt>
          <dd className="mt-0.5 text-2xl font-bold text-slate-900">
            {summary.itemsReused}
          </dd>
        </div>
        <div className="rounded-md bg-white p-3 text-center">
          <dt className="text-xs font-medium text-slate-500">Items purchased</dt>
          <dd className="mt-0.5 text-2xl font-bold text-slate-900">
            {summary.itemsPurchased}
          </dd>
        </div>
      </dl>

      {summary.estimatedSavingsCents > 0 && (
        <p className="mt-3 text-center text-lg font-bold text-brand-900">
          Estimated savings: {formatCents(summary.estimatedSavingsCents)}
        </p>
      )}

      <button
        type="button"
        onClick={() => handleShare(summary)}
        className="mt-4 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
      >
        {copied ? "Copied!" : "Share these savings"}
      </button>
    </div>
  );
}
