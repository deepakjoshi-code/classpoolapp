import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SavingsSummaryCard } from "@/components/SavingsSummaryCard";
import { api } from "@/lib/api/client";
import type { SavingsSummary } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const summary: SavingsSummary = {
  poolId: "pool-1",
  poolName: "Fall Supplies",
  itemsReused: 397,
  itemsPurchased: 42,
  estimatedSavingsCents: 111800,
  shareableMessage:
    '"Fall Supplies" reused 397 items and saved an estimated $1,118.00 with ClassPool!',
};

describe("SavingsSummaryCard", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    // @ts-expect-error - jsdom doesn't implement share; tests opt in per-case
    delete navigator.share;
  });

  afterEach(() => {
    // @ts-expect-error - clean up between tests
    delete navigator.share;
  });

  it("renders the reused/purchased item counts", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<SavingsSummaryCard poolId="pool-1" />);

    expect(await screen.findByText("397")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  it("shows the estimated-savings dollar figure when estimatedSavingsCents > 0", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<SavingsSummaryCard poolId="pool-1" />);

    expect(await screen.findByText(/\$1,118\.00/)).toBeInTheDocument();
  });

  it("hides the dollar figure when estimatedSavingsCents is 0", async () => {
    mockedApi.GET.mockResolvedValue({
      data: { ...summary, estimatedSavingsCents: 0 },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<SavingsSummaryCard poolId="pool-1" />);

    expect(await screen.findByText("397")).toBeInTheDocument();
    expect(screen.queryByText(/estimated savings/i)).not.toBeInTheDocument();
  });

  it("shows a quiet not-yet-available message on the not-reconciled 409, rather than an error", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "not reconciled" },
      response: { status: 409 } as Response,
    } as any);

    render(<SavingsSummaryCard poolId="pool-1" />);

    expect(
      await screen.findByText(/hasn't been worked out yet/i)
    ).toBeInTheDocument();
  });

  it("shares via the Web Share API when available", async () => {
    const shareMock = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "share", {
      value: shareMock,
      configurable: true,
      writable: true,
    });
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    // @testing-library/user-event's setup() attaches a real (non-mock)
    // Clipboard stub to navigator.clipboard, which doesn't otherwise exist
    // in jsdom — spy on it only after setup() so the spy targets that stub's
    // own writeText rather than shadowing a property setup() would replace.
    const user = userEvent.setup();
    const writeTextSpy = vi.spyOn(navigator.clipboard, "writeText");
    render(<SavingsSummaryCard poolId="pool-1" />);

    await user.click(await screen.findByRole("button", { name: /share these savings/i }));

    await waitFor(() =>
      expect(shareMock).toHaveBeenCalledWith(
        expect.objectContaining({ text: summary.shareableMessage })
      )
    );
    expect(writeTextSpy).not.toHaveBeenCalled();
  });

  it("falls back to copying the shareable message to the clipboard when Web Share is unavailable", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    const writeTextSpy = vi.spyOn(navigator.clipboard, "writeText");
    render(<SavingsSummaryCard poolId="pool-1" />);

    const shareButton = await screen.findByRole("button", { name: /share these savings/i });
    await user.click(shareButton);

    await waitFor(() =>
      expect(writeTextSpy).toHaveBeenCalledWith(summary.shareableMessage)
    );
    expect(await screen.findByRole("button", { name: /copied!/i })).toBeInTheDocument();
  });
});
