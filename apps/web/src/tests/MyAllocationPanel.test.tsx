import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MyAllocationPanel } from "@/components/MyAllocationPanel";
import { api } from "@/lib/api/client";
import type { AllocationLine } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

// Unlike Phase 5's Contribution.studentId (always null), AllocationLine's
// studentId/studentFirstName are real per-student values here.
const myLines: AllocationLine[] = [
  {
    requirementId: "req-1",
    requirementName: "Glue Sticks",
    studentId: "student-1",
    studentFirstName: "Emma",
    quantityNeeded: 4,
    ownedQuantity: 4,
    poolFulfilledQuantity: 0,
    purchaseRequiredQuantity: 0,
    status: "SELF_FULFILLED",
  },
  {
    requirementId: "req-2",
    requirementName: "Notebooks",
    studentId: "student-1",
    studentFirstName: "Emma",
    quantityNeeded: 3,
    ownedQuantity: 0,
    poolFulfilledQuantity: 1,
    purchaseRequiredQuantity: 0,
    status: "POOL_FULFILLED",
  },
  {
    requirementId: "req-3",
    requirementName: "Crayons",
    studentId: "student-1",
    studentFirstName: "Emma",
    quantityNeeded: 2,
    ownedQuantity: 0,
    poolFulfilledQuantity: 0,
    purchaseRequiredQuantity: 1,
    status: "PURCHASE_REQUIRED",
  },
];

describe("MyAllocationPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
  });

  it("fetches the caller's own allocation and renders plain-language status per (requirement, student), never a raw enum value", async () => {
    mockedApi.GET.mockResolvedValue({
      data: myLines,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<MyAllocationPanel poolId="pool-1" />);

    expect(await screen.findByText("Glue Sticks")).toBeInTheDocument();

    await waitFor(() =>
      expect(mockedApi.GET).toHaveBeenCalledWith(
        "/pools/{poolId}/allocation/mine",
        expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
      )
    );
    expect(screen.getByText("Notebooks")).toBeInTheDocument();
    expect(screen.getByText("Crayons")).toBeInTheDocument();

    // The three student-facing statuses read as plain language.
    expect(screen.getByText(/already has enough/i)).toBeInTheDocument();
    expect(screen.getByText(/covered by donated supplies/i)).toBeInTheDocument();
    expect(
      screen.getByText(/still needs 1 — will be part of the class purchase/i)
    ).toBeInTheDocument();

    // Never a raw enum value anywhere in rendered output.
    expect(screen.queryByText(/SELF_FULFILLED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/POOL_FULFILLED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/PURCHASE_REQUIRED/)).not.toBeInTheDocument();

    // Only this household's own student ever appears — no other name.
    expect(screen.getAllByText("Emma").length).toBeGreaterThan(0);
  });

  it("shows an empty-state message rather than an error when reconcile hasn't run yet (empty array)", async () => {
    mockedApi.GET.mockResolvedValue({
      data: [],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<MyAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/nothing to show here yet/i)
    ).toBeInTheDocument();
  });

  it("shows a fallback message on a fetch error", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "boom" },
      response: { status: 500 } as Response,
    } as any);

    render(<MyAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/couldn't load this just now/i)
    ).toBeInTheDocument();
  });
});
