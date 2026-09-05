/**
 * Money helpers — the API always works in integer cents (`ProductOffer.
 * priceCents`/`shippingCents`, `PurchasePlan.totalCostCents`,
 * `PurchasePlanLine.totalCostCents`, per `contracts/openapi.yaml`), and this
 * is the one place in the app so far handling money, so it establishes the
 * convention: components format cents for display and parse a normal-looking
 * dollar input back to cents only at the API boundary — never carry a float
 * dollar amount through app state.
 */

/**
 * Integer cents -> a locale-formatted dollar string, e.g. `4647` -> `"$46.47"`.
 * Never render raw cents ("4647 cents") or an unrounded float ("46.47000")
 * to a parent or organizer.
 */
export function formatCents(cents: number): string {
  return (cents / 100).toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
  });
}

/**
 * A dollar-input string (whatever a person typed into a price field, e.g.
 * `"4.99"`) -> integer cents, e.g. `499`. Rounds rather than truncates to
 * avoid float-precision drift (`4.99 * 100` is `498.99999999999994` in IEEE
 * 754 double precision). Returns `NaN` for anything that doesn't parse as a
 * number — callers should validate before submitting, same as every other
 * numeric field in this app (see `RequirementForm`'s `canSubmit` pattern).
 */
export function dollarsToCents(dollars: string): number {
  const parsed = parseFloat(dollars);
  if (!Number.isFinite(parsed)) return NaN;
  return Math.round(parsed * 100);
}
