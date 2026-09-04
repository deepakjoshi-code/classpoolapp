-- Fixes a real correctness gap found during Phase 3 integration review: the V1 migration had no
-- column to persist the "confirmed number of participating students" that PRD §3.4 defines
-- aggregate class demand against, so the first implementation computed it live from current
-- Membership rows on every read. That silently changes already-"confirmed" totals if a family
-- joins after the pool leaves DRAFT (e.g. a late joiner, PRD §13.3) — exactly the kind of moving
-- target the residual-demand engine (Phase 6/7) cannot be built against. Freezing it once, at
-- confirm time, on the Pool itself (shared by every Requirement in it, so one column suffices
-- rather than one per requirement) is the fix.

alter table pool add column confirmed_student_count integer;
