// Builds the Hindi home page (public/hi/index.html, served at https://chhaon.site/hi/) from public/home.html.
// Run: node scripts/build-hindi-home.mjs   (also runs before every `npm run build`). Edit home.html, never the output.
// Why: the home page holds both languages and switches with a button, so search engines mostly see English. A separate
// Hindi address with lang="hi", a Hindi title and description, and hreflang links lets Hindi searches find Chhaon.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const SITE = "https://chhaon.site";
const TITLE_EN = "Chhaon: WhatsApp medicine reminders for elderly parents";
const TITLE_HI = "छाँव: बुज़ुर्ग माता-पिता के लिए WhatsApp पर दवा के रिमाइंडर";
const DESC_EN = "WhatsApp medicine reminders for elderly parents, a pregnancy or a child. Family alerts if a dose is not confirmed, and a daily check-in. Nothing to install.";
const DESC_HI = "बुज़ुर्ग माता-पिता, गर्भावस्था या बच्चे के लिए WhatsApp पर दवा के रिमाइंडर। खुराक की पुष्टि न हो तो परिवार को सूचना, और रोज़ का हाल-चाल। कुछ भी इंस्टॉल नहीं करना।"

let s = readFileSync("public/home.html", "utf8");
const swap = (from, to, min = 1) => {
  const n = s.split(from).length - 1;
  if (n < min) throw new Error(`build-hindi-home: expected "${from.slice(0, 60)}" in home.html (found ${n})`);
  s = s.split(from).join(to);
};
// Every English text in home.html sits next to its Hindi one (<span class="en">…</span><span class="hi">…</span>, or the
// "btns … en" / "btns … hi" pairs). A missing Hindi twin would show nothing on /hi/, so the counts must match.
const count = (re) => (s.match(re) || []).length;
const enCount = count(/class="(?:[^"]* )?en(?: [^"]*)?"/g), hiCount = count(/class="(?:[^"]* )?hi(?: [^"]*)?"/g);
if (enCount !== hiCount) throw new Error(`build-hindi-home: ${enCount} English texts but ${hiCount} Hindi texts in home.html`);
// The page script (public/site-home.js) sets document.title when the language switches: it must say the same as the <title> tags.
const pageScript = readFileSync("public/site-home.js", "utf8");
if (!pageScript.includes(`"${TITLE_EN}"`) || !pageScript.includes(`"${TITLE_HI}"`)) throw new Error("build-hindi-home: document.title in public/site-home.js is out of step with TITLE_EN / TITLE_HI");
swap('<html lang="en" data-site="home">', '<html lang="hi" data-site="home">');
// Labels that live in attributes (screen readers read them; the <span class="hi"> twins cannot reach them).
const ARIA_HI = {
  "Switch language": "भाषा बदलें",
  "Main": "मुख्य",
  "Footer": "फ़ुटर",
  "Who it is for": "किसके लिए",
  "Example WhatsApp chat": "WhatsApp चैट का उदाहरण",
  "Papa gets a WhatsApp poll at medicine time and taps Taken; the family's phone shows Taken; a missed dose sends an alert":
    "दवा के समय पापा को WhatsApp पर पोल आता है और वे ले ली दबाते हैं; परिवार के फ़ोन पर ले ली दिखता है; दवा छूटे तो सूचना आती है",
};
for (const [en, hi] of Object.entries(ARIA_HI)) swap(`aria-label="${en}"`, `aria-label="${hi}"`);
const leftover = [...s.matchAll(/aria-label="([^"]*[A-Za-z][^"]*)"/g)].map((m) => m[1]).filter((v) => v !== "Chhaon" && !/[ऀ-ॿ]/.test(v));
if (leftover.length) throw new Error(`build-hindi-home: English aria-label(s) without Hindi: ${leftover.join(", ")}`);
swap(`<title>${TITLE_EN}</title>`, `<title>${TITLE_HI}</title>`);
swap(`content="${TITLE_EN}"`, `content="${TITLE_HI}"`, 2);   // og:title, twitter:title
swap(`content="${DESC_EN}"`, `content="${DESC_HI}"`, 3);     // description, og:description, twitter:description
swap(`<link rel="canonical" href="${SITE}/" />`, `<link rel="canonical" href="${SITE}/hi/" />`);
swap(`<meta property="og:url" content="${SITE}/" />`, `<meta property="og:url" content="${SITE}/hi/" />`);
swap('<meta property="og:locale" content="en_IN" />\n<meta property="og:locale:alternate" content="hi_IN" />',
     '<meta property="og:locale" content="hi_IN" />\n<meta property="og:locale:alternate" content="en_IN" />');

// Structured data: a Hindi page that belongs to the site, plus the Hindi questions and answers.
const strip = (h) => h.replace(/<[^>]+>/g, "").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim();
const faq = [...s.matchAll(/<details><summary>.*?<span class="hi">(.*?)<\/span><\/summary><p>.*?<span class="hi">(.*?)<\/span><\/p><\/details>/gs)]
  .map((m) => ({ "@type": "Question", name: strip(m[1]), acceptedAnswer: { "@type": "Answer", text: strip(m[2]) } }));
if (faq.length < 5) throw new Error(`build-hindi-home: found only ${faq.length} Hindi FAQ entries`);
const graph = { "@context": "https://schema.org", "@graph": [
  { "@type": "WebPage", "@id": `${SITE}/hi/`, url: `${SITE}/hi/`, name: TITLE_HI, description: DESC_HI, inLanguage: "hi-IN",
    isPartOf: { "@id": `${SITE}/#website` }, about: { "@id": `${SITE}/#app` } },
  { "@type": "FAQPage", inLanguage: "hi-IN", mainEntity: faq },
] };
const ld = /<script type="application\/ld\+json">[\s\S]*?<\/script>/;
if (!ld.test(s)) throw new Error("build-hindi-home: no structured data block in home.html");
s = s.replace(ld, `<script type="application/ld+json">\n${JSON.stringify(graph, null, 1)}\n</script>`);

mkdirSync("public/hi", { recursive: true });
writeFileSync("public/hi/index.html", "<!-- Generated by scripts/build-hindi-home.mjs from public/home.html. Do not edit. -->\n" + s);
console.log(`hi/index.html written (${faq.length} Hindi FAQ entries)`);
