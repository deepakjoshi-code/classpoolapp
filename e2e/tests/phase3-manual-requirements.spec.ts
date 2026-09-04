import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §3 Phase 3 — "complete a supply list without AI": organizer creates a
 * pool, manually adds items, confirms the list, and sees each item's
 * aggregate class demand computed against actually-joined students (not the
 * classroom's studentCountEstimate). Extends first-technical-milestone.spec.ts's
 * pattern rather than re-deriving sign-in/class-creation from scratch.
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

test("organizer builds a manual supply list, confirms it, and sees correct totals", async ({
  browser,
}) => {
  const runId = Date.now();
  const organizerEmail = `phase3-organizer-${runId}@example.com`;
  const parentEmail = `phase3-parent-${runId}@example.com`;
  const schoolName = `Phase 3 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  // Create the class (same flow as first-technical-milestone.spec.ts).
  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 2");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Mr. Phase3");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  // Start a pool.
  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await expect(organizerPage.getByRole("heading", { name: "Start a pool" })).toBeVisible();
  await organizerPage.getByLabel("Pool name").fill("Fall Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  // Add two items manually — no AI in this phase.
  await expect(organizerPage.getByRole("heading", { name: "Add an item" })).toBeVisible();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Pencils");
  await organizerPage.getByLabel("Quantity per student").fill("3");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Pencils")).toBeVisible();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Glue Sticks");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Glue Sticks")).toBeVisible();

  // A parent joins before confirmation — one real student in the classroom.
  const parentContext = await browser.newContext();
  const parentPage = await parentContext.newPage();
  await parentPage.goto(joinPath);
  await parentPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parentPage, parentEmail);
  await parentPage.getByLabel(/student|child/i).fill("Riley");
  await parentPage.getByRole("button", { name: /join/i }).click();
  await expect(parentPage.getByText("You're in!")).toBeVisible();

  // Organizer confirms — a one-way, two-step action.
  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await expect(organizerPage.getByText("This can't be undone")).toBeVisible();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();

  // 1 joined student x quantityPerStudent -> totals appear, and management
  // controls disappear now that the pool is locked.
  await expect(organizerPage.getByText("Total needed for the class: 3")).toBeVisible();
  await expect(organizerPage.getByText("Total needed for the class: 2")).toBeVisible();
  await expect(organizerPage.getByRole("heading", { name: "Add an item" })).not.toBeVisible();
  await expect(organizerPage.getByText(/locked/i)).toBeVisible();

  await organizerContext.close();
  await parentContext.close();
});
