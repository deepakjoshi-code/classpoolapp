"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { Notification } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";

type LoadState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; notifications: Notification[] };

/**
 * Site-wide notification bell (PRD §11.3's event list) — `GET
 * /notifications/mine` / `POST /notifications/{id}/read`. Mounted once in
 * `SiteHeader`, not per-page, so it persists across navigation the same way
 * a native app's notification affordance would.
 *
 * Only renders once `useCurrentUser()` reports `authenticated` — the
 * endpoint 401s for a signed-out visitor, and `SiteHeader` itself is mounted
 * on every page (sign-in included), so this component is what actually
 * decides whether there's anyone to show a bell for.
 *
 * Fetches once on mount (to populate the unread badge before anyone opens
 * the dropdown) and again each time the dropdown opens, so anything that
 * arrived while it was closed shows up — there's no polling/websocket
 * precedent anywhere else in this codebase (see apps/web/README.md), so this
 * is deliberately not building one for V1.
 *
 * Clicking a notification marks it read (if it isn't already — the endpoint
 * is idempotent, but there's no reason to call it again for an already-read
 * row) and, only if it carries a `poolId`, navigates to that pool. A
 * class-wide notification with no `poolId` just gets marked read in place.
 */
export function NotificationBell() {
  const auth = useCurrentUser();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<LoadState>({ status: "idle" });
  const containerRef = useRef<HTMLDivElement>(null);

  function load() {
    setState((prev) => (prev.status === "ready" ? prev : { status: "loading" }));
    api.GET("/notifications/mine").then(({ data, error }) => {
      if (error || !data) {
        setState({ status: "error" });
        return;
      }
      setState({ status: "ready", notifications: data as Notification[] });
    });
  }

  useEffect(() => {
    if (auth.status === "authenticated") load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.status]);

  // Close the dropdown on an outside click.
  useEffect(() => {
    if (!open) return;
    function handleOutsideClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsideClick);
    return () => document.removeEventListener("mousedown", handleOutsideClick);
  }, [open]);

  function toggleOpen() {
    const next = !open;
    setOpen(next);
    if (next) load();
  }

  function handleNotificationClick(notification: Notification) {
    if (notification.readAt === null) {
      // Optimistic: reflect "read" immediately rather than waiting on the
      // network, same convention as InventoryStepperRow's stepper.
      setState((prev) =>
        prev.status === "ready"
          ? {
              status: "ready",
              notifications: prev.notifications.map((n) =>
                n.id === notification.id
                  ? { ...n, readAt: new Date().toISOString() }
                  : n
              ),
            }
          : prev
      );
      api.POST("/notifications/{notificationId}/read", {
        params: { path: { notificationId: notification.id } },
      });
    }

    if (notification.poolId) {
      setOpen(false);
      router.push(`/pools/${notification.poolId}`);
    }
  }

  if (auth.status !== "authenticated") return null;

  const notifications = state.status === "ready" ? state.notifications : [];
  const unreadCount = notifications.filter((n) => n.readAt === null).length;

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={toggleOpen}
        aria-expanded={open}
        aria-label={
          unreadCount > 0
            ? `Notifications, ${unreadCount} unread`
            : "Notifications"
        }
        className="relative flex h-9 w-9 items-center justify-center rounded-full text-slate-600 hover:bg-slate-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.75}
          className="h-5 w-5"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M15 17h5l-1.4-1.4A2 2 0 0 1 18 14.2V11a6 6 0 1 0-12 0v3.2a2 2 0 0 1-.6 1.4L4 17h5m6 0a3 3 0 1 1-6 0m6 0H9"
          />
        </svg>
        {unreadCount > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-[1rem] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
            {unreadCount > 9 ? "9+" : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-20 mt-2 w-80 max-w-[90vw] rounded-lg border border-slate-200 bg-white shadow-lg">
          <div className="border-b border-slate-100 px-4 py-2.5">
            <h2 className="text-sm font-semibold text-slate-900">Notifications</h2>
          </div>

          <div className="max-h-96 overflow-y-auto">
            {state.status === "loading" && (
              <p className="px-4 py-6 text-center text-sm text-slate-500" role="status">
                Loading notifications…
              </p>
            )}
            {state.status === "error" && (
              <p className="px-4 py-6 text-center text-sm text-slate-500">
                Couldn't load notifications just now.
              </p>
            )}
            {state.status === "ready" && notifications.length === 0 && (
              <p className="px-4 py-6 text-center text-sm text-slate-500">
                You're all caught up.
              </p>
            )}
            {state.status === "ready" && notifications.length > 0 && (
              <ul>
                {notifications.map((n) => {
                  const unread = n.readAt === null;
                  return (
                    <li key={n.id} className="border-b border-slate-50 last:border-b-0">
                      <button
                        type="button"
                        onClick={() => handleNotificationClick(n)}
                        className={`block w-full px-4 py-3 text-left text-sm hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand-700 ${
                          unread ? "bg-brand-50/60" : "bg-white"
                        }`}
                      >
                        <span className="flex items-start gap-2">
                          <span
                            aria-hidden="true"
                            className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${
                              unread ? "bg-brand-600" : "bg-transparent"
                            }`}
                          />
                          <span className="min-w-0">
                            <span
                              className={
                                unread
                                  ? "block font-semibold text-slate-900"
                                  : "block font-normal text-slate-600"
                              }
                            >
                              {n.message}
                            </span>
                            <span className="mt-0.5 block text-xs text-slate-400">
                              {new Date(n.createdAt).toLocaleDateString()}
                            </span>
                          </span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
