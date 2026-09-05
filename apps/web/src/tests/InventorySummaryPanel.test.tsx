import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { InventorySummaryPanel } from "@/components/InventorySummaryPanel";
import { api } from "@/lib/api/client";
import type { InventorySummary } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
    PATCH: vi.fn(),
    PUT: vi.fn(),
    DELETE: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const summary: InventorySummary = {
  studentsWithInventorySubmitted: 19,
  totalJoinedStudents: 25,
  perRequirement: [
    { requirementId: "req-1", requirementName: "Glue Stick", totalOwned: 40, totalRequired: 96 },
    { requirementId: "req-2", requirementName: "Pencils", totalOwned: 10, totalRequired: 50 },
  ],
};

describe("InventorySummaryPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
  });

  it("fetches the summary and renders the completion counts and per-requirement totals", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<InventorySummaryPanel poolId="pool-1" />);

    expect(await screen.findByText(/inventory completed 19\/25 students/i)).toBeInTheDocument();

    await waitFor(() =>
      expect(mockedApi.GET).toHaveBeenCalledWith(
        "/pools/{poolId}/inventory/summary",
        expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
      )
    );

    expect(screen.getByText(/glue stick/i)).toBeInTheDocument();
    expect(screen.getByText(/40 already owned of 96 needed/i)).toBeInTheDocument();
    expect(screen.getByText(/pencils/i)).toBeInTheDocument();
    expect(screen.getByText(/10 already owned of 50 needed/i)).toBeInTheDocument();
  });

  it("shows a fallback message if the summary can't be loaded", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "forbidden" },
      response: { status: 403 } as Response,
    } as any);

    render(<InventorySummaryPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/couldn't load the inventory summary/i)
    ).toBeInTheDocument();
  });
});
