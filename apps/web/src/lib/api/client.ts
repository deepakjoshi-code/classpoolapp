import createClient from "openapi-fetch";
import type { paths } from "./generated/types";

/**
 * Base URL for the API. The contract's `servers` entry is a relative path
 * (`/api/v1`), which means apps/web expects apps/api to be reachable at the
 * same origin (e.g. behind a shared reverse proxy / rewrite) OR you can point
 * this at an absolute host via NEXT_PUBLIC_API_BASE_URL for local dev where
 * the two apps run on different ports. See apps/web/README.md.
 */
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "/api/v1";

/**
 * The API's origin with no `/api/v1` suffix — needed for endpoints the
 * backend serves outside that prefix, e.g. Spring Security's conventional
 * `/oauth2/authorization/{registrationId}` login-kickoff redirect (see
 * SignInForm.tsx). Bug found during integration: SignInForm previously built
 * this URL as `${API_BASE_URL}/oauth2/authorization/google`, which resolves
 * to `.../api/v1/oauth2/authorization/google` and 404s, since the backend
 * registers that route at its root, not under /api/v1.
 */
const API_ORIGIN = API_BASE_URL.replace(/\/api\/v1\/?$/, "");

/**
 * Thin typed fetch wrapper around the OpenAPI-generated types
 * (src/lib/api/generated/types.ts). This is the ONLY place that should call
 * fetch() against the ClassPool API — every request/response shape below is
 * derived from contracts/openapi.yaml via openapi-typescript, so there are no
 * hand-written interfaces that could drift from the contract.
 *
 * Sessions are cookie-based (`CLASSPOOL_SESSION`, see openapi.yaml
 * securitySchemes.sessionCookie), so we always send credentials.
 */
export const api = createClient<paths>({
  baseUrl: API_BASE_URL,
  credentials: "include",
});

export { API_BASE_URL, API_ORIGIN };
