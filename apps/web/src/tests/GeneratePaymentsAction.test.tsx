import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GeneratePaymentsAction } from "@/components/GeneratePaymentsAction";
import { api } from "@/lib/api/client";
import type { OrganizerStripeAccount, Payment, PurchasePlan } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const approvedPlan: PurchasePlan = {
  id: "plan-1",
  poolId: "pool-1",
  state: "APPROVED",
  totalCostCents: 4647,
  lines: [],
  proposedAt: new Date().toISOString(),
  approvedAt: new Date().toISOString(),
};

const proposedPlan: PurchasePlan = { ...approvedPlan, state: "PROPOSED", approvedAt: null };

const activeStripe: OrganizerStripeAccount = {
  classroomId: "classroom-1",
  status: "ACTIVE",
  onboardingUrl: null,
};

const pendingStripe: OrganizerStripeAccount = {
  classroomId: "classroom-1",
  status: "PENDING",
  onboardingUrl: "https://stripe.example/onboard",
};

const payments: Payment[] = [
  {
    id: "pay-1",
    poolId: "pool-1",
    householdId: "household-1",
    householdDisplayName: "The Patels",
    amountCents: 1250,
    method: null,
    state: "PENDING",
    createdAt: new Date().toISOString(),
  },
];

function mockGet(planResponse: any, stripeResponse: any) {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}/purchase-plan") return Promise.resolve(planResponse) as any;
    if (path === "/classrooms/{classroomId}/stripe-onboarding/status")
      return Promise.resolve(stripeResponse) as any;
    throw new Error(`Unexpected GET: ${path}`);
  });
}

describe("GeneratePaymentsAction", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("is disabled with a specific reason when the purchase plan isn't approved yet", async () => {
    mockGet(
      { data: proposedPlan, error: undefined, response: { status: 200 } },
      { data: activeStripe, error: undefined, response: { status: 200 } }
    );

    render(
      <GeneratePaymentsAction
        poolId="pool-1"
        classroomId="classroom-1"
        onGenerated={vi.fn()}
      />
    );

    expect(
      await screen.findByText(/purchase plan needs to be approved first/i)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /open payment for this pool/i })
    ).not.toBeInTheDocument();
  });

  it("is disabled with a specific reason when Stripe isn't ACTIVE yet", async () => {
    mockGet(
      { data: approvedPlan, error: undefined, response: { status: 200 } },
      { data: pendingStripe, error: undefined, response: { status: 200 } }
    );

    render(
      <GeneratePaymentsAction
        poolId="pool-1"
        classroomId="classroom-1"
        onGenerated={vi.fn()}
      />
    );

    expect(
      await screen.findByText(/stripe account isn't fully connected/i)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /open payment for this pool/i })
    ).not.toBeInTheDocument();
  });

  it("is disabled with a specific reason when Stripe onboarding was never started (404)", async () => {
    mockGet(
      { data: approvedPlan, error: undefined, response: { status: 200 } },
      { data: undefined, error: { message: "not found" }, response: { status: 404 } }
    );

    render(
      <GeneratePaymentsAction
        poolId="pool-1"
        classroomId="classroom-1"
        onGenerated={vi.fn()}
      />
    );

    expect(
      await screen.findByText(/connect this classroom's bank account/i)
    ).toBeInTheDocument();
  });

  it("requires a second, explicit step before running, once both preconditions are met (one-way action)", async () => {
    mockGet(
      { data: approvedPlan, error: undefined, response: { status: 200 } },
      { data: activeStripe, error: undefined, response: { status: 200 } }
    );

    const onGenerated = vi.fn();
    render(
      <GeneratePaymentsAction
        poolId="pool-1"
        classroomId="classroom-1"
        onGenerated={onGenerated}
      />
    );

    const openButton = await screen.findByRole("button", {
      name: /open payment for this pool/i,
    });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.click(openButton);

    expect(screen.getByText(/this can't be undone/i)).toBeInTheDocument();
    const confirmButton = screen.getByRole("button", { name: /yes, open payment/i });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    mockedApi.POST.mockResolvedValue({
      data: payments,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    await user.click(confirmButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/payments/generate",
      expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
    );
    await waitFor(() => expect(onGenerated).toHaveBeenCalledWith(payments));
  });

  it("lets a cancel on the confirming step back out without calling the API", async () => {
    mockGet(
      { data: approvedPlan, error: undefined, response: { status: 200 } },
      { data: activeStripe, error: undefined, response: { status: 200 } }
    );

    const user = userEvent.setup();
    render(
      <GeneratePaymentsAction
        poolId="pool-1"
        classroomId="classroom-1"
        onGenerated={vi.fn()}
      />
    );

    await user.click(
      await screen.findByRole("button", { name: /open payment for this pool/i })
    );
    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(
      screen.getByRole("button", { name: /open payment for this pool/i })
    ).toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
  });
});
