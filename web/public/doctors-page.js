/* Doctors page: the QR cards (with the optional ?ref= code and clinic name) and the Print button.
   A file, not an inline script: the production CSP is script-src 'self'. Moved unchanged from doctors.html. */
  (function () {
    // ?ref=<code> identifies the doctor's own link (e.g. /doctors?ref=D001). It goes into the printed QR so cards
    // printed today keep working once sign-ups start counting it. Anything unexpected is ignored.
    var raw = new URLSearchParams(location.search).get("ref") || "";
    var ref = /^[A-Za-z0-9-]{1,32}$/.test(raw) ? raw : "";
    // "/app/" with the slash: printed cards live for months, so point straight at the final address (no redirect).
    var target = location.origin + "/app/" + (ref ? "?ref=" + encodeURIComponent(ref) : "");

    var SVG = "http://www.w3.org/2000/svg";
    function qrSvg(value) {
      var qr = qrcode(0, "M");
      qr.addData(value);
      qr.make();
      var n = qr.getModuleCount(), d = "";
      for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (qr.isDark(r, c)) d += "M" + (c + 4) + " " + (r + 4) + "h1v1h-1z";
      var svg = document.createElementNS(SVG, "svg");
      svg.setAttribute("viewBox", "0 0 " + (n + 8) + " " + (n + 8));  // 4-module quiet zone
      svg.setAttribute("shape-rendering", "crispEdges");
      svg.setAttribute("role", "img");
      svg.setAttribute("aria-label", "QR code to open Chhaon");
      var path = document.createElementNS(SVG, "path");
      path.setAttribute("d", d);
      path.setAttribute("fill", "#000");
      svg.appendChild(path);
      return svg;
    }

    function line(cls, text) {
      var el = document.createElement("div");
      el.className = cls;
      el.textContent = text;  // textContent only: the clinic name is never treated as HTML
      return el;
    }

    function card() {
      var el = document.createElement("div");
      el.className = "card";
      el.appendChild(line("c-brand", "Chhaon · छाँव"));
      el.appendChild(line("c-en", "Free WhatsApp medicine reminders for your parents"));
      el.appendChild(line("c-hi", "माता-पिता के लिए मुफ़्त WhatsApp दवा रिमाइंडर"));
      el.appendChild(qrSvg(target));
      el.appendChild(line("c-scan", "Scan with your phone camera · फ़ोन कैमरे से स्कैन करें"));
      el.appendChild(line("c-url", "chhaon.site/app"));
      var foot = line("c-clinic", "");
      foot.hidden = true;
      foot.appendChild(line("c-clinic-name", ""));
      foot.appendChild(line("c-clinic-for", "For our patients · हमारे मरीज़ों के लिए"));
      el.appendChild(foot);
      return el;
    }

    var preview = document.getElementById("preview");
    var sheet = document.getElementById("sheet");
    preview.appendChild(card());
    for (var i = 0; i < 6; i++) sheet.appendChild(card());

    // One plain line: control characters and invisible direction marks removed, spaces collapsed, at most 60 characters.
    function cleanClinic(value) {
      var text = value.replace(/[\u0000-\u001F\u007F-\u009F‎‏‪-‮⁦-⁩﻿]/g, " ").replace(/\s+/g, " ").trim();
      return Array.from(text).slice(0, 60).join("").trim();
    }
    var clinic = document.getElementById("clinic");
    function showClinic() {
      var text = cleanClinic(clinic.value);
      document.querySelectorAll(".c-clinic").forEach(function (el) {
        el.hidden = !text;
        el.querySelector(".c-clinic-name").textContent = text;
      });
    }
    clinic.addEventListener("input", showClinic);
    // The browser may refill the box after Back or a reload without an "input" event: show what is in it.
    window.addEventListener("pageshow", showClinic);
    showClinic();
    document.getElementById("print").addEventListener("click", function () { window.print(); });
  })();
