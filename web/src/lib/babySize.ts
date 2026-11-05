/**
 * PREGNANCY: the baby's approximate size by week, for one calm line on the week card ("Baby is about the size of a mango").
 *
 * Approximate, for interest only — every baby grows differently. Must be read by a gynaecologist before release.
 * (docs/use-cases.md, next to the pregnancy checks note.)
 *
 * LENGTH: crown-to-rump (top of the head to the bottom) for weeks 8-19, crown-to-heel (head to toes) from week 20, as the
 * common charts do. That is why the length jumps between week 19 (about 15 cm) and week 20 (about 26 cm).
 * WEIGHT: grams. Week = completed weeks of pregnancy, the same number the card shows (web/src/lib/pregnancy.ts).
 *
 * Sources (checked 2026-11-05):
 * - The average fetal length and weight chart used by BabyCenter (based on Hadlock-type ultrasound growth data). BabyCenter's
 *   own page could not be fetched; the identical table is widely reposted, e.g.
 *   https://kakya1982.blogspot.com/2011/06/average-fetal-length-and-weight-chart.html . ALL numbers below are taken from it.
 * - Vinmec "Table of fetal weight and length according to WHO standards"
 *   https://www.vinmec.com/eng/blog/table-of-fetal-weight-and-length-according-to-who-standards-en : same values for weeks
 *   8-10 and 28; slightly heavier mid-pregnancy (24 w: 32 cm, 665 g; 40 w: 51 cm, 3338 g) and it has typos (week 14 length).
 * - FirstCry Parenting fruit comparisons (2019) https://parenting.firstcry.ae/?p=9935 : same numbers as the BabyCenter chart.
 * - UK pages (HiPP, Mother&Baby, 2024-25) give about 600 g at week 24, as the BabyCenter chart does.
 * When the sources differ, the BabyCenter chart is used because it is the most widely used and copied week-by-week table.
 * Values are rounded for display (cm: one decimal below 10 cm, whole cm above; grams below 1 kg, from 1 kg as "1.3 kg").
 *
 * COMPARISONS are chosen to be familiar to Indian families and of a plausible size for that week (by length early on, by
 * weight late in pregnancy, where the baby is curled up). Each has a Microsoft Fluent Emoji 3D icon in public/icons/3d/
 * (MIT License, Copyright (c) Microsoft Corporation, https://github.com/microsoft/fluentui-emoji , see
 * public/icons/3d/LICENSE-fluentui-emoji.txt); the emoji is the text fallback when the image does not load.
 *
 * Wording rules (medical safety, PCPNDT): size only. Nothing about the sex of the baby, no "normal / small / big", no advice.
 */
export type BabySize = {
  week: number;
  /** "a mango" / "एक आम": read inside "Baby is about the size of {item}". */
  item: { en: string; hi: string };
  emoji: string;
  /** File name in public/icons/3d/ (without .webp). */
  icon: string;
  /** Approximate length in cm (crown-to-rump before week 20, crown-to-heel from week 20). */
  cm: number;
  /** Approximate weight in grams. */
  g: number;
};

const row = (week: number, en: string, hi: string, emoji: string, icon: string, cm: number, g: number): BabySize =>
  ({ week, item: { en, hi }, emoji, icon, cm, g });

