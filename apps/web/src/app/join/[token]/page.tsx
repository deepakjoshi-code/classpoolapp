"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { InvitePreview, Membership } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { JoinForm } from "@/components/JoinForm";

type PreviewState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; preview: InvitePreview };

/**
 * Public invite landing page (PRD §2.2: "Landing page shows school/class
 * context and participation progress *before* auth"). This page must render
 * useful content for a signed-out visitor — the preview fetch has no auth
 * requirement (see contracts/openapi.yaml previewInvite: security: []).
 */
export default function InviteLandingPage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const router = useRouter();
  const auth = useCurrentUser();

  const [previewState, setPreviewState] = useState<PreviewState>({
    status: "loading",
  });
  const [joinedMembership, setJoinedMembership] = useState<Membership | null>(
    null
  );

  useEffect(() => {
    let cancelled = false;
    api.GET("/invites/{token}", { params: { path: { token } } }).then(
      ({ data, error }) => {
        if (cancelled) return;
        if (error || !data) {
          setPreviewState({ status: "error" });
          return;
        }
        // See src/lib/api/types.ts DeepRequired comment for why this cast.
        setPreviewState({ status: "ready", preview: data as InvitePreview });
      }
    );
    return () => {
      cancelled = true;
    };
  }, [token]);

  function handleContinue() {
    setPendingRedirect(`/join/${token}`);
    router.push(`/sign-in?redirect=${encodeURIComponent(`/join/${token}`)}`);
  }

  if (previewState.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading class invite…
      </div>
    );
  }

  if (previewState.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Invite not found
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          This link may have expired. Ask the class organizer for a new one.
        </p>
      </div>
    );
  }

  const { preview } = previewState;
  const { classroom, membersJoinedCount, studentCountEstimate } = preview;

  if (joinedMembership) {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-xl font-bold text-slate-900">You're in!</h1>
        <p className="mt-2 text-sm text-slate-600">
          {joinedMembership.studentFirstName} is now part of{" "}
          {classroom.grade} · {classroom.teacherLabel}.
          {joinedMembership.lateJoin && (
            <>
              {" "}
              This pool is already underway, so pricing is locked in at the
              current rate for your household.
            </>
          )}
        </p>
        <a
          href="/"
          className="mt-6 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Go to my household dashboard
        </a>
      </div>
    );
  }

  return (
    <div className="px-4 py-8">
      <div className="rounded-lg border border-brand-200 bg-brand-50 p-4 text-center">
        <h1 className="text-lg font-semibold text-brand-900">
          {classroom.grade} · {classroom.teacherLabel}
        </h1>
        <p className="text-sm text-brand-800">{classroom.schoolName}</p>
        <p className="mt-1 text-xs text-brand-700">{classroom.schoolYearLabel}</p>
      </div>

      <div className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
        <p className="text-sm font-medium text-slate-800">
          {membersJoinedCount} famil{membersJoinedCount === 1 ? "y" : "ies"}{" "}
          joined so far
          {studentCountEstimate ? ` out of about ${studentCountEstimate}` : ""}
        </p>
        {studentCountEstimate ? (
          <div
            className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-100"
            role="progressbar"
            aria-valuenow={membersJoinedCount}
            aria-valuemin={0}
            aria-valuemax={studentCountEstimate}
            aria-label="Families joined"
          >
            <div
              className="h-full rounded-full bg-brand-600"
              style={{
                width: `${Math.min(
                  100,
                  Math.round((membersJoinedCount / studentCountEstimate) * 100)
                )}%`,
              }}
            />
          </div>
        ) : null}
      </div>

      <div className="mt-6">
        {auth.status === "loading" && (
          <div className="text-center text-sm text-slate-500" role="status">
            Checking sign-in status…
          </div>
        )}

        {auth.status === "anonymous" && (
          <button
            type="button"
            onClick={handleContinue}
            className="w-full rounded-lg bg-brand-700 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
          >
            Continue to join this class
          </button>
        )}

        {auth.status === "authenticated" && (
          <JoinForm token={token} onJoined={setJoinedMembership} />
        )}
      </div>
    </div>
  );
}
