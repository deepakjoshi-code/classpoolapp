"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { Classroom, ClassroomCreated, School } from "@/lib/api/types";
import { SchoolSearchInput } from "./SchoolSearchInput";
import { DedupWarning } from "./DedupWarning";

type Props = {
  onCreated: (classroom: Classroom) => void;
};

function defaultSchoolYearLabel(): string {
  const now = new Date();
  const year = now.getFullYear();
  // Northern-hemisphere school year: if we're mid-year (Aug or later),
  // assume the year that's starting; otherwise the one already underway.
  const startYear = now.getMonth() >= 6 ? year : year - 1;
  return `${startYear}-${startYear + 1}`;
}

export function CreateClassroomForm({ onCreated }: Props) {
  const [selectedSchool, setSelectedSchool] = useState<School | null>(null);
  const [schoolQueryText, setSchoolQueryText] = useState("");
  const [grade, setGrade] = useState("");
  const [teacherLabel, setTeacherLabel] = useState("");
  const [teacherEmail, setTeacherEmail] = useState("");
  const [schoolYearLabel, setSchoolYearLabel] = useState(defaultSchoolYearLabel());
  const [studentCountEstimate, setStudentCountEstimate] = useState("");

  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<ClassroomCreated | null>(null);

  const canSubmit =
    (selectedSchool !== null || schoolQueryText.trim().length >= 2) &&
    grade.trim().length > 0 &&
    teacherLabel.trim().length > 0 &&
    schoolYearLabel.trim().length > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const { data, error } = await api.POST("/classrooms", {
      body: {
        schoolId: selectedSchool ? selectedSchool.id : null,
        schoolName: selectedSchool ? null : schoolQueryText.trim(),
        schoolYearLabel: schoolYearLabel.trim(),
        grade: grade.trim(),
        teacherLabel: teacherLabel.trim(),
        teacherEmail: teacherEmail.trim() ? teacherEmail.trim() : null,
        studentCountEstimate: studentCountEstimate
          ? Number(studentCountEstimate)
          : null,
      },
    });

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        "We couldn't create the class just now. Please check your connection and try again."
      );
      return;
    }

    // See src/lib/api/types.ts DeepRequired comment for why this cast.
    const created = data as ClassroomCreated;

    if (created.dedupWarning && created.dedupWarning.length > 0) {
      setResult(created);
      return;
    }

    onCreated(created.classroom);
  }

  if (result?.dedupWarning && result.dedupWarning.length > 0) {
    return (
      <DedupWarning
        matches={result.dedupWarning}
        onContinueAsNew={() => onCreated(result.classroom)}
      />
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5" noValidate>
      <SchoolSearchInput
        selectedSchool={selectedSchool}
        queryText={schoolQueryText}
        onQueryTextChange={setSchoolQueryText}
        onSelectSchool={setSelectedSchool}
      />

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="grade" className="block text-sm font-medium text-slate-700">
            Grade
          </label>
          <input
            id="grade"
            type="text"
            required
            value={grade}
            onChange={(e) => setGrade(e.target.value)}
            placeholder="Grade 1"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>
        <div>
          <label htmlFor="schoolYear" className="block text-sm font-medium text-slate-700">
            School year
          </label>
          <input
            id="schoolYear"
            type="text"
            required
            value={schoolYearLabel}
            onChange={(e) => setSchoolYearLabel(e.target.value)}
            placeholder="2026-2027"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>
      </div>

      <div>
        <label htmlFor="teacherLabel" className="block text-sm font-medium text-slate-700">
          Teacher name
        </label>
        <input
          id="teacherLabel"
          type="text"
          required
          value={teacherLabel}
          onChange={(e) => setTeacherLabel(e.target.value)}
          placeholder="Ms. Smith"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
      </div>

      <div>
        <label htmlFor="teacherEmail" className="block text-sm font-medium text-slate-700">
          Teacher email <span className="font-normal text-slate-500">(optional)</span>
        </label>
        <input
          id="teacherEmail"
          type="email"
          value={teacherEmail}
          onChange={(e) => setTeacherEmail(e.target.value)}
          placeholder="teacher@school.edu"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
        <p className="mt-1 text-xs text-slate-500">
          Used only for optional requirement-list verification — teachers
          never handle money or sign in.
        </p>
      </div>

      <div>
        <label
          htmlFor="studentCount"
          className="block text-sm font-medium text-slate-700"
        >
          Approximate student count <span className="font-normal text-slate-500">(optional)</span>
        </label>
        <input
          id="studentCount"
          type="number"
          inputMode="numeric"
          min={1}
          value={studentCountEstimate}
          onChange={(e) => setStudentCountEstimate(e.target.value)}
          placeholder="24"
          className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2.5 text-base shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
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
        {submitting ? "Creating class…" : "Create class"}
      </button>
    </form>
  );
}
