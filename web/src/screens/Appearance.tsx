import { useRef, type KeyboardEvent } from "react";
import { useTranslations } from "use-intl";
import { THEME_CHOICES, useThemeChoice } from "../lib/theme";
import "./appearance.css";

/** Family tab card: System / Light / Dark as a radio group (arrow keys move and select, Tab leaves the group). */
export function AppearanceCard() {
  const t = useTranslations("appearance");
  const [choice, setChoice] = useThemeChoice();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKey = (event: KeyboardEvent, index: number) => {
    const step = event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 : event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = (index + step + THEME_CHOICES.length) % THEME_CHOICES.length;
    setChoice(THEME_CHOICES[next]);
    refs.current[next]?.focus();
  };
  return (
    <section className="appearance-card" aria-labelledby="appearance-title">
      <h3 id="appearance-title">{t("title")}</h3>
      <p>{t("hint")}</p>
      <div className="appearance-options" role="radiogroup" aria-labelledby="appearance-title">
        {THEME_CHOICES.map((value, index) => (
          <button key={value} ref={(el) => { refs.current[index] = el; }} type="button" role="radio" aria-checked={choice === value}
            tabIndex={choice === value ? 0 : -1} className={choice === value ? "on" : ""} onClick={() => setChoice(value)} onKeyDown={(event) => onKey(event, index)}>
            {t(value)}
          </button>
        ))}
      </div>
      <small>{t(`${choice}Note`)}</small>
    </section>
  );
}
