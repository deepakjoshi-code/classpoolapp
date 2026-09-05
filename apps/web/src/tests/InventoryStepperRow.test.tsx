import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { InventoryStepperRow } from "@/components/InventoryStepperRow";
import { api } from "@/lib/api/client";
import type { InventoryLine } from "@/lib/api/types";

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

const baseLine: InventoryLine = {
  requirementId: "req-1",
  requirementName: "Glue Stick",
  quantityPerStudent: 4,
  studentId: "student-1",
  studentFirstName: "Ava",
  ownedQuantity: 2,
  stillNeeded: 2,
};

describe("InventoryStepperRow", () => {
  beforeEach(() => {
    mockedApi.PUT.mockReset();
  });

  it("renders the item name, quantity needed, and current owned count", () => {
    render(
      <InventoryStepperRow poolId="pool-1" line={baseLine} onChange={vi.fn()} />
    );

    expect(screen.getByText(/glue stick/i)).toBeInTheDocument();
    expect(screen.getByText(/4 needed per student/i)).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  it("has real accessible labels on the stepper buttons, not bare icons", () => {
    render(
      <InventoryStepperRow poolId="pool-1" line={baseLine} onChange={vi.fn()} />
    );

    expect(
      screen.getByRole("button", { name: "Decrease owned Glue Stick for Ava" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Increase owned Glue Stick for Ava" })
    ).toBeInTheDocument();
  });

  it("increments optimistically and then calls the upsert endpoint with the right values", async () => {
    mockedApi.PUT.mockResolvedValue({
      data: { ...baseLine, ownedQuantity: 3, stillNeeded: 1 },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <InventoryStepperRow
        poolId="pool-1"
        line={baseLine}
        onChange={onChange}
        debounceMs={10}
      />
    );

    await user.click(
      screen.getByRole("button", { name: "Increase owned Glue Stick for Ava" })
    );

    // Optimistic update happens synchronously, before the network call.
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ ownedQuantity: 3, stillNeeded: 1 })
    );

    await waitFor(() => expect(mockedApi.PUT).toHaveBeenCalledTimes(1));
    expect(mockedApi.PUT).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements/{requirementId}/inventory",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", requirementId: "req-1" } },
        body: { studentId: "student-1", ownedQuantity: 3 },
      })
    );
  });

  it("debounces rapid clicks into a single network call carrying the final value", async () => {
    mockedApi.PUT.mockResolvedValue({
      data: { ...baseLine, ownedQuantity: 4, stillNeeded: 0 },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <InventoryStepperRow
        poolId="pool-1"
        line={baseLine}
        onChange={vi.fn()}
        debounceMs={30}
      />
    );

    const increase = screen.getByRole("button", {
      name: "Increase owned Glue Stick for Ava",
    });
    await user.click(increase);
    await user.click(increase);

    expect(screen.getByText("4")).toBeInTheDocument();

    await waitFor(() => expect(mockedApi.PUT).toHaveBeenCalledTimes(1));
    expect(mockedApi.PUT).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements/{requirementId}/inventory",
      expect.objectContaining({ body: { studentId: "student-1", ownedQuantity: 4 } })
    );
  });

  it("clamps visually at 0 so the decrease button is disabled rather than going negative", async () => {
    const zeroLine: InventoryLine = { ...baseLine, ownedQuantity: 0, stillNeeded: 4 };
    render(
      <InventoryStepperRow poolId="pool-1" line={zeroLine} onChange={vi.fn()} />
    );

    const decrease = screen.getByRole("button", {
      name: "Decrease owned Glue Stick for Ava",
    });
    expect(decrease).toBeDisabled();
    expect(mockedApi.PUT).not.toHaveBeenCalled();
  });

  it("disables the increase button once fully covered", () => {
    const fullLine: InventoryLine = { ...baseLine, ownedQuantity: 4, stillNeeded: 0 };
    render(
      <InventoryStepperRow poolId="pool-1" line={fullLine} onChange={vi.fn()} />
    );

    expect(
      screen.getByRole("button", { name: "Increase owned Glue Stick for Ava" })
    ).toBeDisabled();
    expect(screen.getByText(/fully covered/i)).toBeInTheDocument();
  });

  it("reverts the count and shows an error if the save fails", async () => {
    mockedApi.PUT.mockResolvedValue({
      data: undefined,
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <InventoryStepperRow
        poolId="pool-1"
        line={baseLine}
        onChange={vi.fn()}
        debounceMs={10}
      />
    );

    await user.click(
      screen.getByRole("button", { name: "Increase owned Glue Stick for Ava" })
    );
    expect(screen.getByText("3")).toBeInTheDocument();

    await waitFor(() =>
      expect(screen.getByText(/isn't open for inventory yet/i)).toBeInTheDocument()
    );
    expect(screen.getByText("2")).toBeInTheDocument();
  });
});
