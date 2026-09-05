import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NotificationBell } from "@/components/NotificationBell";
import { api } from "@/lib/api/client";
import { useCurrentUser } from "@/lib/use-current-user";
import type { CurrentUser, Notification } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

vi.mock("@/lib/use-current-user", () => ({
  useCurrentUser: vi.fn(),
}));

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush, replace: vi.fn() }),
}));

const mockedApi = vi.mocked(api);
const mockedUseCurrentUser = vi.mocked(useCurrentUser);

const currentUser: CurrentUser = {
  id: "user-1",
  displayName: "Dana",
  email: "dana@example.com",
  memberships: [],
} as unknown as CurrentUser;

const notifications: Notification[] = [
  {
    id: "notif-1",
    type: "PAYMENT_DUE",
    poolId: "pool-1",
    message: "Your payment for Fall Supplies is due.",
    readAt: null,
    createdAt: new Date("2026-01-01T00:00:00Z").toISOString(),
  },
  {
    id: "notif-2",
    type: "POOL_COMPLETED",
    poolId: null,
    message: "Welcome to ClassPool!",
    readAt: new Date("2026-01-01T00:00:00Z").toISOString(),
    createdAt: new Date("2026-01-01T00:00:00Z").toISOString(),
  },
];

describe("NotificationBell", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
    mockPush.mockReset();
    mockedUseCurrentUser.mockReturnValue({ status: "authenticated", user: currentUser });
  });

  it("renders nothing while signed out, and never calls the notifications endpoint", () => {
    mockedUseCurrentUser.mockReturnValue({ status: "anonymous" });

    const { container } = render(<NotificationBell />);

    expect(container).toBeEmptyDOMElement();
    expect(mockedApi.GET).not.toHaveBeenCalled();
  });

  it("shows the unread count as a badge on the bell", async () => {
    mockedApi.GET.mockResolvedValue({
      data: notifications,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    render(<NotificationBell />);

    expect(await screen.findByText("1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /1 unread/i })).toBeInTheDocument();
  });

  it("opens on click to show each notification's message, newest first as given", async () => {
    mockedApi.GET.mockResolvedValue({
      data: notifications,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<NotificationBell />);

    await user.click(await screen.findByRole("button", { name: /notifications/i }));

    expect(
      await screen.findByText("Your payment for Fall Supplies is due.")
    ).toBeInTheDocument();
    expect(screen.getByText("Welcome to ClassPool!")).toBeInTheDocument();
  });

  it("clicking an unread notification calls markNotificationRead and navigates when it has a poolId", async () => {
    mockedApi.GET.mockResolvedValue({
      data: notifications,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);
    mockedApi.POST.mockResolvedValue({
      data: { ...notifications[0], readAt: new Date().toISOString() },
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<NotificationBell />);

    await user.click(await screen.findByRole("button", { name: /notifications/i }));
    await user.click(
      await screen.findByText("Your payment for Fall Supplies is due.")
    );

    await waitFor(() =>
      expect(mockedApi.POST).toHaveBeenCalledWith(
        "/notifications/{notificationId}/read",
        expect.objectContaining({ params: { path: { notificationId: "notif-1" } } })
      )
    );
    expect(mockPush).toHaveBeenCalledWith("/pools/pool-1");
  });

  it("clicking a notification with no poolId does not navigate", async () => {
    mockedApi.GET.mockResolvedValue({
      data: notifications,
      error: undefined,
      response: { status: 200 } as Response,
    } as any);

    const user = userEvent.setup();
    render(<NotificationBell />);

    await user.click(await screen.findByRole("button", { name: /notifications/i }));
    await user.click(await screen.findByText("Welcome to ClassPool!"));

    expect(mockPush).not.toHaveBeenCalled();
    // Already read — no need to call the mark-read endpoint again.
    expect(mockedApi.POST).not.toHaveBeenCalled();
  });
});
