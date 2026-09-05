import { NotificationBell } from "@/components/NotificationBell";

/**
 * Site-wide persistent header, mounted once in `layout.tsx` above every
 * page's own content (dashboard, sign-in, pool detail, …). Introduced in
 * this phase specifically to give `NotificationBell` one mount point rather
 * than duplicating it per-page — earlier phases had no shared chrome above
 * `<main>` because nothing needed one yet.
 */
export function SiteHeader() {
  return (
    <header className="sticky top-0 z-10 flex items-center justify-between border-b border-slate-200 bg-white/95 px-4 py-2.5 backdrop-blur">
      <a
        href="/"
        className="text-sm font-bold text-brand-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
      >
        ClassPool
      </a>
      <NotificationBell />
    </header>
  );
}
