import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §7 Phase 8 — the bulk-pack optimizer: once a pool has been reconciled
 * (Phase 6+7) and the organizer has priced out candidate retailer packs for
 * whatever still needs buying, "generate the plan" runs a deterministic
 * integer program picking the cheapest combination of packs that covers the
 * residual demand, and the organizer approves it. Uses the PRD's own worked
 * example numbers (§7.1: need=320 pencils, 24-pack@$4.99, 48-pack@$8.49,
 * 144-pack@$18.99 -> 2x144-pack + 1x48-pack = $46.47, waste 16) scaled down
 * to a small class so the flow is exercisable in a single E2E run: one item
 * needed 4 (per two students, 2 each), no inventory/contributions, so the
 * residual demand is exactly 4 — offers of a 3-pack@$3.00 and a 5-pack@$4.00
 * make the cheapest cover-at-least-4 combination unambiguous (one 5-pack,
 * $4.00, waste 1 — cheaper and less wasteful than two 3-packs at $6.00).
 */

async function signInWithMagicLink(page: import("@playwright/test").Page, email: string) {
  await page.goto("/sign-in");
  await page.getByLabel("Email address").fill(email);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByText("Check your email")).toBeVisible();

  const link = extractMagicLinkUrl(email);
  await page.goto(link);
  await page.waitForURL((url) => !url.pathname.startsWith("/auth/verify"), {
    timeout: 10_000,
  });
}

test("bulk pack optimizer: organizer prices offers and generates the cheapest cover", async ({
  browser,
}) => {
  test.setTimeout(60_000);

  const runId = Date.now();
  const organizerEmail = `phase8-organizer-${runId}@example.com`;
  const parentEmail = `phase8-parent-${runId}@example.com`;
  const schoolName = `Phase 8 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 4");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Ms. Optimizer");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Spring Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Notebooks");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Notebooks")).toBeVisible();

  // Two students join, neither owns any and nobody contributes -> residual
  // demand for Notebooks is exactly 2 per student x 2 students = 4.
  const parent1Context = await browser.newContext();
  const parent1Page = await parent1Context.newPage();
  await parent1Page.goto(joinPath);
  await parent1Page.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parent1Page, parentEmail);
  await parent1Page.getByLabel(/student|child/i).fill("Sam");
  await parent1Page.getByRole("button", { name: /join/i }).click();
  await expect(parent1Page.getByText("You're in!")).toBeVisible();

  const parent2Email = `phase8-parent2-${runId}@example.com`;
  const parent2Context = await browser.newContext();
  const parent2Page = await parent2Context.newPage();
  await parent2Page.goto(joinPath);
  await parent2Page.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parent2Page, parent2Email);
  await parent2Page.getByLabel(/student|child/i).fill("Robin");
  await parent2Page.getByRole("button", { name: /join/i }).click();
  await expect(parent2Page.getByText("You're in!")).toBeVisible();

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 4")).toBeVisible();

  // Reconcile with no inventory/contributions recorded -> residual demand is 4.
  await organizerPage.getByRole("button", { name: "Work out what's needed…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByRole("heading", { name: "What still needs to be purchased" })).toBeVisible();
  await expect(organizerPage.getByText("4 still need to be purchased")).toBeVisible();

  // Add two candidate offers: a 3-pack@$3.00 and a 5-pack@$4.00. The
  // cheapest way to cover 4 is one 5-pack ($4.00, waste 1) — two 3-packs
  // would be $6.00 for 6 units (waste 2), strictly worse on both cost and
  // waste, so this pins down a single unambiguous expected answer.
  await organizerPage.getByLabel("Retailer").fill("Amazon");
  await organizerPage.getByLabel("Pack size").fill("3");
  await organizerPage.getByLabel("Price", { exact: true }).fill("3.00");
  await organizerPage.getByRole("button", { name: "Add this price option" }).click();
  await expect(organizerPage.getByText(/pack of 3.*\$3\.00/)).toBeVisible();

  await organizerPage.getByLabel("Retailer").fill("Walmart");
  await organizerPage.getByLabel("Pack size").fill("5");
  await organizerPage.getByLabel("Price", { exact: true }).fill("4.00");
  await organizerPage.getByRole("button", { name: "Add this price option" }).click();
  await expect(organizerPage.getByText(/pack of 5.*\$4\.00/)).toBeVisible();

  // Generate the plan.
  await organizerPage.getByRole("button", { name: "Work out the purchase plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();

  await expect(organizerPage.getByRole("heading", { name: "The purchase plan" })).toBeVisible();
  await expect(organizerPage.getByText("1 pack of 5 from Walmart")).toBeVisible();
  await expect(organizerPage.getByText("$4.00")).toHaveCount(2); // the line cost and the grand total
  await expect(organizerPage.getByText("Waiting for your approval")).toBeVisible();

  // The "generate" action and the price-option forms are gone now that a
  // plan exists — the pool has moved past RECONCILING.
  await expect(
    organizerPage.getByRole("button", { name: "Work out the purchase plan…" })
  ).toHaveCount(0);
  await expect(organizerPage.getByRole("button", { name: "Add this price option" })).toHaveCount(0);

  // Approve the plan.
  await organizerPage.getByRole("button", { name: "Approve this plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, approve this plan" }).click();
  await expect(organizerPage.getByText("Approved", { exact: true })).toBeVisible();
  await expect(organizerPage.getByRole("button", { name: "Approve this plan…" })).toHaveCount(0);

  await organizerContext.close();
  await parent1Context.close();
  await parent2Context.close();
});
