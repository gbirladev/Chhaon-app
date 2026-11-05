import { useTranslations } from "use-intl";
import { PILL_COLOURS, PILL_SHAPES, PILL_SWATCH, type PillColour, type PillShape } from "../lib/pillLook";
import "./pillLook.css";

/** A small drawing of the tablet: the chosen shape in the chosen colour (grey outline when no colour is chosen). */
export function PillGlyph({ colour, shape, size = 22 }: { colour: PillColour | null; shape: PillShape | null; size?: number }) {
  const fill = colour ? PILL_SWATCH[colour] : "transparent";
  const stroke = "var(--pill-outline)";
  const body = (() => {
    switch (shape) {
      case "oval": return <ellipse cx="12" cy="12" rx="10" ry="6.5" fill={fill} stroke={stroke} strokeWidth="1.5" />;
      case "long": return <rect x="2" y="7.5" width="20" height="9" rx="2.5" fill={fill} stroke={stroke} strokeWidth="1.5" />;
      case "capsule": return (
        <>
          <rect x="2" y="7" width="20" height="10" rx="5" fill={fill} stroke={stroke} strokeWidth="1.5" />
          <line x1="12" y1="7" x2="12" y2="17" stroke={stroke} strokeWidth="1.5" />
        </>
      );
      default: return <circle cx="12" cy="12" r="8.5" fill={fill} stroke={stroke} strokeWidth="1.5" />;
    }
  })();
  return <svg className="pill-glyph" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">{body}</svg>;
}

/** "Blue, Round" in the app's language, or "" when nothing is set. */
export function usePillLookText() {
  const t = useTranslations("add");
  return (colour: PillColour | null, shape: PillShape | null) =>
    [colour && t(`pillColours.${colour}`), shape && t(`pillShapes.${shape}`)].filter(Boolean).join(", ");
}

/** Two rows of chips on the medicine form. Tapping the chosen chip again clears it (both are optional). */
export function PillLookPicker({ colour, shape, call, onColour, onShape }: {
  colour: PillColour | null; shape: PillShape | null; call: string;
  onColour: (value: PillColour | null) => void; onShape: (value: PillShape | null) => void;
}) {
  const t = useTranslations("add");
  return (
    <div className="field pill-look" role="group" aria-labelledby="f-look-label">
      <p className="field-label" id="f-look-label">{t("lookTitle")}</p>
      <p className="hint">{t("lookHint", { call })}</p>
      <p className="pill-look-sub" id="f-colour-label">{t("colour")}</p>
      <div className="choice-chips wrap" role="group" aria-labelledby="f-colour-label">
        {PILL_COLOURS.map((value) => (
          <button type="button" key={value} className={colour === value ? "selected" : ""} aria-pressed={colour === value} onClick={() => onColour(colour === value ? null : value)}>
            <span className={`pill-swatch${value === "white" ? " light" : ""}`} style={{ background: PILL_SWATCH[value] }} aria-hidden="true" />
            {t(`pillColours.${value}`)}
          </button>
        ))}
      </div>
      <p className="pill-look-sub" id="f-shape-label">{t("shape")}</p>
      <div className="choice-chips wrap" role="group" aria-labelledby="f-shape-label">
        {PILL_SHAPES.map((value) => (
          <button type="button" key={value} className={shape === value ? "selected" : ""} aria-pressed={shape === value} onClick={() => onShape(shape === value ? null : value)}>
            <PillGlyph colour={colour} shape={value} />
            {t(`pillShapes.${value}`)}
          </button>
        ))}
      </div>
    </div>
  );
}
