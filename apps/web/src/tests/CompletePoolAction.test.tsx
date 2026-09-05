import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CompletePoolAction } from "@/components/CompletePoolAction";
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

const completedPool: PoolDetail = {
  id: "pool-1",
  classroomId: "classroom-1",
  name: "Fall Supplies",
  poolType: "SUPPLIES",
  state: "COMPLETED",
  requirementCount: 0,
  createdAt: new Date().toISOString(),
  requirements: [],
};

describe("CompletePoolAction", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("is unreachable without first passing through the 'this can't be undone' step", async () => {
    const onCompleted = vi.fn();
    const user = userEvent.setup();
    render(
      <CompletePoolAction poolId="pool-1" poolState="DISTRIBUTING" onCompleted={onCompleted} />
    );

    const startButton = screen.getByRole("button", { name: /finish this pool/i });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(startButton);
    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();

    mockedApi.POST.mockResolvedValue({
      data: completedPool,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    await user.click(screen.getByRole("button", { name: /yes, finish this pool/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/complete",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );
    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(completedPool));
  });

  it("cancelling backs out without calling the API", async () => {
    const user = userEvent.setup();
    render(
      <CompletePoolAction poolId="pool-1" poolState="DISTRIBUTING" onCompleted={vi.fn()} />
    );

    await user.click(screen.getByRole("button", { name: /finish this pool/i }));
    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(screen.getByRole("button", { name: /finish this pool/i })).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
  });

  it("shows a warm closing message with no action once the pool is already COMPLETED", () => {
    render(
      <CompletePoolAction poolId="pool-1" poolState="COMPLETED" onCompleted={vi.fn()} />
    );

    expect(screen.getByText(/this pool is complete/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /finish this pool/i })
    ).not.toBeInTheDocument();
  });

  it("shows a generic-conflict message on a 409", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <CompletePoolAction poolId="pool-1" poolState="DISTRIBUTING" onCompleted={vi.fn()} />
    );

    await user.click(screen.getByRole("button", { name: /finish this pool/i }));
    await user.click(screen.getByRole("button", { name: /yes, finish this pool/i }));

    expect(
      await screen.findByText(/pool may already be complete/i)
    ).toBeInTheDocument();
  });
});
