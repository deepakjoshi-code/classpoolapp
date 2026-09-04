-- ClassPool V1 schema.
-- Entity list = PRD docs/PRD.md §13.1 original list + every PM-UPDATE addition
-- (Household, Student, Invite, ClassReserve, Transfer, OrganizerStripeAccount,
-- School.approved_email_domains). Laid down in full now per ARCHITECTURE.md §4 —
-- only Phase 1-2 tables are read/written by this pass's API, the rest exist so
-- later phases don't have to retrofit foreign keys onto live data.

create extension if not exists pgcrypto;
create extension if not exists citext;    -- case-insensitive email columns
create extension if not exists pg_trgm;   -- fuzzy name matching for §2.3's dedup-check gin indexes

-- ============================================================
-- Users, households, students (PRD §2.2, §2.3 PM-update)
-- ============================================================

create table app_user (
    id                  uuid primary key default gen_random_uuid(),
    email               citext not null unique,
    display_name        text not null,
    phone               text,
    phone_sms_opt_in    boolean not null default false,   -- PRD §2.2 update: SMS is a second, explicit opt-in
    auth_provider       text not null check (auth_provider in ('GOOGLE', 'APPLE', 'MAGIC_LINK')),
    auth_provider_sub   text,                              -- provider's subject id, null for magic-link-only users
    created_at          timestamptz not null default now()
);

create table household (
    id                  uuid primary key default gen_random_uuid(),
    primary_parent_id   uuid not null references app_user(id),
    created_at          timestamptz not null default now()
);
create index idx_household_primary_parent on household(primary_parent_id);

create table student (
    id                  uuid primary key default gen_random_uuid(),
    household_id        uuid not null references household(id),
    first_name          text not null,   -- PRD §14: first name/initial only, never full profile
    created_at          timestamptz not null default now()
);
create index idx_student_household on student(household_id);

-- ============================================================
-- School hierarchy (PRD §2.3, §13.1 PM-update: approved_email_domains)
-- ============================================================

create table school (
    id                      uuid primary key default gen_random_uuid(),
    name                    text not null,
    approved_email_domains  text[] not null default '{}',  -- PRD §13.1 update: backs the Payment Unlock Gate's domain check
    created_at              timestamptz not null default now()
);
create index idx_school_name_trgm on school using gin (name gin_trgm_ops);

create table school_year (
    id          uuid primary key default gen_random_uuid(),
    school_id   uuid not null references school(id),
    label       text not null,      -- e.g. "2026-2027"
    created_at  timestamptz not null default now(),
    unique (school_id, label)
);

create table classroom (
    id                      uuid primary key default gen_random_uuid(),
    school_year_id          uuid not null references school_year(id),
    grade                   text not null,
    teacher_label            text not null,     -- e.g. "Ms. Smith" — teacher has no account, this is descriptive only (PRD §2.1: teacher optional)
    teacher_email            citext,             -- optional, used only for the verification-link notification, never an auth account
    student_count_estimate   integer,
    created_at              timestamptz not null default now()
);
create index idx_classroom_school_year on classroom(school_year_id);
create index idx_classroom_grade_teacher_trgm on classroom using gin ((grade || ' ' || teacher_label) gin_trgm_ops);

-- Membership models two distinct things on one row, matching PRD §2.1 exactly:
-- a parent's household participation in a classroom (via a specific student), and/or
-- a user's ORGANIZER permission grant on a classroom (Membership.role = ORGANIZER).
-- An ORGANIZER row's student_id is nullable — an organizer need not have a child
-- in the class they're running, though usually they do.
create table membership (
    id              uuid primary key default gen_random_uuid(),
    classroom_id    uuid not null references classroom(id),
    parent_user_id  uuid not null references app_user(id),
    student_id      uuid references student(id),
    role            text not null check (role in ('PARENT', 'ORGANIZER', 'CO_ORGANIZER')),
    late_join       boolean not null default false, -- PRD §13.3 update: joined after OPEN_FOR_CONTRIBUTIONS closed
    created_at      timestamptz not null default now(),
    unique (classroom_id, parent_user_id, student_id)
);
-- Every cross-tenant query must filter by classroom_id — this index is the
-- backbone of the PRD §14 "Class A can never read Class B" authorization test.
create index idx_membership_classroom on membership(classroom_id);
create index idx_membership_parent on membership(parent_user_id);
create index idx_membership_student on membership(student_id);

