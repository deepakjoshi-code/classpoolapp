"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { DistributionItem, PoolDetail } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { distributionItemStatusLabel } from "@/lib/pool-labels";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; pool: PoolDetail; items: DistributionItem[] };

/**
 * A household's own distribution screen for a pool (PRD §9.3) — `GET
 * /pools/{poolId}/distribution/mine`, a sibling "my own view" subpage to
 * `/pools/[id]/inventory`, `/pools/[id]/contribute`, and `/pools/[id]/
 * payment`. Linked from the pool detail page once `hasDistribution(pool.
 * state)` (`DISTRIBUTING` or later) — before that, calling `.../mine` would
 * just come back an empty array every time (distribution hasn't been set up
 * yet), so the link itself is gated on pool state rather than an extra
 * round trip just to decide whether to show it.
 *
 * An empty array is a normal, non-error response (distribution not
 * generated yet, or this household has nothing to receive) — same
 * "absence is a valid state" pattern as `MyAllocationPanel`'s empty array
 * and `PoolPaymentPage`'s `null`. Grouped by student (mirroring
 * `DistributionPanel`'s own per-student grouping for the organizer), each
 * item's delivered/not-yet-delivered status comes from
 * `distributionItemStatusLabel` — the same plain-language helper the
 * organizer's panel uses, so the two surfaces never describe the same fact
 * two different ways.
 */
export default function PoolDistributionPage() {
  const params = useParams<{ id: string }>();
  const poolId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/pools/${poolId}/distribution`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, poolId, router]);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      api.GET("/pools/{poolId}", { params: { path: { poolId } } }),
      api.GET("/pools/{poolId}/distribution/mine", { params: { path: { poolId } } }),
    ]).then(([poolResult, itemsResult]) => {
      if (cancelled) return;
      if (poolResult.error || !poolResult.data || itemsResult.error || !itemsResult.data) {
        setState({ status: "error" });
        return;
      }
      setState({
        status: "ready",
        // See src/lib/api/types.ts DeepRequired comment for why this cast.
        pool: poolResult.data as PoolDetail,
        items: itemsResult.data as DistributionItem[],
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
        Loading what you're receiving…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">Couldn't load this</h1>
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

  const { pool, items } = state;

  const itemsByStudent = new Map<
    string,
    { studentFirstName: string | null; items: DistributionItem[] }
  >();
  for (const item of items) {
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
    <div className="px-4 py-8">
      <a
        href={`/pools/${poolId}`}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to {pool.name}
      </a>
      <h1 className="mt-2 text-2xl font-bold text-slate-900">What you're receiving</h1>

      {items.length === 0 ? (
        <p className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
          Nothing to show yet — either distribution hasn't been set up for
          this pool, or your household has nothing to receive.
        </p>
      ) : (
        <ul className="mt-6 space-y-4">
          {[...itemsByStudent.entries()].map(([studentId, group]) => (
            <li
              key={studentId}
              className="rounded-lg border border-slate-200 bg-white p-4"
            >
              <p className="text-base font-semibold text-slate-900">
                {group.studentFirstName ?? "Your student"}
              </p>
              <ul className="mt-2 space-y-1.5 text-sm">
                {group.items.map((item) => {
                  const delivered = item.deliveredAt !== null;
                  return (
                    <li key={item.id} className="flex items-center justify-between gap-3">
                      <span className="text-slate-700">
                        {item.quantity} {item.requirementName}
                      </span>
                      <span
                        className={
                          delivered
                            ? "font-medium text-green-800"
                            : "font-medium text-slate-500"
                        }
                      >
                        {distributionItemStatusLabel(item)}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
