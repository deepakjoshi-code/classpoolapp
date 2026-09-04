"use client";

import { useEffect, useState } from "react";
import QRCode from "qrcode";
import type { Classroom, Invite } from "@/lib/api/types";

type Props = {
  invite: Invite;
  classroom: Classroom;
};

function shareText(classroom: Classroom, joinUrl: string): string {
  return `Join our ClassPool for ${classroom.grade} · ${classroom.teacherLabel} (${classroom.schoolName}) — see what's needed and what you can skip buying: ${joinUrl}`;
}

export function InviteShare({ invite, classroom }: Props) {
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState<"link" | "text" | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(invite.qrPayload, { margin: 1, width: 240 }).then(
      (url) => {
        if (!cancelled) setQrDataUrl(url);
      }
    );
    return () => {
      cancelled = true;
    };
  }, [invite.qrPayload]);

  const text = shareText(classroom, invite.joinUrl);

  async function copyToClipboard(value: string, which: "link" | "text") {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(which);
      setTimeout(() => setCopied(null), 2000);
    } catch {
      // Clipboard API unavailable — the value is still visible/selectable on screen.
    }
  }

  async function handleShare() {
    if (navigator.share) {
      try {
        await navigator.share({
          title: "Join our ClassPool",
          text,
          url: invite.joinUrl,
        });
        return;
      } catch {
        // User cancelled the share sheet — no action needed.
        return;
      }
    }
    copyToClipboard(text, "text");
  }

  return (
    <div className="space-y-6">
      <div className="rounded-lg border border-brand-200 bg-brand-50 p-4 text-center">
        <h2 className="text-base font-semibold text-brand-900">
          {classroom.grade} · {classroom.teacherLabel}
        </h2>
        <p className="text-sm text-brand-800">{classroom.schoolName}</p>
      </div>

      <div className="flex justify-center">
        {qrDataUrl ? (
          <img
            src={qrDataUrl}
            alt={`QR code linking to the join page for ${classroom.grade} ${classroom.teacherLabel}`}
            width={240}
            height={240}
            className="rounded-lg border border-slate-200"
          />
        ) : (
          <div
            className="flex h-[240px] w-[240px] items-center justify-center rounded-lg border border-slate-200 text-sm text-slate-500"
            role="status"
          >
            Generating QR code…
          </div>
        )}
      </div>

      <div>
        <label htmlFor="join-url" className="block text-sm font-medium text-slate-700">
          Join link
        </label>
        <div className="mt-1 flex gap-2">
          <input
            id="join-url"
            type="text"
            readOnly
            value={invite.joinUrl}
            className="block w-full rounded-lg border border-slate-300 bg-slate-50 px-3 py-2.5 text-sm text-slate-800"
          />
          <button
            type="button"
            onClick={() => copyToClipboard(invite.joinUrl, "link")}
            className="shrink-0 rounded-lg border border-slate-300 px-3 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
          >
            {copied === "link" ? "Copied!" : "Copy"}
          </button>
        </div>
      </div>

      <button
        type="button"
        onClick={handleShare}
        className="w-full rounded-lg bg-brand-700 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-900"
      >
        {copied === "text" ? "Invite text copied!" : "Share invite"}
      </button>

      <p className="text-xs text-slate-500">
        Works for email, text, or your parent group chat — sharing opens your
        device's share sheet, or copies the message if it isn't available.
      </p>
    </div>
  );
}