create table invite (
    id              uuid primary key default gen_random_uuid(),
    classroom_id    uuid not null references classroom(id),
    token           text not null unique,          -- the /join/{token} slug
    channel         text check (channel in ('EMAIL', 'SMS', 'LINK', 'QR')),
    created_by      uuid not null references app_user(id),
    created_at      timestamptz not null default now(),
    opened_at       timestamptz,
    converted_at    timestamptz,                    -- set when the opener completes signup + join
    converted_user_id uuid references app_user(id)
);
create index idx_invite_classroom on invite(classroom_id);

-- ============================================================
-- Pools and requirements (PRD §3, §13.2, §13.3)
-- ============================================================

create table pool (
    id              uuid primary key default gen_random_uuid(),
    classroom_id    uuid not null references classroom(id),
    name            text not null,     -- e.g. "Fall Supplies", "Science Project"
    pool_type       text not null default 'SUPPLIES',
    state           text not null default 'DRAFT' check (state in (
                        'DRAFT', 'OPEN_FOR_INVENTORY', 'OPEN_FOR_CONTRIBUTIONS',
                        'RECONCILING', 'PURCHASE_PROPOSED', 'PAYMENT_OPEN',
                        'ORDERED', 'DISTRIBUTING', 'COMPLETED'
                     )),
    payment_gate_satisfied boolean not null default false, -- PRD §14 update: Payment Unlock Gate, checked before PAYMENT_OPEN
    payment_threshold_pct  integer not null default 90,     -- PRD §8.4 update: platform-set, not organizer-editable
    created_at      timestamptz not null default now(),
    locked_at       timestamptz
);
create index idx_pool_classroom on pool(classroom_id);

create table requirement_source (
    id              uuid primary key default gen_random_uuid(),
    pool_id         uuid not null references pool(id),
    source_type     text not null check (source_type in (
                        'PDF', 'PHOTO', 'SCREENSHOT', 'WORD_DOC',
                        'PASTED_EMAIL', 'PASTED_PORTAL', 'PASTED_MESSAGE', 'MANUAL'
                     )),
    s3_key          text,
    raw_text        text,
    uploaded_by     uuid not null references app_user(id),
    created_at      timestamptz not null default now()
);
create index idx_requirement_source_pool on requirement_source(pool_id);

create table requirement (
    id                      uuid primary key default gen_random_uuid(),
    pool_id                 uuid not null references pool(id),
    requirement_source_id   uuid references requirement_source(id),
    name                    text not null,
    quantity_per_student    integer not null,
    brand                   text,
    strictness              text not null default 'EQUIVALENT_ALLOWED' check (strictness in ('EXACT', 'EQUIVALENT_ALLOWED', 'GENERIC')),
    source_evidence         text,           -- PRD §3.2: retained verbatim quote from the source list
    confidence              numeric(4,3),   -- PRD §3.2: AI confidence score, null for manual entries
    state                   text not null default 'EXTRACTED' check (state in (
                                'EXTRACTED', 'NEEDS_REVIEW', 'CONFIRMED', 'POOLING',
                                'LOCKED', 'PURCHASING', 'FULFILLED', 'CLOSED'
                             )),
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);
create index idx_requirement_pool on requirement(pool_id);

-- PRD §6 update: organizer-authored substitution rule for equivalent_allowed items —
-- a category + attribute filter, not per-SKU approval, never AI-decided at purchase time.
create table product_specification (
    id              uuid primary key default gen_random_uuid(),
    requirement_id  uuid not null references requirement(id),
    category        text not null,
    attributes      jsonb not null default '{}',
    created_at      timestamptz not null default now()
);
create index idx_product_spec_requirement on product_specification(requirement_id);

