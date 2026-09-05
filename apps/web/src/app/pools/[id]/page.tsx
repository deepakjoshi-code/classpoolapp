"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { PoolDetail } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { hasPayments, hasPurchasePlan, hasReconciled, poolStateLabel } from "@/lib/pool-labels";
import { RequirementForm } from "@/components/RequirementForm";
import { RequirementListItem } from "@/components/RequirementListItem";
import { ConfirmPoolAction } from "@/components/ConfirmPoolAction";
import { InventorySummaryPanel } from "@/components/InventorySummaryPanel";
import { OrganizerContributionsPanel } from "@/components/OrganizerContributionsPanel";
import { ReconcileAction } from "@/components/ReconcileAction";
import { OrganizerAllocationPanel } from "@/components/OrganizerAllocationPanel";
import { MyAllocationPanel } from "@/components/MyAllocationPanel";
import { GeneratePurchasePlanAction } from "@/components/GeneratePurchasePlanAction";
import { PurchasePlanPanel } from "@/components/PurchasePlanPanel";
import { StripeOnboardingCard } from "@/components/StripeOnboardingCard";
import { GeneratePaymentsAction } from "@/components/GeneratePaymentsAction";
import { OrganizerPaymentsPanel } from "@/components/OrganizerPaymentsPanel";
import { PaymentsThresholdPanel } from "@/components/PaymentsThresholdPanel";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; pool: PoolDetail };

/**
 * Pool detail page (PRD §3.3 organizer verification). An organizer/
 * co-organizer sees add/edit/remove controls and the confirm action only
 * while the pool is still DRAFT (contract 409s any of those calls once it
 * isn't); everyone else — and the organizer too, once confirmed — gets the
 * same list read-only, including each requirement's computed
 * `totalDemand` once it's set.
 */
