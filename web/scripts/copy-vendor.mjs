// Copies the QR library (already an app dependency, MIT) into public/vendor/ so static pages such as
// /doctors can draw QR codes in the browser without a third-party CDN. public/vendor/ is gitignored:
// the version stays pinned by package-lock.json. Runs before `npm run dev` and `npm run build`.
import { copyFileSync, mkdirSync } from "node:fs";

mkdirSync("public/vendor", { recursive: true });
copyFileSync("node_modules/qrcode-generator/dist/qrcode.js", "public/vendor/qrcode.js");
console.log("vendor files copied: public/vendor/qrcode.js");
