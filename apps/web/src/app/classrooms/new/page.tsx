"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { CreateClassroomForm } from "@/components/CreateClassroomForm";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";

export default function NewClassroomPage() {
  const auth = useCurrentUser();
  const router = useRouter();

  useEffect(() => {
    if (auth.status === "anonymous") {
      setPendingRedirect("/classrooms/new");
      router.replace("/sign-in?redirect=%2Fclassrooms%2Fnew");
    }
  }, [auth.status, router]);

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

  return (
    <div className="px-4 py-8">
      <h1 className="text-2xl font-bold text-slate-900">Create a class</h1>
      <p className="mt-1 mb-6 text-sm text-slate-600">
        You'll become the organizer for this class — you can add a
        co-organizer later.
      </p>
      <CreateClassroomForm
        onCreated={(classroom) => router.push(`/classrooms/${classroom.id}/invite`)}
      />
    </div>
  );
}
