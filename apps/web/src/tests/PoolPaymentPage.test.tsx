import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import PoolPaymentPage from "@/app/pools/[id]/payment/page";
import { api } from "@/lib/api/client";
import { useCurrentUser } from "@/lib/use-current-user";
import type { CurrentUser, Payment, PoolDetail } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "pool-1" }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

vi.mock("@/lib/use-current-user", () => ({
  useCurrentUser: vi.fn(),
}));

const mockedApi = vi.mocked(api);
const mockedUseCurrentUser = vi.mocked(useCurrentUser);

const currentUser: CurrentUser = {
  id: "user-1",
  email: "parent@example.com",
  displayName: "Test Parent",
  householdId: "household-1",
  memberships: [],
};

const pool: PoolDetail = {
  id: "pool-1",
  classroomId: "classroom-1",
  name: "Fall Supplies",
  poolType: "SUPPLIES",
  state: "PAYMENT_OPEN",
  requirementCount: 0,
  createdAt: new Date().toISOString(),
  requirements: [],
};

// householdDisplayName is deliberately non-null here even though the
// contract says it's always null on the "mine" endpoint — this asserts the
// page never renders it even if a mock (or a future backend bug) sent it.
const pendingPayment: Payment = {
  id: "pay-1",
  poolId: "pool-1",
  householdId: "household-1",
  householdDisplayName: "The Testers",
  amountCents: 1899,
  method: null,
  state: "PENDING",
  createdAt: new Date().toISOString(),
};

function mockGet(paymentResponse: any) {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}") {
      return Promise.resolve({
        data: pool,
        error: undefined,
        response: { status: 200 } as Response,
      }) as any;
    }
    if (path === "/pools/{poolId}/payments/mine") {
      return Promise.resolve(paymentResponse) as any;
    }
    throw new Error(`Unexpected GET: ${path}`);
  });
}

describe("PoolPaymentPage", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
    mockedUseCurrentUser.mockReturnValue({ status: "authenticated", user: currentUser });
  });

  it("shows a nothing-to-pay message when GET .../mine returns null", async () => {
    mockGet({ data: null, error: undefined, response: { status: 200 } });

    render(<PoolPaymentPage />);

    expect(await screen.findByText(/nothing to pay right now/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /pay/i })).not.toBeInTheDocument();
  });

  it("shows the amount, the required disclosure, and a pay action for a PENDING payment — never the household's own name", async () => {
    mockGet({ data: pendingPayment, error: undefined, response: { status: 200 } });

    render(<PoolPaymentPage />);

    expect(await screen.findByText("$18.99")).toBeInTheDocument();
    expect(
      screen.getByText(/you're paying the class organizer.*not classpool/i)
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /pay \$18\.99 with card/i })
    ).toBeInTheDocument();

    // Privacy: householdDisplayName must never render on this "own view"
    // page, even though the mock included it.
    expect(screen.queryByText(/the testers/i)).not.toBeInTheDocument();
  });

  it("clicking pay calls the pay endpoint with method CARD and updates to the paid state", async () => {
    mockGet({ data: pendingPayment, error: undefined, response: { status: 200 } });
    mockedApi.POST.mockResolvedValue({
      data: { ...pendingPayment, state: "PAID", method: "CARD" },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<PoolPaymentPage />);

    await user.click(await screen.findByRole("button", { name: /pay \$18\.99 with card/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/{paymentId}/pay",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", paymentId: "pay-1" } },
        body: { method: "CARD" },
      })
    );

    expect(await screen.findByText(/^paid$/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /pay \$18\.99 with card/i })
    ).not.toBeInTheDocument();
  });

  it("shows a plain-language state with no pay button once already paid by cash", async () => {
    mockGet({
      data: { ...pendingPayment, state: "PAID_CASH_RECEIVED", method: "CASH" },
      error: undefined,
      response: { status: 200 },
    });

    render(<PoolPaymentPage />);

    expect(await screen.findByText(/paid by cash/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /pay/i })).not.toBeInTheDocument();
  });
});
