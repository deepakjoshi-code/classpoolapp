# ClassPool — Web (PWA)

Next.js 15 (App Router) + TypeScript PWA for ClassPool's parent/organizer-facing
frontend. Covers Phase 1 (PWA shell + auth) and Phase 2 (schools/classes/
memberships) of the V1 build order — see `../../ARCHITECTURE.md` §4 and
`../../docs/PRD.md` §17.3.

## Running it

```bash
npm install
npm run dev
```

Opens on http://localhost:3000.

This app expects `apps/api` (the Spring Boot backend) to be running and
reachable, per `contracts/openapi.yaml`'s relative server URL (`/api/v1`).
Two ways to wire that up locally:

- **Same-origin reverse proxy** (matches the contract literally): put both
  apps behind one host (e.g. a dev proxy, or Next's own `rewrites()`) so
  `/api/v1/*` on `localhost:3000` forwards to the API.
- **Cross-port dev** (simplest if you're just running both apps side by
  side): set `NEXT_PUBLIC_API_BASE_URL` to the API's origin, e.g.

  ```bash
  NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 npm run dev
  ```

  (see `src/lib/api/client.ts`).

Sessions are a cookie (`CLASSPOOL_SESSION`) set by the API — the frontend
never touches raw tokens, it just calls `fetch` with `credentials: "include"`.

## Testing

```bash
npm test          # vitest run (component tests)
npm run typecheck # tsc --noEmit
npm run build     # production build (also type-checks + lints via Next)
```

Component tests live in `src/tests/` (Vitest + React Testing Library), per
`../../ARCHITECTURE.md`'s testing strategy. At minimum:

- `CreateClassroomForm.test.tsx` — renders the create-class form, submits it,
  and covers both the plain-success path and the `dedupWarning` path (PRD
  §2.3's "Is this your class?" prompt) — including that the warning is
  actually rendered and actionable, not silently discarded.
- `JoinForm.test.tsx` — the join-via-invite step: student-name form → submit
  → calls `POST /invites/{token}/join`, plus a failure path.

## API client — generated from the contract

`contracts/openapi.yaml` is the source of truth. Nothing in this app
hand-writes a response/request interface that duplicates what the contract
already defines.

```bash
npm run generate:api
```

runs `openapi-typescript` against `../../contracts/openapi.yaml` and writes
`src/lib/api/generated/types.ts`. That output **is committed** — the app
builds without requiring this step on every install — but re-run it whenever
the contract changes.

- `src/lib/api/client.ts` — a thin `openapi-fetch` client typed against the
  generated `paths`. This is the only place that should call `fetch()`
  against the API.
- `src/lib/api/types.ts` — convenience re-exports of the generated component
  schemas, plus one narrowing utility (`DeepRequired`) applied to response
  types only. **Why:** none of the response schemas in `openapi.yaml`
  declare a `required:` list, so every field openapi-typescript generates
  for them is technically optional — even fields like `Classroom.id` that
  are obviously always present. Rather than hand-writing parallel "the real
  shape" interfaces (the exact duplication/drift risk generating from the
  contract is meant to avoid) or editing the shared contract unilaterally,
  this narrows once, in our own code, with the assumption documented inline.
  Flagged for the API side as a possible contract polish item.

## PWA implementation

Hand-rolled, not `next-pwa`:

- `public/manifest.json` — name/short_name/start_url/`display: standalone`/
  icons/theme, per PRD §11.2.
- `public/icons/*.png`, `public/apple-touch-icon.png` — placeholder icons
  (solid brand-blue background + "CP" monogram), generated with Pillow.
  Swap these for real artwork before shipping.
- `public/sw.js` — a small, readable service worker: precaches the app
  shell, network-first for navigations (falling back to cache, then to
  `public/offline.html`), cache-first for same-origin static assets, and
  **never** touches anything under `/api/` (no cached session state, no
  offline payments — PRD §11.4 explicitly rules that out). Registered by
  `src/components/ServiceWorkerRegister.tsx` in the root layout.
- `src/app/layout.tsx` — manifest link, `apple-web-app` meta tags for iOS
  home-screen installability, theme-color viewport meta.

Why hand-rolled instead of `next-pwa`: `next-pwa` wraps Workbox and adds a
build-time dependency whose config surface (runtime caching strategies,
Webpack plugin wiring) is bigger than this phase needs. A ~90-line service
worker covering exactly the three PRD §11.4 behaviors (cache app shell, show
last-cached state offline, never cache `/api/`) is easier for the next
engineer to read end-to-end than to configure through a plugin. Revisit if
later phases need more sophisticated offline queuing (§11.4's "queue simple
local inventory edits" — not yet built, out of scope for Phase 1-2).

## Pages built this pass

| Route | Purpose |
|---|---|
| `/sign-in` | Google button + email magic-link form, no password (PRD §2.2) |
| `/auth/verify` | Exchanges a magic-link token (`?token=`) for a session |
| `/` | Household dashboard — every classroom as its own card (PRD §12 multi-class update) |
| `/classrooms/new` | Create-a-class flow: school search/dedup → grade/teacher/year/count → handles `dedupWarning` |
| `/classrooms/[id]/invite` | Join link + QR + one-tap share, shown right after creation |
| `/join/[token]` | Public, pre-auth invite landing page → sign-in → student-name join step |

## Known discrepancies / assumptions against the contract

Flagged here rather than editing `contracts/openapi.yaml` unilaterally:

1. **Google OAuth initiation has no documented endpoint.** The contract
   documents `GET /auth/google/callback` (the OAuth2 redirect target) but not
   where the "Continue with Google" button should link to. Spring Security
   OAuth2 Client (per `ARCHITECTURE.md` §2) serves that redirect by
   convention at `/oauth2/authorization/{registrationId}`, not as a
   documented JSON operation, so `SignInForm` links there
   (`${API_BASE_URL}/oauth2/authorization/google`). If the backend uses a
   different path, this is a one-line change.
2. **The magic-link email's destination URL is assumed.** The contract only
   defines the token-exchange endpoint (`GET /auth/magic-link/verify`), not
   what URL the emailed link itself points to. `/auth/verify` in this app is
   built as that destination (reads `?token=`, calls the exchange endpoint,
   redirects). If the email is templated to point elsewhere, update that
   route accordingly.
3. **`createClassroom`'s dedup check runs *after* creation, not before.**
   The PRD narrative (§2.3) describes fuzzy-matching prompting the organizer
   *before* a new class is created ("creation is still self-serve, just with
   a dedup check in front of it"). The contract's actual behavior (per its
   own description on `POST /classrooms`) is that the classroom is created
   unconditionally and `dedupWarning` comes back informationally alongside
   it. There's also no endpoint for an organizer to undo that creation and
   join one of the flagged classrooms directly by ID — only
   `POST /invites/{token}/join` exists, which needs an invite token. The UI
   (`DedupWarning.tsx`) is built against the contract as written: it shows
   the warning prominently and never silently discards it, but "yes, this is
   mine" can only point the organizer at pasting an invite link/code from
   that class's real organizer, not perform a merge. Worth a follow-up
   contract conversation with the API side — either a pre-creation
   `POST /classrooms/check-dedup`-style endpoint, or a way to join/merge
   directly from the warning.
4. **Response schemas' missing `required:` lists** — see the `DeepRequired`
   note under "API client" above.
5. **No mobile 5-tab nav (`HOME | POOL | SHARE | ORDERS | PROFILE`, PRD §12)
   yet.** Only Phase 1-2 screens exist (auth, schools/classes/memberships);
   POOL/ORDERS have no endpoints yet in this pass's contract, so building
   nav items that lead nowhere seemed worse than omitting them. Add the full
   tab bar once those phases land.
