import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useAuth } from "./auth";
import { supabase } from "./supabase";
import type { Tables } from "./database.types.helpers";

export type Recipient = Tables<"care_recipients">;
type State = { recipient: Recipient | null; loading: boolean; error: boolean; reload: () => Promise<void> };
const RecipientContext = createContext<State | null>(null);

/**
 * v1: one parent per caregiver (the oldest across their circles; RLS limits rows to their circles).
 * A parent selector arrives with multi-parent support.
 */
export function RecipientProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [recipient, setRecipient] = useState<Recipient | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const reload = useCallback(async () => {
    if (!user) { setRecipient(null); setLoading(false); return; }
    const { data, error } = await supabase
      .from("care_recipients").select("*").order("created_at", { ascending: true }).limit(1).maybeSingle();
    setError(!!error);
    setRecipient(data ?? null);
    setLoading(false);
  }, [user]);

  useEffect(() => { void reload(); }, [reload]);

  const value = useMemo(() => ({ recipient, loading, error, reload }), [recipient, loading, error, reload]);
  return <RecipientContext.Provider value={value}>{children}</RecipientContext.Provider>;
}

export function useRecipient() {
  const ctx = useContext(RecipientContext);
  if (!ctx) throw new Error("useRecipient must be used inside RecipientProvider");
  return ctx;
}

/** What to call the parent in the UI: Papa / Mummy / their name. */
export function callName(r: Pick<Recipient, "name" | "relation">, t: (k: "father" | "mother") => string) {
  return r.relation === "father" || r.relation === "mother" ? t(r.relation) : r.name;
}
