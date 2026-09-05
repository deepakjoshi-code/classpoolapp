import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StripeOnboardingCard } from "@/components/StripeOnboardingCard";
import { api } from "@/lib/api/client";
import type { OrganizerStripeAccount } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const pending: OrganizerStripeAccount = {
  classroomId: "classroom-1",
  status: "PENDING",
  onboardingUrl: "https://stripe.example/onboard/abc",
};

const active: OrganizerStripeAccount = {
  classroomId: "classroom-1",
  status: "ACTIVE",
  onboardingUrl: null,
};

describe("StripeOnboardingCard", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("shows a connect CTA when onboarding hasn't been started (404), and starting it calls the start endpoint", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "not found" },
      response: { status: 404 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: pending,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<StripeOnboardingCard classroomId="classroom-1" />);

    const connectButton = await screen.findByRole("button", {
      name: /connect your bank account/i,
    });
    expect(mockedApi.POST).not.toHaveBeenCalled();

    await user.click(connectButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/classrooms/{classroomId}/stripe-onboarding",
      expect.objectContaining({ params: { path: { classroomId: "classroom-1" } } })
    );

    // Once PENDING, it's honest that this is a placeholder for Stripe's own
    // hosted flow, and offers the "simulate returning" step.
    expect(
      await screen.findByText(/in production, clicking below would open stripe/i)
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /simulate returning from stripe/i })
    ).toBeInTheDocument();
  });

  it("completing onboarding from PENDING calls the complete endpoint and shows the confirmed state", async () => {
    mockedApi.GET.mockResolvedValue({
      data: pending,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: active,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<StripeOnboardingCard classroomId="classroom-1" />);

    const completeButton = await screen.findByRole("button", {
      name: /simulate returning from stripe/i,
    });
    await user.click(completeButton);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/classrooms/{classroomId}/stripe-onboarding/complete",
      expect.objectContaining({ params: { path: { classroomId: "classroom-1" } } })
    );

    expect(
      await screen.findByText(/bank account connected/i)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /simulate returning from stripe/i })
    ).not.toBeInTheDocument();
  });

  it("shows the confirmed state directly when already ACTIVE, with no onboarding buttons", async () => {
    mockedApi.GET.mockResolvedValue({
      data: active,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<StripeOnboardingCard classroomId="classroom-1" />);

    expect(
      await screen.findByText(/bank account connected/i)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /connect your bank account/i })
    ).not.toBeInTheDocument();
    expect(mockedApi.POST).not.toHaveBeenCalled();
  });
});
