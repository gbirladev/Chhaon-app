/**
 * How a tablet looks (colour + shape), so a parent or a helper who cannot read the strip can still pick the right one.
 * The lists match the database checks in migration 0042 and the WhatsApp wording in the Java backend (MessageTexts.pillLook).
 */
export const PILL_COLOURS = ["white", "yellow", "orange", "pink", "red", "blue", "green", "brown", "purple"] as const;
export const PILL_SHAPES = ["round", "oval", "long", "capsule"] as const;
export type PillColour = (typeof PILL_COLOURS)[number];
export type PillShape = (typeof PILL_SHAPES)[number];

/** The swatch drawn on the chip and next to the medicine. White gets a border in CSS so it shows on a white card. */
export const PILL_SWATCH: Record<PillColour, string> = {
  white: "#ffffff",
  yellow: "#f6d443",
  orange: "#f39a3d",
  pink: "#f4a7c3",
  red: "#d8443c",
  blue: "#3f7fd6",
  green: "#3fa865",
  brown: "#8a5a3b",
  purple: "#8a5cc7",
};

export const asPillColour = (value: string | null | undefined): PillColour | null =>
  (PILL_COLOURS as readonly string[]).includes(value ?? "") ? (value as PillColour) : null;
export const asPillShape = (value: string | null | undefined): PillShape | null =>
  (PILL_SHAPES as readonly string[]).includes(value ?? "") ? (value as PillShape) : null;
