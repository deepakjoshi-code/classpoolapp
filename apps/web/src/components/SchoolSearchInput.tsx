"use client";

import { useEffect, useId, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import type { School } from "@/lib/api/types";

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;

type Props = {
  /** Currently selected existing school, or null if the organizer is typing a new one. */
  selectedSchool: School | null;
  /** Raw text in the input — kept in the parent so it can be sent as `schoolName` on submit when nothing is selected. */
  queryText: string;
  onQueryTextChange: (text: string) => void;
  onSelectSchool: (school: School | null) => void;
};

/**
 * Live fuzzy-search-as-you-type against GET /schools/search (PRD §2.3
 * "Is this your class?" dedup update). Offers existing matches before the
 * organizer is allowed to fall through to "create new" by just typing a
 * name and continuing — that fall-through isn't a separate action, it's
 * simply what happens if they submit the form without picking a suggestion.
 */
export function SchoolSearchInput({
  selectedSchool,
  queryText,
  onQueryTextChange,
  onSelectSchool,
}: Props) {
  const [results, setResults] = useState<School[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const listboxId = useId();
  const inputId = useId();

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);

    if (selectedSchool || queryText.trim().length < MIN_QUERY_LENGTH) {
      setResults([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    debounceRef.current = setTimeout(async () => {
      const { data, error } = await api.GET("/schools/search", {
        params: { query: { q: queryText.trim() } },
      });
      setLoading(false);
      if (!error && data) {
        // See src/lib/api/types.ts DeepRequired comment for why this cast.
        setResults(data as School[]);
        setOpen(true);
      }
    }, DEBOUNCE_MS);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryText, selectedSchool]);

  function handleInputChange(text: string) {
    onQueryTextChange(text);
    onSelectSchool(null);
    setOpen(true);
  }

  function handleSelect(school: School) {
    onSelectSchool(school);
    onQueryTextChange(school.name);
    setOpen(false);
  }

  const showCreateNewHint =
    !selectedSchool &&
    queryText.trim().length >= MIN_QUERY_LENGTH &&
    !loading &&
    results.every((r) => r.name.toLowerCase() !== queryText.trim().toLowerCase());

  return (
    <div className="relative">
      <label htmlFor={inputId} className="block text-sm font-medium text-slate-700">
        School name
      </label>
      <input
        id={inputId}
        type="text"
        required
        role="combobox"
        aria-expanded={open && results.length > 0}
        aria-controls={listboxId}
        aria-autocomplete="list"
        autoComplete="off"
        value={queryText}
        onChange={(e) => handleInputChange(e.target.value)}
        onFocus={() => setOpen(true)}
        placeholder="Start typing your school's name…"
        className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
      />

      {selectedSchool && (
        <p className="mt-1 text-sm text-brand-700">
          Selected existing school: <strong>{selectedSchool.name}</strong>
        </p>
      )}

      {loading && (
        <p className="mt-1 text-xs text-slate-500" role="status">
          Searching…
        </p>
      )}

      {open && !selectedSchool && results.length > 0 && (
        <ul
          id={listboxId}
          role="listbox"
          aria-label="Matching schools"
          className="absolute z-10 mt-1 w-full overflow-hidden rounded-lg border border-slate-200 bg-white shadow-lg"
        >
          {results.map((school) => (
            <li key={school.id} role="option" aria-selected={false}>
              <button
                type="button"
                onClick={() => handleSelect(school)}
                className="block w-full px-3 py-2.5 text-left text-sm text-slate-800 hover:bg-brand-50 focus-visible:bg-brand-50 focus-visible:outline-none"
              >
                {school.name}
              </button>
            </li>
          ))}
        </ul>
      )}

      {showCreateNewHint && (
        <p className="mt-1 text-xs text-slate-500">
          No exact match — we'll create <strong>&ldquo;{queryText.trim()}&rdquo;</strong>{" "}
          as a new school if you continue.
        </p>
      )}
    </div>
  );
}
