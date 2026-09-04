"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Pool } from "@/lib/api/types";

type Props = {
  classroomId: string;
  onCreated: (pool: Pool) => void;
};

export function CreatePoolForm({ classroomId, onCreated }: Props) {
  const [name, setName] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const canSubmit = name.trim().length > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const { data, error } = await api.POST("/classrooms/{classroomId}/pools", {
      params: { path: { classroomId } },
      body: { name: name.trim() },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        "We couldn't start the pool just now. Please check your connection and try again."
      );
      return;
    }

    onCreated(data as Pool);
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5" noValidate>
      <div>
        <label htmlFor="poolName" className="block text-sm font-medium text-slate-700">
          Pool name
        </label>
        <input
          id="poolName"
          type="text"
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Fall Supplies"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
        <p className="mt-1 text-xs text-slate-500">
          e.g. "Fall Supplies" — you'll add the item list next. You can start
          another pool for this class later.
        </p>
      </div>

      {errorMessage && (
        <p role="alert" className="text-sm text-red-700">
          {errorMessage}
        </p>
      )}

      <button
        type="submit"
        disabled={!canSubmit || submitting}
        className="w-full rounded-lg bg-brand-700 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
      >
        {submitting ? "Starting pool…" : "Start pool"}
      </button>
    </form>
  );
}
