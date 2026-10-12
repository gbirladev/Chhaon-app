import { IntlProvider } from "use-intl";
import en from "./messages/en.json";
import hi from "./messages/hi.json";
import type { Language } from "./lib/format";

export const MESSAGES = { en, hi } as const;

export function AppIntl({ language, children }: { language: Language; children: React.ReactNode }) {
  return (
    <IntlProvider locale={language} messages={MESSAGES[language]} onError={(error) => { if (import.meta.env.DEV) console.warn(error.message); }}>
      {children}
    </IntlProvider>
  );
}
