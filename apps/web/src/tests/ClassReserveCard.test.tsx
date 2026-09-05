import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { ClassReserveCard } from "@/components/ClassReserveCard";
import { api } from "@/lib/api/client";
import type { ClassReserveEntry } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

describe("ClassReserveCard", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
  });

  it("renders quantity/item name and falls back to 'not yet noted' when custodianLocation is null — never 'null'", async () => {
    const entries: ClassReserveEntry[] = [
      {
        id: "reserve-1",
        classroomId: "classroom-1",
        itemName: "pencils",
        quantity: 8,
        custodianLocation: null,
        createdAt: new Date().toISOString(),
      },
      {
        id: "reserve-2",
        classroomId: "classroom-1",
        itemName: "glue sticks",
        quantity: 3,
        custodianLocation: "Room 12 supply closet",
        createdAt: new Date().toISOString(),
      },
    ];
    mockedApi.GET.mockResolvedValue({
      data: entries,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<ClassReserveCard poolId="pool-1" />);

    expect(await screen.findByText(/8 pencils/)).toBeInTheDocument();
    expect(screen.getByText(/not yet noted/i)).toBeInTheDocument();
    expect(screen.getByText(/Room 12 supply closet/)).toBeInTheDocument();
    expect(screen.queryByText(/\bnull\b/i)).not.toBeInTheDocument();
  });

  it("shows an empty-state message when nothing has been banked", async () => {
    mockedApi.GET.mockResolvedValue({
      data: [],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<ClassReserveCard poolId="pool-1" />);

    expect(await screen.findByText(/nothing banked/i)).toBeInTheDocument();
  });
});
