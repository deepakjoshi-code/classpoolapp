"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import type { Classroom } from "@/lib/api/types";

type Props = {
  matches: Classroom[];
  onContinueAsNew: () => void;
};

/**
 * Shown after POST /classrooms comes back with a non-empty `dedupWarning`
 * (PRD §2.3 "Is this your class?"). Per the contract, the classroom the
 * organizer just submitted has ALREADY been created (createClassroom always
 * creates; dedupWarning is informational, not a pre-creation gate) and there
 * is no endpoint in contracts/openapi.yaml for an organizer to undo that and
 * join one of these existing classrooms directly by ID — only
 * /invites/{token}/join exists, which requires an invite token. So "yes,
 * this is mine" here can't silently merge anything; it points the organizer
 * at pasting the invite link/code they'd get from that class's existing
 * organizer instead. Flagged as a contract gap in the PR description.
 */
export function DedupWarning({ matches, onContinueAsNew }: Props) {
  const router = useRouter();
  const [showJoinInstead, setShowJoinInstead] = useState(false);
  const [inviteInput, setInviteInput] = useState("");

  function handleJoinInsteadSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = inviteInput.trim();
    if (!trimmed) return;
    const token = trimmed.includes("/join/")
      ? trimmed.split("/join/").pop()!.split(/[/?#]/)[0] ?? trimmed
      : trimmed;
    router.push(`/join/${encodeURIComponent(token)}`);
  }

  return (
    <div className="space-y-4 rounded-lg border border-amber-300 bg-amber-50 p-4">
      <div>
        <h2 className="text-base font-semibold text-amber-900">
          Is this already your class?
        </h2>
        <p className="mt-1 text-sm text-amber-800">
          We found {matches.length === 1 ? "a class that looks" : "classes that look"}{" "}
          similar to what you just created. Two separate classes for the same
          real class means the group loses half its bulk-pricing power — take
          a second look before continuing.
        </p>
      </div>

      <ul className="space-y-2">
        {matches.map((m) => (
          <li
            key={m.id}
            className="rounded-md border border-amber-200 bg-white px-3 py-2 text-sm text-slate-800"
          >
            <div className="font-medium">{m.schoolName}</div>
            <div className="text-slate-600">
              {m.grade} · {m.teacherLabel} · {m.schoolYearLabel}
            </div>
          </li>
        ))}
      </ul>

      {!showJoinInstead ? (
        <div className="flex flex-col gap-2 sm:flex-row">
          <button
            type="button"
            onClick={() => setShowJoinInstead(true)}
            className="flex-1 rounded-lg border border-amber-400 bg-white px-4 py-2.5 text-sm font-semibold text-amber-900 hover:bg-amber-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-700"
          >
            Yes, one of these is mine
          </button>
          <button
            type="button"
            onClick={onContinueAsNew}
            className="flex-1 rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
          >
            No, create a new one anyway
          </button>
        </div>
      ) : (
        <div className="space-y-3 rounded-md border border-amber-200 bg-white p-3">
          <p className="text-sm text-slate-700">
            Ask that class's organizer to share their invite link or code with
            you, then paste it below to join instead.
          </p>
          <form onSubmit={handleJoinInsteadSubmit} className="space-y-2">
            <label htmlFor="invite-instead" className="sr-only">
              Invite link or code
            </label>
            <input
              id="invite-instead"
              type="text"
              value={inviteInput}
              onChange={(e) => setInviteInput(e.target.value)}
              placeholder="classpool.app/join/7H2KQ"
              className="block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            />
            <div className="flex gap-2">
              <button
                type="submit"
                className="flex-1 rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
              >
                Go to invite
              </button>
              <button
                type="button"
                onClick={() => setShowJoinInstead(false)}
                className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Back
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
