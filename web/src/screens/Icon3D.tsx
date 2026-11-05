import { useState } from "react";
import { Icon } from "../App";
import "./icon3d.css";

/**
 * The 3D "picture" icons (Microsoft Fluent Emoji 3D, MIT; see public/icons/3d/LICENSE-fluentui-emoji.txt).
 * Used ONLY for: the Family page feature links, empty states,
 * and the headers of the doctor-visits / reports / emergency screens. Everything else stays the flat SVG Icon so it
 * follows the theme colour and dark mode: tabs, bell and header buttons, banners, list rows, buttons, sheets, section
 * headers, ticks, back / chevron / close / edit / plus. (Person badges and the baby size have their own 3D images.)
 *
 * Each name maps to one small WebP in public/icons/3d/ (96x96, ~2 KB), served from our own host and only downloaded
 * where it is shown. If the image cannot load, the flat Icon is shown in its place.
 */
const ICONS = {
  heart: { file: "red-heart", flat: "heart" },
  doctor: { file: "stethoscope", flat: "report" },
  whatsapp: { file: "telephone-receiver", flat: "phone" },
  shield: { file: "shield", flat: "shield" },
  chart: { file: "bar-chart", flat: "chart" },
  plan: { file: "clipboard", flat: "report" },
  lock: { file: "locked", flat: "shield" },
  feedback: { file: "speech-balloon", flat: "send" },
  install: { file: "mobile-phone-with-arrow", flat: "phone" },
  love: { file: "love-letter", flat: "heart" },
  people: { file: "people-hugging", flat: "users" },
  sun: { file: "sun", flat: "check" },
  calendar: { file: "spiral-calendar", flat: "calendar" },
  vaccine: { file: "syringe", flat: "shield" },
  pill: { file: "pill", flat: "pill" },
  page: { file: "page-facing-up", flat: "report" },
  camera: { file: "camera", flat: "camera" },
} as const;

export type Icon3DName = keyof typeof ICONS;

export function Icon3D({ name, size = 38, alt = "", eager = false, className = "" }: {
  name: Icon3DName;
  size?: number;
  /** Empty (decorative) by default: the button or label next to it carries the meaning. */
  alt?: string;
  /** Above the fold: load at once instead of lazily. */
  eager?: boolean;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const { file, flat } = ICONS[name];
  const classes = `icon-3d${className ? ` ${className}` : ""}`;
  if (failed) {
    return (
      <span className={`${classes} icon-3d-flat`} style={{ width: size, height: size }} role={alt ? "img" : undefined} aria-label={alt || undefined} aria-hidden={alt ? undefined : true}>
        <Icon name={flat} size={Math.round(size * 0.6)} />
      </span>
    );
  }
  return (
    <img className={classes} src={`/icons/3d/${file}.webp`} width={size} height={size} alt={alt} loading={eager ? "eager" : "lazy"} decoding="async" draggable={false} onError={() => setFailed(true)} />
  );
}
