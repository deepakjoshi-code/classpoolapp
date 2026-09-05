import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GenerateDistributionAction } from "@/components/GenerateDistributionAction";
import { api } from "@/lib/api/client";
import type { DistributionSummary, Order } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const recordedOrder: Order = {
  id: "order-1",
  poolId: "pool-1",
  orderedBy: "user-1",
  orderedAt: new Date().toISOString(),
  receiptS3Key: null,
  lines: [],
};

const summary: DistributionSummary = {
  id: "dist-1",
  poolId: "pool-1",
  mode: "HOUSEHOLD_BAG",
  createdAt: new Date().toISOString(),
  items: [],
  pickLists: [],
};

function mockGetOrder(response: any) {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}/order") return Promise.resolve(response) as any;
    throw new Error(`Unexpected GET: ${path}`);
  });
}

describe("GenerateDistributionAction", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("shows a specific reason instead of a dead button when no order has been recorded yet", async () => {
    mockGetOrder({ data: undefined, error: { message: "none" }, response: { status: 409 } });

    render(<GenerateDistributionAction poolId="pool-1" onGenerated={vi.fn()} />);

    expect(await screen.findByText(/record the order first/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /set up distribution/i })
    ).not.toBeInTheDocument();
  });

  it("requires picking a mode and passing through the two-step confirm before POSTing", async () => {
    mockGetOrder({ data: recordedOrder, error: undefined, response: { status: 200 } });
    mockedApi.POST.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const onGenerated = vi.fn();
    const user = userEvent.setup();
    render(<GenerateDistributionAction poolId="pool-1" onGenerated={onGenerated} />);

    await screen.findByText(/how will items be handed off/i);
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(screen.getByLabelText(/household bags/i));
    await user.click(screen.getByRole("button", { name: /set up distribution/i }));

    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: /yes, set up distribution/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/distribution/generate",
      expect.objectContaining({
        params: { path: { poolId: "pool-1" } },
        body: { mode: "HOUSEHOLD_BAG" },
      })
    );
    await waitFor(() => expect(onGenerated).toHaveBeenCalledWith(summary));
  });

  it("re-checks the precondition when refreshKey bumps (RecordOrderAction just recorded one)", async () => {
    mockedApi.GET.mockResolvedValueOnce({
      data: undefined,
      error: { message: "none" },
      response: { status: 409 },
    } as any);

    const { rerender } = render(
      <GenerateDistributionAction poolId="pool-1" onGenerated={vi.fn()} refreshKey={0} />
    );

    expect(await screen.findByText(/record the order first/i)).toBeInTheDocument();

    mockedApi.GET.mockResolvedValueOnce({
      data: recordedOrder,
      error: undefined,
      response: { status: 200 },
    } as any);

    rerender(
      <GenerateDistributionAction poolId="pool-1" onGenerated={vi.fn()} refreshKey={1} />
    );

    expect(
      await screen.findByText(/how will items be handed off/i)
    ).toBeInTheDocument();
  });

  it("cancelling the confirm step backs out without calling the API", async () => {
    mockGetOrder({ data: recordedOrder, error: undefined, response: { status: 200 } });

    const user = userEvent.setup();
    render(<GenerateDistributionAction poolId="pool-1" onGenerated={vi.fn()} />);

    await user.click(
      await screen.findByRole("button", { name: /set up distribution/i })
    );
    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(
      screen.getByRole("button", { name: /set up distribution/i })
    ).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
  });
});
