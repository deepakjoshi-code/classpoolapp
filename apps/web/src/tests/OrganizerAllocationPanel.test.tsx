import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { OrganizerAllocationPanel } from "@/components/OrganizerAllocationPanel";
import { api } from "@/lib/api/client";
import type { AllocationSummary } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const summary: AllocationSummary = {
  allocations: [
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      studentId: "student-1",
      studentFirstName: "Ava",
      quantityNeeded: 4,
      ownedQuantity: 4,
      poolFulfilledQuantity: 0,
      purchaseRequiredQuantity: 0,
      status: "SELF_FULFILLED",
    },
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      studentId: "student-2",
      studentFirstName: "Ben",
      quantityNeeded: 4,
      ownedQuantity: 2,
      poolFulfilledQuantity: 0,
      purchaseRequiredQuantity: 2,
      status: "PURCHASE_REQUIRED",
    },
  ],
  residualDemand: [
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      totalRequired: 8,
      totalOwned: 6,
      totalPoolFulfilled: 0,
      residualDemand: 2,
    },
  ],
};

describe("OrganizerAllocationPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
  });

  it("fetches the allocation summary and shows the residual-demand line and per-student breakdown, in plain language", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(await screen.findByText("Glue Sticks")).toBeInTheDocument();
    expect(
      screen.getByText(/2 still needs? to be purchased/i)
    ).toBeInTheDocument();

    expect(screen.getByText("Ava")).toBeInTheDocument();
    expect(screen.getByText(/already has enough/i)).toBeInTheDocument();

    expect(screen.getByText("Ben")).toBeInTheDocument();
    expect(
      screen.getByText(/still needs 2 — will be part of the class purchase/i)
    ).toBeInTheDocument();

    // No raw enum values ever rendered.
    expect(screen.queryByText(/SELF_FULFILLED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/PURCHASE_REQUIRED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/POOL_FULFILLED/)).not.toBeInTheDocument();
  });

  it("shows 'fully covered' when residual demand is zero", async () => {
    mockedApi.GET.mockResolvedValue({
      data: {
        allocations: [],
        residualDemand: [
          {
            requirementId: "req-2",
            requirementName: "Notebooks",
            totalRequired: 20,
            totalOwned: 20,
            totalPoolFulfilled: 0,
            residualDemand: 0,
          },
        ],
      } as AllocationSummary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(await screen.findByText("Notebooks")).toBeInTheDocument();
    expect(screen.getByText(/fully covered!/i)).toBeInTheDocument();
  });

  it("handles the not-yet-reconciled 409 gracefully instead of crashing", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/hasn't been worked out yet/i)
    ).toBeInTheDocument();
  });

  it("shows a fallback message on an unexpected error", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "forbidden" },
      response: { status: 403 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/couldn't load the purchase breakdown/i)
    ).toBeInTheDocument();
  });
});
