import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProductOfferForm } from "@/components/ProductOfferForm";
import { api } from "@/lib/api/client";
import type { ProductOffer } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
    DELETE: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const existingOffer: ProductOffer = {
  id: "offer-1",
  requirementId: "req-1",
  requirementName: "Glue Stick",
  retailer: "Target",
  packQuantity: 12,
  priceCents: 899,
  shippingCents: 0,
  affiliateUrl: null,
  createdAt: new Date().toISOString(),
};

describe("ProductOfferForm", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
    mockedApi.DELETE.mockReset();
  });

  it("renders already-added offers, formatted as dollars, with a remove action", () => {
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[existingOffer]}
        onAdded={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    expect(screen.getByText(/target/i)).toBeInTheDocument();
    expect(screen.getByText(/\$8\.99/)).toBeInTheDocument();
    // Never raw cents.
    expect(screen.queryByText(/899/)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /remove target price option/i })
    ).toBeInTheDocument();
  });

  it("submits a new offer, converting the typed dollar price to integer cents", async () => {
    const created: ProductOffer = {
      ...existingOffer,
      id: "offer-2",
      retailer: "Amazon",
      packQuantity: 24,
      priceCents: 499,
      shippingCents: 599,
    };
    mockedApi.POST.mockResolvedValue({
      data: created,
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onAdded = vi.fn();
    const user = userEvent.setup();
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[]}
        onAdded={onAdded}
        onRemoved={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/retailer/i), "Amazon");
    await user.type(screen.getByLabelText(/pack size/i), "24");
    await user.type(screen.getByLabelText(/^price$/i), "4.99");
    await user.type(screen.getByLabelText(/shipping/i), "5.99");

    await user.click(
      screen.getByRole("button", { name: /add this price option/i })
    );

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements/{requirementId}/product-offers",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", requirementId: "req-1" } },
        body: {
          retailer: "Amazon",
          packQuantity: 24,
          priceCents: 499,
          shippingCents: 599,
          affiliateUrl: null,
        },
      })
    );
    await waitFor(() => expect(onAdded).toHaveBeenCalledWith(created));
  });

  it("disables submit until retailer, pack size, and price are all filled in", async () => {
    const user = userEvent.setup();
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[]}
        onAdded={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    const submit = screen.getByRole("button", { name: /add this price option/i });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/retailer/i), "Amazon");
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/pack size/i), "24");
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/^price$/i), "4.99");
    expect(submit).toBeEnabled();
  });

  it("shows a specific message on the pool-no-longer-RECONCILING 409 when adding", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[]}
        onAdded={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/retailer/i), "Amazon");
    await user.type(screen.getByLabelText(/pack size/i), "24");
    await user.type(screen.getByLabelText(/^price$/i), "4.99");
    await user.click(
      screen.getByRole("button", { name: /add this price option/i })
    );

    expect(
      await screen.findByText(/no longer take new price options/i)
    ).toBeInTheDocument();
  });

  it("removes an existing offer and calls onRemoved", async () => {
    mockedApi.DELETE.mockResolvedValue({
      data: undefined,
      error: undefined,
      response: { status: 204 } as Response,
    } as any);

    const onRemoved = vi.fn();
    const user = userEvent.setup();
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[existingOffer]}
        onAdded={vi.fn()}
        onRemoved={onRemoved}
      />
    );

    await user.click(
      screen.getByRole("button", { name: /remove target price option/i })
    );

    await waitFor(() => expect(mockedApi.DELETE).toHaveBeenCalledTimes(1));
    expect(mockedApi.DELETE).toHaveBeenCalledWith(
      "/pools/{poolId}/product-offers/{offerId}",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", offerId: "offer-1" } },
      })
    );
    await waitFor(() => expect(onRemoved).toHaveBeenCalledWith("offer-1"));
  });

  it("shows a specific message on the plan-already-generated 409 when removing", async () => {
    mockedApi.DELETE.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <ProductOfferForm
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        offers={[existingOffer]}
        onAdded={vi.fn()}
        onRemoved={vi.fn()}
      />
    );

    await user.click(
      screen.getByRole("button", { name: /remove target price option/i })
    );

    expect(
      await screen.findByText(/can't be removed anymore/i)
    ).toBeInTheDocument();
  });
});
