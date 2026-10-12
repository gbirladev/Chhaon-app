import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { IntlProvider } from "use-intl";
import en from "@/messages/en.json";
import hi from "@/messages/hi.json";
import { useAuth } from "@/lib/auth";
import { supabase } from "@/lib/supabase";

export type Locale = "en" | "hi";
const MESSAGES = { en, hi } as const;
const STORAGE_KEY = "carelink-locale";

/** Saved choice first; otherwise follow the phone's language (Hindi phones get Hindi). */
function initialLocale(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === "en" || saved === "hi") return saved;
  } catch { /* storage blocked: fall through */ }
  return navigator.language?.toLowerCase().startsWith("hi") ? "hi" : "en";
}

const LocaleContext = createContext<{ locale: Locale; setLocale: (l: Locale) => void } | null>(null);

export function IntlRoot({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [locale, setLocaleState] = useState<Locale>(initialLocale);

  useEffect(() => { document.documentElement.lang = locale; }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch { /* ignore */ }
    if (user) void supabase.from("profiles").update({ language: next }).eq("id", user.id);
  }, [user]);

  const value = useMemo(() => ({ locale, setLocale }), [locale, setLocale]);
  return (
    <LocaleContext.Provider value={value}>
      <IntlProvider locale={locale} messages={MESSAGES[locale]} onError={() => {}}>{children}</IntlProvider>
    </LocaleContext.Provider>
  );
}

export function useLocaleSwitch() {
  const ctx = useContext(LocaleContext);
  if (!ctx) throw new Error("useLocaleSwitch must be used inside IntlRoot");
  return ctx;
}
