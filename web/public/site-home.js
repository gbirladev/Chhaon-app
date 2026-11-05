/* Home page (/ and the generated /hi/): language button, sticky header shadow, gentle reveal on scroll.
   A file, not an inline script: the production CSP is script-src 'self'. Loaded with defer at the end of home.html.
   TITLE_EN / TITLE_HI must match the <title> of home.html and of hi/index.html (scripts/build-hindi-home.mjs checks this). */
(function () {
  var TITLE_EN = "Chhaon: WhatsApp medicine reminders for elderly parents";
  var TITLE_HI = "छाँव: बुज़ुर्ग माता-पिता के लिए WhatsApp पर दवा के रिमाइंडर";
  var root = document.documentElement, btn = document.getElementById("lang");
  root.classList.add("js");
  function set(l) { root.lang = l; try { localStorage.setItem("chhaon-site-lang", l); } catch (e) {} document.title = l === "hi" ? TITLE_HI : TITLE_EN; }
  var saved = null; try { saved = localStorage.getItem("chhaon-site-lang"); } catch (e) {}
  // The Hindi page (/hi/) always opens in Hindi. The button moves between / and /hi/, so each language has its own address for search engines.
  var onHi = location.pathname.indexOf("/hi/") === 0;
  set(onHi ? "hi" : (saved || ((navigator.language || "").toLowerCase().indexOf("hi") === 0 ? "hi" : "en")));
  btn.addEventListener("click", function () {
    var to = root.lang === "hi" ? "en" : "hi";
    set(to);
    if (to === "hi" && !onHi) location.href = "/hi/";
    else if (to === "en" && onHi) location.href = "/";
  });
  document.getElementById("year").textContent = new Date().getFullYear();
  var bar = document.getElementById("bar");
  function onScroll() { bar.classList.toggle("stuck", window.scrollY > 8); }
  onScroll(); window.addEventListener("scroll", onScroll, { passive: true });
  var items = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    var io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }); }, { threshold: .12, rootMargin: "0px 0px -40px 0px" });
    items.forEach(function (el) { io.observe(el); });
  } else { items.forEach(function (el) { el.classList.add("in"); }); }
})();
