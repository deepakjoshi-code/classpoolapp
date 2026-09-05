import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GeneratePurchasePlanAction } from "@/components/GeneratePurchasePlanAction";
import { api } from "@/lib/api/client";
import type { PurchasePlan } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const plan: PurchasePlan = {
  id: "plan-1",
  poolId: "pool-1",
  state: "PROPOSED",
  totalCostCents: 4647,
  lines: [
    {
      requirementId: "req-1",
      requirementName: "Glue Stick",
      productOfferId: "offer-1",
      retailer: "Amazon",
      packQuantity: 24,
      packCount: 2,
      totalCostCents: 4647,
      wasteQuantity: 3,
    },
  ],
  proposedAt: new Date().toISOString(),
  approvedAt: null,
};

describe("GeneratePurchasePlanAction", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("requires a second, explicit step before actually running (one-way action)", async () => {
    const onGenerated = vi.fn();
    render(
      <GeneratePurchasePlanAction poolId="pool-1" onGenerated={onGenerated} />
    );

    expect(
      screen.queryByRole("button", { name: /yes, work it out/i })
    ).not.toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.click(
      screen.getByRole("button", { name: /work out the purchase plan/i })
    );

    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    const runButton = screen.getByRole("button", { name: /yes, work it out/i });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    mockedApi.POST.mockResolvedValue({
      data: plan,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    await user.click(runButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/purchase-plan/generate",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );
    await waitFor(() => expect(onGenerated).toHaveBeenCalledWith(plan));
  });

  it("lets a cancel on the confirming step back out without calling the API", async () => {
    const onGenerated = vi.fn();
    const user = userEvent.setup();
    render(
      <GeneratePurchasePlanAction poolId="pool-1" onGenerated={onGenerated} />
    );

    await user.click(
      screen.getByRole("button", { name: /work out the purchase plan/i })
    );
    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(
      screen.getByRole("button", { name: /work out the purchase plan/i })
    ).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
    expect(onGenerated).not.toHaveBeenCalled();
  });

  it("shows the specific missing-price-option message on a 409, not the generic message", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const onGenerated = vi.fn();
    const user = userEvent.setup();
    render(
      <GeneratePurchasePlanAction poolId="pool-1" onGenerated={onGenerated} />
    );

    await user.click(
      screen.getByRole("button", { name: /work out the purchase plan/i })
    );
    await user.click(screen.getByRole("button", { name: /yes, work it out/i }));

    expect(
      await screen.findByText(
        /every item that still needs buying needs at least one price option first/i
      )
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/couldn't do this just now/i)
    ).not.toBeInTheDocument();
    expect(onGenerated).not.toHaveBeenCalled();
  });

  it("shows a generic retry message on a non-409 failure, distinct from the 409 message", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "boom" },
      response: { status: 500 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<GeneratePurchasePlanAction poolId="pool-1" onGenerated={vi.fn()} />);

    await user.click(
      screen.getByRole("button", { name: /work out the purchase plan/i })
    );
    await user.click(screen.getByRole("button", { name: /yes, work it out/i }));

    expect(
      await screen.findByText(/couldn't do this just now/i)
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/at least one price option first/i)
    ).not.toBeInTheDocument();
  });
});
