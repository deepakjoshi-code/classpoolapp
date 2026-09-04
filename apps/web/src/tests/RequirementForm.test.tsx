import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RequirementForm } from "@/components/RequirementForm";
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

const mockedApi = vi.mocked(api);

const baseRequirement: Requirement = {
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
};

describe("RequirementForm", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
    mockedApi.PATCH.mockReset();
  });

  it("renders the item fields and the three strictness modes in plain language, then submits an add", async () => {
    mockedApi.POST.mockResolvedValue({
      data: baseRequirement,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onSaved = vi.fn();
    render(<RequirementForm poolId="pool-1" onSaved={onSaved} />);

    expect(screen.getByLabelText(/^item$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/quantity per student/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/brand/i)).toBeInTheDocument();

    const strictnessSelect = screen.getByLabelText(/how strict is this item/i);
    expect(strictnessSelect).toBeInTheDocument();
    expect(strictnessSelect.tagName).toBe("SELECT");

    // Plain-language copy, not the raw enum names (PRD §3.3's three modes).
    expect(
      screen.getByRole("option", { name: /must match exactly/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /any equivalent brand or type is fine/i })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /any item that fits the description works/i })
    ).toBeInTheDocument();
    expect(screen.queryByText(/^EXACT$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^EQUIVALENT_ALLOWED$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^GENERIC$/)).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^item$/i), "Glue Stick");
    await user.type(screen.getByLabelText(/quantity per student/i), "4");
    await user.type(screen.getByLabelText(/brand/i), "Elmer's");
    await user.click(screen.getByRole("button", { name: /add item/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: expect.objectContaining({
          name: "Glue Stick",
          quantityPerStudent: 4,
          brand: "Elmer's",
          strictness: "EQUIVALENT_ALLOWED",
        }),
      })
    );
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(baseRequirement));
  });

  it("shows a clear, specific message when the pool is no longer DRAFT (409)", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(<RequirementForm poolId="pool-1" onSaved={vi.fn()} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^item$/i), "Glue Stick");
    await user.type(screen.getByLabelText(/quantity per student/i), "4");
    await user.click(screen.getByRole("button", { name: /add item/i }));

    expect(
      await screen.findByText(/locked in.*moved past the draft stage/i)
    ).toBeInTheDocument();
  });

  it("edits an existing requirement via PATCH, pre-filled with its current values", async () => {
    const updated: Requirement = { ...baseRequirement, quantityPerStudent: 6 };
    mockedApi.PATCH.mockResolvedValue({
      data: updated,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const onSaved = vi.fn();
    render(
      <RequirementForm
        poolId="pool-1"
        requirement={baseRequirement}
        onSaved={onSaved}
      />
    );

    expect(screen.getByLabelText(/^item$/i)).toHaveValue("Glue Stick");
    expect(screen.getByLabelText(/quantity per student/i)).toHaveValue(4);

    const user = userEvent.setup();
    const qtyInput = screen.getByLabelText(/quantity per student/i);
    await user.clear(qtyInput);
    await user.type(qtyInput, "6");
    await user.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(mockedApi.PATCH).toHaveBeenCalledTimes(1));
    expect(mockedApi.PATCH).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements/{requirementId}",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", requirementId: "req-1" } },
        body: expect.objectContaining({ quantityPerStudent: 6 }),
      })
    );
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(updated));
  });
});
