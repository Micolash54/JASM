// Search, item tooltips, the menu on phones, and the version picker and tags. Pages read fine without it.
(() => {
  const body = document.body, root = body.dataset.root, line = body.dataset.line, here = body.dataset.page;
  localStorage.setItem("jasmLine", line);

  // the phone menu; a tap on the page closes it again
  const sidebar = document.getElementById("sidebar");
  const calm = matchMedia("(prefers-reduced-motion: reduce)");
  const closeMenu = () => {
    if (!body.classList.contains("menu-open")) return;
    body.classList.remove("menu-open");
    if (calm.matches) return;
    body.classList.add("menu-closing");
    sidebar.addEventListener("animationend", () => body.classList.remove("menu-closing"), { once: true });
  };
  document.getElementById("menu").onclick = () => {
    if (body.classList.contains("menu-open")) closeMenu();
    else { body.classList.remove("menu-closing"); body.classList.add("menu-open"); }
  };
  document.addEventListener("click", e => {
    if (!body.classList.contains("menu-open") || e.target.closest("#sidebar, #menu")) return;
    e.preventDefault();
    e.stopPropagation();
    closeMenu();
  }, true);

  // tooltips
  const tip = document.createElement("div");
  tip.id = "tip";
  body.appendChild(tip);
  document.addEventListener("mouseover", e => {
    const t = e.target.closest("[data-tip]");
    tip.style.display = t ? "block" : "none";
    if (t) tip.textContent = t.dataset.tip;
  });
  document.addEventListener("mousemove", e => { tip.style.left = e.clientX + 14 + "px"; tip.style.top = e.clientY + 14 + "px"; });

  // search
  const box = document.getElementById("search"), results = document.getElementById("results");
  let index = null;
  box.addEventListener("focus", () => {
    if (!index) fetch(root + "/search.json").then(r => r.json()).then(j => { index = j; find(); });
  });
  box.addEventListener("input", find);
  box.addEventListener("keydown", e => {
    if (e.key === "Enter") { const first = results.querySelector("a"); if (first) location.href = first.href; }
    if (e.key === "Escape") { box.value = ""; find(); }
  });
  function find() {
    const q = box.value.trim().toLowerCase();
    if (!index || q.length < 2) { results.style.display = "none"; return; }
    const hits = [];
    for (const p of index) {
      const t = p.t.toLowerCase(), x = p.x.toLowerCase();
      const score = t.includes(q) ? (t.startsWith(q) ? 0 : 1) : x.includes(q) ? 2 : -1;
      if (score >= 0) hits.push([score, p]);
    }
    hits.sort((a, b) => a[0] - b[0]);
    results.innerHTML = "";
    for (const [, p] of hits.slice(0, 8)) {
      const a = document.createElement("a");
      a.href = root + "/" + p.u;
      const i = p.x.toLowerCase().indexOf(q);
      const bit = i < 0 ? "" : (i > 40 ? "..." : "") + p.x.slice(Math.max(0, i - 40), i + 80) + "...";
      a.innerHTML = "<b></b><small></small>";
      a.firstChild.textContent = p.t;
      a.lastChild.textContent = bit;
      results.appendChild(a);
    }
    results.style.display = hits.length ? "block" : "none";
  }
  document.addEventListener("click", e => { if (!e.target.closest("#results, #search")) results.style.display = "none"; });

  // versions: the picker in the header and the tags under the title
  fetch(root + "/../versions.json").then(r => r.json()).then(v => {
    const lines = Object.keys(v.lines).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
    const picker = document.getElementById("versions"), tags = document.getElementById("tags");
    picker.innerHTML = "";
    for (const l of lines) {
      const o = document.createElement("option");
      o.value = l; o.textContent = v.lines[l].mc; o.selected = l === line;
      picker.appendChild(o);
    }
    const go = l => {
      localStorage.setItem("jasmLine", l);
      const has = v.lines[l].pages.includes(here);
      location.href = root + "/../" + l + "/" + (has ? here : "index.html" + "?missing=" + encodeURIComponent(here));
    };
    picker.onchange = () => go(picker.value);
    tags.innerHTML = "";
    for (const l of lines) {
      if (!v.lines[l].pages.includes(here)) continue;
      const t = document.createElement(l === line ? "span" : "a");
      t.className = "tag" + (l === line ? " current" : "");
      t.textContent = v.lines[l].mc;
      if (l !== line) { t.href = "#"; t.onclick = e => { e.preventDefault(); go(l); }; }
      tags.appendChild(t);
    }
    const built = document.createElement("span");
    built.className = "built";
    built.textContent = "JASM " + v.lines[line].jasm;
    tags.appendChild(built);
  }).catch(() => {});

  // landed here from another version that has no such page
  const missing = new URLSearchParams(location.search).get("missing");
  if (missing) {
    const note = document.createElement("p");
    note.className = "notice";
    note.textContent = "That page isn't in this version of JASM, so here is the start page.";
    document.querySelector("main h1").after(note);
  }
})();
