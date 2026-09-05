import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §4 Phase 4 — "Shop Your Home First": once a pool is confirmed, a
 * parent records how much of each item their household already owns via a
 * quick +/- stepper, sees an honest coverage message (no fabricated dollar
 * figure — this phase has no prices), and the organizer sees a completion
 * summary. Extends the Phase 3 spec's flow rather than re-deriving
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

test("parent records household inventory and sees honest coverage, organizer sees the summary", async ({
  browser,
}) => {
  const runId = Date.now();
  const organizerEmail = `phase4-organizer-${runId}@example.com`;
  const parentEmail = `phase4-parent-${runId}@example.com`;
  const schoolName = `Phase 4 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 3");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Ms. Inventory");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Fall Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  await organizerPage.getByLabel("Item", { exact: true }).fill("Folders");
  await organizerPage.getByLabel("Quantity per student").fill("2");
  await organizerPage.getByRole("button", { name: "Add item" }).click();
  await expect(organizerPage.getByText("Folders")).toBeVisible();

  // A parent joins before confirmation.
  const parentContext = await browser.newContext();
  const parentPage = await parentContext.newPage();
  await parentPage.goto(joinPath);
  await parentPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parentPage, parentEmail);
  await parentPage.getByLabel(/student|child/i).fill("Nora");
  await parentPage.getByRole("button", { name: /join/i }).click();
  await expect(parentPage.getByText("You're in!")).toBeVisible();

  // Organizer confirms.
  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 2")).toBeVisible();

  // Parent goes to "Shop your home first" and records owning both folders.
  await parentPage.goto("/");
  await parentPage.getByRole("link", { name: /checking what families already have/ }).click();
  await parentPage.getByRole("link", { name: "Shop your home first →" }).click();
  await expect(parentPage.getByRole("heading", { name: "Shop your home first" })).toBeVisible();
  await expect(parentPage.getByText("0 of 1 item already covered.")).toBeVisible();

  const increaseButton = parentPage.getByRole("button", { name: /Increase owned Folders/ });
  await increaseButton.click();
  await increaseButton.click();

  await expect(parentPage.getByText("You already have all 1 item covered!")).toBeVisible();

  // Organizer sees the completion summary reflect it.
  await organizerPage.reload();
  await expect(organizerPage.getByText(/Inventory completed/)).toBeVisible();
  await expect(organizerPage.getByText(/1\s*\/\s*1 students/)).toBeVisible();
  await expect(organizerPage.getByText(/2 already owned of 2 needed/)).toBeVisible();

  await organizerContext.close();
  await parentContext.close();
});
