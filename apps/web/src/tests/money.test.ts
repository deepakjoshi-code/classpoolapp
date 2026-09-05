import { describe, expect, it } from "vitest";
import { dollarsToCents, formatCents } from "@/lib/money";

describe("money helpers", () => {
  it("formats integer cents as a dollar string", () => {
    expect(formatCents(4647)).toBe("$46.47");
    expect(formatCents(0)).toBe("$0.00");
    expect(formatCents(100)).toBe("$1.00");
    expect(formatCents(499)).toBe("$4.99");
  });

  it("parses a dollar input string into integer cents, rounding away float drift", () => {
    expect(dollarsToCents("46.47")).toBe(4647);
    expect(dollarsToCents("4.99")).toBe(499);
    expect(dollarsToCents("0.10")).toBe(10);
    expect(dollarsToCents("5")).toBe(500);
  });

  it("round-trips a dollar amount through cents and back", () => {
    const inputs = ["46.47", "4.99", "0.01", "1200.00", "3.50"];
    for (const dollars of inputs) {
      const cents = dollarsToCents(dollars);
      expect(formatCents(cents)).toBe(
        (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" })
      );
      // The round trip lands back on the same number of cents.
      expect(dollarsToCents((cents / 100).toString())).toBe(cents);
    }
  });

  it("returns NaN for unparseable input rather than silently defaulting to 0", () => {
    expect(Number.isNaN(dollarsToCents(""))).toBe(true);
    expect(Number.isNaN(dollarsToCents("not a number"))).toBe(true);
  });
});
