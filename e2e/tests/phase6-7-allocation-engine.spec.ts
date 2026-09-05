import { test, expect } from "@playwright/test";
import { extractMagicLinkUrl } from "../helpers/magic-link";

/**
 * PRD §6 Phase 6+7 — the allocation & residual-demand engine: once a pool
 * leaves OPEN_FOR_INVENTORY, the organizer runs "reconcile" to freeze, for
 * every (requirement, student) pair, how much is self-fulfilled from
 * recorded inventory (Phase 4), how much is covered on top by RECEIVED
 * surplus contributions (Phase 5, allocated first-joined-first-served), and
 * how much still needs to be purchased — aggregated per requirement into
 * the class's residual demand. Extends the Phase 3/4/5 specs' flow rather
 * than re-deriving sign-in/class-creation/confirm from scratch.
 *
 * Scenario exercises all three outcomes:
 * - Ava (joined first) fully owns both items herself -> SELF_FULFILLED.
 * - Ben (joined second) owns neither. A donated Glue Stick only partially
 *   covers his need (1 of 2) -> PURCHASE_REQUIRED, with the shortfall named.
 *   A donated Folder exactly covers his need (1 of 1) -> POOL_FULFILLED.
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

async function joinClass(
  page: import("@playwright/test").Page,
  joinPath: string,
  email: string,
  studentName: string
) {
  await page.goto(joinPath);
  await page.getByRole("button", { name: "Continue to join this class" }).click();
  await signInWithMagicLink(page, email);
  await page.getByLabel(/student|child/i).fill(studentName);
  await page.getByRole("button", { name: /join/i }).click();
  await expect(page.getByText("You're in!")).toBeVisible();
}

async function goToPool(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByRole("link", { name: /checking what families already have|building the supply list|open for contributions|reconciling/i }).click();
}

test("allocation engine: self-fulfilled, partially pool-covered, and fully pool-covered outcomes", async ({
  browser,
}) => {
  test.setTimeout(90_000); // three concurrent users, many steps — longer than the 30s default

  const runId = Date.now();
  const organizerEmail = `phase67-organizer-${runId}@example.com`;
  const avaEmail = `phase67-ava-parent-${runId}@example.com`;
  const benEmail = `phase67-ben-parent-${runId}@example.com`;
  const schoolName = `Phase 6-7 Test School ${runId}`;

  const organizerContext = await browser.newContext();
  const organizerPage = await organizerContext.newPage();
  await signInWithMagicLink(organizerPage, organizerEmail);

  await organizerPage.goto("/classrooms/new");
  await organizerPage.getByLabel("School name").fill(schoolName);
  await organizerPage.getByLabel("Grade").fill("Grade 2");
  await organizerPage.getByLabel("School year").fill("2026-2027");
  await organizerPage.getByLabel("Teacher name").fill("Mr. Allocation");
  await organizerPage.getByRole("button", { name: "Create class" }).click();
  await expect(organizerPage.getByText("Class created!")).toBeVisible();
  const joinUrl = await organizerPage.locator("#join-url").inputValue();
  const joinPath = joinUrl.replace(/^https?:\/\/[^/]+/, "");

  await organizerPage.getByRole("link", { name: "Start your first pool" }).click();
  await organizerPage.getByLabel("Pool name").fill("Winter Supplies");
  await organizerPage.getByRole("button", { name: "Start pool" }).click();

  async function addItem(name: string, qty: number) {
    await organizerPage.getByLabel("Item", { exact: true }).fill(name);
    await organizerPage.getByLabel("Quantity per student").fill(String(qty));
    await organizerPage.getByRole("button", { name: "Add item" }).click();
    await organizerPage.getByText(name).waitFor({ state: "visible" });
  }
  await addItem("Glue Sticks", 2);
  await addItem("Folders", 1);

  // Ava joins first, Ben second — join order drives the pool-allocation tie-break.
  const avaContext = await browser.newContext();
  const avaPage = await avaContext.newPage();
  await joinClass(avaPage, joinPath, avaEmail, "Ava");

  const benContext = await browser.newContext();
  const benPage = await benContext.newPage();
  await joinClass(benPage, joinPath, benEmail, "Ben");

  await organizerPage.reload();
  await organizerPage.getByRole("button", { name: "Confirm list…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, confirm and lock the list" }).click();
  await expect(organizerPage.getByText("Total needed for the class: 4")).toBeVisible();

  // Ava records owning everything she needs.
  await goToPool(avaPage);
  await avaPage.getByRole("link", { name: "Shop your home first →" }).click();
  await expect(avaPage.getByRole("heading", { name: "Shop your home first" })).toBeVisible();
  const avaGlueIncrease = avaPage.getByRole("button", { name: /Increase owned Glue Sticks/ });
  await avaGlueIncrease.click();
  await avaGlueIncrease.click();
  await avaPage.getByRole("button", { name: /Increase owned Folders/ }).click();
  await avaPage.waitForTimeout(800); // let the debounced saves land before navigating away

  // Ben leaves his inventory at 0 — he needs the pool + purchase to cover everything.

  // Ben's household offers 1 Glue Stick and 1 Folder as surplus.
  await goToPool(benPage);
  await benPage.getByRole("link", { name: /offer extra supplies to the class/i }).click();
  await expect(benPage.getByRole("heading", { name: "Offer your extra supplies" })).toBeVisible();

  const offerCards = benPage.locator("li", { has: benPage.getByRole("button", { name: "Offer to donate" }) });
  await offerCards.filter({ hasText: "Glue Sticks" }).getByLabel(/Extra you can give/i).fill("1");
  await offerCards.filter({ hasText: "Glue Sticks" }).getByRole("button", { name: "Offer to donate" }).click();
  await expect(benPage.getByText(/You offered 1/)).toBeVisible();

  await offerCards.filter({ hasText: "Folders" }).getByLabel(/Extra you can give/i).fill("1");
  await offerCards.filter({ hasText: "Folders" }).getByRole("button", { name: "Offer to donate" }).click();
  await expect(benPage.getByText(/You offered 1/)).toHaveCount(2);

  // Organizer marks both contributions received (only received surplus counts).
  await organizerPage.reload();
  await expect(organizerPage.getByRole("heading", { name: "Contributions" })).toBeVisible();
  const receiveButtons = organizerPage.getByRole("button", { name: "Mark received" });
  await expect(receiveButtons).toHaveCount(2);
  await receiveButtons.first().click();
  await expect(organizerPage.getByRole("button", { name: "Mark received" })).toHaveCount(1);
  await organizerPage.getByRole("button", { name: "Mark received" }).click();
  await expect(organizerPage.getByRole("button", { name: "Mark received" })).toHaveCount(0);

  // Organizer runs the allocation & residual-demand engine.
  await organizerPage.getByRole("button", { name: "Work out what's needed…" }).click();
  await organizerPage.getByRole("button", { name: "Yes, work it out" }).click();
  await expect(organizerPage.getByRole("heading", { name: "What still needs to be purchased" })).toBeVisible();

  // Scope strictly to the allocation panel — several other sections on this
  // page (household inventory, the requirement list) also mention item
  // names, so an unscoped `li` search would match the wrong element.
  const allocationPanel = organizerPage
    .getByRole("heading", { name: "What still needs to be purchased" })
    .locator("xpath=..");

  // Glue Sticks: total need 4, Ava owns 2 (self-fulfilled), Ben's 1 donated
  // Glue Stick only partly covers his remaining 2 -> 1 still needs purchasing.
  const glueSection = allocationPanel.locator("li", { hasText: "Glue Sticks" }).first();
  await expect(glueSection.getByText("1 still needs to be purchased")).toBeVisible();
  await expect(glueSection.getByText(/Ava:\s*Already has enough/)).toBeVisible();
  await expect(
    glueSection.getByText(/Ben:\s*Still needs 1 — will be part of the class purchase/)
  ).toBeVisible();

  // Folders: total need 2, Ava owns 1 (self-fulfilled), Ben's 1 donated
  // Folder exactly covers his remaining 1 -> fully covered.
  const foldersSection = allocationPanel.locator("li", { hasText: "Folders" }).first();
  await expect(foldersSection.getByText("Fully covered!")).toBeVisible();
  await expect(foldersSection.getByText(/Ava:\s*Already has enough/)).toBeVisible();
  await expect(foldersSection.getByText(/Ben:\s*Covered by donated supplies/)).toBeVisible();

  // The reconcile action is gone now that the pool has moved past OPEN_FOR_INVENTORY.
  await expect(organizerPage.getByRole("button", { name: "Work out what's needed…" })).toHaveCount(0);

  // Ava's own view: everything self-fulfilled, no mention of Ben or other
  // households. Navigate back to the pool page itself (she's currently on
  // the inventory subpage) rather than just reloading her current URL.
  await goToPool(avaPage);
  await expect(avaPage.getByRole("heading", { name: "Where things stand for your student" })).toBeVisible();
  await expect(avaPage.getByText(/Ava.*Glue Sticks/)).toBeVisible();
  await expect(avaPage.getByText("Already has enough")).toHaveCount(2);
  await expect(avaPage.getByText(/Ben/)).toHaveCount(0);

  // Ben's own view: purchase-required for Glue Sticks, pool-fulfilled for
  // Folders. He's currently on the /contribute subpage.
  await goToPool(benPage);
  await expect(benPage.getByRole("heading", { name: "Where things stand for your student" })).toBeVisible();
  await expect(benPage.getByText(/Still needs 1 — will be part of the class purchase/)).toBeVisible();
  await expect(benPage.getByText("Covered by donated supplies")).toBeVisible();
  await expect(benPage.getByText(/Ava/)).toHaveCount(0);

  await organizerContext.close();
  await avaContext.close();
  await benContext.close();
});
