import { LOGO_HEART, LOGO_ROOF } from "../lib/logo";

/** The Chhaon logo, drawn in the current colour theme (tile = --primary, roof and heart = --on-fill), so it follows
 *  the colour picker and dark mode. `inverse` swaps them (a light tile, marks in the primary colour) for a place that
 *  is already the primary colour, such as the WhatsApp header. With a label it is the brand ("Chhaon"); without one it is decorative. */
export function Logo({ size = 48, label, className, inverse = false }: { size?: number; label?: string; className?: string; inverse?: boolean }) {
  const tile = inverse ? "var(--surface)" : "var(--primary)";
  const ink = inverse ? "var(--primary-text)" : "var(--on-fill)";
  return (
    <svg className={className ? `logo ${className}` : "logo"} width={size} height={size} viewBox="0 0 64 64"
      role={label ? "img" : undefined} aria-label={label} aria-hidden={label ? undefined : true} focusable="false">
      <rect width="64" height="64" rx="14" style={{ fill: tile }} />
      <path fill="none" style={{ stroke: ink }} strokeWidth="5.5" strokeLinecap="round" strokeLinejoin="round" d={LOGO_ROOF} />
      <path style={{ fill: ink }} d={LOGO_HEART} />
    </svg>
  );
}