export const BABY_SIZES: BabySize[] = [
  row(8, "a rajma bean", "एक राजमा के दाने", "🫘", "beans", 1.6, 1),
  row(9, "a grape", "एक अंगूर", "🍇", "grapes", 2.3, 2),
  row(10, "a strawberry", "एक स्ट्रॉबेरी", "🍓", "strawberry", 3.1, 4),
  row(11, "a garlic bulb", "लहसुन की एक गाँठ", "🧄", "garlic", 4.1, 7),
  row(12, "a lemon", "एक नींबू", "🍋", "lemon", 5.4, 14),
  row(13, "an onion", "एक प्याज़", "🧅", "onion", 7.4, 23),
  row(14, "an apple", "एक सेब", "🍎", "red-apple", 8.7, 43),
  row(15, "an orange", "एक संतरे", "🍊", "tangerine", 10.1, 70),
  row(16, "a pear", "एक नाशपाती", "🍐", "pear", 11.6, 100),
  row(17, "a big potato", "एक बड़े आलू", "🥔", "potato", 13, 140),
  row(18, "a mango", "एक आम", "🥭", "mango", 14.2, 190),
  row(19, "a cucumber", "एक खीरे", "🥒", "cucumber", 15.3, 240),
  row(20, "a long carrot", "एक लंबी गाजर", "🥕", "carrot", 25.6, 300),
  row(21, "a long kakdi", "एक लंबी ककड़ी", "🥒", "cucumber", 26.7, 360),
  row(22, "a long brinjal", "एक लंबे बैंगन", "🍆", "eggplant", 27.8, 430),
  row(23, "a bunch of spinach", "पालक की एक गड्डी", "🥬", "leafy-green", 28.9, 501),
  row(24, "a corn cob (bhutta)", "एक भुट्टे", "🌽", "ear-of-corn", 30, 600),
  row(25, "a broccoli", "एक ब्रोकली", "🥦", "broccoli", 34.6, 660),
  row(26, "a big round brinjal", "एक बड़े गोल बैंगन", "🍆", "eggplant", 35.6, 760),
  row(27, "a cabbage", "एक पत्तागोभी", "🥬", "leafy-green", 36.6, 875),
  row(28, "a coconut", "एक नारियल", "🥥", "coconut", 37.6, 1005),
  row(29, "a pineapple", "एक अनानास", "🍍", "pineapple", 38.6, 1153),
  row(30, "a small melon (kharbuja)", "एक छोटे ख़रबूज़े", "🍈", "melon", 39.9, 1319),
  row(31, "a big pineapple", "एक बड़े अनानास", "🍍", "pineapple", 41.1, 1502),
  row(32, "a melon (kharbuja)", "एक ख़रबूज़े", "🍈", "melon", 42.4, 1702),
  row(33, "a big melon", "एक बड़े ख़रबूज़े", "🍈", "melon", 43.7, 1918),
  row(34, "a big melon", "एक बड़े ख़रबूज़े", "🍈", "melon", 45, 2146),
  row(35, "a small watermelon", "एक छोटे तरबूज़", "🍉", "watermelon", 46.2, 2383),
  row(36, "a small watermelon", "एक छोटे तरबूज़", "🍉", "watermelon", 47.4, 2622),
  row(37, "a small watermelon", "एक छोटे तरबूज़", "🍉", "watermelon", 48.6, 2859),
  row(38, "a watermelon", "एक तरबूज़", "🍉", "watermelon", 49.8, 3083),
  row(39, "a watermelon", "एक तरबूज़", "🍉", "watermelon", 50.7, 3288),
  row(40, "a watermelon", "एक तरबूज़", "🍉", "watermelon", 51.2, 3462),
];

/** The row for this completed week, or null (before week 8, after week 40, or no week). */
export const babySizeFor = (week: number | null | undefined): BabySize | null =>
  week === null || week === undefined ? null : BABY_SIZES.find((s) => s.week === week) ?? null;

/** "/icons/3d/mango.webp": served from public/, so only the one image on screen is ever downloaded. */
export const babySizeIcon = (size: BabySize) => `/icons/3d/${size.icon}.webp`;

/** Rounded for the card: "1.6" below 10 cm, whole cm above; grams below 1 kg, "1.3" kg above. */
export const roundCm = (cm: number) => (cm < 10 ? String(Math.round(cm * 10) / 10) : String(Math.round(cm)));
export const roundWeight = (g: number): { unit: "g" | "kg"; value: string } =>
  g < 1000 ? { unit: "g", value: String(g < 100 ? g : Math.round(g / 10) * 10) } : { unit: "kg", value: String(Math.round(g / 100) / 10) };
