"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { InventoryLine, PoolDetail } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { inventoryCoverageMessage } from "@/lib/pool-labels";
import { InventoryStepperRow } from "@/components/InventoryStepperRow";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; pool: PoolDetail; lines: InventoryLine[] };

function lineKey(line: Pick<InventoryLine, "requirementId" | "studentId">): string {
  return `${line.requirementId}:${line.studentId}`;
}

/**
 * "Shop Your Home First" (PRD §4) — the household's own single-screen
 * inventory checklist for one pool. One row per (requirement, student the
 * caller has in this classroom) — a household with more than one student in
 * the class (e.g. twins) gets an independent row, and independent tracking,
 * per student for the same requirement, matching the contract's own framing
 * of an InventoryLine.
 *
 * Only meaningful once the pool has left DRAFT — GET .../inventory returns
 * an empty array for a still-DRAFT pool per the contract, so this page
 * treats that combination as "not open yet" rather than "empty list", and
 * the pool detail page only links here once the pool is past DRAFT anyway.
 */
export default function PoolInventoryPage() {
  const params = useParams<{ id: string }>();
  const poolId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/pools/${poolId}/inventory`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, poolId, router]);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      api.GET("/pools/{poolId}", { params: { path: { poolId } } }),
      api.GET("/pools/{poolId}/inventory", { params: { path: { poolId } } }),
    ]).then(([poolResult, inventoryResult]) => {
      if (cancelled) return;
      if (poolResult.error || !poolResult.data || inventoryResult.error || !inventoryResult.data) {
        setState({ status: "error" });
        return;
      }
      setState({
        status: "ready",
        // See src/lib/api/types.ts DeepRequired comment for why these casts.
        pool: poolResult.data as PoolDetail,
        lines: inventoryResult.data as InventoryLine[],
      });
    });

    return () => {
      cancelled = true;
    };
  }, [poolId]);

  if (auth.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading…
      </div>
    );
  }

  if (auth.status === "anonymous") {
    return null;
  }

  if (state.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading your inventory…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Couldn't load this pool's inventory
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          It may not exist, or you may not have access to it.
        </p>
        <a
          href={`/pools/${poolId}`}
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Back to pool
        </a>
      </div>
    );
  }

  const { pool, lines } = state;

  function handleRowChange(updated: InventoryLine) {
    setState((prev) =>
      prev.status === "ready"
        ? {
            ...prev,
            lines: prev.lines.map((l) =>
              lineKey(l) === lineKey(updated) ? updated : l
            ),
          }
        : prev
    );
  }

  const coveredCount = lines.filter((l) => l.stillNeeded === 0).length;
  const coverageMessage = inventoryCoverageMessage(coveredCount, lines.length);
  const fullyCovered = lines.length > 0 && coveredCount === lines.length;

  return (
    <div className="px-4 py-8">
      <a
        href={`/pools/${poolId}`}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to {pool.name}
      </a>
      <h1 className="mt-2 text-2xl font-bold text-slate-900">
        Shop your home first
      </h1>
      <p className="mt-1 text-sm text-slate-600">
        Before anyone buys anything new, tell us what your household already
        has for {pool.name}. Tap + or − for each item — we'll save it as you
        go.
      </p>

      {pool.state === "DRAFT" ? (
        <p className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
          This pool's supply list hasn't been confirmed yet, so there's
          nothing to check off just yet. Check back once the organizer
          confirms the list.
        </p>
      ) : lines.length === 0 ? (
        <p className="mt-6 rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
          Nothing to report here — you don't have a student joined to this
          classroom yet.
        </p>
      ) : (
        <>
          {coverageMessage && (
            <p
              role="status"
              aria-live="polite"
              className={
                fullyCovered
                  ? "mt-6 rounded-lg border-2 border-green-300 bg-green-50 p-4 text-sm font-semibold text-green-900"
                  : "mt-6 rounded-lg border border-brand-200 bg-brand-50 p-4 text-sm font-medium text-brand-900"
              }
            >
              {coverageMessage}
            </p>
          )}

          <ul className="mt-6 space-y-3">
            {lines.map((line) => (
              <InventoryStepperRow
                key={lineKey(line)}
                poolId={poolId}
                line={line}
                onChange={handleRowChange}
              />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
