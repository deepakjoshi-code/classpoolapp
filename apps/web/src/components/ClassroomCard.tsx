import type { Membership } from "@/lib/api/types";

/**
 * One classroom pool as its own card (PRD §12 multi-class HOME update — NOT
 * a single-pool screen). Phase 1-2 scope only carries membership + classroom
 * context, no pool financials yet (those land with later phases per the
 * contract's getHouseholdDashboard summary), so this card is intentionally
 * light — readiness %, savings, and pay CTAs return once the pool endpoints
 * exist.
 */
export function ClassroomCard({ membership }: { membership: Membership }) {
  const { classroom } = membership;

  return (
    <a
      href={`/classrooms/${classroom.id}/invite`}
      className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition hover:border-brand-300 hover:shadow focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 className="font-semibold text-slate-900">
            {classroom.grade} · {classroom.teacherLabel}
          </h2>
          <p className="text-sm text-slate-600">{classroom.schoolName}</p>
        </div>
        {(membership.role === "ORGANIZER" ||
          membership.role === "CO_ORGANIZER") && (
          <span className="shrink-0 rounded-full bg-brand-100 px-2 py-0.5 text-xs font-medium text-brand-800">
            {membership.role === "ORGANIZER" ? "Organizer" : "Co-organizer"}
          </span>
        )}
      </div>

      <dl className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
        <div>
          <dt className="inline font-medium">School year: </dt>
          <dd className="inline">{classroom.schoolYearLabel}</dd>
        </div>
        {membership.studentFirstName && (
          <div>
            <dt className="inline font-medium">Student: </dt>
            <dd className="inline">{membership.studentFirstName}</dd>
          </div>
        )}
      </dl>

      {membership.lateJoin && (
        <p className="mt-2 text-xs text-amber-700">
          Joined after this pool's contribution window — billed at the
          locked rate, no reuse/exchange step needed.
        </p>
      )}
    </a>
  );
}
