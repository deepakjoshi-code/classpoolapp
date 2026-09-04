"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import type { Requirement } from "@/lib/api/types";
import { strictnessLabel } from "@/lib/pool-labels";
import { RequirementForm } from "./RequirementForm";

type Props = {
  poolId: string;
  requirement: Requirement;
  /** Organizer/co-organizer AND pool still DRAFT — see PoolDetailPage. */
  canEdit: boolean;
  onUpdated: (requirement: Requirement) => void;
  onRemoved: (requirementId: string) => void;
};

export function RequirementListItem({
  poolId,
  requirement,
  canEdit,
  onUpdated,
  onRemoved,
}: Props) {
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleRemove() {
    setRemoving(true);
    setErrorMessage(null);

    const { error, response } = await api.DELETE(
      "/pools/{poolId}/requirements/{requirementId}",
      { params: { path: { poolId, requirementId: requirement.id } } }
    );

    setRemoving(false);

    if (error) {
      setErrorMessage(
        response.status === 409
          ? "This pool's list is already locked in — items can no longer be removed."
          : "We couldn't remove that item just now. Please try again."
      );
      return;
    }

    onRemoved(requirement.id);
  }

  if (editing) {
    return (
      <li className="rounded-lg border border-brand-200 bg-brand-50 p-4">
        <RequirementForm
          poolId={poolId}
          requirement={requirement}
          onSaved={(updated) => {
            setEditing(false);
            onUpdated(updated);
          }}
          onCancel={() => setEditing(false)}
        />
      </li>
    );
  }

  return (
    <li className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-medium text-slate-900">
            {requirement.name}
            {requirement.brand && (
              <span className="font-normal text-slate-500">
                {" "}
                · {requirement.brand}
              </span>
            )}
          </p>
          <p className="mt-1 text-sm text-slate-600">
            {requirement.quantityPerStudent} per student ·{" "}
            {strictnessLabel(requirement.strictness)}
          </p>
          {requirement.totalDemand != null && (
            <p className="mt-1 text-sm font-medium text-brand-700">
              Total needed for the class: {requirement.totalDemand}
            </p>
          )}
        </div>
        {canEdit && (
          <div className="flex shrink-0 gap-2">
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            >
              Edit
            </button>
            <button
              type="button"
              onClick={handleRemove}
              disabled={removing}
              className="rounded-lg border border-red-300 px-3 py-1.5 text-xs font-medium text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700"
            >
              {removing ? "Removing…" : "Remove"}
            </button>
          </div>
        )}
      </div>
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {errorMessage}
        </p>
      )}
    </li>
  );
}
