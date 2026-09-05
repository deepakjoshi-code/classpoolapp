"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import type { ProductOffer } from "@/lib/api/types";
import { dollarsToCents, formatCents } from "@/lib/money";

type Props = {
  poolId: string;
  requirementId: string;
  requirementName: string;
  /** Offers already entered for this requirement, newest additions included. */
  offers: ProductOffer[];
  onAdded: (offer: ProductOffer) => void;
  onRemoved: (offerId: string) => void;
};

/**
 * Organizer's "add a price option" form for one requirement that still needs
 * buying (PRD §7.3) — `POST .../requirements/{requirementId}/product-offers`
 * — plus the list of offers already entered for it, with a remove action
 * (`DELETE .../product-offers/{offerId}`). Sibling to `RequirementForm`'s
 * add/edit pattern: its own local form state, POSTs, and calls back to its
 * parent (`OrganizerAllocationPanel`) with the created/removed offer rather
 * than owning the list itself — the panel already fetches every offer across
 * the pool in one call (`GET /pools/{poolId}/product-offers`) and groups by
 * requirement, so this component never fetches on its own.
 *
 * Money: the API works in integer cents (`priceCents`/`shippingCents`) but
 * this is the one place an organizer types a normal-looking price, so it's
 * also the one boundary that converts dollars <-> cents (`src/lib/money.ts`).
 *
 * Mounted only while `pool.state === "RECONCILING"` (the contract's own
 * gate — `addProductOffer`/`removeProductOffer` both 409 once a plan has
 * been generated) — see `OrganizerAllocationPanel`, which owns that check.
 */
