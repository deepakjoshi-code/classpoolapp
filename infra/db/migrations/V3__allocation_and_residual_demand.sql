-- Phase 6/7 allocation & residual-demand engine (PRD §6 — "Deterministic business logic, not LLM
-- reasoning"). POST /pools/{poolId}/reconcile freezes, for every (requirement, student) pair, how
-- much is self-fulfilled from the household's own recorded inventory (Phase 4), how much is
-- covered by the pool's RECEIVED surplus contributions (Phase 5), and how much still needs to be
-- purchased — same "freeze a snapshot, never recompute live" instinct as V2's
-- pool.confirmed_student_count / Requirement.totalDemand.
--
-- Deliberately NOT reusing the V1 migration's already-present `allocation` table: that table was
-- laid down speculatively ("Phases 3-11 tables ... no API endpoints in this pass") with a single
-- `fulfillment_type` + `quantity` shape that can't back the contract's AllocationLine schema, which
-- needs the owned/pool/purchase breakdown as three separate columns (so a caller can see, e.g.,
-- that a line is PURCHASE_REQUIRED *and* how much of the shortfall the pool already covered).
-- Widening/renaming that table is a schema decision for separate review, same as the Contribution
-- studentId gap flagged in apps/api/README.md; this migration adds new tables instead and leaves
-- the V1 `allocation` table untouched and unused.

create table allocation_line (
    id                          uuid primary key default gen_random_uuid(),
    requirement_id              uuid not null references requirement(id),
    student_id                  uuid not null references student(id),
    quantity_needed             integer not null,
    owned_quantity              integer not null,
    pool_fulfilled_quantity     integer not null,
    purchase_required_quantity  integer not null,
    status                      text not null check (status in ('SELF_FULFILLED', 'POOL_FULFILLED', 'PURCHASE_REQUIRED')),
    created_at                  timestamptz not null default now(),
    unique (requirement_id, student_id)
);
create index idx_allocation_line_requirement on allocation_line(requirement_id);
create index idx_allocation_line_student on allocation_line(student_id);

create table residual_demand_line (
    id                      uuid primary key default gen_random_uuid(),
    requirement_id          uuid not null references requirement(id),
    total_required          integer not null,
    total_owned             integer not null,
    total_pool_fulfilled    integer not null,
    residual_demand         integer not null,
    created_at              timestamptz not null default now(),
    unique (requirement_id)
);
