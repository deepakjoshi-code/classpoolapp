import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §9 Phase 10 — ordering and distribution: once payment is finalized
 * (pool state ORDERED), the organizer records the purchase, sets up
 * distribution (a printable per-household pick list + delivery tracking),
 * banks bulk-buy leftovers in Class Reserve, and completes the pool. A
 * single-item, single-household scenario keeps the earlier phases (already
 * covered by their own E2E specs) to the minimum needed to reach ORDERED.
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

async function goToPool(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page
    .getByRole("link", {
      name: /checking what families already have|reconciling|purchase proposed|payment open|^ordered$|distributing/i,
    })
    .click();
}

test("ordering and distribution: record order, set up pick lists, deliver, bank leftovers, complete", async ({
  browser,
}) => {
  test.setTimeout(90_000);

  const runId = Date.now();
  const organizerEmail = `phase10-organizer-${runId}@example.com`;
  const jamieEmail = `phase10-jamie-${runId}@example.com`;
  const schoolName = `Phase 10 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 1");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Mx. Crate");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Order Test Pool");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Folders");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Folders")).toBeVisible();

  const jamieContext = await browser.newContext();
  const jamiePage = await jamieContext.newPage();
  await jamiePage.goto(joinPath);
  await jamiePage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(jamiePage, jamieEmail);
  await jamiePage.getByLabel(/student|child/i).fill("Jamie");
  await jamiePage.getByRole("button", { name: /join/i }).click();
  await expect(jamiePage.getByText("You're in!")).toBeVisible();

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 2")).toBeVisible();

  // Reconcile with no inventory/contributions -> residual demand is 2.
  await organizerPage.getByRole("button", { name: "Work out what's needed…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByText("2 still need to be purchased")).toBeVisible();

  // A 5-pack at $5.00 covers 2 needed -> waste 3 -> $5.00 owed by Jamie's household.
  await organizerPage.getByLabel("Retailer").fill("Amazon");
  await organizerPage.getByLabel("Pack size").fill("5");
  await organizerPage.getByLabel("Price", { exact: true }).fill("5.00");
  await organizerPage.getByRole("button", { name: "Add this price option" }).click();
  await expect(organizerPage.getByText(/pack of 5.*\$5\.00/)).toBeVisible();

  await organizerPage.getByRole("button", { name: "Work out the purchase plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByRole("heading", { name: "The purchase plan" })).toBeVisible();

  await organizerPage.getByRole("button", { name: "Approve this plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, approve this plan" }).click();
  await expect(organizerPage.getByText("Approved", { exact: true })).toBeVisible();

  await organizerPage.getByRole("button", { name: "Connect your bank account" }).click();
  await organizerPage.getByRole("button", { name: "Simulate returning from Stripe" }).click();

  await organizerPage.getByRole("button", { name: "Open payment for this pool…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, open payment" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Payments" })).toBeVisible();

  // Jamie's household pays the full amount -> 100% collected (only household on this pool).
  await goToPool(jamiePage);
  await jamiePage.getByRole("link", { name: /your payment/i }).click();
  await expect(jamiePage.getByText("$5.00", { exact: true })).toBeVisible();
  await jamiePage.getByRole("button", { name: /pay \$5\.00 with card/i }).click();
  await expect(jamiePage.getByText("Paid", { exact: true })).toBeVisible();

  await organizerPage.reload();
  await expect(organizerPage.getByText("$5.00 collected of $5.00 owed.")).toBeVisible();
  await organizerPage.getByRole("button", { name: "Finalize payment and proceed to ordering…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, finalize" }).click();
  await expect(
    organizerPage.getByText("Payment has been finalized — this pool has moved on to ordering.")
  ).toBeVisible();

  // Record the order exactly as planned — no substitution.
  await organizerPage.getByRole("button", { name: "Yes, I bought this — nothing was substituted" }).click();
  await organizerPage.getByRole("button", { name: "Yes, record this order" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Order recorded" })).toBeVisible();

  // Set up distribution.
  await organizerPage.getByRole("button", { name: "Set up distribution…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, set up distribution" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Distribution" })).toBeVisible();

  // Pick list groups Jamie's household with the full 2 Folders needed.
  await expect(organizerPage.getByText(/phase10-jamie-\d+/).first()).toBeVisible();
  await expect(organizerPage.getByText("2 Folders").first()).toBeVisible();

  // Class reserve banked the 3 leftover folders (5-pack bought to cover 2 needed).
  await expect(organizerPage.getByText(/3 Folders — kept at: not yet noted/)).toBeVisible();

  // Mark Jamie's item delivered (the button's accessible name is the fuller
  // aria-label, "Mark Folders delivered for Jamie", not its visible text).
  await organizerPage.getByRole("button", { name: "Mark Folders delivered for Jamie" }).click();
  await expect(organizerPage.getByText("Not yet delivered")).toHaveCount(0);

  // Jamie's own view reflects delivery.
  await goToPool(jamiePage);
  await jamiePage.getByRole("link", { name: /what you're receiving/i }).click();
  await expect(jamiePage.getByRole("heading", { name: "What you're receiving" })).toBeVisible();
  await expect(jamiePage.getByText("2 Folders")).toBeVisible();
  await expect(jamiePage.getByText("Delivered")).toBeVisible();

  // Complete the pool.
  await organizerPage.getByRole("button", { name: "Finish this pool…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, finish this pool" }).click();
  await expect(organizerPage.getByRole("heading", { name: "This pool is complete" })).toBeVisible();

  await organizerContext.close();
  await jamieContext.close();
});
