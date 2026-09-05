import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §5 Phase 5 — "Surplus contribution pool": once a pool is confirmed, a
 * parent can pledge extra supplies to the class (DONATE mode only in V1),
 * withdraw an unreceived pledge, and the organizer sees every pledge with
 * the offering household's identity (PRD §5.3 privacy model) and can mark
 * one received. Extends the Phase 3/4 spec's flow rather than re-deriving
 * sign-in/class-creation/confirm from scratch.
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

test("parent offers surplus, withdraws one, organizer receives the other with identity visible — and a plain parent never sees it", async ({
  browser,
}) => {
  const runId = Date.now();
  const organizerEmail = `phase5-organizer-${runId}@example.com`;
  const parentEmail = `phase5-parent-${runId}@example.com`;
  const schoolName = `Phase 5 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 3");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Ms. Surplus");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Fall Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Glue Sticks");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Glue Sticks")).toBeVisible();

  // A parent joins and the organizer confirms.
  const parentContext = await browser.newContext();
  const parentPage = await parentContext.newPage();
  await parentPage.goto(joinPath);
  await parentPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parentPage, parentEmail);
  await parentPage.getByLabel(/student|child/i).fill("Nora");
  await parentPage.getByRole("button", { name: /join/i }).click();
  await expect(parentPage.getByText("You're in!")).toBeVisible();

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 2")).toBeVisible();

  // Parent offers two separate pledges: one they'll withdraw, one they'll leave for the organizer to receive.
  await parentPage.goto("/");
  await parentPage.getByRole("link", { name: /checking what families already have/ }).click();
  await parentPage.getByRole("link", { name: /offer extra supplies to the class/i }).click();
  await expect(
    parentPage.getByRole("heading", { name: "Offer your extra supplies" })
  ).toBeVisible();

  const quantityInput = parentPage.getByLabel(/Extra you can give/i);
  await quantityInput.fill("3");
  await parentPage.getByRole("button", { name: "Offer to donate" }).click();
  await expect(parentPage.getByText("You offered 3 · Pledged — not yet received")).toBeVisible();

  await quantityInput.fill("1");
  await parentPage.getByRole("button", { name: "Offer to donate" }).click();
  await expect(parentPage.getByText("You offered 1 · Pledged — not yet received")).toBeVisible();

  // Withdraw the first (3-unit) pledge — only the still-pledged one has a Withdraw button.
  const withdrawButtons = parentPage.getByRole("button", { name: /withdraw your offer of 3/i });
  await withdrawButtons.click();
  await expect(parentPage.getByText("You offered 3 · Pledged — not yet received")).toHaveCount(0);
  await expect(parentPage.getByText("You offered 1 · Pledged — not yet received")).toBeVisible();

  // A plain parent never sees the organizer's identity-carrying contributions panel.
  await expect(parentPage.getByRole("heading", { name: "Contributions" })).toHaveCount(0);

  // Organizer sees only the remaining (1-unit) pledge, with the parent's identity, and marks it received.
  await organizerPage.reload();
  await expect(organizerPage.getByRole("heading", { name: "Contributions" })).toBeVisible();
  await expect(organizerPage.getByText(/1 × Glue Sticks/)).toBeVisible();
  await expect(organizerPage.getByText(/3 × Glue Sticks/)).toHaveCount(0);
  await expect(organizerPage.getByText(/From .*·.*Pledged — not yet received/)).toBeVisible();

  await organizerPage.getByRole("button", { name: "Mark received" }).click();
  await expect(organizerPage.getByText(/Received — thank you!/)).toBeVisible();
  await expect(organizerPage.getByRole("button", { name: "Mark received" })).toHaveCount(0);

  await organizerContext.close();
  await parentContext.close();
});
