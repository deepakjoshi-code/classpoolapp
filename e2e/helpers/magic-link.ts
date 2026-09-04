import { readFileSync } from "node:fs";

/**
 * V1 has no real inbox to check in E2E — apps/api's LoggingEmailSender
 * (see apps/api/README.md) logs the magic-link email to stdout instead of
 * sending it. CI redirects that stdout to API_LOG_FILE; this helper tails it
 * for the most recent link emailed to a given address.
 *
 * This is a stopgap for V1, not the long-term answer — once a real email
 * provider (or a test inbox like Mailhog) is wired up for later phases,
 * this should be replaced with actually receiving the email.
 */
export function extractMagicLinkUrl(email: string): string {
  const logFile = process.env.API_LOG_FILE;
  if (!logFile) {
    throw new Error(
      "API_LOG_FILE is not set — E2E needs the backend's stdout captured to a file to read magic-link tokens from. See e2e/README.md."
    );
  }

  const contents = readFileSync(logFile, "utf-8");
  const blocks = contents.split("=== [LoggingEmailSender] Would send email ===");

  // Last block addressed to this email wins — the most recently requested link.
  for (let i = blocks.length - 1; i >= 1; i--) {
    const block = blocks[i];
    if (!block.includes(`To: ${email}`)) continue;

    const match = block.match(/http\S+[?&]token=[\w-]+/);
    if (match) return match[0];
  }

  throw new Error(
    `No magic-link email found for ${email} in ${logFile}. Did the request actually reach the backend?`
  );
}
