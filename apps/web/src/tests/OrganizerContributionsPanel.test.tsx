import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrganizerContributionsPanel } from "@/components/OrganizerContributionsPanel";
import PoolDetailPage from "@/app/pools/[id]/page";
import { api } from "@/lib/api/client";
import { useCurrentUser } from "@/lib/use-current-user";
import type { Contribution, CurrentUser, PoolDetail } from "@/lib/api/types";

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

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "pool-1" }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

vi.mock("@/lib/use-current-user", () => ({
  useCurrentUser: vi.fn(),
}));

const mockedApi = vi.mocked(api);
const mockedUseCurrentUser = vi.mocked(useCurrentUser);

// studentId/studentFirstName are always null in V1 (see openapi.yaml's
// Contribution schema) — contributions aren't tracked per-student.
const pledged: Contribution = {
  id: "contrib-1",
  requirementId: "req-1",
  requirementName: "Glue Stick",
  studentId: null,
  studentFirstName: null,
  offeringParentDisplayName: "Priya Patel",
  quantity: 3,
  mode: "DONATE",
  state: "PLEDGED",
  createdAt: new Date().toISOString(),
};

const received: Contribution = {
  id: "contrib-2",
  requirementId: "req-1",
  requirementName: "Glue Stick",
  studentId: null,
  studentFirstName: null,
  offeringParentDisplayName: "Marcus Lee",
  quantity: 1,
  mode: "DONATE",
  state: "RECEIVED",
  createdAt: new Date().toISOString(),
};

describe("OrganizerContributionsPanel", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("fetches contributions and shows each offering parent's name — organizer-only identity per PRD §5.3", async () => {
    mockedApi.GET.mockResolvedValue({
      data: [pledged, received],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<OrganizerContributionsPanel poolId="pool-1" />);

    expect(await screen.findByText(/priya patel/i)).toBeInTheDocument();
    expect(screen.getByText(/marcus lee/i)).toBeInTheDocument();

    await waitFor(() =>
      expect(mockedApi.GET).toHaveBeenCalledWith(
        "/pools/{poolId}/contributions",
        expect.objectContaining({ params: { path: { poolId: "pool-1" } } })
      )
    );
  });

  it("shows a mark-received action only for a still-PLEDGED contribution, and calls the receive endpoint", async () => {
    mockedApi.GET.mockResolvedValue({
      data: [pledged, received],
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...pledged, state: "RECEIVED" },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<OrganizerContributionsPanel poolId="pool-1" />);

    await screen.findByText(/priya patel/i);

    // Only the PLEDGED row gets a "Mark received" button.
    const receiveButtons = screen.getAllByRole("button", { name: /mark received/i });
    expect(receiveButtons).toHaveLength(1);

    await user.click(receiveButtons[0]!);

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/pools/{poolId}/contributions/{contributionId}/receive",
      expect.objectContaining({
        params: { path: { poolId: "pool-1", contributionId: "contrib-1" } },
      })
    );

    await waitFor(() =>
      expect(screen.queryAllByRole("button", { name: /mark received/i })).toHaveLength(0)
    );
  });

  it("shows a fallback message if contributions can't be loaded (e.g. a non-organizer's 403)", async () => {
    mockedApi.GET.mockResolvedValue({
      data: undefined,
      error: { message: "forbidden" },
      response: { status: 403 } as Response,
    } as any);

    render(<OrganizerContributionsPanel poolId="pool-1" />);

    expect(
      await screen.findByText(/couldn't load contributions/i)
    ).toBeInTheDocument();
  });
});

describe("OrganizerContributionsPanel visibility (PRD §5.3 privacy model)", () => {
  const classroomId = "classroom-1";

  const pool: PoolDetail = {
    id: "pool-1",
    classroomId,
    name: "Fall Supplies",
    poolType: "SUPPLIES",
    state: "OPEN_FOR_INVENTORY",
    requirementCount: 1,
    createdAt: new Date().toISOString(),
    requirements: [
      {
        id: "req-1",
        poolId: "pool-1",
        name: "Glue Stick",
        quantityPerStudent: 4,
        brand: null,
        strictness: "EQUIVALENT_ALLOWED",
        state: "CONFIRMED",
        sourceEvidence: null,
        confidence: null,
        totalDemand: 96,
        createdAt: new Date().toISOString(),
      },
    ],
  };

  function currentUser(role: "PARENT" | "ORGANIZER"): CurrentUser {
    return {
      id: "user-1",
      email: "parent@example.com",
      displayName: "Test User",
      householdId: "household-1",
      memberships: [
        {
          id: "membership-1",
          classroomId,
          role,
          studentId: "student-1",
          studentFirstName: "Ava",
          lateJoin: false,
          classroom: { ...pool, requirements: undefined } as any,
        },
      ],
    };
  }

  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
  });

  it("is never rendered on the pool page for a plain parent — no contribution/identity fetch happens at all", async () => {
    mockedUseCurrentUser.mockReturnValue({
      status: "authenticated",
      user: currentUser("PARENT"),
    });
    mockedApi.GET.mockImplementation((path: string) => {
      if (path === "/pools/{poolId}") {
        return Promise.resolve({
          data: pool,
          error: undefined,
          response: { status: 200 } as Response,
        }) as any;
      }
      throw new Error(`Unexpected GET for a non-organizer: ${path}`);
    });

    render(<PoolDetailPage />);

    await screen.findByText("Fall Supplies");

    // The organizer-only panel's own heading, and any contributor identity,
    // must never appear for a plain parent.
    expect(screen.queryByRole("heading", { name: "Contributions" })).not.toBeInTheDocument();
    expect(screen.queryByText(/priya patel/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark received/i })).not.toBeInTheDocument();

    // Not just hidden in the DOM — the identity-carrying endpoint itself is
    // never called for a non-organizer viewer.
    expect(mockedApi.GET).not.toHaveBeenCalledWith(
      "/pools/{poolId}/contributions",
      expect.anything()
    );
  });

  it("IS rendered on the pool page for the organizer, confirming the gate is role-based, not accidentally always-off", async () => {
    mockedUseCurrentUser.mockReturnValue({
      status: "authenticated",
      user: currentUser("ORGANIZER"),
    });
    mockedApi.GET.mockImplementation((path: string) => {
      if (path === "/pools/{poolId}") {
        return Promise.resolve({
          data: pool,
          error: undefined,
          response: { status: 200 } as Response,
        }) as any;
      }
      if (path === "/pools/{poolId}/inventory/summary") {
        return Promise.resolve({
          data: { studentsWithInventorySubmitted: 0, totalJoinedStudents: 0, perRequirement: [] },
          error: undefined,
          response: { status: 200 } as Response,
        }) as any;
      }
      if (path === "/pools/{poolId}/contributions") {
        return Promise.resolve({
          data: [pledged],
          error: undefined,
          response: { status: 200 } as Response,
        }) as any;
      }
      throw new Error(`Unexpected GET: ${path}`);
    });

    render(<PoolDetailPage />);

    expect(
      await screen.findByRole("heading", { name: "Contributions" })
    ).toBeInTheDocument();
    expect(await screen.findByText(/priya patel/i)).toBeInTheDocument();
  });
});
