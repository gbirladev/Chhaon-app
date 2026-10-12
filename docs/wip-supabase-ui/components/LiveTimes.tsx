import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";

function clock(tz: string, locale: string) {
  return new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { hour: "numeric", minute: "2-digit", timeZone: tz }).format(new Date());
}

/** Parent's local time and, when it differs, the caregiver's own time. */
export function LiveTimes({ parentTz, city }: { parentTz: string; city?: string | null }) {
  const t = useTranslations("dashboard");
  const locale = useLocale();
  const [text, setText] = useState("");

  useEffect(() => {
    const update = () => {
      const mine = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const theirs = `${city ? city + " • " : ""}${clock(parentTz, locale)}`;
      setText(mine === parentTz ? theirs : `${theirs} · ${t("yourTime")} ${clock(mine, locale)}`);
    };
    update();
    const id = window.setInterval(update, 30_000);
    return () => window.clearInterval(id);
  }, [parentTz, city, locale, t]);

  return <em>{text || " "}</em>;
}
