"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { SignInForm } from "@/components/SignInForm";

function SignInPageInner() {
  const searchParams = useSearchParams();
  const redirectTo = searchParams.get("redirect") ?? undefined;

  return (
    <div className="px-4 py-10 sm:py-16">
      <div className="mb-8 text-center">
        <h1 className="text-2xl font-bold text-slate-900">
          Sign in to ClassPool
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          Join your class pool, track savings, and pay only for what your
          family still needs.
        </p>
      </div>
      <SignInForm redirectTo={redirectTo} />
    </div>
  );
}

export default function SignInPage() {
  return (
    <Suspense fallback={null}>
      <SignInPageInner />
    </Suspense>
  );
}