-- ============================================================
-- Phases 3-11 tables (schema laid down now; no API endpoints in this pass —
-- see ARCHITECTURE.md §4)
-- ============================================================

create table parent_inventory (
    id                  uuid primary key default gen_random_uuid(),
    requirement_id      uuid not null references requirement(id),
    student_id          uuid not null references student(id),
    parent_user_id      uuid not null references app_user(id),
    owned_quantity      integer not null default 0,
    surplus_offered_quantity integer not null default 0,
    condition           text,
    updated_at          timestamptz not null default now(),
    unique (requirement_id, student_id)
);
create index idx_parent_inventory_requirement on parent_inventory(requirement_id);

create table contribution (
    id                      uuid primary key default gen_random_uuid(),
    requirement_id          uuid not null references requirement(id),
    offering_parent_id      uuid not null references app_user(id),
    quantity                integer not null,
    mode                    text not null check (mode in ('DONATE', 'LEND', 'SELL', 'KEEP')),
    state                   text not null default 'PLEDGED' check (state in (
                                'PLEDGED', 'RECEIVED', 'ALLOCATED', 'DISTRIBUTED',
                                'RETURN_DUE', 'RETURNED', 'OVERDUE', 'LOST_OR_DAMAGED'
                             )),
    return_due_date         date,   -- PRD §5.4 update: only set for mode = LEND
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);
create index idx_contribution_requirement on contribution(requirement_id);

create table allocation (
    id                  uuid primary key default gen_random_uuid(),
    requirement_id      uuid not null references requirement(id),
    student_id          uuid not null references student(id),
    fulfillment_type    text not null check (fulfillment_type in ('SELF_FULFILLED', 'POOL_FULFILLED', 'PURCHASE_REQUIRED')),
    quantity            integer not null,
    created_at          timestamptz not null default now()
);
create index idx_allocation_requirement on allocation(requirement_id);

create table product_offer (
    id                  uuid primary key default gen_random_uuid(),
    requirement_id      uuid not null references requirement(id),
    retailer            text not null,
    pack_quantity       integer not null,
    price_cents         integer not null,
    shipping_cents      integer not null default 0,
    affiliate_url       text,   -- PRD §7.4 update: click-through target that earns V1 affiliate revenue
    delivery_date       date,
    minimum_order_cents integer,
    reliability_score   numeric(3,2),
    created_at          timestamptz not null default now()
);
create index idx_product_offer_requirement on product_offer(requirement_id);

create table purchase_plan (
    id          uuid primary key default gen_random_uuid(),
    pool_id     uuid not null references pool(id),
    state       text not null default 'PROPOSED' check (state in ('PROPOSED', 'APPROVED')),
    proposed_at timestamptz not null default now(),
    approved_at timestamptz
);
create index idx_purchase_plan_pool on purchase_plan(pool_id);

create table purchase_plan_line (
    id                          uuid primary key default gen_random_uuid(),
    purchase_plan_id            uuid not null references purchase_plan(id),
    requirement_id              uuid not null references requirement(id),
    product_offer_id            uuid references product_offer(id),
    pack_count                  integer not null,
    total_cost_cents            integer not null,
    waste_quantity              integer not null default 0,   -- PRD §9.4 example: the 8 surplus pens
    substitution_note           text,
    substitution_delta_cents    integer,
    substitution_delta_resolution text check (substitution_delta_resolution in ('ABSORBED', 'TOP_UP_CHARGED')), -- PRD §9.1 update: 10% threshold rule
    created_at                  timestamptz not null default now()
);
create index idx_purchase_plan_line_plan on purchase_plan_line(purchase_plan_id);

