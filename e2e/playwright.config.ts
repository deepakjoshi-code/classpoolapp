import { defineConfig, devices } from "@playwright/test";

/**
 * Spans both apps/api and apps/web, so it lives at the repo root rather than
 * inside either app — see ARCHITECTURE.md's directory-ownership note.
 *
 * This does NOT start the backend or frontend itself: CI (.github/workflows/ci.yml)
 * boots both (real Postgres/Redis services, the built API jar, `next start`)
 * before invoking this config, because the API's stdout has to be captured to
 * a log file the tests can read magic-link tokens from — see helpers/magic-link.ts.
 * For local runs, start both apps yourself first (see e2e/README.md).
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false, // tests share a database; parallel runs would race on dedup/invite state
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["html", { open: "never" }], ["list"]] : "list",
  use: {
    baseURL: process.env.WEB_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    // Use a pre-installed Chromium build rather than `playwright install`'s
    // download when PLAYWRIGHT_CHROMIUM_PATH is set (e.g. this sandbox — see
    // e2e/README.md). CI installs its own browsers normally and leaves this unset.
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH }
      : {},
  },
  projects: [
    {
      name: "mobile-chrome",
      // Mobile-first PWA (PRD §11) — the primary viewport this product ships for.
      use: { ...devices["Pixel 7"] },
    },
  ],
});
