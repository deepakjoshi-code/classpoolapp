import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PurchasePlanPanel } from "@/components/PurchasePlanPanel";
import { api } from "@/lib/api/client";
import type { PurchasePlan } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const proposedPlan: PurchasePlan = {
  id: "plan-1",
  poolId: "pool-1",
  state: "PROPOSED",
  totalCostCents: 9647,
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
    {
      requirementId: "req-2",
      requirementName: "Notebooks",
      productOfferId: "offer-2",
      retailer: "Target",
      packQuantity: 10,
      packCount: 5,
      totalCostCents: 5000,
      wasteQuantity: 0,
    },
  ],
  proposedAt: new Date().toISOString(),
  approvedAt: null,
};

describe("PurchasePlanPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("renders each line's retailer/pack/cost, the running total, and the plan's state in plain language", async () => {
    mockedApi.GET.mockResolvedValue({
      data: proposedPlan,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<PurchasePlanPanel poolId="pool-1" />);

    expect(await screen.findByText("Glue Stick")).toBeInTheDocument();
    expect(screen.getByText(/2 packs of 24 from amazon/i)).toBeInTheDocument();
    expect(screen.getByText("$46.47")).toBeInTheDocument();

    expect(screen.getByText("Notebooks")).toBeInTheDocument();
    expect(screen.getByText(/5 packs of 10 from target/i)).toBeInTheDocument();
    expect(screen.getByText("$50.00")).toBeInTheDocument();

    // Grand total, formatted as dollars, never raw cents.
    expect(screen.getByText("$96.47")).toBeInTheDocument();
    expect(screen.queryByText(/9647/)).not.toBeInTheDocument();

    // Plain-language plan state, never the raw enum.
    expect(screen.getByText(/waiting for your approval/i)).toBeInTheDocument();
    expect(screen.queryByText(/^PROPOSED$/)).not.toBeInTheDocument();
  });

  it("handles the not-generated 409 gracefully instead of crashing", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(<PurchasePlanPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/hasn't been worked out yet/i)
    ).toBeInTheDocument();
  });

  it("approves the plan after an explicit confirm step, updating local state without a reload", async () => {
    mockedApi.GET.mockResolvedValue({
      data: proposedPlan,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const approvedPlan: PurchasePlan = {
      ...proposedPlan,
      state: "APPROVED",
      approvedAt: new Date().toISOString(),
    };
    mockedApi.POST.mockResolvedValue({
      data: approvedPlan,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<PurchasePlanPanel poolId="pool-1" />);

    await screen.findByText("Glue Stick");

    // Not reachable in a single click.
    expect(
      screen.queryByRole("button", { name: /yes, approve this plan/i })
    ).not.toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: /approve this plan/i }));
    expect(screen.getByText(/commits the class to spending/i)).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: /yes, approve this plan/i })
    );

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/purchase-plan/approve",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );

    expect(await screen.findByText(/^approved$/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /approve this plan/i })
    ).not.toBeInTheDocument();
  });

  it("shows a generic error message if approving fails", async () => {
    mockedApi.GET.mockResolvedValue({
      data: proposedPlan,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "boom" },
      response: { status: 500 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<PurchasePlanPanel poolId="pool-1" />);

    await screen.findByText("Glue Stick");
    await user.click(screen.getByRole("button", { name: /approve this plan/i }));
    await user.click(
      screen.getByRole("button", { name: /yes, approve this plan/i })
    );

    expect(
      await screen.findByText(/couldn't approve this just now/i)
    ).toBeInTheDocument();
  });
});
