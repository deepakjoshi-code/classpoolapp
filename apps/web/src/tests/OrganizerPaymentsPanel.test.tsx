import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrganizerPaymentsPanel } from "@/components/OrganizerPaymentsPanel";
import { api } from "@/lib/api/client";
import type { Payment } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

function payment(overrides: Partial<Payment>): Payment {
  return {
    id: "pay-1",
    poolId: "pool-1",
    householdId: "household-1",
    householdDisplayName: "The Patels",
    amountCents: 1250,
    method: null,
    state: "PENDING",
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe("OrganizerPaymentsPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("fetches and renders every household's payment, identity + amount + plain-language state", async () => {
    const pending = payment({ id: "pay-1", householdDisplayName: "The Patels", amountCents: 1250 });
    mockedApi.GET.mockResolvedValue({
      data: [pending],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="PAYMENT_OPEN" />);

    expect(await screen.findByText(/the patels/i)).toBeInTheDocument();
    expect(screen.getByText("$12.50")).toBeInTheDocument();
    expect(screen.getByText(/payment due/i)).toBeInTheDocument();
    expect(screen.queryByText(/^PENDING$/)).not.toBeInTheDocument();

    await waitFor(() =>
      expect(mockedApi.GET).toHaveBeenCalledWith(
        "/pools/{poolId}/payments",
        expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
      )
    );
  });

  it("shows Mark cash pending only on a PENDING row, and calls the endpoint", async () => {
    const pending = payment({ id: "pay-1", state: "PENDING" });
    mockedApi.GET.mockResolvedValue({
      data: [pending],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...pending, state: "PENDING_CASH" },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="PAYMENT_OPEN" />);

    const button = await screen.findByRole("button", { name: /mark cash pending/i });
    await user.click(button);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/{paymentId}/mark-cash-pending",
      expect.objectContaining({ params: { path: { poolId: "pool-1", paymentId: "pay-1" } } })
    );

    // Row flips to PENDING_CASH: "mark cash pending" disappears, "mark cash
    // received" appears in its place.
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: /mark cash pending/i })).not.toBeInTheDocument()
    );
    expect(screen.getByRole("button", { name: /mark cash received/i })).toBeInTheDocument();
  });

  it("shows Mark cash received only on a PENDING_CASH row, and calls the endpoint", async () => {
    const cashPending = payment({ id: "pay-1", state: "PENDING_CASH" });
    mockedApi.GET.mockResolvedValue({
      data: [cashPending],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...cashPending, state: "PAID_CASH_RECEIVED" },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="PAYMENT_OPEN" />);

    await user.click(await screen.findByRole("button", { name: /mark cash received/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/{paymentId}/mark-cash-received",
      expect.objectContaining({ params: { path: { poolId: "pool-1", paymentId: "pay-1" } } })
    );
  });

  it("shows a refund button only for PAID/PAID_CASH_RECEIVED rows, never for PENDING/REFUNDED/etc, and calls the endpoint", async () => {
    const paid = payment({ id: "pay-1", state: "PAID" });
    const refunded = payment({ id: "pay-2", state: "REFUNDED" });
    const pending = payment({ id: "pay-3", state: "PENDING" });
    mockedApi.GET.mockResolvedValue({
      data: [paid, refunded, pending],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...paid, state: "REFUNDED" },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="PAYMENT_OPEN" />);

    await screen.findByText(/payment due/i);
    const refundButtons = screen.getAllByRole("button", { name: /^refund$/i });
    expect(refundButtons).toHaveLength(1);

    await user.click(refundButtons[0]!);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/{paymentId}/refund",
      expect.objectContaining({ params: { path: { poolId: "pool-1", paymentId: "pay-1" } } })
    );
  });

  it("never shows a refund button once the pool has reached ORDERED, even for a PAID row", async () => {
    const paid = payment({ id: "pay-1", state: "PAID" });
    mockedApi.GET.mockResolvedValue({
      data: [paid],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="ORDERED" />);

    await screen.findByText(/the patels/i);
    expect(screen.queryByRole("button", { name: /^refund$/i })).not.toBeInTheDocument();
  });

  it("shows a fallback message if payments can't be loaded", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "forbidden" },
      response: { status: 403 } as Response,
    } as any);

    render(<OrganizerPaymentsPanel poolId="pool-1" poolState="PAYMENT_OPEN" />);

    expect(await screen.findByText(/couldn't load payments/i)).toBeInTheDocument();
  });
});
