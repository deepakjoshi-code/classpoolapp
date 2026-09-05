import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ContributionOfferCard } from "@/components/ContributionOfferCard";
import { api } from "@/lib/api/client";
import type { Contribution } from "@/lib/api/types";

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

const pledgedContribution: Contribution = {
  id: "contrib-1",
  requirementId: "req-1",
  requirementName: "Glue Stick",
  studentId: "student-1",
  studentFirstName: "Ava",
  offeringParentDisplayName: null,
  quantity: 2,
  mode: "DONATE",
  state: "PLEDGED",
  createdAt: new Date().toISOString(),
};

const receivedContribution: Contribution = {
  ...pledgedContribution,
  id: "contrib-2",
  quantity: 1,
  state: "RECEIVED",
};

describe("ContributionOfferCard", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
    mockedApi.DELETE.mockReset();
  });

  it("renders the requirement name, quantity needed, and an offer form", () => {
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[]}
        onOffered={vi.fn()}
        onWithdrawn={vi.fn()}
      />
    );

    expect(screen.getByText(/glue stick/i)).toBeInTheDocument();
    expect(screen.getByText(/4 needed per student/i)).toBeInTheDocument();
    expect(
      screen.getByLabelText(/extra you can give for ava/i)
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /offer to donate/i })
    ).toBeInTheDocument();
  });

  it("submits an offer with the correct payload — studentId, quantity, and mode: DONATE", async () => {
    mockedApi.POST.mockResolvedValue({
      data: { ...pledgedContribution, quantity: 3 },
      error: undefined,
      response: { status: 201 } as Response,
    } as any);

    const onOffered = vi.fn();
    const user = userEvent.setup();
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[]}
        onOffered={onOffered}
        onWithdrawn={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/extra you can give for ava/i), "3");
    await user.click(screen.getByRole("button", { name: /offer to donate/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/requirements/{requirementId}/contributions",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", requirementId: "req-1" } },
        body: { studentId: "student-1", quantity: 3, mode: "DONATE" },
      })
    );
    await waitFor(() =>
      expect(onOffered).toHaveBeenCalledWith(
        expect.objectContaining({ quantity: 3 })
      )
    );
  });

  it("disables the submit button until a valid quantity is entered", async () => {
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[]}
        onOffered={vi.fn()}
        onWithdrawn={vi.fn()}
      />
    );

    const submitButton = screen.getByRole("button", { name: /offer to donate/i });
    expect(submitButton).toBeDisabled();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/extra you can give for ava/i), "0");
    expect(submitButton).toBeDisabled();

    await user.clear(screen.getByLabelText(/extra you can give for ava/i));
    await user.type(screen.getByLabelText(/extra you can give for ava/i), "2");
    expect(submitButton).not.toBeDisabled();
  });

  it("shows this household's own pledge status, reading unambiguously different from received", () => {
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[pledgedContribution, receivedContribution]}
        onOffered={vi.fn()}
        onWithdrawn={vi.fn()}
      />
    );

    expect(screen.getByText(/you offered 2/i)).toBeInTheDocument();
    expect(screen.getByText(/pledged — not yet received/i)).toBeInTheDocument();
    expect(screen.getByText(/you offered 1/i)).toBeInTheDocument();
    expect(screen.getByText(/received — thank you!/i)).toBeInTheDocument();
  });

  it("never renders another household's identity — this card only ever sees the caller's own pledges", () => {
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[pledgedContribution]}
        onOffered={vi.fn()}
        onWithdrawn={vi.fn()}
      />
    );

    // offeringParentDisplayName is irrelevant to viewing your own pledge and
    // is never surfaced here, even when present on the object.
    expect(screen.queryByText(/from /i)).not.toBeInTheDocument();
  });

  it("shows a withdraw action only for a still-PLEDGED contribution, and calls DELETE", async () => {
    mockedApi.DELETE.mockResolvedValue({
      error: undefined,
      response: { status: 204 } as Response,
    } as any);

    const onWithdrawn = vi.fn();
    const user = userEvent.setup();
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[pledgedContribution, receivedContribution]}
        onOffered={vi.fn()}
        onWithdrawn={onWithdrawn}
      />
    );

    // Only one withdraw button — for the PLEDGED contribution, not the
    // RECEIVED one (contract: withdraw only valid while state === PLEDGED).
    const withdrawButtons = screen.getAllByRole("button", { name: /withdraw/i });
    expect(withdrawButtons).toHaveLength(1);

    await user.click(withdrawButtons[0]!);

    await waitFor(() => expect(mockedApi.DELETE).toHaveBeenCalledTimes(1));
    expect(mockedApi.DELETE).toHaveBeenCalledWith(
      "/pools/{poolId}/contributions/{contributionId}",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", contributionId: "contrib-1" } },
      })
    );
    await waitFor(() => expect(onWithdrawn).toHaveBeenCalledWith("contrib-1"));
  });

  it("surfaces a clear message if withdrawing a since-received pledge 409s", async () => {
    mockedApi.DELETE.mockResolvedValue({
      error: { message: "conflict" },
      response: { status: 409 } as Response,
    } as any);

    const user = userEvent.setup();
    render(
      <ContributionOfferCard
        poolId="pool-1"
        requirementId="req-1"
        requirementName="Glue Stick"
        quantityPerStudent={4}
        studentId="student-1"
        studentFirstName="Ava"
        contributions={[pledgedContribution]}
        onOffered={vi.fn()}
        onWithdrawn={vi.fn()}
      />
    );

    await user.click(screen.getByRole("button", { name: /withdraw/i }));

    expect(
      await screen.findByText(/already been received.*can no longer be withdrawn/i)
    ).toBeInTheDocument();
  });
});
