/**
 * Convenience re-exports of the OpenAPI-generated component schemas
 * (contracts/openapi.yaml `components.schemas`). Import domain types from
 * here rather than reaching into `generated/types.ts` directly, and never
 * hand-write a parallel interface for something the contract already
 * defines — regenerate via `npm run generate:api` instead.
 */
import type { components } from "./generated/types";

export type Schemas = components["schemas"];

/**
 * Narrowing helper — and a flagged discrepancy against the contract.
 *
 * None of the *response* schemas in contracts/openapi.yaml declare a
 * `required:` list, so per JSON Schema/OpenAPI 3.1 semantics every property
 * openapi-typescript generates for them is optional (`prop?: T`), even
 * though the field descriptions and worked examples in the spec clearly
 * intend them to always be present (e.g. `Classroom.id`, `Membership.role`).
 * Rather than hand-writing parallel "the real shape" interfaces (which is
 * exactly the drift risk we're avoiding by generating from the contract) or
 * editing openapi.yaml unilaterally, we apply one narrowing utility here, in
 * our own code, at the single point where generated types become app types.
 * `null` is preserved wherever the schema marks a field `nullable: true`
 * (e.g. `teacherEmail`) — only the *missing-entirely* possibility is removed.
 */
type DeepRequired<T> = T extends (infer U)[]
  ? DeepRequired<U>[]
  : T extends object
    ? { [K in keyof T]-?: DeepRequired<T[K]> }
    : T;

export type Session = DeepRequired<Schemas["Session"]>;
export type CurrentUser = DeepRequired<Schemas["CurrentUser"]>;
export type School = DeepRequired<Schemas["School"]>;
export type ClassroomCreated = DeepRequired<Schemas["ClassroomCreated"]>;
export type Classroom = DeepRequired<Schemas["Classroom"]>;
export type Invite = DeepRequired<Schemas["Invite"]>;
export type InvitePreview = DeepRequired<Schemas["InvitePreview"]>;
export type Membership = DeepRequired<Schemas["Membership"]>;
export type HouseholdDashboard = DeepRequired<Schemas["HouseholdDashboard"]>;
export type Pool = DeepRequired<Schemas["Pool"]>;
export type PoolDetail = DeepRequired<Schemas["PoolDetail"]>;
export type Requirement = DeepRequired<Schemas["Requirement"]>;
export type InventoryLine = DeepRequired<Schemas["InventoryLine"]>;
export type InventorySummary = DeepRequired<Schemas["InventorySummary"]>;
export type Contribution = DeepRequired<Schemas["Contribution"]>;

/**
 * The allocation & residual-demand engine's output (PRD §6) — the frozen
 * result of `POST /pools/{poolId}/reconcile`, also fetched by
 * `GET .../allocation` (organizer) and `.../allocation/mine` (caller's own
 * students). `AllocationStatus` is a plain string union (not an object
 * schema), so `DeepRequired` doesn't apply to it — there's nothing on it to
 * narrow.
 */
export type AllocationStatus = Schemas["AllocationStatus"];
export type AllocationLine = DeepRequired<Schemas["AllocationLine"]>;
export type ResidualDemandLine = DeepRequired<Schemas["ResidualDemandLine"]>;
export type AllocationSummary = DeepRequired<Schemas["AllocationSummary"]>;

/**
 * The bulk-pack purchase-plan engine's raw material and output (PRD §7-8) —
 * `ProductOffer` is a candidate retailer pack an organizer enters per
 * requirement (`POST/GET/DELETE .../product-offers`); `PurchasePlan` (made
 * of `PurchasePlanLine`s) is what `POST .../purchase-plan/generate` computes
 * from those offers. Same `DeepRequired` narrowing as every other response
 * type above — none of these declare a contract `required:` list either,
 * even though fields like `ProductOffer.id` are obviously always present.
 */
export type ProductOffer = DeepRequired<Schemas["ProductOffer"]>;
export type PurchasePlanLine = DeepRequired<Schemas["PurchasePlanLine"]>;
export type PurchasePlan = DeepRequired<Schemas["PurchasePlan"]>;

/**
 * Request body type — left exactly as generated. Unlike the responses above,
 * this one already carries a real `required:` list in the contract
 * (schoolYearLabel, grade, teacherLabel), and the rest (schoolId vs.
 * schoolName, teacherEmail, studentCountEstimate) are genuinely optional by
 * design, not an omission — narrowing it would be wrong.
 */
export type CreateClassroomRequest = Schemas["CreateClassroomRequest"];

/**
 * Also left exactly as generated, same reasoning: `CreateRequirementRequest`
 * already declares `required: [name, quantityPerStudent]` in the contract,
 * and `brand`/`strictness` are genuinely optional (strictness defaults to
 * EQUIVALENT_ALLOWED server-side).
 */
export type CreateRequirementRequest = Schemas["CreateRequirementRequest"];
