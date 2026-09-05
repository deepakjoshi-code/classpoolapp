import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DistributionPanel } from "@/components/DistributionPanel";
import { api } from "@/lib/api/client";
import type { DistributionItem, DistributionSummary } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const items: DistributionItem[] = [
  {
    id: "item-1",
    studentId: "student-1",
    studentFirstName: "Ava",
    requirementId: "req-1",
    requirementName: "pencils",
    quantity: 12,
    deliveredAt: null,
  },
  {
    id: "item-2",
    studentId: "student-1",
    studentFirstName: "Ava",
    requirementId: "req-2",
    requirementName: "notebooks",
    quantity: 2,
    deliveredAt: null,
  },
  {
    id: "item-3",
    studentId: "student-2",
    studentFirstName: "Ben",
    requirementId: "req-1",
    requirementName: "pencils",
    quantity: 6,
    deliveredAt: new Date().toISOString(),
  },
];

const summary: DistributionSummary = {
  id: "dist-1",
  poolId: "pool-1",
  mode: "HOUSEHOLD_BAG",
  createdAt: new Date().toISOString(),
  items,
  pickLists: [
    {
      householdId: "household-1",
      householdDisplayName: "Family A",
      lines: [
        { requirementName: "pencils", quantity: 12 },
        { requirementName: "notebooks", quantity: 2 },
      ],
    },
    {
      householdId: "household-2",
      householdDisplayName: "Family B",
      lines: [{ requirementName: "pencils", quantity: 6 }],
    },
  ],
};

describe("DistributionPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
    window.print = vi.fn();
  });

  it("renders each household's pick list, grouped, with multiple items summed per line", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<DistributionPanel poolId="pool-1" />);

    const familyACard = (await screen.findByText("Family A")).closest("li") as HTMLElement;
    const familyBCard = screen.getByText("Family B").closest("li") as HTMLElement;
    expect(within(familyACard).getByText(/12 pencils/)).toBeInTheDocument();
    expect(within(familyACard).getByText(/2 notebooks/)).toBeInTheDocument();
    expect(within(familyBCard).getByText(/6 pencils/)).toBeInTheDocument();
  });

  it("shows a not-generated message on a 409 instead of crashing", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "none" },
      response: { status: 409 } as Response,
    } as any);

    render(<DistributionPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/distribution hasn't been set up yet/i)
    ).toBeInTheDocument();
  });

  it("groups the raw delivery-tracking list by student and only shows Mark delivered for undelivered items", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<DistributionPanel poolId="pool-1" />);

    const avaCard = (await screen.findByText("Ava")).closest("li") as HTMLElement;
    expect(within(avaCard).getAllByRole("button", { name: /mark.*delivered/i })).toHaveLength(2);

    const benCard = screen.getByText("Ben").closest("li") as HTMLElement;
    expect(within(benCard).queryByRole("button", { name: /mark.*delivered/i })).not.toBeInTheDocument();
    expect(within(benCard).getByText(/^delivered$/i)).toBeInTheDocument();
  });

  it("marking an item delivered calls the deliver endpoint and updates that row without a full reload", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...items[0], deliveredAt: new Date().toISOString() },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<DistributionPanel poolId="pool-1" />);

    const avaCard = (await screen.findByText("Ava")).closest("li") as HTMLElement;
    const pencilsButton = within(avaCard).getAllByRole("button", { name: /mark.*delivered/i })[0]!;
    await user.click(pencilsButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/distribution/items/{itemId}/deliver",
      expect.objectContaining({ params: { path: { poolId: "pool-1", itemId: "item-1" } } })
    );

    await waitFor(() =>
      expect(within(avaCard).getAllByRole("button", { name: /mark.*delivered/i })).toHaveLength(1)
    );
  });

  it("the print button triggers window.print", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<DistributionPanel poolId="pool-1" />);

    await user.click(await screen.findByRole("button", { name: /print pick lists/i }));
    expect(window.print).toHaveBeenCalledTimes(1);
  });
});
