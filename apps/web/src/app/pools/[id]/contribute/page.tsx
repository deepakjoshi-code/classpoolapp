"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { Contribution, PoolDetail } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { ContributionOfferCard } from "@/components/ContributionOfferCard";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; pool: PoolDetail; contributions: Contribution[] };

type StudentInClassroom = {
  studentId: string;
  studentFirstName: string | null;
};

/**
 * Keyed by requirementId alone, not studentId — a contribution is recorded
 * against the offering household, not a specific student (the `contribution`
 * table has no student_id column; see apps/api/README.md's "Flagged schema
 * gap"), so `Contribution.studentId` from the API is always null and can't
 * be used to attribute a pledge to one child's card. A household with more
 * than one student in this classroom will see the same pledge reflected on
 * every sibling's card for that requirement.
 */
function contributionKey(c: Pick<Contribution, "requirementId">): string {
  return c.requirementId;
}

/**
 * "Offer surplus" (PRD §5.1) — a related but distinct action from
 * `/pools/[id]/inventory`'s "Shop Your Home First" stepper: that page
 * records what a household already owns, this page lets them optionally
 * pledge some of it (or extras bought specifically to give) to the class.
 * Framed as low-pressure throughout (PRD §5.1 "proactively ask whether the
 * surplus can help the class") — there's no requirement to visit this page
 * at all, unlike the organizer's one-way confirm action.
 *
 * Reachable from the pool detail page once the pool is past DRAFT, same
 * gating as the inventory page — there's nothing to offer against a
 * requirement list that isn't confirmed yet (no `quantityPerStudent`
 * context to weigh a pledge against).
 *
 * One card per (requirement, student the caller has in this classroom) —
 * a household with more than one student in the class gets an independent
 * card per student per requirement, same per-student framing as
 * `InventoryLine`/the inventory page. Unlike inventory (which the API scopes
 * for us via GET .../inventory), there's no contribution-specific endpoint
 * for "which of my students are in this classroom" — this page reuses
 * `GET /me`'s own `memberships` (already scoped to the caller, per
 * PRD §14) to derive that list, filtered to this pool's classroom.
 *
 * PRIVACY: this page only ever fetches and renders the caller's OWN
 * contributions (`GET /pools/{poolId}/contributions/mine`), never anyone
 * else's — the contract's own doc comment for `Contribution` says
 * `offeringParentDisplayName` is "omitted from the offering parent's own
 * 'mine' view", and this page never asks for or displays it. See
 * `OrganizerContributionsPanel` for the separate, organizer-only,
 * identity-carrying view.
 */
export default function OfferSurplusPage() {
  const params = useParams<{ id: string }>();
  const poolId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/pools/${poolId}/contribute`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, poolId, router]);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      api.GET("/pools/{poolId}", { params: { path: { poolId } } }),
      api.GET("/pools/{poolId}/contributions/mine", { params: { path: { poolId } } }),
    ]).then(([poolResult, contributionsResult]) => {
      if (cancelled) return;
      if (
        poolResult.error ||
        !poolResult.data ||
        contributionsResult.error ||
        !contributionsResult.data
      ) {
        setState({ status: "error" });
        return;
      }
      setState({
        status: "ready",
        // See src/lib/api/types.ts DeepRequired comment for why these casts.
        pool: poolResult.data as PoolDetail,
        contributions: contributionsResult.data as Contribution[],
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
        Loading ways to help…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Couldn't load this pool
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

  const { pool, contributions } = state;

  const students: StudentInClassroom[] = [];
  const seenStudentIds = new Set<string>();
  for (const membership of auth.user.memberships) {
    if (membership.classroomId !== pool.classroomId) continue;
    if (!membership.studentId || seenStudentIds.has(membership.studentId)) continue;
    seenStudentIds.add(membership.studentId);
    students.push({
      studentId: membership.studentId,
      studentFirstName: membership.studentFirstName,
    });
  }

  function handleOffered(next: Contribution) {
    setState((prev) =>
      prev.status === "ready"
        ? { ...prev, contributions: [...prev.contributions, next] }
        : prev
    );
  }

  function handleWithdrawn(contributionId: string) {
    setState((prev) =>
      prev.status === "ready"
        ? {
            ...prev,
            contributions: prev.contributions.filter((c) => c.id !== contributionId),
          }
        : prev
    );
  }

  return (
    <div className="px-4 py-8">
      <a
        href={`/pools/${poolId}`}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to {pool.name}
      </a>
      <h1 className="mt-2 text-2xl font-bold text-slate-900">
        Offer your extra supplies
      </h1>
      <p className="mt-1 text-sm text-slate-600">
        If your household ends up with more than you need for {pool.name},
        offering it can help another family skip a purchase. This is
        completely optional — there's no obligation to give anything.
      </p>

      {pool.state === "DRAFT" ? (
        <p className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
          This pool's supply list hasn't been confirmed yet, so there's
          nothing to offer against just yet. Check back once the organizer
          confirms the list.
        </p>
      ) : students.length === 0 ? (
        <p className="mt-6 rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
          Nothing to offer here — you don't have a student joined to this
          classroom yet.
        </p>
      ) : pool.requirements.length === 0 ? (
        <p className="mt-6 rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
          No items on this list yet.
        </p>
      ) : (
        <ul className="mt-6 space-y-3">
          {pool.requirements.flatMap((requirement) =>
            students.map((student) => (
              <ContributionOfferCard
                key={`${requirement.id}:${student.studentId}`}
                poolId={poolId}
                requirementId={requirement.id}
                requirementName={requirement.name}
                quantityPerStudent={requirement.quantityPerStudent}
                studentId={student.studentId}
                studentFirstName={student.studentFirstName}
                contributions={contributions.filter(
                  (c) => contributionKey(c) === contributionKey({ requirementId: requirement.id })
                )}
                onOffered={handleOffered}
                onWithdrawn={handleWithdrawn}
              />
            ))
          )}
        </ul>
      )}
    </div>
  );
}
