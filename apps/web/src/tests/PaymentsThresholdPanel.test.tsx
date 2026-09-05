import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PaymentsThresholdPanel } from "@/components/PaymentsThresholdPanel";
import { api } from "@/lib/api/client";
import type { PaymentsSummary, PoolDetail } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const belowThreshold: PaymentsSummary = {
  totalOwedCents: 10000,
  totalCollectedCents: 5000,
  percentCollected: 50,
  thresholdPercent: 90,
  meetsThreshold: false,
  outstandingHouseholds: [
    { householdId: "h-1", householdDisplayName: "The Ngs", amountCents: 3000 },
    { householdId: "h-2", householdDisplayName: "The Ortiz Family", amountCents: 2000 },
  ],
};

const meetsThreshold: PaymentsSummary = {
  totalOwedCents: 10000,
  totalCollectedCents: 9500,
  percentCollected: 95,
  thresholdPercent: 90,
  meetsThreshold: true,
  outstandingHouseholds: [
    { householdId: "h-3", householdDisplayName: "The Diaz Family", amountCents: 500 },
  ],
};

const finalizedPool: PoolDetail = {
  id: "pool-1",
  classroomId: "classroom-1",
  name: "Fall Supplies",
  poolType: "SUPPLIES",
  state: "ORDERED",
  requirementCount: 0,
  createdAt: new Date().toISOString(),
  requirements: [],
};

describe("PaymentsThresholdPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("shows the risk banner naming outstanding households when below threshold", async () => {
    mockedApi.GET.mockResolvedValue({
      data: belowThreshold,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(
      <PaymentsThresholdPanel poolId="pool-1" poolState="PAYMENT_OPEN" onFinalized={vi.fn()} />
    );

    expect(await screen.findByText(/below the 90% collection target/i)).toBeInTheDocument();
    expect(screen.getByText(/the ngs/i)).toBeInTheDocument();
    expect(screen.getByText(/the ortiz family/i)).toBeInTheDocument();
  });

  it("does not show the risk banner once at/above threshold", async () => {
    mockedApi.GET.mockResolvedValue({
      data: meetsThreshold,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(
      <PaymentsThresholdPanel poolId="pool-1" poolState="PAYMENT_OPEN" onFinalized={vi.fn()} />
    );

    await screen.findByText(/95% collected/i);
    expect(
      screen.queryByText(/below the 90% collection target/i)
    ).not.toBeInTheDocument();
  });

  it("below threshold: finalize requires ticking an explicit acknowledgment before it can be confirmed, and sends acknowledgeBelowThreshold: true", async () => {
    mockedApi.GET.mockResolvedValue({
      data: belowThreshold,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: finalizedPool,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const onFinalized = vi.fn();
    const user = userEvent.setup();
    render(
      <PaymentsThresholdPanel poolId="pool-1" poolState="PAYMENT_OPEN" onFinalized={onFinalized} />
    );

    await user.click(
      await screen.findByRole("button", { name: /finalize payment and proceed/i })
    );

    const confirmButton = screen.getByRole("button", {
      name: /yes, finalize below threshold/i,
    });
    // Disabled until the checkbox is ticked.
    expect(confirmButton).toBeDisabled();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(screen.getByRole("checkbox"));
    expect(confirmButton).toBeEnabled();

    await user.click(confirmButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/finalize",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: { acknowledgeBelowThreshold: true },
      })
    );
    await waitFor(() => expect(onFinalized).toHaveBeenCalledWith(finalizedPool));
  });

  it("at/above threshold: a normal one-step confirm (no checkbox) finalizes without the acknowledgment flag", async () => {
    mockedApi.GET.mockResolvedValue({
      data: meetsThreshold,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: finalizedPool,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const onFinalized = vi.fn();
    const user = userEvent.setup();
    render(
      <PaymentsThresholdPanel poolId="pool-1" poolState="PAYMENT_OPEN" onFinalized={onFinalized} />
    );

    await user.click(
      await screen.findByRole("button", { name: /finalize payment and proceed/i })
    );

    // No checkbox gate for the at/above-threshold path.
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /yes, finalize$/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/finalize",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: { acknowledgeBelowThreshold: false },
      })
    );
    await waitFor(() => expect(onFinalized).toHaveBeenCalledWith(finalizedPool));
  });

  it("hides the finalize action once the pool has already moved past PAYMENT_OPEN", async () => {
    mockedApi.GET.mockResolvedValue({
      data: meetsThreshold,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(
      <PaymentsThresholdPanel poolId="pool-1" poolState="ORDERED" onFinalized={vi.fn()} />
    );

    await screen.findByText(/95% collected/i);
    expect(
      screen.queryByRole("button", { name: /finalize payment and proceed/i })
    ).not.toBeInTheDocument();
    expect(screen.getByText(/has been finalized/i)).toBeInTheDocument();
  });
});
