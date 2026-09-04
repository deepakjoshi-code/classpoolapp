"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Requirement } from "@/lib/api/types";
import { STRICTNESS_OPTIONS } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  /** Present => edit an existing requirement (PATCH). Absent => add one (POST). */
  requirement?: Requirement | null;
  onSaved: (requirement: Requirement) => void;
  onCancel?: () => void;
};

/**
 * Add/Edit a manual requirement (PRD §3.1/§3.2 update — manual entry is a
 * permanent parallel path, not a pre-AI placeholder, so this form is the
 * primary way an organizer builds a list in this phase, not a fallback).
 * Same component backs both the "add" and "Correct" (edit) actions from
 * PRD §3.3 — they hit different endpoints but share every field and the
 * 409-when-not-DRAFT handling.
 */
export function RequirementForm({ poolId, requirement, onSaved, onCancel }: Props) {
  const isEditing = requirement != null;
  const idSuffix = requirement?.id ?? "new";

  const [name, setName] = useState(requirement?.name ?? "");
  const [quantity, setQuantity] = useState(
    requirement ? String(requirement.quantityPerStudent) : ""
  );
  const [brand, setBrand] = useState(requirement?.brand ?? "");
  const [strictness, setStrictness] = useState<Requirement["strictness"]>(
    requirement?.strictness ?? "EQUIVALENT_ALLOWED"
  );
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const quantityNumber = Number(quantity);
  const canSubmit =
    name.trim().length > 0 &&
    quantity.trim().length > 0 &&
    Number.isInteger(quantityNumber) &&
    quantityNumber >= 1;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const body = {
      name: name.trim(),
      quantityPerStudent: quantityNumber,
      brand: brand.trim() ? brand.trim() : null,
      strictness,
    };

    const { data, error, response } = isEditing
      ? await api.PATCH("/pools/{poolId}/requirements/{requirementId}", {
          params: { path: { poolId, requirementId: requirement.id } },
          body,
        })
      : await api.POST("/pools/{poolId}/requirements", {
          params: { path: { poolId } },
          body,
        });

    setSubmitting(false);

    if (error || !data) {
      if (response.status === 409) {
        setErrorMessage(
          "This pool's list is already locked in — it's moved past the draft stage, so items can no longer be added or edited."
        );
      } else {
        setErrorMessage(
          isEditing
            ? "We couldn't save that change just now. Please check your connection and try again."
            : "We couldn't add that item just now. Please check your connection and try again."
        );
      }
      return;
    }

    const saved = data as Requirement;

    if (!isEditing) {
      setName("");
      setQuantity("");
      setBrand("");
      setStrictness("EQUIVALENT_ALLOWED");
    }

    onSaved(saved);
  }

  const selectedOption = STRICTNESS_OPTIONS.find((o) => o.value === strictness);

  return (
    <form onSubmit={handleSubmit} className="space-y-4" noValidate>
      <div>
        <label
          htmlFor={`req-name-${idSuffix}`}
          className="block text-sm font-medium text-slate-700"
        >
          Item
        </label>
        <input
          id={`req-name-${idSuffix}`}
          type="text"
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Glue Stick"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label
            htmlFor={`req-qty-${idSuffix}`}
            className="block text-sm font-medium text-slate-700"
          >
            Quantity per student
          </label>
          <input
            id={`req-qty-${idSuffix}`}
            type="number"
            inputMode="numeric"
            min={1}
            required
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="4"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>
        <div>
          <label
            htmlFor={`req-brand-${idSuffix}`}
            className="block text-sm font-medium text-slate-700"
          >
            Brand <span className="font-normal text-slate-500">(optional)</span>
          </label>
          <input
            id={`req-brand-${idSuffix}`}
            type="text"
            value={brand}
            onChange={(e) => setBrand(e.target.value)}
            placeholder="Elmer's"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>
      </div>

      <div>
        <label
          htmlFor={`req-strictness-${idSuffix}`}
          className="block text-sm font-medium text-slate-700"
        >
          How strict is this item?
        </label>
        <select
          id={`req-strictness-${idSuffix}`}
          value={strictness}
          onChange={(e) =>
            setStrictness(e.target.value as Requirement["strictness"])
          }
          className="mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        >
          {STRICTNESS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {selectedOption && (
          <p className="mt-1 text-xs text-slate-500">{selectedOption.hint}</p>
        )}
      </div>

      {errorMessage && (
        <p role="alert" className="text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={!canSubmit || submitting}
          className="flex-1 rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          {submitting
            ? isEditing
              ? "Saving…"
              : "Adding…"
            : isEditing
              ? "Save changes"
              : "Add item"}
        </button>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
