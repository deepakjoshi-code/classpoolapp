"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Membership } from "@/lib/api/types";

type Props = {
  token: string;
  onJoined: (membership: Membership) => void;
};

/**
 * Final step of the invite flow (PRD §2.2/§2.3): once the parent is
 * authenticated, they name the student joining this class, and we call
 * POST /invites/{token}/join.
 */
export function JoinForm({ token, onJoined }: Props) {
  const [studentFirstName, setStudentFirstName] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = studentFirstName.trim();
    if (!trimmed || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const { data, error } = await api.POST("/invites/{token}/join", {
      params: { path: { token } },
      body: { studentFirstName: trimmed },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        "We couldn't join you to this class. The invite may have expired — ask the organizer for a fresh link."
      );
      return;
    }

    // See src/lib/api/types.ts DeepRequired comment for why this cast.
    onJoined(data as Membership);
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3" noValidate>
      <div>
        <label
          htmlFor="studentFirstName"
          className="block text-sm font-medium text-slate-700"
        >
          Your child's first name (or initials)
        </label>
        <input
          id="studentFirstName"
          type="text"
          required
          value={studentFirstName}
          onChange={(e) => setStudentFirstName(e.target.value)}
          placeholder="Alex"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
        <p className="mt-1 text-xs text-slate-500">
          First name or initials only — we never ask for a birthdate or full
          profile.
        </p>
      </div>

      {errorMessage && (
        <p role="alert" className="text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      <button
        type="submit"
        disabled={!studentFirstName.trim() || submitting}
        className="w-full rounded-lg bg-brand-700 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
      >
        {submitting ? "Joining…" : "Join this class"}
      </button>
    </form>
  );
}
