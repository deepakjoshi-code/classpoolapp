"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { HouseholdDashboard } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { ClassroomCard } from "@/components/ClassroomCard";

type DashboardState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; dashboard: HouseholdDashboard };

/**
 * Household dashboard (post-auth "/"). Multi-class view per PRD §12's
 * update: every classroom this household belongs to, each as its own card —
 * not a single-pool screen.
 */
export default function HouseholdDashboardPage() {
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<DashboardState>({ status: "loading" });

  useEffect(() => {
    if (auth.status !== "authenticated") return;

    let cancelled = false;
    api.GET("/household/dashboard").then(({ data, error }) => {
      if (cancelled) return;
      if (error || !data) {
        setState({ status: "error" });
        return;
      }
      // See src/lib/api/types.ts DeepRequired comment for why this cast.
      setState({ status: "ready", dashboard: data as HouseholdDashboard });
    });
    return () => {
      cancelled = true;
    };
  }, [auth.status]);

  useEffect(() => {
    if (auth.status === "anonymous") {
      router.replace("/sign-in");
    }
  }, [auth.status, router]);

  if (auth.status === "loading" || (auth.status === "authenticated" && state.status === "loading")) {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading your classes…
      </div>
    );
  }

  if (auth.status === "anonymous") {
    return null;
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Couldn't load your dashboard
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          Please check your connection and try reloading.
        </p>
      </div>
    );
  }

  const memberships = state.status === "ready" ? state.dashboard.memberships : [];

  return (
    <div className="px-4 py-8">
      <h1 className="text-2xl font-bold text-slate-900">Your classes</h1>
      <p className="mt-1 text-sm text-slate-600">
        Every class pool your household belongs to, in one place.
      </p>

      {memberships.length === 0 ? (
        <div className="mt-8 rounded-lg border border-dashed border-slate-300 p-6 text-center">
          <p className="text-sm text-slate-600">
            You haven't joined any classes yet.
          </p>
          <a
            href="/classrooms/new"
            className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
          >
            Create a class
          </a>
        </div>
      ) : (
        <>
          <ul className="mt-6 space-y-3">
            {memberships.map((m) => (
              <li key={m.id}>
                <ClassroomCard membership={m} />
              </li>
            ))}
          </ul>
          <a
            href="/classrooms/new"
            className="mt-6 block rounded-lg border border-dashed border-slate-300 px-4 py-3 text-center text-sm font-medium text-slate-600 hover:border-brand-400 hover:text-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            + Start another class
          </a>
        </>
      )}
    </div>
  );
}
