/* /tools/emergency-card: a printable emergency card (wallet 85.6 x 54 mm folded, or an A5 fridge sheet), English or Hindi.
 * PRIVACY: runs only in this browser. Nothing typed is sent, stored (no localStorage, no cookies) or put in the address bar.
 * No medical content: the person types what the doctor prescribed. */
(function () {
  var T = window.ChhaonTools, el = T.el;
  var form = document.getElementById("ec-form"), out = document.getElementById("ec-out");
  var warn = document.getElementById("ec-warn"), status = document.getElementById("ec-status"), pageStyle = document.getElementById("ec-page");
  var outLang = null;   // the card's language; follows the page language until the person picks one

  var TXT = {
    en: {
      band: "EMERGENCY INFORMATION", age: "Age", blood: "Blood group", allergies: "Allergies", conditions: "Conditions",
      meds: "Medicines", call: "In an emergency, please call", doctor: "Doctor", sos: "Ambulance 108 · Emergency 112",
      none: "—", made: "Made free at chhaon.site", name: "(name)",
      disc: "This is not medical advice. Please follow your doctor's prescription.",
    },
    hi: {
      band: "आपातकालीन जानकारी", age: "उम्र", blood: "ब्लड ग्रुप", allergies: "एलर्जी", conditions: "बीमारियाँ",
      meds: "दवाएँ", call: "आपात स्थिति में कृपया फ़ोन करें", doctor: "डॉक्टर", sos: "एम्बुलेंस 108 · इमरजेंसी 112",
      none: "—", made: "chhaon.site पर मुफ़्त बनाया गया", name: "(नाम)",
      disc: "यह चिकित्सा सलाह नहीं है। कृपया अपने डॉक्टर के पर्चे के अनुसार ही दवा लें।",
    },
  };

  function val(n) { var f = form.elements[n]; return f ? String(f.value || "").trim() : ""; }
  function read() {
    var age = parseInt(val("age"), 10);
    return {
      name: val("name"), age: age >= 0 && age <= 120 ? String(age) : "", blood: val("blood"),
      allergies: val("allergies"), conditions: val("conditions"),
      meds: val("meds").split(/\n+/).map(function (s) { return s.trim(); }).filter(Boolean),
      contacts: [[val("c1name"), val("c1phone")], [val("c2name"), val("c2phone")]].filter(function (c) { return c[0] || c[1]; }),
      doc: [val("docname"), val("docphone")],
      fmt: (form.querySelector("input[name=fmt]:checked") || {}).value || "wallet",
    };
  }
  function L() { return outLang || T.lang(); }

  function bloodBadge(d, t) {
    return el("div", { class: "bg", "aria-label": t.blood + ": " + (d.blood || "?") }, [el("small", { text: t.blood }), d.blood || "?"]);
  }
  function line(label, text, red) {
    return el("div", { class: "row" }, [el("span", { class: "k" + (red ? " red" : ""), text: label + ": " }), text]);
  }

  function wallet(d, t) {
    var front = el("div", { class: "face" }, [
      el("div", { class: "band", text: "✚ " + t.band }),
      el("div", { class: "body" }, [
        el("div", { class: "nm" }, [el("div", null, [el("b", { text: d.name || t.name }), d.age ? el("div", { class: "age", text: t.age + ": " + d.age }) : null]), bloodBadge(d, t)]),
        line(t.allergies, d.allergies || t.none, true),
        line(t.conditions, d.conditions || t.none),
      ]),
    ]);
    var back = el("div", { class: "face" }, [
      el("div", { class: "band", text: "☎ " + t.call }),
      el("div", { class: "body" }, d.contacts.map(function (c) {
        return el("div", { class: "call row" }, [c[0] ? c[0] + ": " : "", el("b", { text: c[1] || "" })]);
      }).concat([
        d.doc[0] || d.doc[1] ? line(t.doctor, [d.doc[0], d.doc[1]].filter(Boolean).join(", ")) : null,
        line(t.meds, d.meds.length ? d.meds.join(", ") : t.none),
        el("div", { class: "fine" }, [el("span", { class: "sos", text: t.sos }), el("span", { text: t.made })]),
      ])),
    ]);
    return [el("div", { class: "pair" }, [front, back]), el("p", { class: "cut-help" }, [el("span", { class: "disc", text: t.disc })])];
  }

  function a5(d, t) {
    function sec(label, body, red) { return el("div", { class: "sec" }, [el("span", { class: "k" + (red ? " red" : ""), text: label }), body]); }
    return [el("div", { class: "a5" }, [
      el("div", { class: "band", text: "✚ " + t.band }),
      el("div", { class: "nm" }, [el("div", null, [el("b", { text: d.name || t.name }), d.age ? el("span", { class: "age", text: t.age + ": " + d.age }) : null]), bloodBadge(d, t)]),
      sec(t.allergies, el("div", { text: d.allergies || t.none }), true),
      sec(t.conditions, el("div", { text: d.conditions || t.none })),
      sec(t.meds, d.meds.length ? el("ul", null, d.meds.map(function (m) { return el("li", { text: m }); })) : el("div", { text: t.none })),
      sec(t.call, el("div", null, d.contacts.length ? d.contacts.map(function (c) { return el("div", { class: "call" }, [c[0] ? c[0] + ": " : "", c[1]]); }) : [t.none])),
      d.doc[0] || d.doc[1] ? sec(t.doctor, el("div", { class: "call", text: [d.doc[0], d.doc[1]].filter(Boolean).join(", ") })) : null,
      el("div", { class: "sos", text: t.sos }),
      el("p", { class: "disc", text: t.disc }),
      el("div", { class: "fine", text: t.made }),
    ])];
  }

  function render() {
    var d = read(), lang = L(), t = TXT[lang];
    form.querySelectorAll("input[name=out-lang]").forEach(function (r) { r.checked = r.value === lang; });
    out.setAttribute("lang", lang);
    out.innerHTML = "";
    (d.fmt === "a5" ? a5(d, t) : wallet(d, t)).forEach(function (n) { out.appendChild(n); });
    pageStyle.textContent = d.fmt === "a5"
      ? "@media print { @page { size: A5 portrait; margin: 8mm; } }"
      : "@media print { @page { size: A4 portrait; margin: 12mm; } }";
    // Wallet card: warn when the text does not fit (sizes scale with the card, so the screen preview tells the truth).
    var over = false;
    if (d.fmt === "wallet") out.querySelectorAll(".face .body").forEach(function (b) { if (b.scrollHeight > b.clientHeight + 1) over = true; });
    warn.hidden = !over;
  }

  function saveImage() {
    var d = read(), lang = L(), t = TXT[lang];
    var items = [
      { text: "✚ " + t.band, size: 50, bold: true, color: "#ffffff", bg: "#c62828", pad: 24, align: "center" },
      { text: d.name || t.name, size: 64, bold: true, gap: 4 },
      { text: [d.age ? t.age + ": " + d.age : "", t.blood + ": " + (d.blood || "?")].filter(Boolean).join("   ·   "), size: 46, bold: true, color: "#c62828" },
      { rule: true },
      { text: t.allergies, size: 32, bold: true, color: "#c62828", gap: 2 }, { text: d.allergies || t.none, size: 42 },
      { text: t.conditions, size: 32, bold: true, color: "#555", gap: 2 }, { text: d.conditions || t.none, size: 42 },
      { text: t.meds, size: 32, bold: true, color: "#555", gap: 2 }, { text: d.meds.length ? d.meds.join("\n") : t.none, size: 40 },
      { rule: true },
      { text: t.call, size: 32, bold: true, color: "#555", gap: 6 },
    ];
    (d.contacts.length ? d.contacts : [["", t.none]]).forEach(function (c) { items.push({ text: (c[0] ? c[0] + ": " : "") + (c[1] || ""), size: 46, bold: true, gap: 8 }); });
    if (d.doc[0] || d.doc[1]) items.push({ text: t.doctor + ": " + [d.doc[0], d.doc[1]].filter(Boolean).join(", "), size: 40, bold: true });
    items.push({ text: t.sos, size: 42, bold: true, align: "center", bg: "#f2f2f2", pad: 18 });
    items.push({ text: t.disc, size: 30, bold: true, bg: "#fff0e6", pad: 18 });
    items.push({ text: t.made, size: 28, color: "#555", align: "center" });
    T.drawImage(items, T.fileName("emergency-card", d.name)).then(function () {
      status.textContent = lang === "hi" ? "फ़ोटो सेव हो गई (डाउनलोड में देखें)।" : "Image saved (check your downloads).";
    }, function () {
      status.textContent = lang === "hi" ? "यह ब्राउज़र फ़ोटो सेव नहीं कर पाया। प्रिंट विंडो से PDF सेव करें।" : "This browser could not save the image. Use Print, then Save as PDF.";
    });
  }

  function fixOptions() {
    var lang = T.lang();
    form.querySelectorAll("option[data-en]").forEach(function (o) { o.textContent = o.getAttribute("data-" + lang); });
  }

  form.addEventListener("input", function (e) { if (e.target && e.target.name === "out-lang") outLang = e.target.value; render(); });
  form.addEventListener("change", function (e) { if (e.target && e.target.name === "out-lang") outLang = e.target.value; render(); });
  form.addEventListener("submit", function (e) { e.preventDefault(); });
  document.getElementById("do-print").addEventListener("click", function () { render(); window.print(); });
  document.getElementById("do-image").addEventListener("click", saveImage);
  T.onLang(function () { fixOptions(); render(); });
  window.addEventListener("resize", render);
  render();
})();
