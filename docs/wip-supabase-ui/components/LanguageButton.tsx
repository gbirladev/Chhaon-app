import { useTranslations } from "use-intl";
import { useLocaleSwitch } from "@/i18n/IntlRoot";

export function LanguageButton() {
  const t = useTranslations("common");
  const { locale, setLocale } = useLocaleSwitch();
  return (
    <button type="button" className="language-button" aria-label={t("switchLanguageLabel")} onClick={() => setLocale(locale === "en" ? "hi" : "en")}>
      {t("switchLanguage")}
    </button>
  );
}
