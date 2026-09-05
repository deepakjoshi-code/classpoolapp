"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import type { Payment, PoolDetail } from "@/lib/api/types";
import { useCurrentUser } from "@/lib/use-current-user";
import { setPendingRedirect } from "@/lib/pending-redirect";
import { formatCents } from "@/lib/money";
import { paymentStateLabel } from "@/lib/pool-labels";

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; pool: PoolDetail; payment: Payment | null };

/**
 * A household's own payment screen for a pool (PRD §8.4) — `GET
 * /pools/{poolId}/payments/mine`, a sibling "my own view" subpage to
 * `/pools/[id]/inventory` and `/pools/[id]/contribute`. Linked from the pool
 * detail page once `hasPayments(pool.state)` (`PAYMENT_OPEN` or later) —
 * before that, calling `.../payments/mine` would just come back `null`
 * every time, so the link itself is gated on pool state rather than an
 * extra round trip just to decide whether to show it.
 *
 * `null` is a normal, non-error response (either payments haven't been
 * generated yet, or this household has no residual purchase to pay for) —
 * same "absence is a valid state" pattern as `MyAllocationPanel`'s empty
 * array.
 *
 * PRIVACY: `Payment.householdDisplayName` is null on this "mine" endpoint
 * per the contract's own doc comment ("null on the household's own 'mine'
 * view, where it would be redundant") — this page never reads or renders
 * it, exactly the boundary `ContributionOfferCard` draws for
 * `offeringParentDisplayName`.
 *
 * PAYMENT DISCLOSURE (PRD §8.4): before the pay action, this page always
 * shows "you're paying the class organizer, not ClassPool" — required
 * regardless of V1's stub implementation of `pay` (no real Stripe charge
 * happens yet; see the component-level note on the button below). There's
 * no organizer-name field anywhere in the contract (`Classroom.teacherLabel`
 * names the teacher, not necessarily the organizing parent), so this uses
 * the honest fallback "the class organizer" rather than inventing a name.
 */
export default function PoolPaymentPage() {
  const params = useParams<{ id: string }>();
  const poolId = params.id;
  const auth = useCurrentUser();
  const router = useRouter();
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [paying, setPaying] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (auth.status === "anonymous") {
      const path = `/pools/${poolId}/payment`;
      setPendingRedirect(path);
      router.replace(`/sign-in?redirect=${encodeURIComponent(path)}`);
    }
  }, [auth.status, poolId, router]);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      api.GET("/pools/{poolId}", { params: { path: { poolId } } }),
      api.GET("/pools/{poolId}/payments/mine", { params: { path: { poolId } } }),
    ]).then(([poolResult, paymentResult]) => {
      if (cancelled) return;
      if (poolResult.error || !poolResult.data || paymentResult.error) {
        setState({ status: "error" });
        return;
      }
      setState({
        status: "ready",
        // See src/lib/api/types.ts DeepRequired comment for why this cast.
        pool: poolResult.data as PoolDetail,
        payment: (paymentResult.data as Payment | null) ?? null,
      });
    });

    return () => {
      cancelled = true;
    };
  }, [poolId]);

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

  if (state.status === "loading") {
    return (
      <div className="px-4 py-16 text-center text-slate-600" role="status">
        Loading your payment…
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="px-4 py-16 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          Couldn't load this pool's payment
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          It may not exist, or you may not have access to it.
        </p>
        <a
          href={`/pools/${poolId}`}
          className="mt-4 inline-block rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
        >
          Back to pool
        </a>
      </div>
    );
  }

  const { pool, payment } = state;

  async function handlePay() {
    if (!payment) return;
    setPaying(true);
    setErrorMessage(null);

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/payments/{paymentId}/pay",
      {
        params: { path: { poolId, paymentId: payment.id } },
        body: { method: "CARD" },
      }
    );

    setPaying(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This payment isn't waiting on you anymore — refresh to see its current status."
          : "We couldn't process that just now. Please check your connection and try again."
      );
      return;
    }

    setState((prev) =>
      prev.status === "ready" ? { ...prev, payment: data as Payment } : prev
    );
  }

  return (
    <div className="px-4 py-8">
      <a
        href={`/pools/${poolId}`}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to {pool.name}
      </a>
      <h1 className="mt-2 text-2xl font-bold text-slate-900">
        Your payment
      </h1>

      {payment === null && (
        <p className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
          Nothing to pay right now — either payment hasn't opened for this
          pool yet, or your household doesn't have a purchase left to pay
          for.
        </p>
      )}

      {payment !== null && payment.state === "PENDING" && (
        <div className="mt-6 rounded-lg border-2 border-brand-200 bg-brand-50 p-4">
          <p className="text-2xl font-semibold text-brand-900">
            {formatCents(payment.amountCents)}
          </p>
          <p className="mt-1 text-sm text-brand-800">
            Your household's share of this pool's purchase.
          </p>

          <p className="mt-3 rounded-md bg-white p-3 text-sm text-slate-700">
            You're paying the class organizer — not ClassPool. Payment goes
            straight to them; ClassPool never holds your money.
          </p>

          {errorMessage && (
            <p role="alert" className="mt-2 text-sm font-medium text-red-700">
              {errorMessage}
            </p>
          )}

          <button
            type="button"
            onClick={handlePay}
            disabled={paying}
            className="mt-3 w-full rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
          >
            {paying ? "Paying…" : `Pay ${formatCents(payment.amountCents)} with card`}
          </button>
          <p className="mt-1 text-xs text-brand-700">
            This is a placeholder in V1 — there's no real card entry or
            Stripe redirect yet, so this button marks your payment as paid
            immediately without actually charging a card.
          </p>
        </div>
      )}

      {payment !== null && payment.state !== "PENDING" && (
        <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
          <p className="text-2xl font-semibold text-slate-900">
            {formatCents(payment.amountCents)}
          </p>
          <p className="mt-1 text-sm text-slate-700">
            {paymentStateLabel(payment.state)}
          </p>
        </div>
      )}
    </div>
  );
}
