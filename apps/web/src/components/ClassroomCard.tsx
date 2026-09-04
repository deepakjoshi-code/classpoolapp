import type { Membership } from "@/lib/api/types";
import { poolStateLabel } from "@/lib/pool-labels";

/**
 * One classroom pool as its own card (PRD §12 multi-class HOME update — NOT
 * a single-pool screen). Phase 3 adds pool state: `classroom.pools` is
 * already sorted most-recent-first per the contract, so `pools[0]` is "the"
 * active pool for this card's purposes — a classroom running more than one
 * pool at once isn't a Phase 3 case worth building extra UI for yet (see
 * apps/web/README.md).
 *
 * The whole-card-is-a-link pattern from Phase 1-2 doesn't work once there
 * are two different destinations (invite vs. pool), so this renders as a
 * non-link card with explicit action links instead of nesting an <a> inside
 * an <a>.
 */
export function ClassroomCard({ membership }: { membership: Membership }) {
  const { classroom } = membership;
  const isOrganizer =
    membership.role === "ORGANIZER" || membership.role === "CO_ORGANIZER";
  const activePool = classroom.pools[0] ?? null;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition hover:border-brand-300 hover:shadow">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 className="font-semibold text-slate-900">
            {classroom.grade} · {classroom.teacherLabel}
          </h2>
          <p className="text-sm text-slate-600">{classroom.schoolName}</p>
        </div>
        {isOrganizer && (
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

      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-slate-100 pt-3 text-sm">
        <a
          href={`/classrooms/${classroom.id}/invite`}
          className="font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        >
          Invite link
        </a>
        {activePool ? (
          <a
            href={`/pools/${activePool.id}`}
            className="font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            {poolStateLabel(activePool.state)} · {activePool.requirementCount}{" "}
            item{activePool.requirementCount === 1 ? "" : "s"}
          </a>
        ) : isOrganizer ? (
          <a
            href={`/classrooms/${classroom.id}/pools/new`}
            className="font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            Start a pool
          </a>
        ) : (
          <span className="text-slate-500">No pool started yet</span>
        )}
      </div>
    </div>
  );
}
