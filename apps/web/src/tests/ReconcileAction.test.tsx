import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReconcileAction } from "@/components/ReconcileAction";
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
      requirementName: "Glue Stick",
      studentId: "student-1",
      studentFirstName: "Ava",
      quantityNeeded: 4,
      ownedQuantity: 4,
      poolFulfilledQuantity: 0,
      purchaseRequiredQuantity: 0,
      status: "SELF_FULFILLED",
    },
  ],
  residualDemand: [
    {
      requirementId: "req-1",
      requirementName: "Glue Stick",
      totalRequired: 96,
      totalOwned: 96,
      totalPoolFulfilled: 0,
      residualDemand: 0,
    },
  ],
};

describe("ReconcileAction", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("requires a second, explicit step before actually running (one-way action)", async () => {
    const onReconciled = vi.fn();
    render(<ReconcileAction poolId="pool-1" onReconciled={onReconciled} />);

    // The consequential action isn't reachable in one click.
    expect(
      screen.queryByRole("button", { name: /yes, work it out/i })
    ).not.toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.click(
      screen.getByRole("button", { name: /work out what's needed/i })
    );

    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    const runButton = screen.getByRole("button", { name: /yes, work it out/i });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    mockedApi.POST.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    await user.click(runButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/reconcile",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );
    await waitFor(() => expect(onReconciled).toHaveBeenCalledWith(summary));
  });

  it("lets a cancel on the confirming step back out without calling the API", async () => {
    const onReconciled = vi.fn();
    const user = userEvent.setup();
    render(<ReconcileAction poolId="pool-1" onReconciled={onReconciled} />);

    await user.click(
      screen.getByRole("button", { name: /work out what's needed/i })
    );
    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(
      screen.getByRole("button", { name: /work out what's needed/i })
    ).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
    expect(onReconciled).not.toHaveBeenCalled();
  });

  it("shows a clear message on the already-reconciled 409, and doesn't call onReconciled", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const onReconciled = vi.fn();
    const user = userEvent.setup();
    render(<ReconcileAction poolId="pool-1" onReconciled={onReconciled} />);

    await user.click(
      screen.getByRole("button", { name: /work out what's needed/i })
    );
    await user.click(screen.getByRole("button", { name: /yes, work it out/i }));

    expect(
      await screen.findByText(/already been done for this pool/i)
    ).toBeInTheDocument();
    expect(onReconciled).not.toHaveBeenCalled();
  });

  it("shows a generic retry message on a non-409 failure", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "boom" },
      response: { status: 500 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<ReconcileAction poolId="pool-1" onReconciled={vi.fn()} />);

    await user.click(
      screen.getByRole("button", { name: /work out what's needed/i })
    );
    await user.click(screen.getByRole("button", { name: /yes, work it out/i }));

    expect(
      await screen.findByText(/couldn't do this just now/i)
    ).toBeInTheDocument();
  });
});
