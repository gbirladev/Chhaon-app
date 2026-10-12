import { useMemo } from "react";
import qrcode from "qrcode-generator";

/** Drawn in the browser (no outside service): the link never leaves the device to make the picture. */
export function QrCode({ value, label, size = 200 }: { value: string; label: string; size?: number }) {
  const { path, count } = useMemo(() => {
    const qr = qrcode(0, "M");
    qr.addData(value);
    qr.make();
    const n = qr.getModuleCount();
    let d = "";
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c + 4} ${r + 4}h1v1h-1z`;
    return { path: d, count: n + 8 };  // 4-module quiet zone, as the QR standard asks
  }, [value]);
  return (
    <svg role="img" aria-label={label} width={size} height={size} viewBox={`0 0 ${count} ${count}`} shapeRendering="crispEdges" style={{ display: "block", margin: "0 auto", background: "#fff", borderRadius: 12, maxWidth: "100%", height: "auto" }}>
      <path d={path} fill="#000" />
    </svg>
  );
}
