import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConfirmPoolAction } from "@/components/ConfirmPoolAction";
import { api } from "@/lib/api/client";
import type { PoolDetail } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const confirmedPool: PoolDetail = {
  id: "pool-1",
  classroomId: "classroom-1",
  name: "Fall Supplies",
  poolType: "SUPPLIES",
  state: "OPEN_FOR_INVENTORY",
  requirementCount: 1,
  createdAt: new Date().toISOString(),
  requirements: [
    {
      id: "req-1",
      poolId: "pool-1",
      name: "Glue Stick",
      quantityPerStudent: 4,
      brand: null,
      strictness: "EQUIVALENT_ALLOWED",
      state: "CONFIRMED",
      sourceEvidence: null,
      confidence: null,
      totalDemand: 96,
      createdAt: new Date().toISOString(),
    },
  ],
};

describe("ConfirmPoolAction", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("requires a second, explicit step before actually confirming (one-way action)", async () => {
    const onConfirmed = vi.fn();
    render(
      <ConfirmPoolAction
        poolId="pool-1"
        requirementCount={1}
        onConfirmed={onConfirmed}
      />
    );

    // The consequential action isn't reachable in one click.
    expect(
      screen.queryByRole("button", { name: /yes, confirm and lock/i })
    ).not.toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^confirm list/i }));

    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    const confirmButton = screen.getByRole("button", {
      name: /yes, confirm and lock/i,
    });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    mockedApi.POST.mockResolvedValue({
      data: confirmedPool,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    await user.click(confirmButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/confirm",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );
    await waitFor(() =>
      expect(onConfirmed).toHaveBeenCalledWith(confirmedPool)
    );
  });

  it("disables confirming with zero requirements and shows a clear message on the zero-requirements 409", async () => {
    render(
      <ConfirmPoolAction
        poolId="pool-1"
        requirementCount={0}
        onConfirmed={vi.fn()}
      />
    );

    expect(screen.getByRole("button", { name: /^confirm list/i })).toBeDisabled();
    expect(
      screen.getByText(/add at least one item before you can confirm/i)
    ).toBeInTheDocument();
  });

  it("surfaces the 409 error clearly if confirm is attempted anyway and fails", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(
      <ConfirmPoolAction
        poolId="pool-1"
        requirementCount={1}
        onConfirmed={vi.fn()}
      />
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^confirm list/i }));
    await user.click(
      screen.getByRole("button", { name: /yes, confirm and lock/i })
    );

    expect(
      await screen.findByText(/already been confirmed/i)
    ).toBeInTheDocument();
  });
});