export default function PoolDetailPage() {
  const params = useParams<{ id: string }>();
  const poolId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/pools/${poolId}`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, poolId, router]);

  useEffect(() => {
    let cancelled = false;

    api.GET("/pools/{poolId}", { params: { path: { poolId } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setState({ status: "error" });
          return;
        }
        // See src/lib/api/types.ts DeepRequired comment for why this cast.
        setState({ status: "ready", pool: data as PoolDetail });
      }
    );

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
        Loading pool…
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
          href="/"
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Back to home
        </a>
      </div>
    );
  }

  const { pool } = state;
  const membership = auth.user.memberships.find(
    (m) => m.classroomId === pool.classroomId
  );
  const isOrganizer =
    membership?.role === "ORGANIZER" || membership?.role === "CO_ORGANIZER";
  const canManage = isOrganizer && pool.state === "DRAFT";

  function updatePool(updater: (prev: PoolDetail) => PoolDetail) {
    setState((prev) =>
      prev.status === "ready" ? { status: "ready", pool: updater(prev.pool) } : prev
    );
  }

  return (
    <div className="px-4 py-8">
      <h1 className="text-2xl font-bold text-slate-900">{pool.name}</h1>
      <p className="mt-1 text-sm text-slate-600">{poolStateLabel(pool.state)}</p>

      {!isOrganizer && (
        <p className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
          You're viewing this list as a parent — only the organizer can add,
          edit, or confirm items.
        </p>
      )}
      {isOrganizer && pool.state !== "DRAFT" && (
        <p className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
          This list is locked — the pool has moved past the draft stage, so
          items can no longer be changed here.
        </p>
      )}

      {pool.state !== "DRAFT" && (
        <a
          href={`/pools/${pool.id}/inventory`}
          className="mt-4 block rounded-lg border-2 border-brand-200 bg-brand-50 p-4 hover:bg-brand-100"
        >
          <p className="text-base font-semibold text-brand-900">
            Shop your home first →
          </p>
          <p className="mt-1 text-sm text-brand-800">
            Tell us what your household already has before anyone buys
            anything new.
          </p>
        </a>
      )}

      {pool.state !== "DRAFT" && (
        <a
          href={`/pools/${pool.id}/contribute`}
          className="mt-3 block rounded-lg border border-slate-200 bg-white p-4 hover:bg-slate-50"
        >
          <p className="text-base font-semibold text-slate-900">
            Offer extra supplies to the class →
          </p>
          <p className="mt-1 text-sm text-slate-600">
            Have more than you need? Offering it — completely optional — can
            save another family a purchase.
          </p>
        </a>
      )}

      {hasPayments(pool.state) && (
        <a
          href={`/pools/${pool.id}/payment`}
          className="mt-3 block rounded-lg border-2 border-brand-200 bg-brand-50 p-4 hover:bg-brand-100"
        >
          <p className="text-base font-semibold text-brand-900">
            Your payment →
          </p>
          <p className="mt-1 text-sm text-brand-800">
            See what your household owes for this pool's purchase, and pay
            it.
          </p>
        </a>
      )}

      {isOrganizer && pool.state !== "DRAFT" && (
        <div className="mt-4 space-y-4">
          <InventorySummaryPanel poolId={pool.id} />
          <OrganizerContributionsPanel poolId={pool.id} />
        </div>
      )}

      {isOrganizer && pool.state === "OPEN_FOR_INVENTORY" && (
        <div className="mt-4">
          <ReconcileAction
            poolId={pool.id}
            onReconciled={() =>
              updatePool((prev) => ({ ...prev, state: "RECONCILING" }))
            }
          />
        </div>
      )}

      {hasReconciled(pool.state) && (
        <div className="mt-4 space-y-4">
          {isOrganizer && (
            <OrganizerAllocationPanel poolId={pool.id} poolState={pool.state} />
          )}
          {isOrganizer && pool.state === "RECONCILING" && (
            <GeneratePurchasePlanAction
              poolId={pool.id}
              onGenerated={() =>
                updatePool((prev) => ({ ...prev, state: "PURCHASE_PROPOSED" }))
              }
            />
          )}
          {isOrganizer && hasPurchasePlan(pool.state) && (
            <PurchasePlanPanel poolId={pool.id} />
          )}
          {isOrganizer && hasPurchasePlan(pool.state) && (
            <StripeOnboardingCard classroomId={pool.classroomId} />
          )}
          {isOrganizer && pool.state === "PURCHASE_PROPOSED" && (
            <GeneratePaymentsAction
              poolId={pool.id}
              classroomId={pool.classroomId}
              onGenerated={() =>
                updatePool((prev) => ({ ...prev, state: "PAYMENT_OPEN" }))
              }
            />
          )}
          {isOrganizer && hasPayments(pool.state) && (
            <>
              <PaymentsThresholdPanel
                poolId={pool.id}
                poolState={pool.state}
                onFinalized={(finalized) =>
                  setState({ status: "ready", pool: finalized })
                }
              />
              <OrganizerPaymentsPanel poolId={pool.id} poolState={pool.state} />
            </>
          )}
          <MyAllocationPanel poolId={pool.id} />
        </div>
      )}

      <ul className="mt-6 space-y-3">
        {pool.requirements.length === 0 && (
          <li className="rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
            No items on this list yet.
          </li>
        )}
        {pool.requirements.map((req) => (
          <RequirementListItem
            key={req.id}
            poolId={pool.id}
            requirement={req}
            canEdit={canManage}
            onUpdated={(updated) =>
              updatePool((prev) => ({
                ...prev,
                requirements: prev.requirements.map((r) =>
                  r.id === updated.id ? updated : r
                ),
              }))
            }
            onRemoved={(id) =>
              updatePool((prev) => ({
                ...prev,
                requirements: prev.requirements.filter((r) => r.id !== id),
                requirementCount: Math.max(0, prev.requirementCount - 1),
              }))
            }
          />
        ))}
      </ul>

      {canManage && (
        <>
          <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4">
            <h2 className="text-base font-semibold text-slate-900">
              Add an item
            </h2>
            <div className="mt-3">
              <RequirementForm
                poolId={pool.id}
                onSaved={(created) =>
                  updatePool((prev) => ({
                    ...prev,
                    requirements: [...prev.requirements, created],
                    requirementCount: prev.requirementCount + 1,
                  }))
                }
              />
            </div>
          </div>

          <div className="mt-8">
            <ConfirmPoolAction
              poolId={pool.id}
              requirementCount={pool.requirements.length}
              onConfirmed={(confirmed) =>
                setState({ status: "ready", pool: confirmed })
              }
            />
          </div>
        </>
      )}
    </div>
  );
}
