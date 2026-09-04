"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { CreatePoolForm } from "@/components/CreatePoolForm";

/**
 * Pool-creation entry point reached from the invite page's "start your first
 * pool" CTA and from the household dashboard's ClassroomCard for classrooms
 * with no active pool yet. Only an organizer/co-organizer on THIS classroom
 * may create a pool (contract 403s otherwise) — checked client-side via the
 * matching Membership from GET /me, same pattern as the rest of the app's
 * client-only auth gating (see useCurrentUser).
 */
export default function NewPoolPage() {
  const params = useParams<{ id: string }>();
  const classroomId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/classrooms/${classroomId}/pools/new`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, classroomId, router]);

  if (auth.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading…
      </div>
    );
  }

  if (auth.status === "anonymous") {
    return null;
  }

  const membership = auth.user.memberships.find(
    (m) => m.classroomId === classroomId
  );
  const isOrganizer =
    membership?.role === "ORGANIZER" || membership?.role === "CO_ORGANIZER";

  if (!isOrganizer) {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Organizers only
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          Only this class's organizer or co-organizer can start a pool.
        </p>
        <a
          href="/"
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Back to dashboard
        </a>
      </div>
    );
  }

  return (
    <div className="px-4 py-8">
      <h1 className="text-2xl font-bold text-slate-900">Start a pool</h1>
      <p className="mt-1 mb-6 text-sm text-slate-600">
        A pool tracks one supply list for this class — what's needed, how
        much, and from whom. You'll add items next.
      </p>
      <CreatePoolForm
        classroomId={classroomId}
        onCreated={(pool) => router.push(`/pools/${pool.id}`)}
      />
    </div>
  );
}
