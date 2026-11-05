import { supabase } from "./supabase";
import { resolveLocalUrl } from "./hosts";
import { POLICY_VERSION } from "../content/legal";
import { authToken, authUserId } from "./auth";

const api = (path: string) => `${resolveLocalUrl(import.meta.env.VITE_API_URL)}${path}`;
const token = async () => authToken();

export type PrivacyState = { accepted: boolean; isNew: boolean };

/** Has this caregiver agreed to the CURRENT policy version? null if we cannot tell (offline): then we do not ask. */
export async function loadPrivacyState(): Promise<PrivacyState | null> {
  const uid = authUserId();
  if (!uid) return null;
  const { data, error } = await supabase.from("profiles").select("privacy_accepted_at, policy_version").eq("id", uid).maybeSingle();
  if (error || !data) return null;
  return {
    accepted: !!data.privacy_accepted_at && data.policy_version === POLICY_VERSION,
    isNew: !data.privacy_accepted_at,  // never agreed (any account age): consent is required. 'Not now' is only for a newer policy version.
  };
}

export async function acceptPrivacy(): Promise<boolean> {
  const uid = authUserId();
  if (!uid) return false;
  const { data, error } = await supabase.from("profiles").update({ privacy_accepted_at: new Date().toISOString(), policy_version: POLICY_VERSION }).eq("id", uid).select("id");
  return !error && !!data?.length;
}

/** Downloads everything the caregiver can access as a JSON file. Returns false on any failure. */
export async function downloadMyData(): Promise<boolean> {
  try {
    const t = await token();
    if (!t) return false;
    const response = await fetch(api("/privacy/export"), { headers: { Authorization: `Bearer ${t}` } });
    if (!response.ok) return false;
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = `carelink-my-data-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
    return true;
  } catch {
    return false;
  }
}

async function post(path: string, body?: unknown): Promise<boolean> {
  try {
    const t = await token();
    if (!t) return false;
    const response = await fetch(api(path), {
      method: "POST",
      headers: { Authorization: `Bearer ${t}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(60_000),  // erasing files can take a while
    });
    return response.ok;
  } catch {
    return false;
  }
}

export const deleteMyAccount = () => post("/privacy/delete-account", { confirm: "DELETE" });
export const deleteParentData = (recipientId: string) => post(`/privacy/recipients/${recipientId}/delete`);