create table organizer_stripe_account (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references app_user(id),
    classroom_id    uuid not null references classroom(id),
    stripe_account_id text not null,
    status          text not null default 'PENDING' check (status in ('PENDING', 'ACTIVE', 'RESTRICTED')),
    created_at      timestamptz not null default now(),
    unique (user_id, classroom_id)
);

create table payment (
    id                      uuid primary key default gen_random_uuid(),
    pool_id                 uuid not null references pool(id),
    household_id            uuid not null references household(id),
    amount_cents            integer not null,
    method                  text not null check (method in ('CARD', 'APPLE_PAY', 'GOOGLE_PAY', 'CASH')),
    state                   text not null default 'PENDING' check (state in (
                                'PENDING', 'PAID', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED',
                                'PENDING_CASH', 'PAID_CASH_RECEIVED'
                             )),
    stripe_payment_intent_id text,     -- null for CASH payments — PRD §8.4 update
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);
create index idx_payment_pool on payment(pool_id);
create index idx_payment_household on payment(household_id);

create table "order" (
    id                  uuid primary key default gen_random_uuid(),
    pool_id             uuid not null references pool(id),
    ordered_by          uuid not null references app_user(id),
    receipt_s3_key      text,
    ordered_at          timestamptz not null default now()
);

create table order_line (
    id                      uuid primary key default gen_random_uuid(),
    order_id                uuid not null references "order"(id),
    purchase_plan_line_id   uuid not null references purchase_plan_line(id),
    actual_description      text,
    actual_cost_cents       integer,
    created_at              timestamptz not null default now()
);

create table distribution_batch (
    id          uuid primary key default gen_random_uuid(),
    pool_id     uuid not null references pool(id),
    mode        text not null check (mode in ('CLASSROOM_DESK', 'LOBBY_PICKUP', 'HOUSEHOLD_BAG')),
    created_at  timestamptz not null default now()
);

create table distribution_item (
    id                      uuid primary key default gen_random_uuid(),
    distribution_batch_id   uuid not null references distribution_batch(id),
    student_id              uuid not null references student(id),
    requirement_id          uuid not null references requirement(id),
    quantity                integer not null,
    delivered_at            timestamptz
);
create index idx_distribution_item_batch on distribution_item(distribution_batch_id);

-- PRD §9.4 update + §13.1 update: scoped to classroom OR school (stranded-reserve
-- donate-up case), never both.
create table class_reserve (
    id                  uuid primary key default gen_random_uuid(),
    classroom_id        uuid references classroom(id),
    school_id           uuid references school(id),
    item_name           text not null,
    quantity            integer not null,
    custodian_location  text,   -- free-text, e.g. "Ms. Smith's classroom, supply cabinet"
    created_at          timestamptz not null default now(),
    check (
        (classroom_id is not null and school_id is null) or
        (classroom_id is null and school_id is not null)
    )
);
create index idx_class_reserve_classroom on class_reserve(classroom_id);
create index idx_class_reserve_school on class_reserve(school_id);

create table transfer (
    id                  uuid primary key default gen_random_uuid(),
    class_reserve_id    uuid not null references class_reserve(id),
    from_classroom_id   uuid references classroom(id),
    to_classroom_id     uuid references classroom(id),
    to_school_id        uuid references school(id),
    quantity            integer not null,
    reason              text,
    created_at          timestamptz not null default now()
);

create table notification (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references app_user(id),
    type            text not null,
    channel         text not null check (channel in ('PUSH', 'EMAIL', 'SMS')),
    payload         jsonb not null default '{}',
    sent_at         timestamptz,
    read_at         timestamptz,
    created_at      timestamptz not null default now()
);
create index idx_notification_user on notification(user_id);

create table audit_event (
    id              uuid primary key default gen_random_uuid(),
    actor_user_id   uuid references app_user(id),
    action          text not null,
    entity_type     text not null,
    entity_id       uuid,
    metadata        jsonb not null default '{}',
    created_at      timestamptz not null default now()
);
create index idx_audit_event_entity on audit_event(entity_type, entity_id);
