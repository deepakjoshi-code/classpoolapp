"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Requirement, RequirementImportResult } from "@/lib/api/types";
import { REQUIREMENT_SOURCE_TYPE_OPTIONS } from "@/lib/pool-labels";

type Props = {
  poolId: string;
  /** Called with every `Requirement` the import produced, to append to the parent's list. */
  onImported: (requirements: Requirement[]) => void;
};

type ImportSourceType = (typeof REQUIREMENT_SOURCE_TYPE_OPTIONS)[number]["value"];

type ResultSummary = {
  extractedCount: number;
  needsReviewCount: number;
};

/**
 * Organizer's "import from pasted text" form (PRD §3.1/§3.2, Phase 11) —
 * `POST /pools/{poolId}/requirement-sources` — the AI-assisted second path
 * to building a pool's supply list, shown alongside (never replacing)
 * `RequirementForm`'s manual add form while the pool is still `DRAFT`. Per
 * the PRD's "manual entry stays available indefinitely" principle, this is
 * an equally-first-class path, not a default that buries manual entry — see
 * where `PoolDetailPage` mounts the two side by side.
 *
 * On success this never silently merges the extracted items into the list
 * with no acknowledgment — it shows a plain-language summary distinguishing
 * items that are ready to review (`state === "EXTRACTED"`, at/above the
 * API's confidence threshold) from ones that need a closer look
 * (`state === "NEEDS_REVIEW"`, below it) before calling back to the parent
 * with the full batch, mirroring `RequirementForm`'s `onSaved` callback
 * shape but for a whole batch at once (`onImported`, not `onSaved`).
 */
export function ImportRequirementsForm({ poolId, onImported }: Props) {
  const [sourceType, setSourceType] = useState<ImportSourceType>(
    REQUIREMENT_SOURCE_TYPE_OPTIONS[0]!.value
  );
  const [rawText, setRawText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<ResultSummary | null>(null);

  const canSubmit = rawText.trim().length > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);
    setResult(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/requirement-sources",
      {
        params: { path: { poolId } },
        body: { sourceType, rawText: rawText.trim() },
      }
    );

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This pool's list is already locked in — it's moved past the draft stage, so nothing more can be imported."
          : "We couldn't read that text just now. Please check your connection and try again."
      );
      return;
    }

    const requirements = (data as RequirementImportResult).requirements;
    const needsReviewCount = requirements.filter(
      (r) => r.state === "NEEDS_REVIEW"
    ).length;
    const extractedCount = requirements.length - needsReviewCount;

    setResult({ extractedCount, needsReviewCount });
    setRawText("");

    if (requirements.length > 0) {
      onImported(requirements);
    }
  }

  const selectedOption = REQUIREMENT_SOURCE_TYPE_OPTIONS.find(
    (o) => o.value === sourceType
  );

  return (
    <form onSubmit={handleSubmit} className="space-y-4" noValidate>
      <div>
        <label
          htmlFor="import-source-type"
          className="block text-sm font-medium text-slate-700"
        >
          Where is this from?
        </label>
        <select
          id="import-source-type"
          value={sourceType}
          onChange={(e) => setSourceType(e.target.value as ImportSourceType)}
          className="mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        >
          {REQUIREMENT_SOURCE_TYPE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {selectedOption && (
          <p className="mt-1 text-xs text-slate-500">{selectedOption.hint}</p>
        )}
      </div>

      <div>
        <label
          htmlFor="import-raw-text"
          className="block text-sm font-medium text-slate-700"
        >
          Paste the text here
        </label>
        <textarea
          id="import-raw-text"
          required
          rows={6}
          value={rawText}
          onChange={(e) => setRawText(e.target.value)}
          placeholder="Paste the supply list text — e.g. a forwarded email, a portal page, or a message…"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
      </div>

      {errorMessage && (
        <p role="alert" className="text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      {result && (
        <p
          role="status"
          className="rounded-lg border border-brand-200 bg-brand-50 p-3 text-sm text-brand-900"
        >
          {result.extractedCount === 0 && result.needsReviewCount === 0 &&
            "No items were found in that text — try pasting more of the list, or add items manually below."}
          {result.extractedCount > 0 && result.needsReviewCount === 0 &&
            `${result.extractedCount} item${result.extractedCount === 1 ? "" : "s"} found, ready to review.`}
          {result.extractedCount === 0 && result.needsReviewCount > 0 &&
            `${result.needsReviewCount} item${result.needsReviewCount === 1 ? "" : "s"} found — these need a closer look before you can confirm the list.`}
          {result.extractedCount > 0 && result.needsReviewCount > 0 &&
            `${result.extractedCount} item${result.extractedCount === 1 ? "" : "s"} found, ready to review. ${result.needsReviewCount} more item${result.needsReviewCount === 1 ? "" : "s"} need a closer look before you can confirm the list.`}
        </p>
      )}

      <button
        type="submit"
        disabled={!canSubmit || submitting}
        className="w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
      >
        {submitting ? "Reading…" : "Import items from this text"}
      </button>
    </form>
  );
}
