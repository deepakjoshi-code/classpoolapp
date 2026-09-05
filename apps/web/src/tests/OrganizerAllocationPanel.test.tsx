import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrganizerAllocationPanel } from "@/components/OrganizerAllocationPanel";
import { api } from "@/lib/api/client";
import type { AllocationSummary, ProductOffer } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
    DELETE: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const summary: AllocationSummary = {
  allocations: [
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      studentId: "student-1",
      studentFirstName: "Ava",
      quantityNeeded: 4,
      ownedQuantity: 4,
      poolFulfilledQuantity: 0,
      purchaseRequiredQuantity: 0,
      status: "SELF_FULFILLED",
    },
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      studentId: "student-2",
      studentFirstName: "Ben",
      quantityNeeded: 4,
      ownedQuantity: 2,
      poolFulfilledQuantity: 0,
      purchaseRequiredQuantity: 2,
      status: "PURCHASE_REQUIRED",
    },
  ],
  residualDemand: [
    {
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      totalRequired: 8,
      totalOwned: 6,
      totalPoolFulfilled: 0,
      residualDemand: 2,
    },
  ],
};

describe("OrganizerAllocationPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
  });

  it("fetches the allocation summary and shows the residual-demand line and per-student breakdown, in plain language", async () => {
    mockedApi.GET.mockResolvedValue({
      data: summary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(await screen.findByText("Glue Sticks")).toBeInTheDocument();
    expect(
      screen.getByText(/2 still needs? to be purchased/i)
    ).toBeInTheDocument();

    expect(screen.getByText("Ava")).toBeInTheDocument();
    expect(screen.getByText(/already has enough/i)).toBeInTheDocument();

    expect(screen.getByText("Ben")).toBeInTheDocument();
    expect(
      screen.getByText(/still needs 2 — will be part of the class purchase/i)
    ).toBeInTheDocument();

    // No raw enum values ever rendered.
    expect(screen.queryByText(/SELF_FULFILLED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/PURCHASE_REQUIRED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/POOL_FULFILLED/)).not.toBeInTheDocument();
  });

  it("shows 'fully covered' when residual demand is zero", async () => {
    mockedApi.GET.mockResolvedValue({
      data: {
        allocations: [],
        residualDemand: [
          {
            requirementId: "req-2",
            requirementName: "Notebooks",
            totalRequired: 20,
            totalOwned: 20,
            totalPoolFulfilled: 0,
            residualDemand: 0,
          },
        ],
      } as AllocationSummary,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(await screen.findByText("Notebooks")).toBeInTheDocument();
    expect(screen.getByText(/fully covered!/i)).toBeInTheDocument();
  });

  it("handles the not-yet-reconciled 409 gracefully instead of crashing", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/hasn't been worked out yet/i)
    ).toBeInTheDocument();
  });

  it("shows a fallback message on an unexpected error", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "forbidden" },
      response: { status: 403 } as Response,
    } as any);

    render(<OrganizerAllocationPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/couldn't load the purchase breakdown/i)
    ).toBeInTheDocument();
  });

  describe("while the pool is RECONCILING", () => {
    const existingOffer: ProductOffer = {
      id: "offer-1",
      requirementId: "req-1",
      requirementName: "Glue Sticks",
      retailer: "Target",
      packQuantity: 12,
      priceCents: 899,
      shippingCents: 0,
      affiliateUrl: null,
      createdAt: new Date().toISOString(),
    };

    function mockGetByPath(offers: ProductOffer[] = [existingOffer]) {
      mockedApi.GET.mockImplementation((path: any) => {
        if (path === "/pools/{poolId}/product-offers") {
          return Promise.resolve({
            data: offers,
            error: undefined,
            response: { status: 200 } as Response,
          }) as any;
        }
        return Promise.resolve({
          data: summary,
          error: undefined,
          response: { status: 200 } as Response,
        }) as any;
      });
    }

    it("shows the add-a-price-option form only for requirements that still need buying, listing offers already added", async () => {
      mockGetByPath();

      render(<OrganizerAllocationPanel poolId="pool-1" poolState="RECONCILING" />);

      await screen.findByText("Glue Sticks");

      // req-1 has residual demand (2) — the form and its existing offer show.
      expect(await screen.findByText(/target/i)).toBeInTheDocument();
      expect(screen.getByText(/\$8\.99/)).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /add this price option/i })
      ).toBeInTheDocument();
    });

    it("hides the form once the pool has moved past RECONCILING (e.g. a plan already exists)", async () => {
      mockGetByPath();

      render(
        <OrganizerAllocationPanel poolId="pool-1" poolState="PURCHASE_PROPOSED" />
      );

      await screen.findByText("Glue Sticks");

      expect(
        screen.queryByRole("button", { name: /add this price option/i })
      ).not.toBeInTheDocument();
      // Never even fetches offers once a plan may already exist.
      expect(mockedApi.GET).not.toHaveBeenCalledWith(
        "/pools/{poolId}/product-offers",
        expect.anything()
      );
    });

    it("adding a price option through the embedded form updates the list without a full reload", async () => {
      mockGetByPath([]);
      mockedApi.POST.mockResolvedValue({
        data: {
          ...existingOffer,
          id: "offer-2",
          retailer: "Amazon",
        },
        error: undefined,
        response: { status: 201 } as Response,
      } as any);

      const user = userEvent.setup();
      render(<OrganizerAllocationPanel poolId="pool-1" poolState="RECONCILING" />);

      await screen.findByText("Glue Sticks");
      expect(
        await screen.findByLabelText(/retailer/i)
      ).toBeInTheDocument();

      await user.type(screen.getByLabelText(/retailer/i), "Amazon");
      await user.type(screen.getByLabelText(/pack size/i), "24");
      await user.type(screen.getByLabelText(/^price$/i), "4.99");
      await user.click(
        screen.getByRole("button", { name: /add this price option/i })
      );

      expect(await screen.findByText(/amazon/i)).toBeInTheDocument();
    });
  });
});
