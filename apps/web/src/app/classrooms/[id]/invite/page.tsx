"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api } from "@/lib/api/client";
import type { Classroom, Invite } from "@/lib/api/types";
import { InviteShare } from "@/components/InviteShare";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; classroom: Classroom; invite: Invite };

export default function ClassroomInvitePage() {
  const params = useParams<{ id: string }>();
  const classroomId = params.id;
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const [classroomRes, inviteRes] = await Promise.all([
        api.GET("/classrooms/{classroomId}", {
          params: { path: { classroomId } },
        }),
        api.POST("/classrooms/{classroomId}/invites", {
          params: { path: { classroomId } },
          body: { channel: "LINK" },
        }),
      ]);

      if (cancelled) return;

      if (
        classroomRes.error ||
        !classroomRes.data ||
        inviteRes.error ||
        !inviteRes.data
      ) {
        setState({ status: "error" });
        return;
      }

      // See src/lib/api/types.ts DeepRequired comment for why these casts.
      setState({
        status: "ready",
        classroom: classroomRes.data as Classroom,
        invite: inviteRes.data as Invite,
      });
    }

    load();
    return () => {
      cancelled = true;
    };
  }, [classroomId]);

  if (state.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Setting up your invite…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Couldn't load this invite
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          The class may not exist, or you may not have access to it.
        </p>
        <a
          href="/"
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Back to home
        </a>
      </div>
    );
  }

  return (
    <div className="px-4 py-8">
      <h1 className="mb-1 text-2xl font-bold text-slate-900">Class created!</h1>
      <p className="mb-6 text-sm text-slate-600">
        Share this with families to get them into the pool.
      </p>
      <InviteShare invite={state.invite} classroom={state.classroom} />

      <div className="mt-8 rounded-lg border border-slate-200 bg-slate-50 p-4 text-center">
        <h2 className="text-base font-semibold text-slate-900">
          Next: build the supply list
        </h2>
        <p className="mt-1 text-sm text-slate-600">
          Start a pool to add what families need to bring — manually now,
          from a photo or PDF later. Nothing is billed until you confirm it.
        </p>
        <a
          href={`/classrooms/${classroomId}/pools/new`}
          className="mt-3 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Start your first pool
        </a>
      </div>

      <a
        href="/"
        className="mt-6 block text-center text-sm font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800"
      >
        Go to my household dashboard
      </a>
    </div>
  );
}
