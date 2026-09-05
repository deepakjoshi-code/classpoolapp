import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §3.1/§3.2 Phase 11 — AI ingestion: the organizer pastes a supply list
 * and the extraction engine produces candidate requirements with source
 * evidence and a confidence score per item, never a silent guess for a
 * below-threshold item (NEEDS_REVIEW instead of EXTRACTED). Manual entry
 * stays available side by side. Exercises a real, deterministic mix: two
 * clean lines that should extract confidently, one vaguer line that should
 * land as NEEDS_REVIEW, and a sign-off line that must produce nothing at
 * all (never fabricate a requirement for it).
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

test("AI ingestion: pasted text extracts confident and needs-review items, never fabricates from a sign-off line", async ({
  page,
}) => {
  const runId = Date.now();
  const organizerEmail = `phase11-organizer-${runId}@example.com`;
  const schoolName = `Phase 11 Test School ${runId}`;

  await signInWithMagicLink(page, organizerEmail);

  await page.goto("/classrooms/new");
  await page.getByLabel("School name").fill(schoolName);
  await page.getByLabel("Grade").fill("Grade 2");
  await page.getByLabel("School year").fill("2026-2027");
  await page.getByLabel("Teacher name").fill("Ms. Parser");
  await page.getByRole("button", { name: "Create class" }).click();
  await expect(page.getByText("Class created!")).toBeVisible();

  await page.getByRole("link", { name: "Start your first pool" }).click();
  await page.getByLabel("Pool name").fill("Imported Supplies");
  await page.getByRole("button", { name: "Start pool" }).click();

  await expect(page.getByLabel("Where is this from?")).toBeVisible();

  const pasteText = [
    "4 Elmer's glue sticks",
    "2 boxes of tissues",
    "a couple of notebooks",
    "Thanks so much!",
  ].join("\n");

  await page.getByLabel("Paste the text here").fill(pasteText);
  await page.getByRole("button", { name: "Import items from this text" }).click();

  // Two clean lines extract confidently; the vague "a couple of" line needs review.
  await expect(page.getByText(/2 items found, ready to review/)).toBeVisible();
  await expect(page.getByText(/1 more item.*closer look/)).toBeVisible();

  // The sign-off line never became a requirement.
  await expect(page.getByText(/thanks so much/i)).toHaveCount(0);

  // The two confident items. (.first() everywhere here because each item's
  // own hidden source-evidence <details> also contains its name as a
  // substring, e.g. "4 Elmer's glue sticks" contains "glue sticks".)
  await expect(page.getByText("glue sticks").first()).toBeVisible();
  await expect(page.getByText("· Elmer's")).toBeVisible();
  await expect(page.getByText("tissues").first()).toBeVisible();
  await expect(page.getByText(/AI-extracted — \d+% confidence/).first()).toBeVisible();

  // The vague item needs review, visually and textually distinct.
  await expect(page.getByText("notebooks").first()).toBeVisible();
  await expect(page.getByText(/Needs a closer look — \d+% confidence/)).toBeVisible();

  // Source evidence is accessible (not just discarded after extraction).
  await page.getByText("Why was this extracted this way?").first().click();
  await expect(page.getByText('"4 Elmer\'s glue sticks"')).toBeVisible();

  // Manual entry still works side by side with the imported items.
  await page.getByLabel("Item", { exact: true }).fill("Folders");
  await page.getByLabel("Quantity per student").fill("1");
  await page.getByRole("button", { name: "Add item" }).click();
  await expect(page.getByText("Folders")).toBeVisible();

  // Confirming works with a mix of EXTRACTED/NEEDS_REVIEW/manual requirements —
  // nothing about the import path breaks the existing Phase 3 confirm flow.
  await page.getByRole("button", { name: "Confirm list…" }).click();
  await page.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(page.getByText("This list is locked")).toBeVisible();
});
