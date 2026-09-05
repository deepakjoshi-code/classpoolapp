import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RecordOrderAction } from "@/components/RecordOrderAction";
import { api } from "@/lib/api/client";
import type { Order, PurchasePlan } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const plan: PurchasePlan = {
  id: "plan-1",
  poolId: "pool-1",
  state: "APPROVED",
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
  approvedAt: new Date().toISOString(),
};

const recordedOrder: Order = {
  id: "order-1",
  poolId: "pool-1",
  orderedBy: "user-1",
  orderedAt: new Date().toISOString(),
  receiptS3Key: null,
  lines: [
    {
      id: "line-1",
      purchasePlanLineId: "req-1",
      requirementId: "req-1",
      requirementName: "Glue Stick",
      plannedCostCents: 4647,
      actualCostCents: 4700,
      actualDescription: null,
      substitutionDeltaCents: 53,
      substitutionResolution: "ABSORBED",
    },
    {
      id: "line-2",
      purchasePlanLineId: "req-2",
      requirementId: "req-2",
      requirementName: "Notebooks",
      plannedCostCents: 5000,
      actualCostCents: 5800,
      actualDescription: "Different brand notebooks",
      substitutionDeltaCents: 800,
      substitutionResolution: "TOP_UP_CHARGED",
    },
  ],
};

function mockNotYetRecorded() {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}/purchase-plan")
      return Promise.resolve({ data: plan, error: undefined, response: { status: 200 } }) as any;
    if (path === "/pools/{poolId}/order")
      return Promise.resolve({
        data: undefined,
        error: { message: "not recorded" },
        response: { status: 409 },
      }) as any;
    throw new Error(`Unexpected GET: ${path}`);
  });
}

function mockAlreadyRecorded() {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}/purchase-plan")
      return Promise.resolve({ data: plan, error: undefined, response: { status: 200 } }) as any;
    if (path === "/pools/{poolId}/order")
      return Promise.resolve({
        data: recordedOrder,
        error: undefined,
        response: { status: 200 },
      }) as any;
    throw new Error(`Unexpected GET: ${path}`);
  });
}

describe("RecordOrderAction", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("records the order with no line overrides via the primary confirm path", async () => {
    mockNotYetRecorded();
    mockedApi.POST.mockResolvedValue({
      data: recordedOrder,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onRecorded = vi.fn();
    const user = userEvent.setup();
    render(<RecordOrderAction poolId="pool-1" onRecorded={onRecorded} />);

    const primaryButton = await screen.findByRole("button", {
      name: /yes, i bought this — nothing was substituted/i,
    });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(primaryButton);
    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /yes, record this order/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/order",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: {},
      })
    );
    await waitFor(() => expect(onRecorded).toHaveBeenCalledWith(recordedOrder));
  });

  it("lets the organizer edit a specific line's actual cost/description before submitting", async () => {
    mockNotYetRecorded();
    mockedApi.POST.mockResolvedValue({
      data: recordedOrder,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<RecordOrderAction poolId="pool-1" onRecorded={vi.fn()} />);

    await user.click(
      await screen.findByRole("button", {
        name: /something was different\? edit specific items first/i,
      })
    );

    // There are two lines — grab the second one's inputs explicitly.
    const costInputs = screen.getAllByLabelText(/actual cost/i);
    const descInputs = screen.getAllByLabelText(/what you actually bought/i);
    await user.type(costInputs[1]!, "58.00");
    await user.type(descInputs[1]!, "Different brand notebooks");

    await user.click(screen.getByRole("button", { name: /record with these changes/i }));
    await user.click(screen.getByRole("button", { name: /yes, record this order/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/order",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: {
          lines: [
            {
              purchasePlanLineId: "req-2",
              actualCostCents: 5800,
              actualDescription: "Different brand notebooks",
            },
          ],
        },
      })
    );
  });

  it("shows the already-recorded order with plain-language ABSORBED vs TOP_UP_CHARGED wording, never raw enum values", async () => {
    mockAlreadyRecorded();

    render(<RecordOrderAction poolId="pool-1" onRecorded={vi.fn()} />);

    expect(await screen.findByText(/order recorded/i)).toBeInTheDocument();
    expect(
      screen.getByText(/small enough to just absorb/i)
    ).toBeInTheDocument();
    expect(
      screen.getByText(/extra charge has been added for the families/i)
    ).toBeInTheDocument();
    expect(screen.queryByText(/ABSORBED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/TOP_UP_CHARGED/)).not.toBeInTheDocument();
  });

  it("shows a generic-conflict message on a 409 when submitting", async () => {
    mockNotYetRecorded();
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<RecordOrderAction poolId="pool-1" onRecorded={vi.fn()} />);

    await user.click(
      await screen.findByRole("button", {
        name: /yes, i bought this — nothing was substituted/i,
      })
    );
    await user.click(screen.getByRole("button", { name: /yes, record this order/i }));

    expect(
      await screen.findByText(/order may already have been recorded/i)
    ).toBeInTheDocument();
  });
});
