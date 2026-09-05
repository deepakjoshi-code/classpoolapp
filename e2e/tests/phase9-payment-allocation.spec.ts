import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §8 Phase 9 — payment allocation via a stubbed Stripe Connect: once a
 * purchase plan is approved, the organizer connects a (stub) Stripe account,
 * opens payment, and each household pays its own share (need-based split,
 * §8.1-8.3). Exercises the 90% payment threshold gate both below and at
 * threshold: one household pays by card, the other is marked cash-received
 * by the organizer, then the organizer finalizes into ORDERED.
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

test("payment allocation: below-threshold risk banner, cash fallback, then finalize into ORDERED", async ({
  browser,
}) => {
  test.setTimeout(90_000);

  const runId = Date.now();
  const organizerEmail = `phase9-organizer-${runId}@example.com`;
  const samEmail = `phase9-sam-${runId}@example.com`;
  const robinEmail = `phase9-robin-${runId}@example.com`;
  const schoolName = `Phase 9 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 5");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Mx. Ledger");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Payment Test Pool");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Notebooks");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Notebooks")).toBeVisible();

  const samContext = await browser.newContext();
  const samPage = await samContext.newPage();
  await samPage.goto(joinPath);
  await samPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(samPage, samEmail);
  await samPage.getByLabel(/student|child/i).fill("Sam");
  await samPage.getByRole("button", { name: /join/i }).click();
  await expect(samPage.getByText("You're in!")).toBeVisible();

  const robinContext = await browser.newContext();
  const robinPage = await robinContext.newPage();
  await robinPage.goto(joinPath);
  await robinPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(robinPage, robinEmail);
  await robinPage.getByLabel(/student|child/i).fill("Robin");
  await robinPage.getByRole("button", { name: /join/i }).click();
  await expect(robinPage.getByText("You're in!")).toBeVisible();

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 4")).toBeVisible();

  // Reconcile with no inventory/contributions -> residual demand is 4 (2 per student).
  await organizerPage.getByRole("button", { name: "Work out what's needed…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByText("4 still need to be purchased")).toBeVisible();

  // One 5-pack at $4.00 covers all 4 -> unit cost $1.00 -> each of Sam/Robin owes $2.00 (2 units each).
  await organizerPage.getByLabel("Retailer").fill("Walmart");
  await organizerPage.getByLabel("Pack size").fill("5");
  await organizerPage.getByLabel("Price", { exact: true }).fill("4.00");
  await organizerPage.getByRole("button", { name: "Add this price option" }).click();
  await expect(organizerPage.getByText(/pack of 5.*\$4\.00/)).toBeVisible();

  await organizerPage.getByRole("button", { name: "Work out the purchase plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByRole("heading", { name: "The purchase plan" })).toBeVisible();

  await organizerPage.getByRole("button", { name: "Approve this plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, approve this plan" }).click();
  await expect(organizerPage.getByText("Approved", { exact: true })).toBeVisible();

  // Stub Stripe onboarding.
  await organizerPage.getByRole("button", { name: "Connect your bank account" }).click();
  await organizerPage.getByRole("button", { name: "Simulate returning from Stripe" }).click();

  // Open payment for the pool -> two $2.00 payments.
  await organizerPage.getByRole("button", { name: "Open payment for this pool…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, open payment" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Payments" })).toBeVisible();
  await expect(organizerPage.getByText("$2.00", { exact: true })).toHaveCount(2);

  // Sam pays via their own household payment page.
  await samPage.goto("/");
  await samPage.getByRole("link", { name: /open for contributions|reconciling|purchase proposed|payment open/i }).click();
  await samPage.getByRole("link", { name: /your payment/i }).click();
  await expect(samPage.getByText("$2.00", { exact: true })).toBeVisible();
  await expect(samPage.getByText(/paying the class organizer.*not ClassPool/i)).toBeVisible();
  await samPage.getByRole("button", { name: /pay \$2\.00 with card/i }).click();
  await expect(samPage.getByText("Paid", { exact: true })).toBeVisible();

  // Organizer sees 50% collected — below the 90% threshold — with Robin outstanding.
  await organizerPage.reload();
  await expect(organizerPage.getByText("$2.00 collected of $4.00 owed.")).toBeVisible();
  await expect(organizerPage.getByText("50% collected · needs 90% to proceed normally")).toBeVisible();
  await expect(organizerPage.getByText(/still owes \$2\.00/)).toBeVisible();

  // Finalizing now would require the explicit below-threshold acknowledgment.
  await organizerPage.getByRole("button", { name: "Finalize payment and proceed to ordering…" }).click();
  await expect(organizerPage.getByRole("button", { name: "Yes, finalize below threshold" })).toBeDisabled();
  await organizerPage.getByRole("checkbox").check();
  await expect(organizerPage.getByRole("button", { name: "Yes, finalize below threshold" })).toBeEnabled();
  // Back out — resolve Robin's payment by cash instead of finalizing early.
  await organizerPage.getByRole("button", { name: "Cancel" }).click();

  // Organizer marks Robin's payment (the only still-pending one) as cash, then received.
  await organizerPage.getByRole("button", { name: "Mark cash pending" }).click();
  await expect(organizerPage.getByRole("button", { name: "Mark cash received" })).toBeVisible();
  await organizerPage.getByRole("button", { name: "Mark cash received" }).click();
  await expect(organizerPage.getByText("Paid by cash — received")).toBeVisible();

  // Now fully collected — the simple (non-checkbox) finalize path.
  await expect(organizerPage.getByText("$4.00 collected of $4.00 owed.")).toBeVisible();
  await expect(organizerPage.getByText("100% collected · needs 90% to proceed normally")).toBeVisible();
  await organizerPage.getByRole("button", { name: "Finalize payment and proceed to ordering…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, finalize" }).click();
  await expect(
    organizerPage.getByText("Payment has been finalized — this pool has moved on to ordering.")
  ).toBeVisible();

  await organizerContext.close();
  await samContext.close();
  await robinContext.close();
});
