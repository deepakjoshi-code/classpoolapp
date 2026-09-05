import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RequirementListItem } from "@/components/RequirementListItem";
import { api } from "@/lib/api/client";
import type { Requirement } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
    PATCH: vi.fn(),
    DELETE: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

function makeRequirement(overrides: Partial<Requirement>): Requirement {
  return {
    id: "req-1",
    poolId: "pool-1",
    name: "Glue Stick",
    quantityPerStudent: 4,
    brand: "Elmer's",
    strictness: "EQUIVALENT_ALLOWED",
    state: "EXTRACTED",
    sourceEvidence: null,
    confidence: null,
    totalDemand: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe("RequirementListItem", () => {
  beforeEach(() => {
    vi.mocked(api).DELETE.mockReset();
  });

  it("shows no AI badge or confidence text for a manual entry (confidence: null)", () => {
    const manual = makeRequirement({ confidence: null, state: "EXTRACTED" });
    render(
      <RequirementListItem
        poolId="pool-1"
        requirement={manual}
        canEdit={true}
        onUpdated={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    expect(screen.queryByText(/confidence/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/AI-extracted/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/needs a closer look/i)).not.toBeInTheDocument();
    expect(
      screen.queryByText(/why was this extracted this way/i)
    ).not.toBeInTheDocument();
  });

  it("shows confidence for an EXTRACTED item, distinct from a NEEDS_REVIEW item, and exposes sourceEvidence", () => {
    const extracted = makeRequirement({
      id: "req-extracted",
      name: "Glue Stick",
      state: "EXTRACTED",
      confidence: 0.92,
      sourceEvidence: "4 glue sticks per student",
    });
    const { unmount } = render(
      <RequirementListItem
        poolId="pool-1"
        requirement={extracted}
        canEdit={true}
        onUpdated={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    expect(screen.getByText(/AI-extracted — 92% confidence/i)).toBeInTheDocument();
    expect(screen.queryByText(/needs a closer look/i)).not.toBeInTheDocument();
    const disclosure = screen.getByText(/why was this extracted this way/i);
    expect(disclosure).toBeInTheDocument();
    // sourceEvidence text is present in the DOM (inside a <details>), i.e.
    // accessible, whether or not it's expanded by default.
    expect(screen.getByText(/4 glue sticks per student/i)).toBeInTheDocument();
    unmount();

    const needsReview = makeRequirement({
      id: "req-needs-review",
      name: "Notebook",
      state: "NEEDS_REVIEW",
      confidence: 0.4,
      sourceEvidence: "a couple notebooks maybe",
    });
    render(
      <RequirementListItem
        poolId="pool-1"
        requirement={needsReview}
        canEdit={true}
        onUpdated={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    expect(
      screen.getByText(/needs a closer look — 40% confidence/i)
    ).toBeInTheDocument();
    expect(screen.queryByText(/^AI-extracted — 40% confidence$/i)).not.toBeInTheDocument();
  });

  it("still shows edit/remove controls for an organizer alongside AI provenance", async () => {
    vi.mocked(api).DELETE.mockResolvedValue({
      data: undefined,
      error: undefined,
      response: { status: 204 } as Response,
    } as any);

    const needsReview = makeRequirement({
      state: "NEEDS_REVIEW",
      confidence: 0.5,
      sourceEvidence: "some text",
    });
    const onRemoved = vi.fn();
    render(
      <RequirementListItem
        poolId="pool-1"
        requirement={needsReview}
        canEdit={true}
        onUpdated={vi.fn()}
        onRemoved={onRemoved}
      />
    );

    expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /remove/i }));
    expect(onRemoved).toHaveBeenCalledWith(needsReview.id);
  });
});
