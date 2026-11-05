import { createClient } from "@supabase/supabase-js";
import type { Database } from "./database.types";
import { resolveLocalUrl } from "./hosts";
import { authToken } from "./auth";

/** A request that never answers (phone dropped off Wi-Fi, server restarting) must fail, not leave a button on "Saving…" forever. */
const fetchWithTimeout: typeof fetch = (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  const limit = url.includes("/storage/v1/") ? 120_000 : 20_000;  // photo uploads on slow networks get longer
  if (typeof AbortSignal === "undefined" || !("timeout" in AbortSignal)) return fetch(input, init);
  const timeout = AbortSignal.timeout(limit);
  const signal = init?.signal && "any" in AbortSignal ? AbortSignal.any([init.signal, timeout]) : (init?.signal ?? timeout);
  return fetch(input, { ...init, signal });
};

// Login is owned by our Java backend now (phone OTP). We hand the token it issues to supabase-js via the
// `accessToken` provider, so every direct DB/storage call and its RLS keep working — but supabase-js's own
// auth (GoTrue: signInWithOtp/getSession/...) is disabled, which is what we want. See lib/auth.ts.
export const supabase = createClient<Database>(
  resolveLocalUrl(import.meta.env.VITE_SUPABASE_URL),
  import.meta.env.VITE_SUPABASE_ANON_KEY,
  {
    global: { fetch: fetchWithTimeout },
    accessToken: async () => authToken() ?? "",
  },
);
