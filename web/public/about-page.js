/* About page: English / Hindi switch (same saved choice as the home page, "chhaon-site-lang"), title and year.
   A file, not an inline script: the production CSP is script-src 'self'. */
(function () {
  var TITLE = { en: "About Chhaon: who we are", hi: "छाँव के बारे में: हम कौन हैं" };
  var root = document.documentElement, app = document.getElementById("open-app");
  function set(l) {
    root.lang = l;
    document.title = TITLE[l];
    try { localStorage.setItem("chhaon-site-lang", l); } catch (e) {}
    // The app reads ?lang= once on load (web/src/App.tsx), so a Hindi reader lands in the Hindi app.
    // Only ?lang changes: a ?ref= added by ref-carry.js stays.
    if (app) try {
      var u = new URL(app.getAttribute("href"), location.href);
      u.pathname = "/app/";
      if (l === "hi") u.searchParams.set("lang", "hi"); else u.searchParams.delete("lang");
      app.setAttribute("href", u.pathname + u.search);
    } catch (e) {}
  }
  var saved = null; try { saved = localStorage.getItem("chhaon-site-lang"); } catch (e) {}
  set(saved === "hi" || saved === "en" ? saved : ((navigator.language || "").toLowerCase().indexOf("hi") === 0 ? "hi" : "en"));
  document.getElementById("lang").addEventListener("click", function () { set(root.lang === "hi" ? "en" : "hi"); });
  document.getElementById("year").textContent = new Date().getFullYear();
})();
