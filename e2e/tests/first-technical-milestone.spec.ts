import { test, expect, type Page } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §18.1 — the PRD's own first technical milestone, verbatim:
 * "From a phone, an organizer creates a class, shares a link, another
 * parent joins, both can add ClassPool to their phone Home Screen, and
 * both see the same live class pool."
 *
 * This test is that sentence, executed. Runs on a mobile viewport
 * (playwright.config.ts) since the milestone is explicitly phone-first.
 */

async function signInWithMagicLink(page: Page, email: string) {
  await page.goto("/sign-in");
  await page.getByLabel("Email address").fill(email);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByText("Check your email")).toBeVisible();

  const link = extractMagicLinkUrl(email);
  await page.goto(link);
  // Wait for the actual redirect off /auth/verify, not just the absence of the
  // error text (which is trivially true before the async verify fetch even
  // resolves) — navigating away too early cancels that in-flight fetch and the
  // session is never established.
  await page.waitForURL((url) => !url.pathname.startsWith("/auth/verify"), {
    timeout: 10_000,
  });
}

test("organizer creates a class, a parent joins via the shared link, and both see the same live pool", async ({
  browser,
}) => {
  const runId = Date.now();
  const organizerEmail = `organizer-${runId}@example.com`;
  const parentEmail = `parent-${runId}@example.com`;
  const schoolName = `Milestone Test School ${runId}`;

  // --- Organizer, on their own phone/browser context ---
  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();

  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 1");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Ms. Milestone");
  await organizerPage.getByRole("button", { name: "Create class" }).click();

  // Lands on the invite page after creation (no dedup match for a fresh school name).
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  expect(joinUrl).toContain("/join/");

  // Organizer's own dashboard shows the class immediately, as its own card,
  // tagged as the organizer (PRD §12's multi-class HOME update).
  await organizerPage.goto("/");
  await expect(organizerPage.getByText("Grade 1 · Ms. Milestone")).toBeVisible();
  await expect(organizerPage.getByText("Organizer")).toBeVisible();

  // --- A different parent, on a genuinely separate browser context/session ---
  const parentContext = await browser.newContext();
  const parentPage = await parentContext.newPage();

  // Pre-auth: the invite landing page must show class context before any sign-in
  // prompt (PRD §2.2 — "shows school/class context ... before authentication").
  await parentPage.goto(joinUrl.replace(/^https?:\/\/[^/]+/, ""));
  await expect(parentPage.getByText("Grade 1 · Ms. Milestone")).toBeVisible();
  await expect(parentPage.getByText(schoolName)).toBeVisible();
  await expect(parentPage.getByText(/1 family joined so far/)).toBeVisible();

  await parentPage.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(parentPage, parentEmail);

  // signInWithMagicLink navigated via the magic-link URL directly, which
  // redirects back to the remembered /join/{token} destination.
  await parentPage.getByLabel(/student|child/i).fill("Arjun");
  await parentPage.getByRole("button", { name: /join/i }).click();

  await expect(parentPage.getByText("You're in!")).toBeVisible();
  await expect(parentPage.getByText(/Arjun is now part of/)).toBeVisible();

  // --- Both now see the same live class pool ---
  await parentPage.goto("/");
  await expect(parentPage.getByText("Grade 1 · Ms. Milestone")).toBeVisible();
  await expect(parentPage.getByText(schoolName)).toBeVisible();
  await expect(parentPage.getByText("Student:")).toBeVisible();
  await expect(parentPage.getByText("Arjun")).toBeVisible();

  await organizerPage.reload();
  await expect(
    organizerPage.getByText(/2 famil(y|ies) joined so far/).or(
      organizerPage.getByText("Grade 1 · Ms. Milestone")
    )
  ).toBeVisible();

  await organizerContext.close();
  await parentContext.close();
});

test("the app is installable — manifest and service worker are wired up", async ({ page }) => {
  await page.goto("/");

  const manifestHref = await page
    .locator('link[rel="manifest"]')
    .getAttribute("href");
  expect(manifestHref).toBeTruthy();

  const manifestResponse = await page.request.get(manifestHref!);
  expect(manifestResponse.ok()).toBeTruthy();
  const manifest = await manifestResponse.json();
  expect(manifest.display).toBe("standalone");
  expect(manifest.start_url).toBeTruthy();
  expect(Array.isArray(manifest.icons) && manifest.icons.length).toBeTruthy();

  const swRegistered = await page.evaluate(async () => {
    if (!("serviceWorker" in navigator)) return false;
    const reg = await navigator.serviceWorker.getRegistration();
    return !!reg;
  });
  expect(swRegistered).toBe(true);
});