export function ProductOfferForm({
  poolId,
  requirementId,
  requirementName,
  offers,
  onAdded,
  onRemoved,
}: Props) {
  const [retailer, setRetailer] = useState("");
  const [packQuantity, setPackQuantity] = useState("");
  const [price, setPrice] = useState("");
  const [shipping, setShipping] = useState("");
  const [affiliateUrl, setAffiliateUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const packQuantityNumber = Number(packQuantity);
  const priceCents = dollarsToCents(price);
  const canSubmit =
    retailer.trim().length > 0 &&
    packQuantity.trim().length > 0 &&
    Number.isInteger(packQuantityNumber) &&
    packQuantityNumber >= 1 &&
    price.trim().length > 0 &&
    Number.isFinite(priceCents) &&
    priceCents > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || submitting) return;

    setSubmitting(true);
    setErrorMessage(null);

    const shippingTrimmed = shipping.trim();
    const shippingCents = shippingTrimmed ? dollarsToCents(shippingTrimmed) : 0;
    const affiliateUrlTrimmed = affiliateUrl.trim();

    const { data, error, response } = await api.POST(
      "/pools/{poolId}/requirements/{requirementId}/product-offers",
      {
        params: { path: { poolId, requirementId } },
        body: {
          retailer: retailer.trim(),
          packQuantity: packQuantityNumber,
          priceCents,
          shippingCents: Number.isFinite(shippingCents) ? shippingCents : 0,
          affiliateUrl: affiliateUrlTrimmed ? affiliateUrlTrimmed : null,
        },
      }
    );

    setSubmitting(false);

    if (error || !data) {
      setErrorMessage(
        response.status === 409
          ? "This pool can no longer take new price options — a purchase plan may already be in progress."
          : "We couldn't add that price option just now. Please check your connection and try again."
      );
      return;
    }

    setRetailer("");
    setPackQuantity("");
    setPrice("");
    setShipping("");
    setAffiliateUrl("");
    onAdded(data as ProductOffer);
  }

  async function handleRemove(offerId: string) {
    setRemovingId(offerId);
    setErrorMessage(null);

    const { error, response } = await api.DELETE(
      "/pools/{poolId}/product-offers/{offerId}",
      { params: { path: { poolId, offerId } } }
    );

    setRemovingId(null);

    if (error) {
      setErrorMessage(
        response.status === 409
          ? "That can't be removed anymore — a purchase plan may already be in progress."
          : "We couldn't remove that just now. Please try again."
      );
      return;
    }

    onRemoved(offerId);
  }

  return (
    <div className="mt-3 border-t border-slate-100 pt-3">
      <p className="text-sm font-medium text-slate-800">
        Price options for {requirementName}
      </p>

      {offers.length > 0 && (
        <ul className="mt-2 space-y-1.5">
          {offers.map((offer) => (
            <li
              key={offer.id}
              className="flex items-center justify-between gap-3 rounded-md bg-slate-50 px-2.5 py-1.5 text-sm"
            >
              <span className="text-slate-700">
                <span className="font-medium text-slate-900">{offer.retailer}</span>
                {" · "}
                pack of {offer.packQuantity} · {formatCents(offer.priceCents)}
                {offer.shippingCents > 0 &&
                  ` + ${formatCents(offer.shippingCents)} shipping`}
              </span>
              <button
                type="button"
                onClick={() => handleRemove(offer.id)}
                disabled={removingId === offer.id}
                aria-label={`Remove ${offer.retailer} price option for ${requirementName}`}
                className="shrink-0 rounded-lg border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
              >
                {removingId === offer.id ? "Removing…" : "Remove"}
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={handleSubmit} className="mt-2 space-y-2" noValidate>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label
              htmlFor={`offer-retailer-${requirementId}`}
              className="block text-xs font-medium text-slate-700"
            >
              Retailer
            </label>
            <input
              id={`offer-retailer-${requirementId}`}
              type="text"
              value={retailer}
              onChange={(e) => setRetailer(e.target.value)}
              placeholder="Amazon"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            />
          </div>
          <div>
            <label
              htmlFor={`offer-packqty-${requirementId}`}
              className="block text-xs font-medium text-slate-700"
            >
              Pack size
            </label>
            <input
              id={`offer-packqty-${requirementId}`}
              type="number"
              inputMode="numeric"
              min={1}
              value={packQuantity}
              onChange={(e) => setPackQuantity(e.target.value)}
              placeholder="24"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <div>
            <label
              htmlFor={`offer-price-${requirementId}`}
              className="block text-xs font-medium text-slate-700"
            >
              Price
            </label>
            <input
              id={`offer-price-${requirementId}`}
              type="number"
              inputMode="decimal"
              step="0.01"
              min="0"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              placeholder="4.99"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            />
          </div>
          <div>
            <label
              htmlFor={`offer-shipping-${requirementId}`}
              className="block text-xs font-medium text-slate-700"
            >
              Shipping <span className="font-normal text-slate-500">(optional)</span>
            </label>
            <input
              id={`offer-shipping-${requirementId}`}
              type="number"
              inputMode="decimal"
              step="0.01"
              min="0"
              value={shipping}
              onChange={(e) => setShipping(e.target.value)}
              placeholder="0.00"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
            />
          </div>
        </div>

        <div>
          <label
            htmlFor={`offer-affiliate-${requirementId}`}
            className="block text-xs font-medium text-slate-700"
          >
            Link <span className="font-normal text-slate-500">(optional)</span>
          </label>
          <input
            id={`offer-affiliate-${requirementId}`}
            type="url"
            value={affiliateUrl}
            onChange={(e) => setAffiliateUrl(e.target.value)}
            placeholder="https://…"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-2.5 py-2 text-sm shadow-sm focus-visible:border-brand-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          />
        </div>

        {errorMessage && (
          <p role="alert" className="text-sm text-red-700">
            {errorMessage}
          </p>
        )}

        <button
          type="submit"
          disabled={!canSubmit || submitting}
          className="w-full rounded-lg border border-brand-700 bg-white px-3 py-2 text-sm font-semibold text-brand-800 hover:bg-brand-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        >
          {submitting ? "Adding…" : "Add this price option"}
        </button>
      </form>
    </div>
  );
}
