/**
 * Minimal hand-rolled service worker for basic app-shell caching (PRD §11.2,
 * §11.4). We deliberately did NOT use next-pwa here — see apps/web/README.md
 * "PWA implementation" for why — this file is small enough to read and
 * reason about directly instead.
 *
 * Strategy:
 *  - Precache a tiny app shell (manifest, icons, offline fallback) at install.
 *  - Navigations (HTML page loads): network-first, falling back to the last
 *    cached copy of that exact URL, and finally to the static offline page.
 *  - Same-origin GET requests for our own static assets (_next/static, etc):
 *    cache-first (they're content-hashed and safe to cache aggressively).
 *  - Everything under /api/ (session cookies, mutations, live pool data) is
 *    NEVER cached — §11.4 explicitly rules out offline payments/commerce, and
 *    caching auth responses would be a straight-up security bug.
 */

const CACHE_VERSION = "classpool-shell-v1";
const OFFLINE_URL = "/offline.html";
const PRECACHE_URLS = [
  "/",
  OFFLINE_URL,
  "/manifest.json",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_VERSION)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key !== CACHE_VERSION)
            .map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

function isApiRequest(url) {
  return url.pathname.startsWith("/api/");
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);

  // Only handle same-origin GET requests; let everything else (POST/PUT,
  // cross-origin, the API) pass straight through to the network untouched.
  if (request.method !== "GET" || url.origin !== self.location.origin) {
    return;
  }
  if (isApiRequest(url)) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_VERSION).then((cache) => cache.put(request, copy));
          return response;
        })
        .catch(
          () =>
            caches.match(request).then((cached) => cached) ||
            caches.match(OFFLINE_URL)
        )
        .then((res) => res || caches.match(OFFLINE_URL))
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_VERSION).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
    })
  );
});
