import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import PoolDistributionPage from "@/app/pools/[id]/distribution/page";
import { api } from "@/lib/api/client";
import { useCurrentUser } from "@/lib/use-current-user";
import type { CurrentUser, DistributionItem, PoolDetail } from "@/lib/api/types";

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
  state: "DISTRIBUTING",
  requirementCount: 0,
  createdAt: new Date().toISOString(),
  requirements: [],
};

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
    deliveredAt: new Date().toISOString(),
  },
];

function mockGet(itemsResponse: any) {
  mockedApi.GET.mockImplementation((path: string) => {
    if (path === "/pools/{poolId}") {
      return Promise.resolve({
        data: pool,
        error: undefined,
        response: { status: 200 } as Response,
      }) as any;
    }
    if (path === "/pools/{poolId}/distribution/mine") {
      return Promise.resolve(itemsResponse) as any;
    }
    throw new Error(`Unexpected GET: ${path}`);
  });
}

describe("PoolDistributionPage", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedUseCurrentUser.mockReturnValue({ status: "authenticated", user: currentUser });
  });

  it("shows a nothing-to-show message for an empty array", async () => {
    mockGet({ data: [], error: undefined, response: { status: 200 } });

    render(<PoolDistributionPage />);

    expect(await screen.findByText(/nothing to show yet/i)).toBeInTheDocument();
  });

  it("groups items by student and shows plain-language delivered/not-yet-delivered status", async () => {
    mockGet({ data: items, error: undefined, response: { status: 200 } });

    render(<PoolDistributionPage />);

    expect(await screen.findByText("Ava")).toBeInTheDocument();
    expect(screen.getByText(/12 pencils/)).toBeInTheDocument();
    expect(screen.getByText(/2 notebooks/)).toBeInTheDocument();
    expect(screen.getByText(/not yet delivered/i)).toBeInTheDocument();
    expect(screen.getByText(/^delivered$/i)).toBeInTheDocument();
  });
});
