import { useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useTranslations } from "use-intl";
import { THEME_CHOICES, useThemeChoice, type ThemeChoice } from "../lib/theme";
import { DEFAULT_PALETTE, PALETTES, PALETTE_SWATCH, swatchOf, usePalette } from "../lib/palette";
import { useDialog } from "../App";
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

/** The palette button at the top right of the Family page: opens the theme sheet. */
export function ThemeButton() {
  const t = useTranslations("appearance");
  const [open, setOpen] = useState(false);
  const [palette] = usePalette();
  return (
    <>
      <button type="button" className="icon-button theme-button" onClick={() => setOpen(true)} aria-label={t("openThemes")} aria-haspopup="dialog">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 3a9 9 0 1 0 0 18c1.1 0 1.8-.8 1.8-1.8 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.8 1.8-1.8H17a4 4 0 0 0 4-4c0-4.4-4-8-9-8z" />
          <circle cx="7.5" cy="11.5" r="1.2" fill="currentColor" /><circle cx="10.5" cy="7.5" r="1.2" fill="currentColor" /><circle cx="15" cy="7.5" r="1.2" fill="currentColor" />
        </svg>
        {palette !== DEFAULT_PALETTE && <span className="theme-dot" style={{ background: swatchOf(palette) }} aria-hidden="true" />}
      </button>
      {open && <ThemeSheet onClose={() => setOpen(false)} />}
    </>
  );
}

const MODE_ICONS: Record<ThemeChoice, ReactNode> = {
  system: <><rect x="7" y="2.5" width="10" height="19" rx="2.5" /><path d="M11 18.5h2" /></>,
  light: <><circle cx="12" cy="12" r="4" /><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4" /></>,
  dark: <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />,
};

/** Colour theme (Chhaon first, the default; then ready-made colours and a custom one) and light/dark, in one sheet.
 *  Every choice applies at once; "Back to default" returns to the Chhaon colours. */
function ThemeSheet({ onClose }: { onClose: () => void }) {
  const t = useTranslations("appearance");
  const [palette, custom, setPalette] = usePalette();
  const [mode, setMode] = useThemeChoice();
  const dialog = useDialog(onClose);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet theme-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="theme-sheet-title" onClick={(event) => event.stopPropagation()}>
        <h3 id="theme-sheet-title">{t("themesTitle")}</h3>
        <p className="theme-sheet-hint">{t("themesHint")}</p>

        <p className="theme-label" id="theme-colour-label">{t("colourLabel")}</p>
        <div className="theme-grid" role="radiogroup" aria-labelledby="theme-colour-label">
          {PALETTES.map((value) => (
            <button key={value} type="button" role="radio" aria-checked={palette === value} className={palette === value ? "on" : ""} onClick={() => setPalette(value)}>
              <span className="palette-swatch" style={{ background: PALETTE_SWATCH[value] }} aria-hidden="true">{palette === value && "✓"}</span>
              <span className="palette-name">{t(`palettes.${value}`)}</span>
              {value === DEFAULT_PALETTE && <small>{t("defaultTag")}</small>}
            </button>
          ))}
          <label className={`custom-tile${palette === "custom" ? " on" : ""}`}>
            <input type="color" value={custom} aria-label={t("customPick")} onChange={(event) => setPalette("custom", event.target.value)} />
            <span className="palette-swatch custom-swatch" style={palette === "custom" ? { background: swatchOf("custom") } : undefined} aria-hidden="true">{palette === "custom" ? "✓" : "+"}</span>
            <span className="palette-name">{t("palettes.custom")}</span>
          </label>
        </div>
        <p className="theme-note">{t(palette === "custom" ? "customNote" : "colourNote")}</p>
        {palette !== DEFAULT_PALETTE && <button type="button" className="secondary-button theme-reset" onClick={() => setPalette(DEFAULT_PALETTE)}>{t("backToDefault")}</button>}

        <p className="theme-label" id="theme-mode-label">{t("modeLabel")}</p>
        <div className="theme-grid mode-grid" role="radiogroup" aria-labelledby="theme-mode-label">
          {THEME_CHOICES.map((value) => (
            <button key={value} type="button" role="radio" aria-checked={mode === value} className={mode === value ? "on" : ""} onClick={() => setMode(value)}>
              <svg className="mode-icon" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{MODE_ICONS[value]}</svg>
              <span className="palette-name">{t(value)}</span>
            </button>
          ))}
        </div>
        <p className="theme-note">{t(`${mode}Note`)}</p>

        <button type="button" className="primary-button theme-done" onClick={onClose}>{t("done")}</button>
      </section>
    </div>
  );
}
