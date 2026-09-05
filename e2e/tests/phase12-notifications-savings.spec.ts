import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §11.3/§16.3 Phase 12 — the in-app notification bell and the shareable
 * "how much this pool saved" savings summary. One household (Alex's) is
 * fully self-fulfilled from home inventory; the other (Bailey's) needs the
 * full purchase, so only Bailey's household ever owes anything and only
 * Bailey's parent gets a PAYMENT_DUE notification once payments are
 * generated. Extends the Phase 4 (inventory) and Phase 9 (payments) specs'
 * flows rather than re-deriving them.
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

test("notifications and savings summary: only the paying household is notified, savings reflect real numbers", async ({
  browser,
}) => {
  test.setTimeout(90_000);

  const runId = Date.now();
  const organizerEmail = `phase12-organizer-${runId}@example.com`;
  const alexEmail = `phase12-alex-${runId}@example.com`;
  const baileyEmail = `phase12-bailey-${runId}@example.com`;
  const schoolName = `Phase 12 Test School ${runId}`;

  // Clipboard permission for the savings-summary share fallback (this sandbox's Chromium
  // has no Web Share API target, so SavingsSummaryCard falls back to clipboard.writeText).
  const organizerContext = await browser.newContext({
    permissions: ["clipboard-read", "clipboard-write"],
  });
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 1");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Ms. Ledger");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Fall Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Pencils");
  await organizerPage.getByLabel("Quantity per student").fill("4");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Pencils")).toBeVisible();

  const alexContext = await browser.newContext();
  const alexPage = await alexContext.newPage();
  await alexPage.goto(joinPath);
  await alexPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(alexPage, alexEmail);
  await alexPage.getByLabel(/student|child/i).fill("Alex");
  await alexPage.getByRole("button", { name: /join/i }).click();
  await expect(alexPage.getByText("You're in!")).toBeVisible();

  const baileyContext = await browser.newContext();
  const baileyPage = await baileyContext.newPage();
  await baileyPage.goto(joinPath);
  await baileyPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(baileyPage, baileyEmail);
  await baileyPage.getByLabel(/student|child/i).fill("Bailey");
  await baileyPage.getByRole("button", { name: /join/i }).click();
  await expect(baileyPage.getByText("You're in!")).toBeVisible();

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 8")).toBeVisible();

  // Alex's household already owns all 4 pencils at home — fully self-fulfilled.
  await alexPage.goto("/");
  await alexPage.getByRole("link", { name: /checking what families already have/ }).click();
  await alexPage.getByRole("link", { name: "Shop your home first →" }).click();
  await expect(alexPage.getByRole("heading", { name: "Shop your home first" })).toBeVisible();
  const increaseButton = alexPage.getByRole("button", { name: /Increase owned Pencils/ });
  await increaseButton.click();
  await increaseButton.click();
  await increaseButton.click();
  await increaseButton.click();
  await expect(alexPage.getByText("You already have all 1 item covered!")).toBeVisible();

  // Reconcile -> itemsReused = Alex's 4 owned, itemsPurchased = Bailey's 4 residual.
  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Work out what's needed…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByText("4 still need to be purchased")).toBeVisible();

  // No purchase plan yet -> zero savings, no dollar figure, but reuse/purchase counts are real.
  const savingsCard = organizerPage.locator("text=Fall Supplies savings").locator("..");
  await expect(organizerPage.getByRole("heading", { name: "Fall Supplies savings" })).toBeVisible();
  await expect(organizerPage.getByText("Items reused")).toBeVisible();
  await expect(savingsCard.getByText("4", { exact: true }).first()).toBeVisible();
  await expect(organizerPage.getByText(/Estimated savings/)).toHaveCount(0);

  // One 4-pack at $4.00 covers Bailey's 4 residual exactly -> unit cost $1.00.
  await organizerPage.getByLabel("Retailer").fill("Amazon");
  await organizerPage.getByLabel("Pack size").fill("4");
  await organizerPage.getByLabel("Price", { exact: true }).fill("4.00");
  await organizerPage.getByRole("button", { name: "Add this price option" }).click();
  await expect(organizerPage.getByText(/pack of 4.*\$4\.00/)).toBeVisible();

  await organizerPage.getByRole("button", { name: "Work out the purchase plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByRole("heading", { name: "The purchase plan" })).toBeVisible();

  await organizerPage.getByRole("button", { name: "Approve this plan…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, approve this plan" }).click();
  await expect(organizerPage.getByText("Approved", { exact: true })).toBeVisible();

  // Now the savings summary reflects a real price signal: avgUnitCost $1.00 * 4 reused = $4.00.
  await organizerPage.reload();
  await expect(organizerPage.getByText("Estimated savings: $4.00")).toBeVisible();
  await organizerPage.getByRole("button", { name: "Share these savings" }).click();
  await expect(organizerPage.getByRole("button", { name: "Copied!" })).toBeVisible();

  // Stub Stripe onboarding, then open payment -> only Bailey's household owes anything.
  await organizerPage.getByRole("button", { name: "Connect your bank account" }).click();
  await organizerPage.getByRole("button", { name: "Simulate returning from Stripe" }).click();
  await organizerPage.getByRole("button", { name: "Open payment for this pool…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, open payment" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Payments" })).toBeVisible();

  // Alex's household never owed anything -> no notification, bell shows no unread badge.
  await alexPage.goto("/");
  const alexBell = alexPage.getByRole("button", { name: "Notifications" });
  await expect(alexBell).toBeVisible();
  await alexBell.click();
  await expect(alexPage.getByText("You're all caught up.")).toBeVisible();

  // Bailey's household owes $4.00 -> exactly one PAYMENT_DUE notification, unread.
  await baileyPage.goto("/");
  const baileyBell = baileyPage.getByRole("button", { name: "Notifications, 1 unread" });
  await expect(baileyBell).toBeVisible();
  await baileyBell.click();
  await expect(baileyPage.getByText(/You owe \$4\.00 for Fall Supplies\./)).toBeVisible();

  // Clicking it marks it read and navigates to the pool.
  await baileyPage.getByText(/You owe \$4\.00 for Fall Supplies\./).click();
  await baileyPage.waitForURL(/\/pools\//);

  // Reopening the bell shows no more unread badge, and the read notification persists.
  await baileyPage.getByRole("button", { name: "Notifications" }).click();
  await expect(baileyPage.getByText(/You owe \$4\.00 for Fall Supplies\./)).toBeVisible();

  await organizerContext.close();
  await alexContext.close();
  await baileyContext.close();
});
