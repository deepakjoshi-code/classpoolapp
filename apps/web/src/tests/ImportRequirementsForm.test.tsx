import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ImportRequirementsForm } from "@/components/ImportRequirementsForm";
import { api } from "@/lib/api/client";
import type { Requirement, RequirementImportResult } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
    PATCH: vi.fn(),
    DELETE: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

function makeRequirement(overrides: Partial<Requirement>): Requirement {
  return {
    id: "req-1",
    poolId: "pool-1",
    name: "Glue Stick",
    quantityPerStudent: 4,
    brand: null,
    strictness: "EQUIVALENT_ALLOWED",
    state: "EXTRACTED",
    sourceEvidence: "4 glue sticks per student",
    confidence: 0.92,
    totalDemand: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe("ImportRequirementsForm", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("lets the organizer pick a source type in plain language and submits pasted text", async () => {
    const extracted = makeRequirement({ id: "req-1", state: "EXTRACTED" });
    const result: RequirementImportResult = {
      source: {
        id: "src-1",
        poolId: "pool-1",
        sourceType: "PASTED_EMAIL",
        rawText: "4 glue sticks per student",
        extractedRequirementCount: 1,
        createdAt: new Date().toISOString(),
      },
      requirements: [extracted],
    };
    mockedApi.POST.mockResolvedValue({
      data: result,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onImported = vi.fn();
    render(<ImportRequirementsForm poolId="pool-1" onImported={onImported} />);

    const select = screen.getByLabelText(/where is this from/i);
    expect(select.tagName).toBe("SELECT");
    expect(
      screen.getByRole("option", { name: /forwarded email/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /school portal text/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /pasted message/i })
    ).toBeInTheDocument();
    // Plain language, never the raw enum values.
    expect(screen.queryByText(/^PASTED_EMAIL$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^PASTED_PORTAL$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^PASTED_MESSAGE$/)).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(select, "PASTED_PORTAL");
    await user.type(
      screen.getByLabelText(/paste the text here/i),
      "4 glue sticks per student"
    );
    await user.click(
      screen.getByRole("button", { name: /import items from this text/i })
    );

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/requirement-sources",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: {
          sourceType: "PASTED_PORTAL",
          rawText: "4 glue sticks per student",
        },
      })
    );
    await waitFor(() => expect(onImported).toHaveBeenCalledWith([extracted]));
  });

  it("shows a distinct summary count for ready-to-review vs needs-a-closer-look items", async () => {
    const ready = makeRequirement({ id: "req-1", state: "EXTRACTED" });
    const needsReview1 = makeRequirement({
      id: "req-2",
      state: "NEEDS_REVIEW",
      confidence: 0.4,
    });
    const needsReview2 = makeRequirement({
      id: "req-3",
      state: "NEEDS_REVIEW",
      confidence: 0.5,
    });
    const result: RequirementImportResult = {
      source: {
        id: "src-1",
        poolId: "pool-1",
        sourceType: "PASTED_EMAIL",
        rawText: "text",
        extractedRequirementCount: 3,
        createdAt: new Date().toISOString(),
      },
      requirements: [ready, needsReview1, needsReview2],
    };
    mockedApi.POST.mockResolvedValue({
      data: result,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onImported = vi.fn();
    render(<ImportRequirementsForm poolId="pool-1" onImported={onImported} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/paste the text here/i), "some text");
    await user.click(
      screen.getByRole("button", { name: /import items from this text/i })
    );

    const summary = await screen.findByRole("status");
    expect(summary).toHaveTextContent(/1 item found, ready to review/i);
    expect(summary).toHaveTextContent(
      /2 more items need a closer look before you can confirm the list/i
    );
    expect(onImported).toHaveBeenCalledWith([ready, needsReview1, needsReview2]);
  });

  it("shows a specific message when the pool is no longer DRAFT (409)", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(<ImportRequirementsForm poolId="pool-1" onImported={vi.fn()} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/paste the text here/i), "some text");
    await user.click(
      screen.getByRole("button", { name: /import items from this text/i })
    );

    expect(
      await screen.findByText(/locked in.*moved past the draft stage/i)
    ).toBeInTheDocument();
  });

  it("shows a generic error message on a non-409 failure and does not call onImported", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "boom" },
      response: { status: 500 } as Response,
    } as any);

    const onImported = vi.fn();
    render(<ImportRequirementsForm poolId="pool-1" onImported={onImported} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/paste the text here/i), "some text");
    await user.click(
      screen.getByRole("button", { name: /import items from this text/i })
    );

    expect(
      await screen.findByText(/couldn't read that text just now/i)
    ).toBeInTheDocument();
    expect(onImported).not.toHaveBeenCalled();
  });

  it("keeps the submit button disabled until text has been pasted", () => {
    render(<ImportRequirementsForm poolId="pool-1" onImported={vi.fn()} />);
    expect(
      screen.getByRole("button", { name: /import items from this text/i })
    ).toBeDisabled();
  });
});
