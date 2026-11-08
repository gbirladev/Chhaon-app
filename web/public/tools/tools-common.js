/* Free tools (/tools/*), shared helpers: page language toggle, small DOM helper, "Save as image" drawn on a canvas.
 * PRIVACY: everything here runs in the browser. Nothing typed is sent anywhere, stored, or put in the address bar.
 * No storage at all: the page language comes from ?lang=hi (a link choice, never user data) or the phone's language.
 * A file, not inline: the production CSP is script-src 'self'. */
(function () {
  var root = document.documentElement;
  var listeners = [];

  function initialLang() {
    try { var q = new URLSearchParams(location.search).get("lang"); if (q === "hi" || q === "en") return q; } catch (e) { /* ignore */ }
    var nav = (navigator.languages && navigator.languages[0]) || navigator.language || "en";
    return /^hi\b/i.test(nav) ? "hi" : "en";
  }
  var lang = initialLang();

  function applyLang() {
    root.setAttribute("data-ui", lang);
    root.setAttribute("lang", lang);
    var ph = document.querySelectorAll("[data-ph-en]");
    for (var i = 0; i < ph.length; i++) ph[i].setAttribute("placeholder", ph[i].getAttribute(lang === "hi" ? "data-ph-hi" : "data-ph-en") || "");
    var t = document.querySelector("meta[name='x-title-" + lang + "']");
    if (t) document.title = t.getAttribute("content");
    for (var j = 0; j < listeners.length; j++) listeners[j](lang);
  }
  root.setAttribute("data-ui", lang);

  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    if (attrs) for (var k in attrs) {
      if (attrs[k] === null || attrs[k] === undefined || attrs[k] === false) continue;
      if (k === "text") n.textContent = attrs[k];
      else if (k === "class") n.className = attrs[k];
      else n.setAttribute(k, attrs[k] === true ? "" : attrs[k]);
    }
    if (kids) for (var i = 0; i < kids.length; i++) if (kids[i] !== null && kids[i] !== undefined && kids[i] !== false) n.appendChild(typeof kids[i] === "string" ? document.createTextNode(kids[i]) : kids[i]);
    return n;
  }

  /* ---- Save as image: a simple portrait picture (1080 px wide) drawn line by line on a canvas, then downloaded as a PNG
   * through a blob: URL. Nothing is uploaded. items: [{text, size, bold, color, bg, pad, gap, align}] or {rule:true}. */
  var FONT = 'system-ui, -apple-system, "Segoe UI", "Noto Sans Devanagari", "Nirmala UI", sans-serif';
  function wrap(ctx, text, width) {
    var out = [];
    String(text).split("\n").forEach(function (para) {
      var words = para.split(/\s+/).filter(Boolean), line = "";
      if (!words.length) { out.push(""); return; }
      words.forEach(function (w) {
        var test = line ? line + " " + w : w;
        if (ctx.measureText(test).width <= width || !line) line = test; else { out.push(line); line = w; }
        while (ctx.measureText(line).width > width && line.length > 1) {   // one very long word: cut it
          var cut = line.length - 1;
          while (cut > 1 && ctx.measureText(line.slice(0, cut)).width > width) cut--;
          out.push(line.slice(0, cut)); line = line.slice(cut);
        }
      });
      out.push(line);
    });
    return out;
  }
  function drawImage(items, filename) {
    var W = 1080, M = 64, inner = W - 2 * M;
    var c = document.createElement("canvas"), ctx = c.getContext("2d");
    function font(it) { return (it.bold ? "700 " : "400 ") + (it.size || 36) + "px " + FONT; }
    var laid = items.map(function (it) {
      if (it.rule) return { it: it, h: 30 };
      ctx.font = font(it);
      var pad = it.bg ? (it.pad || 22) : 0;
      var lines = wrap(ctx, it.text, inner - 2 * pad);
      var lh = Math.round((it.size || 36) * 1.35);
      return { it: it, lines: lines, lh: lh, pad: pad, h: lines.length * lh + 2 * pad + (it.gap === undefined ? 14 : it.gap) };
    });
    var H = M * 2 + laid.reduce(function (s, l) { return s + l.h; }, 0);
    c.width = W; c.height = Math.max(H, 600);
    ctx.fillStyle = "#ffffff"; ctx.fillRect(0, 0, c.width, c.height);
    ctx.textBaseline = "top";
    var y = M;
    laid.forEach(function (l) {
      var it = l.it;
      if (it.rule) { ctx.fillStyle = "#cfcad8"; ctx.fillRect(M, y + 12, inner, 3); y += l.h; return; }
      if (it.bg) { ctx.fillStyle = it.bg; roundRect(ctx, M, y, inner, l.lines.length * l.lh + 2 * l.pad, 18); ctx.fill(); }
      ctx.font = font(it); ctx.fillStyle = it.color || "#1d1928";
      ctx.textAlign = it.align === "center" ? "center" : "left";
      var x = it.align === "center" ? W / 2 : M + l.pad;
      l.lines.forEach(function (line, i) { ctx.fillText(line, x, y + l.pad + i * l.lh + Math.round((l.lh - (it.size || 36)) / 2)); });
      y += l.h;
    });
    return new Promise(function (resolve, reject) {
      if (!c.toBlob) { reject(new Error("no toBlob")); return; }
      c.toBlob(function (blob) {
        if (!blob) { reject(new Error("no image")); return; }
        var url = URL.createObjectURL(blob);
        var a = el("a", { href: url, download: filename });
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
        resolve();
      }, "image/png");
    });
  }
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }

  /* A file name from the person's name, kept to plain letters (the name never leaves the phone; this only names the download). */
  function fileName(base, name) {
    var safe = String(name || "").normalize("NFKD").replace(/[^\w\s-]/g, "").trim().replace(/\s+/g, "-").slice(0, 40);
    return base + (safe ? "-" + safe : "") + ".png";
  }

  window.ChhaonTools = {
    lang: function () { return lang; },
    onLang: function (fn) { listeners.push(fn); },
    el: el,
    drawImage: drawImage,
    fileName: fileName,
  };

  function wire() {
    var btn = document.getElementById("t-lang");
    if (btn) btn.addEventListener("click", function () { lang = lang === "hi" ? "en" : "hi"; applyLang(); });
    applyLang();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire); else wire();
})();
