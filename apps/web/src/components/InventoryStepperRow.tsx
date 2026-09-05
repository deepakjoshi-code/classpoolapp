"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import type { InventoryLine } from "@/lib/api/types";

type Props = {
  poolId: string;
  line: InventoryLine;
  /** Called whenever this row's owned quantity changes — both immediately
   * (optimistic, for the parent's completion summary) and once more if the
   * server's saved value ever differs (or a failed save reverts it). */
  onChange: (line: InventoryLine) => void;
  /** How long to wait after the last click before actually calling the API
   * (PRD §4.2 single-screen stepper — this avoids firing a request per
   * click while still feeling instant, since the stepper itself updates
   * synchronously). Exposed for tests; defaults to a real debounce. */
  debounceMs?: number;
};

const DEFAULT_DEBOUNCE_MS = 400;

/**
 * One row of the "Shop Your Home First" stepper (PRD §4.2) — a fast +/-
 * control for how many of one requirement the caller's household already
 * owns, for one student. Self-contained like RequirementListItem: owns its
 * local value and its own PUT call, and reports back via `onChange` rather
 * than the parent driving its state directly.
 *
 * UX notes:
 * - The displayed count updates the instant a button is clicked (optimistic
 *   local state) — the network call is debounced behind it, so rapid
 *   clicking never spams the API but never feels laggy either.
 * - Clamped to [0, quantityPerStudent] client-side so a user can't even
 *   visually spam it negative (or past what's needed) before the server's
 *   own clamp would apply (contract: "ownedQuantity is clamped to
 *   [0, quantityPerStudent] server-side").
 * - The count is inside an `aria-live="polite"` region, and the buttons
 *   carry full accessible names ("Decrease/Increase owned <item>[, <student>]")
 *   rather than bare icon buttons — PRD §14's WCAG 2.1 AA bar calls this
 *   stepper out by name as original custom UI.
 */
export function InventoryStepperRow({
  poolId,
  line,
  onChange,
  debounceMs = DEFAULT_DEBOUNCE_MS,
}: Props) {
  const [ownedQuantity, setOwnedQuantity] = useState(line.ownedQuantity);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const lastConfirmedRef = useRef(line.ownedQuantity);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const max = line.quantityPerStudent;

  function withLine(value: number): InventoryLine {
    return { ...line, ownedQuantity: value, stillNeeded: Math.max(0, max - value) };
  }

  function commit(nextValue: number) {
    const clamped = Math.max(0, Math.min(max, nextValue));
    setOwnedQuantity(clamped);
    setErrorMessage(null);
    onChange(withLine(clamped));

    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      const { data, error, response } = await api.PUT(
        "/pools/{poolId}/requirements/{requirementId}/inventory",
        {
          params: {
            path: { poolId, requirementId: line.requirementId },
          },
          body: { studentId: line.studentId, ownedQuantity: clamped },
        }
      );

      if (error || !data) {
        const reverted = lastConfirmedRef.current;
        setOwnedQuantity(reverted);
        onChange(withLine(reverted));
        setErrorMessage(
          response.status === 409
            ? "This pool isn't open for inventory yet."
            : "We couldn't save that just now. Please try again."
        );
        return;
      }

      const saved = data as InventoryLine;
      lastConfirmedRef.current = saved.ownedQuantity;
      setOwnedQuantity(saved.ownedQuantity);
      onChange(saved);
    }, debounceMs);
  }

  const studentSuffix = line.studentFirstName ? ` for ${line.studentFirstName}` : "";
  const decreaseLabel = `Decrease owned ${line.requirementName}${studentSuffix}`;
  const increaseLabel = `Increase owned ${line.requirementName}${studentSuffix}`;

  return (
    <li className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="font-medium text-slate-900">
            {line.requirementName}
            {line.studentFirstName && (
              <span className="font-normal text-slate-500"> · {line.studentFirstName}</span>
            )}
          </p>
          <p className="mt-1 text-sm text-slate-600">
            {max} needed per student
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-3">
          <button
            type="button"
            onClick={() => commit(ownedQuantity - 1)}
            disabled={ownedQuantity <= 0}
            aria-label={decreaseLabel}
            className="flex h-9 w-9 items-center justify-center rounded-full border border-slate-300 text-lg font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            <span aria-hidden="true">−</span>
          </button>

          <span
            aria-live="polite"
            className="min-w-[6.5rem] text-center text-sm font-medium text-slate-700 tabular-nums"
          >
            <span className="text-lg font-semibold text-slate-900">
              {ownedQuantity}
            </span>{" "}
            of {max} owned
          </span>

          <button
            type="button"
            onClick={() => commit(ownedQuantity + 1)}
            disabled={ownedQuantity >= max}
            aria-label={increaseLabel}
            className="flex h-9 w-9 items-center justify-center rounded-full border border-slate-300 text-lg font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            <span aria-hidden="true">+</span>
          </button>
        </div>
      </div>

      {ownedQuantity < max ? (
        <p className="mt-2 text-xs text-slate-500">
          {max - ownedQuantity} still needed{studentSuffix}.
        </p>
      ) : (
        <p className="mt-2 text-xs font-medium text-green-700">
          Fully covered{studentSuffix}.
        </p>
      )}

      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {errorMessage}
        </p>
      )}
    </li>
  );
}
