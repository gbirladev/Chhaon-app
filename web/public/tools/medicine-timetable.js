/* /tools/medicine-timetable: a big-print, printable weekly medicine timetable (English or Hindi).
 * PRIVACY: runs only in this browser. Nothing typed is sent, stored (no localStorage, no cookies) or put in the address bar.
 * No medical content: the person types what the doctor prescribed; there is no drug list and no dose suggestion. */
(function () {
  var T = window.ChhaonTools, el = T.el;
  var form = document.getElementById("tt-form"), list = document.getElementById("med-list"), sheet = document.getElementById("sheet");
  var status = document.getElementById("tt-status");
  var SLOTS = ["morning", "afternoon", "evening", "night"];
  var outLang = null;          // the printout's language; follows the page language until the person picks one

  var TXT = {
    en: {
      title: "Medicine timetable", week: "Week starting", medicine: "Medicine",
      days: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"],
      slot: { morning: "☀ Morning", afternoon: "🌤 Afternoon", evening: "🌇 Evening", night: "🌙 Night" },
      food: { before: "Before food", after: "After food", with: "With food" },
      how: "After taking a medicine, put a ✓ in that day's box.",
      disc: "This is not medical advice. Please follow your doctor's prescription.",
      foot: "Made free at chhaon.site · Want reminders on WhatsApp instead? chhaon.site",
      empty: "Add a medicine above to see the timetable here.",
      unnamed: "(medicine name)",
    },
    hi: {
      title: "दवा की समय-सारणी", week: "हफ़्ता शुरू", medicine: "दवा",
      days: ["सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि", "रवि"],
      slot: { morning: "☀ सुबह", afternoon: "🌤 दोपहर", evening: "🌇 शाम", night: "🌙 रात" },
      food: { before: "खाने से पहले", after: "खाने के बाद", with: "खाने के साथ" },
      how: "दवा लेने के बाद उस दिन के खाली बॉक्स में ✓ लगाएँ।",
      disc: "यह चिकित्सा सलाह नहीं है। कृपया अपने डॉक्टर के पर्चे के अनुसार ही दवा लें।",
      foot: "chhaon.site पर मुफ़्त बनाया गया · WhatsApp पर याद दिलाना चाहते हैं? chhaon.site",
      empty: "समय-सारणी देखने के लिए ऊपर एक दवा जोड़ें।",
      unnamed: "(दवा का नाम)",
    },
  };
  // Labels for the form (both languages in the page; CSS shows the page language).
  function bi(en, hi) { return [el("span", { class: "en", text: en }), el("span", { class: "hi", text: hi })]; }
  var SLOT_LABEL = { morning: ["Morning", "सुबह"], afternoon: ["Afternoon", "दोपहर"], evening: ["Evening", "शाम"], night: ["Night", "रात"] };

  var counter = 0;
  function medCard() {
    var n = ++counter, id = "m" + n;
    var slots = SLOTS.map(function (s) {
      return el("label", { class: "chip" }, [el("input", { type: "checkbox", name: id + "-slot", value: s }), el("span", null, bi(SLOT_LABEL[s][0], SLOT_LABEL[s][1]))]);
    });
    var times = el("div", { class: "times", "data-times": "" });
    var addTime = el("button", { type: "button", class: "btn ghost small" }, ["＋ "].concat(bi("Add an exact time", "सही समय जोड़ें")));
    addTime.addEventListener("click", function () { addTimeRow(times, n); render(); var i = times.querySelectorAll("input"); if (i.length) i[i.length - 1].focus(); });
    var remove = el("button", { type: "button", class: "btn danger small" }, bi("Remove", "हटाएँ"));
    var card = el("fieldset", { class: "card-f med-card", "data-med": "" }, [
      el("legend", { class: "sr" }, bi("Medicine " + n, "दवा " + n)),
      el("div", { class: "head" }, [el("b", { "aria-hidden": "true" }, bi("Medicine", "दवा")), remove]),
      el("label", { class: "field" }, [el("span", null, bi("Medicine name (as on the prescription)", "दवा का नाम (जैसा पर्चे पर लिखा है)")),
        el("input", { type: "text", name: "name", maxlength: "60", "data-ph-en": "Name on the strip or bottle", "data-ph-hi": "पत्ते या बोतल पर लिखा नाम" })]),
      el("label", { class: "field" }, [el("span", null, bi("Dose (as the doctor wrote it)", "खुराक (जैसा डॉक्टर ने लिखा)")),
        el("input", { type: "text", name: "dose", maxlength: "40", "data-ph-en": "e.g. 1 tablet", "data-ph-hi": "जैसे: 1 गोली" })]),
      el("fieldset", { class: "chips" }, [el("legend", null, bi("When", "कब"))].concat(slots)),
      el("div", { class: "lbl" }, bi("Or exact times (optional)", "या सही समय (ज़रूरी नहीं)")),
      times, el("div", { style: "margin:-4px 0 12px" }, [addTime]),
      el("label", { class: "field" }, [el("span", null, bi("Food", "खाना")),
        el("select", { name: "food" }, [
          el("option", { value: "" }, bi("No food instruction", "खाने से जुड़ा कुछ नहीं")),
          el("option", { value: "before" }, bi("Before food", "खाने से पहले")),
          el("option", { value: "after" }, bi("After food", "खाने के बाद")),
          el("option", { value: "with" }, bi("With food", "खाने के साथ")),
        ])]),
      el("label", { class: "field" }, [el("span", null, bi("Note (optional)", "नोट (ज़रूरी नहीं)")),
        el("input", { type: "text", name: "note", maxlength: "80", "data-ph-en": "e.g. white round tablet", "data-ph-hi": "जैसे: सफ़ेद गोल गोली" })]),
    ]);
    // <option> cannot hide child spans in every browser: give options plain text in the page language instead.
    fixOptions(card);
    remove.addEventListener("click", function () {
      var cards = list.querySelectorAll("[data-med]");
      if (cards.length <= 1) { card.querySelectorAll("input[type=text], input[type=time]").forEach(function (i) { i.value = ""; }); card.querySelectorAll("input[type=checkbox]").forEach(function (i) { i.checked = false; }); card.querySelector("select").value = ""; times.innerHTML = ""; }
      else card.remove();
      render();
      announce(T.lang() === "hi" ? "दवा हटा दी गई।" : "Medicine removed.");
    });
    return card;
  }
  function addTimeRow(times, n) {
    var k = times.children.length + 1;
    var input = el("input", { type: "time", name: "time", "aria-label": (T.lang() === "hi" ? "सही समय " : "Exact time ") + k });
    var del = el("button", { type: "button", class: "btn danger small", "aria-label": T.lang() === "hi" ? "यह समय हटाएँ" : "Remove this time" }, ["✕"]);
    var row = el("div", { class: "time-row" }, [input, del]);
    del.addEventListener("click", function () { row.remove(); render(); });
    times.appendChild(row);
  }
  function fixOptions(scope) {
    var L = T.lang();
    (scope || document).querySelectorAll("option").forEach(function (o) {
      if (!o.dataset.en) { var s = o.querySelectorAll("span"); if (s.length === 2) { o.dataset.en = s[0].textContent; o.dataset.hi = s[1].textContent; } }
      if (o.dataset.en) o.textContent = L === "hi" ? o.dataset.hi : o.dataset.en;
    });
    (scope || document).querySelectorAll("[data-ph-en]").forEach(function (i) { i.setAttribute("placeholder", i.getAttribute(L === "hi" ? "data-ph-hi" : "data-ph-en")); });
  }

  function addMed(focus) {
    var c = medCard();
    list.appendChild(c);
    if (focus) c.querySelector("input[name=name]").focus();
    render();
  }

  // ---- Reading the form
  function slotOfTime(hhmm) {
    var h = parseInt(hhmm.slice(0, 2), 10);
    if (h >= 4 && h < 11) return "morning";
    if (h >= 11 && h < 16) return "afternoon";
    if (h >= 16 && h < 20) return "evening";
    return "night";
  }
  function readMeds() {
    var out = [];
    list.querySelectorAll("[data-med]").forEach(function (c) {
      var m = {
        name: c.querySelector("[name=name]").value.trim(),
        dose: c.querySelector("[name=dose]").value.trim(),
        food: c.querySelector("[name=food]").value,
        note: c.querySelector("[name=note]").value.trim(),
        slots: [], times: [],
      };
      c.querySelectorAll("input[type=checkbox]:checked").forEach(function (b) { m.slots.push(b.value); });
      c.querySelectorAll("input[type=time]").forEach(function (t) { if (/^\d\d:\d\d/.test(t.value)) m.times.push(t.value.slice(0, 5)); });
      if (m.name || m.dose || m.slots.length || m.times.length) out.push(m);
    });
    return out;
  }
  // Rows grouped by part of the day (exact times go to the part they fall in). A medicine with no time chosen still shows, in an untitled group at the end.
  function groups(meds) {
    var g = { morning: [], afternoon: [], evening: [], night: [], none: [] };
    meds.forEach(function (m) {
      m.slots.forEach(function (s) { g[s].push({ m: m, time: "" }); });
      m.times.forEach(function (t) { g[slotOfTime(t)].push({ m: m, time: t }); });
      if (!m.slots.length && !m.times.length) g.none.push({ m: m, time: "" });
    });
    Object.keys(g).forEach(function (k) {
      g[k].sort(function (a, b) {
        var ka = a.time ? (a.time < "04:00" ? "3" + a.time : "1" + a.time) : "0", kb = b.time ? (b.time < "04:00" ? "3" + b.time : "1" + b.time) : "0";
        return ka < kb ? -1 : ka > kb ? 1 : 0;
      });
    });
    return g;
  }
  function fmtTime(hhmm, L) {
    var h = parseInt(hhmm.slice(0, 2), 10), m = hhmm.slice(3, 5), h12 = h % 12 || 12;
    return L === "hi" ? h12 + ":" + m + " बजे" : h12 + ":" + m + (h < 12 ? " AM" : " PM");
  }
  function currentOutLang() { return outLang || T.lang(); }

  // ---- The sheet
  function render() {
    var L = currentOutLang(), t = TXT[L], meds = readMeds(), name = document.getElementById("tt-name").value.trim();
    document.querySelectorAll("input[name=out-lang]").forEach(function (r) { r.checked = r.value === L; });
    sheet.setAttribute("lang", L);
    sheet.innerHTML = "";
    sheet.appendChild(el("h2", { text: t.title }));
    if (name) sheet.appendChild(el("p", { class: "who", text: name }));
    sheet.appendChild(el("div", { class: "meta" }, [el("span", null, [t.week + ": ", el("span", { class: "blank" })])]));
    if (!meds.length) {
      sheet.appendChild(el("p", { class: "empty", text: t.empty }));
    } else {
      var cols = el("colgroup", null, [el("col")].concat(t.days.map(function () { return el("col", { class: "days" }); })));
      var head = el("thead", null, [el("tr", null, [el("th", { class: "med", scope: "col", text: t.medicine })].concat(t.days.map(function (d) { return el("th", { scope: "col", text: d }); })))]);
      var body = el("tbody");
      var g = groups(meds);
      SLOTS.concat(["none"]).forEach(function (s) {
        if (!g[s].length) return;
        if (s !== "none") body.appendChild(el("tr", { class: "grp" }, [el("th", { colspan: "8", scope: "colgroup", text: t.slot[s] })]));
        g[s].forEach(function (r) {
          var cell = el("td", null, [
            el("div", { class: "m-name" }, [r.time ? el("span", { class: "m-time", text: fmtTime(r.time, L) }) : null, r.m.name || t.unnamed]),
            r.m.dose ? el("div", { class: "m-dose", text: r.m.dose }) : null,
            r.m.food ? el("span", { class: "m-food", text: t.food[r.m.food] }) : null,
            r.m.note ? el("span", { class: "m-note", text: r.m.note }) : null,
          ]);
          body.appendChild(el("tr", null, [cell].concat(t.days.map(function () { return el("td", { class: "box" }, [el("span", { "aria-hidden": "true" })]); }))));
        });
      });
      sheet.appendChild(el("table", null, [cols, head, body]));
      sheet.appendChild(el("p", { class: "how", text: t.how }));
    }
    sheet.appendChild(el("p", { class: "disc", text: t.disc }));
    sheet.appendChild(el("p", { class: "foot", text: t.foot }));
  }

  function announce(msg) { status.textContent = msg; }

  // ---- Save as image (for WhatsApp / the phone): a simple list, drawn on a canvas. Nothing is uploaded.
  function saveImage() {
    var L = currentOutLang(), t = TXT[L], meds = readMeds(), name = document.getElementById("tt-name").value.trim();
    if (!meds.length) { announce(t.empty); return; }
    var items = [{ text: t.title, size: 58, bold: true, color: "#2a2140" }];
    if (name) items.push({ text: name, size: 48, bold: true });
    var g = groups(meds);
    SLOTS.concat(["none"]).forEach(function (s) {
      if (!g[s].length) return;
      items.push({ rule: true });
      if (s !== "none") items.push({ text: t.slot[s], size: 46, bold: true, color: "#6943a5" });
      g[s].forEach(function (r) {
        var line = (r.time ? fmtTime(r.time, L) + "  ·  " : "") + (r.m.name || t.unnamed) + (r.m.dose ? "  —  " + r.m.dose : "");
        items.push({ text: line, size: 42, bold: true, gap: 2 });
        var extra = [r.m.food ? t.food[r.m.food] : "", r.m.note].filter(Boolean).join("  ·  ");
        items.push({ text: extra || " ", size: 34, color: "#444", gap: 18 });
      });
    });
    items.push({ rule: true });
    items.push({ text: t.disc, size: 32, bold: true, bg: "#fff0e6", pad: 20 });
    items.push({ text: t.foot, size: 28, color: "#555", align: "center" });
    T.drawImage(items, T.fileName("medicine-timetable", name)).then(function () {
      announce(L === "hi" ? "फ़ोटो सेव हो गई (डाउनलोड में देखें)।" : "Image saved (check your downloads).");
    }, function () {
      announce(L === "hi" ? "यह ब्राउज़र फ़ोटो सेव नहीं कर पाया। प्रिंट विंडो से PDF सेव करें।" : "This browser could not save the image. Use Print, then Save as PDF.");
    });
  }

  // ---- Wiring
  form.addEventListener("input", function (e) {
    if (e.target && e.target.name === "out-lang") outLang = e.target.value;
    render();
  });
  form.addEventListener("change", function (e) {
    if (e.target && e.target.name === "out-lang") outLang = e.target.value;
    render();
  });
  form.addEventListener("submit", function (e) { e.preventDefault(); });
  document.getElementById("add-med").addEventListener("click", function () { addMed(true); });
  document.getElementById("do-print").addEventListener("click", function () { render(); window.print(); });
  document.getElementById("do-image").addEventListener("click", saveImage);
  T.onLang(function () { fixOptions(); render(); });

  addMed(false);
})();
