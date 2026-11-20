"use strict";
/* Pocket Cloud web panel (v2 design). Everything shown comes from the control API (/api/...); every button calls it.
   Screens: Home · Apps (+ app page, Connect a project) · Files · Phone (+ storage, jobs, alerts, logs) · Settings. */

// ---------------------------------------------------------------- helpers
const $ = (id) => document.getElementById(id);
const KB = 1024, MB = 1024 ** 2, GB = 1024 ** 3;
const n = (x) => Math.round(x || 0).toLocaleString("en-US");
function fb(b) {
  b = b || 0;
  if (b < 1024) return Math.round(b) + " B";
  const u = ["KB", "MB", "GB", "TB"]; let i = -1;
  do { b /= 1024; i++; } while (b >= 1024 && i < 3);
  return (b >= 100 ? b.toFixed(0) : b >= 10 ? b.toFixed(1) : b.toFixed(2)) + " " + u[i];
}
const gbv = (bytes) => bytes >= GB ? (Math.round(bytes / GB * 100) / 100) + " GB" : fb(bytes);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const dt = (ts) => ts ? new Date(ts * 1000).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";
const hm = (ts) => ts ? new Date(ts * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "—";
const hms = (ts) => ts ? new Date(ts * 1000).toLocaleTimeString("en-GB") : "";
function ago(ts) {
  if (!ts) return "never";
  const s = Date.now() / 1000 - ts;
  if (s < 90) return "just now";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  return Math.round(s / 86400) + " days ago";
}
function dur(s) {
  s = Math.max(0, s || 0);
  const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
}
const pctCls = (pct, warn) => pct >= 100 ? "bad" : pct >= warn ? "warn" : "";
const meter = (used, total, warn = 101) => { const pct = total ? used / total * 100 : 0;
  return `<div class="meter" role="img" aria-label="${pct.toFixed(0)}% used"><i class="${pctCls(pct, warn)}" style="width:${Math.min(100, Math.max(used ? 2 : 0, pct))}%"></i></div>`; };
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} },
};
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const s13 = (t) => `<span class="soft small">${t}</span>`;

// app marks: a stable colour per app, its initials on it
const MARKS = ["#3350e8", "#0f8f7a", "#8a4fd6", "#d1344b", "#b86e00", "#4a5578", "#1f7fb8", "#b0479a"];
function markColor(id) { let h = 0; for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return MARKS[h % MARKS.length]; }
const initials = (name) => String(name || "?").replace(/[-_]+/g, " ").split(/\s+/).filter(Boolean).map((w) => w[0]).join("").slice(0, 2).toUpperCase() || "?";
const mark = (a) => `<span class="mark" style="background:${markColor(a.id)}" aria-hidden="true">${esc(initials(a.name))}</span>`;

// ---------------------------------------------------------------- talking to the server
let TOKEN = store.get("pocketToken");
class ApiError extends Error { constructor(status, body) { super(body?.message || `HTTP ${status}`); this.status = status; this.body = body; } }
async function api(method, path, body, raw) {
  const headers = {};
  if (TOKEN) headers.Authorization = "Bearer " + TOKEN;
  let payload;
  if (raw !== undefined) { payload = raw; headers["Content-Type"] = "application/octet-stream"; }
  else if (body !== undefined) { payload = JSON.stringify(body); headers["Content-Type"] = "application/json"; }
  let res;
  try { res = await fetch(path, { method, headers, body: payload }); }
  catch { throw new ApiError(0, { message: "Pocket Cloud can't be reached. Is the phone on and connected?" }); }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== "/api/login" && path !== "/api/password") { signedOut(); throw new ApiError(401, data); }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}
function toast(msg, bad) {
  const t = $("toast");
  t.textContent = msg; t.className = "toast" + (bad ? " bad" : ""); t.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => (t.hidden = true), bad ? 6000 : 3000);
}
async function act(fn, done) {      // run an action, show its outcome
  try { const r = await fn(); if (done) toast(typeof done === "function" ? done(r) : done); return r; }
  catch (e) { if (e.status !== 401) toast(e.message, true); return null; }
}
async function copyText(text, what) {
  try { await navigator.clipboard.writeText(text); toast(what + " copied."); }
  catch { toast("Copying isn't allowed here; select the text and copy it by hand.", true); }
}

// ---------------------------------------------------------------- dialogs (a card on desktop, a bottom sheet on a phone)
let lastFocus = null;
function openModal(html, wide) {
  lastFocus = document.activeElement;
  $("modal-box").className = "card modal-box" + (wide ? " wide" : ""); $("modal-box").innerHTML = html; $("modal").hidden = false;
  const f = $("modal-box").querySelector("input, select, button.primary, button"); if (f) f.focus();
}
function closeModal() { $("modal").hidden = true; $("modal-box").innerHTML = ""; if (lastFocus?.focus) lastFocus.focus(); }
$("modal").addEventListener("click", (e) => { if (e.target.id === "modal") closeModal(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !$("modal").hidden) closeModal(); });
const foot = (ok, danger, id = "m-yes") => `<div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn ${danger ? "danger fill" : "primary"}" id="${id}">${esc(ok)}</button></div>`;
function confirmBox(title, text, ok = "OK", danger = false) {
  return new Promise((resolve) => {
    openModal(`<h2>${esc(title)}</h2><div class="body"><p>${text}</p></div>${foot(ok, danger)}`);
    $("m-no").onclick = () => { closeModal(); resolve(false); };
    $("m-yes").onclick = () => { closeModal(); resolve(true); };
  });
}
function promptBox(title, label, value = "", ok = "Save", type = "text") {
  return new Promise((resolve) => {
    openModal(`<h2>${esc(title)}</h2><div class="body"><label class="field">${esc(label)}<input type="${type}" id="m-in" value="${esc(value)}"></label></div>${foot(ok)}`);
    const input = $("m-in"); input.focus(); input.select();
    const done = (v) => { closeModal(); resolve(v); };
    $("m-no").onclick = () => done(null);
    $("m-yes").onclick = () => done(input.value);
    input.onkeydown = (e) => { if (e.key === "Enter") done(input.value); };
  });
}

// ---------------------------------------------------------------- switches (role="switch" buttons)
const sw = (attrs, on, label, disabled) => `<button class="sw" role="switch" aria-checked="${!!on}" aria-label="${esc(label)}" ${attrs}${disabled ? " disabled" : ""}></button>`;
const swOn = (el) => el.getAttribute("aria-checked") === "true";
document.addEventListener("click", (e) => {
  const t = e.target.closest(".sw");
  if (t && !t.disabled && !t.dataset.live) t.setAttribute("aria-checked", String(!swOn(t)));
});

// ---------------------------------------------------------------- sliders (number box beside; either one moves the other)
const markLeft = (v, min, max) => { const P = (v - min) / (max - min || 1) * 100; return `calc(${P}% + ${9 - P * 0.18}px)`; };
const markEdge = (P) => P < 12 ? "--tx:0%;--tick:0%" : P > 88 ? "--tx:-100%;--tick:100%" : "--tx:-50%;--tick:50%";
function slider({ min, max, step = 1, value, unit = "", none = false, marks = null, cls = "", compact = false, key = "", label = "" }) {
  if (max < min) max = min;
  const v = Math.min(max, Math.max(min, value));
  const p = (v - min) / (max - min || 1) * 100;
  const mk = (m) => { const mv = Math.min(max, Math.max(min, m.v));
    return `<span class="mk${m.k === "warn" ? " up" : ""}" data-k="${m.k || ""}" style="left:${markLeft(mv, min, max)};${markEdge((mv - min) / (max - min || 1) * 100)}"><i style="background:${m.color}"></i>${m.label}</span>`; };
  return `<div class="sl ${cls}${compact ? " compact" : ""}${marks ? " has-marks" : ""}"${key ? ` data-key="${key}"` : ""}>
    <div class="sl-track"><input type="range" class="sl-range" aria-label="${esc(label)}" min="${min}" max="${max}" step="${step}" value="${v}" style="--p:${p}%"${none ? ' data-none="1"' : ""}>
      ${marks ? `<div class="sl-marks">${marks.map(mk).join("")}</div>` : ""}</div>
    <input type="number" class="sl-num" aria-label="${esc(label)}" min="${min}" max="${max}" step="${step}" value="${none && v === min ? "" : v}"${none ? ' placeholder="none"' : ""}><span class="unit">${unit}</span></div>`;
}
function paintRange(r) { r.style.setProperty("--p", ((+r.value - +r.min) / (+r.max - +r.min || 1) * 100) + "%"); }
const slVal = (el) => +el.querySelector(".sl-range").value;
function refreshGroup(g) {                 // allocation slider + warning slider + per-device cap/reserve of one app
  if (!g) return;
  const ar = g.querySelector(".sl-alloc .sl-range");
  const alloc = +ar.value * GB;
  const warnEl = g.querySelector(".sl-warn .sl-range");
  const warn = warnEl ? +warnEl.value : +g.dataset.warn;
  const used = +g.dataset.used;
  const pct = used / alloc * 100;
  g.querySelector(".sl-preview").innerHTML =
    `${fb(used)} of ${gbv(alloc)} used <span class="${pctCls(pct, warn)}">(${pct.toFixed(0)}%)</span>, warning at ${gbv(alloc * warn / 100)}`;
  const wm = g.querySelector(".sl-alloc .mk[data-k='warn']");
  if (wm) {
    const wv = +ar.value * warn / 100, P = (wv - +ar.min) / (+ar.max - +ar.min || 1) * 100;
    wm.style.display = wv < +ar.min ? "none" : "";
    wm.style.left = markLeft(Math.max(+ar.min, wv), +ar.min, +ar.max);
    markEdge(P).split(";").forEach((d) => { const [k, v] = d.split(":"); wm.style.setProperty(k, v); });
  }
  const cap = g.querySelector(".sl-cap");
  if (cap) {
    const cr = cap.querySelector(".sl-range"), cn = cap.querySelector(".sl-num");
    cr.max = cn.max = ar.value;
    if (+cr.value > +ar.value) { cr.value = ar.value; if (cn.value !== "") cn.value = ar.value; }
    paintRange(cr);
  }
  const res = g.querySelector(".sl-res");          // the reserve times the devices must fit in the allocation
  if (res) {
    const rr = res.querySelector(".sl-range"), rn = res.querySelector(".sl-num");
    const top = +ar.value / (+g.dataset.devices || 1);
    rr.max = rn.max = top;
    if (+rr.value > top) { rr.value = top; if (rn.value !== "") rn.value = top; }
    paintRange(rr);
  }
}
function refreshPool(g) {
  if (!g) return;
  const P = STATE.pool;
  const ir = g.querySelector(".sl-pint .sl-range"), inum = g.querySelector(".sl-pint .sl-num");
  const reserve = slVal(g.querySelector(".sl-res")) * GB;
  const max = Math.max(+ir.min, Math.floor((P.int_max + P.reserve - reserve) / GB), Math.round(P.int_limit / GB));
  ir.max = inum.max = max;
  if (+ir.value > max) { ir.value = max; inum.value = max; }
  paintRange(ir);
  const internal = +ir.value * GB, sd = slVal(g.querySelector(".sl-psd")) * GB, total = internal + sd;
  g.querySelector(".pool-preview").innerHTML = total < P.promised
    ? `<span class="bad">Pool ${gbv(total)} is smaller than the ${gbv(P.promised)} already promised to apps. Lower app allocations first.</span>`
    : `Pool: <b>${gbv(total)}</b> (${gbv(internal)} internal + ${gbv(sd)} SD card) · ${gbv(P.promised)} promised to apps · ${gbv(total - P.promised)} unpromised · ${fb(P.used)} in use`;
}
document.addEventListener("input", (e) => {
  const t = e.target, box = t.closest(".sl");
  if (!box) return;
  const r = box.querySelector(".sl-range"), num = box.querySelector(".sl-num");
  box.dataset.moved = "1";             // only sliders the person actually moved are saved
  if (t === r) num.value = (r.dataset.none && +r.value === +r.min) ? "" : r.value;
  else if (t === num && num.value !== "") r.value = Math.min(+r.max, Math.max(+r.min, +num.value));
  else if (t === num) r.value = r.min;
  paintRange(r);
  refreshGroup(box.closest("[data-ag]"));
  refreshPool(box.closest("[data-pool]"));
});
document.addEventListener("change", (e) => {
  const t = e.target, box = t.closest(".sl");
  if (box && t.classList.contains("sl-num") && t.value !== "") t.value = box.querySelector(".sl-range").value;
});

// ---------------------------------------------------------------- charts (uPlot; colours from the theme)
const CHARTS = {};
const seriesColors = () => [css("--brand"), css("--warn"), css("--soft"), css("--bad"), css("--ok"), css("--faint")];
function chart(key, el, series, data, opts = {}) {
  if (CHARTS[key]) { CHARTS[key].destroy(); delete CHARTS[key]; }
  if (!el || !window.uPlot) return;
  const line = css("--line"), soft = css("--soft");
  const axis = { stroke: soft, grid: { stroke: line, width: 1 }, ticks: { stroke: line, width: 1 }, font: "12px " + css("--body") };
  const col = seriesColors();
  CHARTS[key] = new uPlot({
    width: Math.max(200, el.clientWidth || 600), height: opts.height || 230,
    cursor: { points: { size: 6 } }, scales: { x: { time: true } },
    series: [{ label: "Time", value: (u, v) => v == null ? "" : new Date(v * 1000).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) },
      ...series.map((s, i) => { const c = col[i % col.length];
        return { label: s.label, stroke: c, width: 2, fill: s.fill ? c + "1a" : undefined, points: { show: false },
                 value: (u, v) => v == null ? "" : (opts.fmt ? opts.fmt(v) : n(v)) }; })],
    axes: [opts.daily ? { ...axis, splits: () => data[0], values: (u, vals) => vals.map((v) => new Date(v * 1000).toLocaleDateString("en-GB", { weekday: "short", day: "numeric" })) } : axis,
      { ...axis, size: 64, values: (u, vals) => vals.map((v) => opts.axisFmt ? opts.axisFmt(v) : n(v)) }],
  }, data, el);
}
window.addEventListener("resize", () => Object.values(CHARTS).forEach((c) => { const el = c.root.parentElement; if (el?.clientWidth) c.setSize({ width: el.clientWidth, height: c.height }); }));

// ---------------------------------------------------------------- theme (System / Light / Dark)
function themeChoice() { const t = document.documentElement.dataset.theme; return t === "light" || t === "dark" ? t : "system"; }
function setTheme(t) {
  if (t === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t;
  store.set("pocketTheme", t === "system" ? null : t);
  drawThemeSeg(); rerender();
}
const themeSeg = () => [["system", "System"], ["light", "Light"], ["dark", "Dark"]]
  .map(([k, l]) => `<button data-theme-set="${k}" aria-pressed="${themeChoice() === k}">${l}</button>`).join("");
function drawThemeSeg() { $("theme-seg").innerHTML = themeSeg(); document.querySelectorAll(".theme-seg-here").forEach((x) => (x.innerHTML = themeSeg())); }
document.addEventListener("click", (e) => { const b = e.target.closest("[data-theme-set]"); if (b) setTheme(b.dataset.themeSet); });
matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", () => { if (themeChoice() === "system") rerender(); });
function rerender() { if (!$("shell").hidden && $("modal").hidden) route(STATE.route, true); }

// ---------------------------------------------------------------- navigation (in script: works even where the address can't change)
const NAV = [["home", "Home"], ["apps", "Apps"], ["files", "Files"], ["phone", "Phone"], ["settings", "Settings"]];
const ICON = {
  home: '<path d="M3 10.5 11 4l8 6.5V19H3z"/>',
  apps: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="12" y="3" width="7" height="7" rx="2"/><rect x="3" y="12" width="7" height="7" rx="2"/><rect x="12" y="12" width="7" height="7" rx="2"/>',
  files: '<path d="M3 6h6l2 2h8v10H3z"/>',
  phone: '<rect x="6" y="2" width="10" height="18" rx="2.5"/><path d="M10 17h2"/>',
  settings: '<circle cx="11" cy="11" r="3"/><path d="M11 2v3M11 17v3M2 11h3M17 11h3M4.6 4.6l2.1 2.1M15.3 15.3l2.1 2.1M4.6 17.4l2.1-2.1M15.3 6.7l2.1-2.1"/>',
};
const icon = (k) => `<svg width="20" height="20" viewBox="0 0 22 22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[k]}</svg>`;
// v1 addresses keep working
const LEGACY = { overview: "home", storage: "phone/storage", devices: "settings/devices", jobs: "phone/jobs", alerts: "phone/alerts", logs: "phone/logs" };
const STATE = { route: "home", top: "home", needs: 0, needsLevel: "", pool: null, features: [] };
let TICKET = 0;
function setAddress(h, replace) { try { history[replace ? "replaceState" : "pushState"](null, "", "#" + h); } catch {} }
function go(h) { setAddress(h); route(h); }
document.addEventListener("click", (e) => {
  const a = e.target.closest("a[href^='#']");
  if (!a || e.defaultPrevented || e.ctrlKey || e.metaKey) return;
  e.preventDefault();
  const h = a.getAttribute("href").slice(1);
  if (h) go(h);
});
const fromAddress = () => { if (!$("shell").hidden && decodeURIComponent(location.hash.slice(1) || "home") !== STATE.route) route(); };
window.addEventListener("popstate", fromAddress);
window.addEventListener("hashchange", fromAddress);
function drawNav() {
  const top = STATE.top;
  const badge = STATE.needs ? `<span class="badge ${STATE.needsLevel === "bad" ? "bad" : "warn"}">${STATE.needs}</span>` : "";
  $("rail-links").innerHTML = NAV.map(([k, l]) => `<a class="nav ${k === top ? "on" : ""}" href="#${k}"${k === top ? ' aria-current="page"' : ""}>${icon(k)}${l}${k === "home" ? badge : ""}</a>`).join("");
  $("tabs").innerHTML = NAV.map(([k, l]) => `<a class="${k === top ? "on" : ""}" href="#${k}"${k === top ? ' aria-current="page"' : ""}>${icon(k)}${l}${k === "home" && STATE.needs ? '<span class="pip"></span>' : ""}</a>`).join("");
}
function normalise(h) {
  let [p, ...rest] = h.split("/");
  if (LEGACY[p]) { const [np, ...nr] = LEGACY[p].split("/"); p = np; rest = [...nr, ...rest]; }
  if (p === "apps" && rest.length) { p = "app"; }
  if (!RENDER[p]) { p = "home"; rest = []; }
  return [p, rest];
}
function route(target, quiet) {
  const raw = target ?? (location.hash.slice(1) || "home");
  const [page, rest] = normalise(raw);
  const h = [page, ...rest].join("/");
  if (h !== raw && target == null) setAddress(h, true);
  STATE.route = h; STATE.page = page;
  STATE.top = page === "app" || page === "connect" ? "apps" : page;
  drawNav();
  const ticket = ++TICKET;
  const v = $("view");
  if (!quiet) { v.innerHTML = mobileTop() + `<p class="loading">Loading…</p>`; window.scrollTo(0, 0); }
  Object.keys(CHARTS).forEach((k) => { CHARTS[k].destroy(); delete CHARTS[k]; });
  Promise.resolve(RENDER[page](rest.map(decodeURIComponent), ticket))
    .catch((e) => { if (e.status !== 401 && ticket === TICKET) paint(`<h1>${esc(NAV.find((x) => x[0] === STATE.top)?.[1] || "Pocket Cloud")}</h1><p class="note bad">${esc(e.message)}</p>`); });
}
const stale = (ticket) => ticket !== TICKET;
const RENDER = {};
function mobileTop() {
  return `<div class="mobile-top"><img src="favicon.svg" alt="">Pocket Cloud<span class="dot ${STATE.health || "off"}" title="${esc($("health")?.textContent || "")}"></span></div>`;
}
function paint(html) { $("view").innerHTML = mobileTop() + html; }
const crumb = (parts) => `<div class="crumb">${parts.map(([h, l]) => h ? `<a href="#${h}">${esc(l)}</a>` : esc(l)).join(" / ")}</div>`;

// ---------------------------------------------------------------- sign-in
function gate(html) { $("shell").hidden = true; $("tabs").hidden = true; $("gate").hidden = false; $("gate").innerHTML = `<div class="card"><img class="logo" src="favicon.svg" alt="">${html}</div>`; }
function signedOut() { TOKEN = null; store.set("pocketToken", null); clearInterval(start.timer); showLogin(); }
function showLogin() {
  gate(`<h1>Pocket Cloud</h1><p class="lede" style="margin-bottom:6px">Sign in to the control panel.</p>
    <label class="field">Admin password<input type="password" id="g-pw" autocomplete="current-password"></label>
    <div class="err" id="g-err"></div><button class="btn primary" id="g-go">Sign in</button>`);
  const go_ = async () => {
    try { const r = await api("POST", "/api/login", { password: $("g-pw").value, device: "Web panel" }); TOKEN = r.token; store.set("pocketToken", TOKEN); start(); }
    catch (e) { $("g-err").textContent = e.message; }
  };
  $("g-go").onclick = go_; $("g-pw").onkeydown = (e) => { if (e.key === "Enter") go_(); }; $("g-pw").focus();
}
function showSetup() {
  gate(`<h1>Welcome to Pocket Cloud</h1><p class="lede" style="margin-bottom:6px">Choose the admin password for this control panel and the Android app. At least 8 characters.</p>
    <label class="field">Admin password<input type="password" id="g-pw" autocomplete="new-password"></label>
    <label class="field">Type it again<input type="password" id="g-pw2" autocomplete="new-password"></label>
    <div class="err" id="g-err"></div><button class="btn primary" id="g-go">Set password</button>`);
  $("g-go").onclick = async () => {
    if ($("g-pw").value !== $("g-pw2").value) { $("g-err").textContent = "The two passwords are different."; return; }
    try { const r = await api("POST", "/api/setup", { password: $("g-pw").value, device: "Web panel" }); TOKEN = r.token; store.set("pocketToken", TOKEN); start(); }
    catch (e) { $("g-err").textContent = e.message; }
  };
  $("g-pw").focus();
}
async function signOut() { await api("POST", "/api/logout", {}).catch(() => {}); signedOut(); }
$("signout").onclick = signOut;

async function boot() {
  let st;
  try { st = await api("GET", "/api/status"); }
  catch (e) { gate(`<h1>Pocket Cloud</h1><p class="note bad">${esc(e.message)}</p>`); return; }
  if (Array.isArray(st.features)) STATE.features = st.features;
  if (!st.password_set) return showSetup();
  if (!TOKEN || !st.signed_in) return showLogin();
  start();
}
function start() {
  $("gate").hidden = true; $("shell").hidden = false; $("tabs").hidden = false;
  $("host").textContent = location.host;
  drawThemeSeg();
  route();
  refreshTop();
  clearInterval(start.timer);
  // keep Home live, but never redraw under an open dialog
  start.timer = setInterval(() => { refreshTop(); if (STATE.page === "home" && $("modal").hidden) route(STATE.route, true); }, 15000);
}
const has = (f) => STATE.features.includes(f);
async function refreshTop() {
  try {
    const o = await api("GET", "/api/overview");
    noteOverview(o);
  } catch {}
}
function noteOverview(o) {
  STATE.overview = o;
  if (Array.isArray(o.features)) STATE.features = o.features;
  const worst = o.alerts.some((a) => a.level === "bad") ? "bad" : o.alerts.some((a) => a.level === "warn") ? "warn" : "";
  const okAll = o.services.every((s) => s.ok);
  STATE.health = !okAll || worst === "bad" ? "bad" : worst ? "warn" : "";
  $("health-dot").className = "dot " + STATE.health;
  $("health").textContent = !okAll ? "A service is down" : worst === "bad" ? "Needs attention" : worst ? "Warning" : "All healthy";
  $("updated").textContent = "Updated " + hm(o.now);
  STATE.needs = o.alerts.filter((a) => a.level !== "info").length;
  STATE.needsLevel = worst;
  drawNav();
  const md = document.querySelector(".mobile-top .dot"); if (md) md.className = "dot " + (STATE.health || "");
}

// ---------------------------------------------------------------- shared bits
const alertRow = (a) => `<div class="row"><span class="dot ${a.level === "bad" ? "bad" : a.level === "warn" ? "warn" : "info"}"></span><div class="grow">${esc(a.text)}<div class="faint small">since ${dt(a.raised)}</div></div></div>`;
function stat(l, v, s) { return `<div class="card stat"><div class="l">${l}</div><div class="v">${v}</div><div class="s">${s}</div></div>`; }
function legend(color, label, value) { return `<div class="legend-row"><span><span class="swatch" style="background:${color}"></span>${label}</span><span class="num">${value}</span></div>`; }
function frow(label, hint, control) { return `<div class="frow"><div>${label}${hint ? `<div class="h">${hint}</div>` : ""}</div><div class="c">${control}</div></div>`; }
const spaceLabel = (sp) => sp === "shared" ? "shared" : sp === "family" ? "family" : "device " + sp.slice(7);
const KIND_LABEL = { video: "Video", photo: "Photo", audio: "Audio", archive: "Archive", other: "Other" };

// ================================================================ HOME
RENDER.home = async (args, ticket) => {
  const [o, apps, cd, jobs] = await Promise.all([api("GET", "/api/overview"), api("GET", "/api/apps"), api("GET", "/api/cards").catch(() => null), api("GET", "/api/jobs").catch(() => null)]);
  if (stale(ticket)) return;
  noteOverview(o);
  const P = o.pool, ph = o.phone;
  const names = Object.fromEntries(o.allocation.map((a) => [a.id, a.name]));
  const realApps = apps.apps.filter((a) => !a.is_family);
  // the drawing: internal pool on the screen, SD pool on the card
  const H = 196, ip = P.int_limit ? Math.min(1, P.int_used / P.int_limit) : 0, sp = P.sd_limit ? Math.min(1, P.sd_used / P.sd_limit) : 0;
  const ih = H * ip, sh = 74 * sp, hasSd = P.sd_limit > 0;
  // what needs the person
  const need = [];
  for (const a of o.alerts.filter((x) => x.level !== "info")) need.push(`<a class="row listlink" href="#phone/alerts"><span class="dot ${a.level === "bad" ? "bad" : "warn"}"></span><div class="grow"><h3>${esc(a.text)}</h3>${s13("since " + dt(a.raised))}</div><span class="btn small">Open</span></a>`);
  for (const a of apps.apps.filter((x) => x.status !== "active")) need.push(`<a class="row listlink" href="#app/${a.id}">${mark(a)}<div class="grow"><h3>${esc(a.name)} is waiting</h3>${s13("It connected for the first time and waits for you to allow it.")}</div><span class="btn small">Open</span></a>`);
  for (const a of o.allocation) { const pct = a.used / (a.alloc || 1) * 100;    // unless an alert already says so
    if (a.alloc && pct >= a.warn_pct && !o.alerts.some((x) => x.text.includes(a.name))) need.push(`<a class="row listlink" href="#app/${a.id}">${mark(a)}<div class="grow"><h3>${esc(a.name)}</h3>${s13(`At ${pct.toFixed(0)}% of its ${gbv(a.alloc)}`)}</div><span class="btn small">Open</span></a>`); }
  if (cd && cd.mode === "cards") {
    const sdRow = (t, s) => need.push(`<a class="row listlink" href="#phone"><span class="mark" style="background:var(--faint)">SD</span><div class="grow"><h3>${t}</h3>${s13(s)}</div><span class="btn small">Open</span></a>`);
    if (cd.active && !cd.active.present) sdRow(`${esc(cd.active.name)} was taken out without a swap`, "Its files are unavailable until it is back.");
    if (cd.slot?.state === "new") sdRow("A new SD card is in the phone", "Set it up to add room and a nightly copy.");
    if (cd.slot?.state === "needs_setup") sdRow("A card is in, but Termux can't see it yet", "Run termux-setup-storage on the phone.");
    if (cd.slot?.state === "archive") sdRow(`${esc(cd.slot.card.name)}, an archive card, is in the phone`, "Browse it, or put it back to work.");
    if (cd.slot?.state === "shelved_here") sdRow(`${esc(cd.slot.card.name)} is ready to take out`, "Unmount it in Android's settings first.");
    if (cd.job?.running) sdRow("An SD card job is running", esc(cd.job.step || ""));
  }
  const hourly = Object.keys(o.hourly);
  const nightly = jobs?.jobs?.find((j) => j.key !== "health" && j.last);
  paint(`
  <div class="hero">
    <div class="device">
      <svg viewBox="0 0 250 300" role="img" aria-label="The phone: Pocket's internal pool ${(ip * 100).toFixed(0)}% full${hasSd ? `, SD card pool ${(sp * 100).toFixed(0)}% full` : ", no SD card"}">
        <rect x="30" y="14" width="128" height="272" rx="22" fill="var(--surface)" stroke="var(--ink)" stroke-width="3"/>
        <clipPath id="scr"><rect x="42" y="50" width="104" height="${H}" rx="8"/></clipPath>
        <rect x="42" y="50" width="104" height="${H}" rx="8" fill="var(--sunk)"/>
        <rect class="level" data-h="${ih}" data-y="${50 + H - ih}" x="42" y="${50 + H}" width="104" height="0" fill="var(--brand)" clip-path="url(#scr)"/>
        <circle cx="94" cy="32" r="3.5" fill="var(--ink)"/>
        <rect x="76" y="262" width="36" height="5" rx="2.5" fill="var(--line)"/>
        <text x="94" y="${Math.max(64, 50 + H - ih - 8)}" text-anchor="middle" font-family="var(--mono)" font-size="11" fill="${ih > H - 20 ? "var(--accent-ink)" : "var(--soft)"}">${fb(P.int_used)} / ${gbv(P.int_limit)}</text>
        ${hasSd ? `<path d="M176 196h44l10 12v70a6 6 0 0 1-6 6h-48a6 6 0 0 1-6-6v-76a6 6 0 0 1 6-6z" fill="var(--surface)" stroke="var(--ink)" stroke-width="3"/>
        <clipPath id="sd"><rect x="178" y="208" width="44" height="74" rx="3"/></clipPath>
        <rect x="178" y="208" width="44" height="74" rx="3" fill="var(--sunk)"/>
        <rect class="level" data-h="${sh}" data-y="${208 + 74 - sh}" x="178" y="282" width="44" height="0" fill="var(--warn)" clip-path="url(#sd)"/>
        <text x="200" y="188" text-anchor="middle" font-family="var(--mono)" font-size="11" fill="var(--soft)">SD ${fb(P.sd_used)}</text>` : ""}
      </svg>
    </div>
    <div>
      <h1>Your pocket is holding ${fb(o.apps_used)} for ${realApps.length} app${realApps.length === 1 ? "" : "s"}.</h1>
      <p class="lede" style="margin-bottom:16px">${gbv(Math.max(0, P.total - P.used))} still free in the pool${hasSd ? " across the phone and its SD card" : " on the phone"}. ${n(o.files)} files, ${o.device_count} device${o.device_count === 1 ? "" : "s"}.</p>
      <div class="kv">
        <span>Up for</span><span>${dur(o.now - o.started_at)} <span class="faint">· since ${dt(o.started_at)}</span></span>
        <span>Last nightly jobs</span><span>${nightly ? `${dt(nightly.last.started)} <span class="${nightly.last.result === "ok" ? "faint" : "warn"}">· ${esc(nightly.last.detail || nightly.last.result || "")}</span>` : '<span class="faint">not run yet</span>'}</span>
        <span>Phone</span><span>${ph ? [ph.battery != null ? ph.battery + "% battery" : "", ph.plugged ? esc(ph.plugged) : "", ph.temp_c != null ? ph.temp_c.toFixed(1) + " °C" : ""].filter(Boolean).join(", ") || "—" : '<span class="faint">no readings (Termux:API)</span>'}</span>
        <span>Server</span><span class="mono">${esc(o.version)}</span>
      </div>
    </div>
  </div>
  <div class="grid g2">
    <section class="card">
      <h2>Needs you</h2>
      <div class="list">${need.length ? need.join("") : `<div class="row"><span class="dot"></span><span class="soft">Nothing needs you. Everything is running.</span></div>`}</div>
    </section>
    <section class="card">
      <div class="card-head"><h2>Apps</h2><a href="#apps">All apps</a></div>
      <div class="list">${o.allocation.length ? o.allocation.slice().sort((x, y) => y.used / (y.alloc || 1) - x.used / (x.alloc || 1)).slice(0, 6).map((a) =>
        `<a class="row listlink" href="#app/${a.id}">${mark(a)}<div class="grow"><div class="row between"><h3>${esc(a.name)}</h3><span class="soft small num">${fb(a.used)} of ${gbv(a.alloc)}</span></div><div style="margin-top:6px">${meter(a.used, a.alloc, a.warn_pct)}</div></div></a>`).join("")
        : `<p class="soft">No apps yet. They appear when they first connect. <a href="#connect">Connect a project</a></p>`}</div>
    </section>
  </div>
  <div class="grid g4 mt">
    ${stat("Requests, 24 h", n(o.traffic.requests), `${n(o.traffic.errors)} errors${o.traffic.requests ? ` (${(o.traffic.errors / o.traffic.requests * 100).toFixed(2)}%)` : ""}`)}
    ${stat("Traffic, 24 h", fb(o.traffic.bytes_in + o.traffic.bytes_out), `${fb(o.traffic.bytes_in)} in · ${fb(o.traffic.bytes_out)} out`)}
    ${stat("Promised to apps", gbv(P.promised), `${gbv(P.unpromised)} of ${gbv(P.total)} unpromised`)}
    ${stat("Battery", ph && ph.battery != null ? ph.battery + "%" : "—", ph ? `${esc(ph.plugged || "")}${ph.temp_c != null ? " · " + ph.temp_c.toFixed(1) + " °C" : ""}` : "no phone readings")}
  </div>
  <section class="card mt">
    <div class="card-head"><h2>Requests per hour, by app</h2><span class="soft small">last 24 h</span></div>
    ${hourly.length ? `<div class="chart" id="ch-req"></div>` : `<p class="empty">No requests in the last 24 hours.</p>`}
  </section>`);
  requestAnimationFrame(() => document.querySelectorAll(".level").forEach((r) => { r.setAttribute("height", r.dataset.h); r.setAttribute("y", r.dataset.y); }));
  if (hourly.length) chart("req", $("ch-req"), hourly.map((a) => ({ label: names[a] || a })), [o.hours, ...hourly.map((a) => o.hourly[a])]);
};

// ================================================================ APPS
function allocSlider(a) {
  const min = Math.max(0.5, Math.ceil(a.used / GB * 2) / 2);
  const max = Math.max(min, Math.floor((a.alloc_max ?? a.alloc + STATE.unpromised) / GB * 2) / 2);
  return slider({ min, max, step: 0.5, value: a.alloc / GB, unit: "GB", cls: "sl-alloc", label: "Allocation",
    marks: [{ v: a.used / GB, label: "in use " + fb(a.used), color: css("--soft") }, { k: "warn", v: a.alloc / GB * a.warn_pct / 100, label: "warning", color: css("--warn") }] });
}
async function openAlloc(id) {
  const a = await act(() => api("GET", "/api/apps/" + id));
  if (!a) return;
  STATE.unpromised = a.unpromised;
  openModal(`<h2>Space for ${esc(a.name)}</h2>
    <div class="body" data-ag data-used="${a.used}" data-warn="${a.warn_pct}">${allocSlider(a)}
      <p class="sl-preview" style="margin:10px 0 0"></p>
      <p class="faint small" style="margin-top:6px">Up to ${gbv(a.alloc_max)}: this app's allocation plus the ${gbv(a.unpromised)} of the pool not yet promised to any app. It can't go below what the app already holds.</p></div>
    ${foot("Save", false, "m-save")}`);
  refreshGroup($("modal-box").querySelector("[data-ag]"));
  $("m-no").onclick = closeModal;
  $("m-save").onclick = async () => {
    const alloc = Math.round(slVal($("modal-box").querySelector(".sl-alloc")) * GB);
    if (await act(() => api("PATCH", "/api/apps/" + id, { alloc }), "Space saved.")) { closeModal(); route(STATE.route, true); }
  };
}
async function newApp() {
  const id = await promptBox("New app", "App name (lowercase letters, digits and dashes)", "", "Create");
  if (id && await act(() => api("POST", "/api/apps", { id: id.trim(), name: id.trim() }), "App created.")) go("app/" + id.trim());
}

RENDER.apps = async (args, ticket) => {
  const d = await api("GET", "/api/apps");
  if (stale(ticket)) return;
  STATE.unpromised = d.pool.unpromised;
  const P = d.pool;
  paint(`
  <div class="row between wrap" style="margin-bottom:6px"><h1>Apps</h1><div class="actions"><button class="btn" id="new-app">New app</button><a class="btn primary" href="#connect">Connect a project</a></div></div>
  <p class="lede">Each of your projects gets its own space on the phone. A project shows up here the first time it connects. New apps get ${d.defaults.alloc_gb} GB, a warning at ${d.defaults.warn_pct}% and ${d.defaults.at_limit === "refuse" ? "refused uploads" : "a warning only"} at 100%.</p>
  <section class="card" style="margin-bottom:16px"><div class="row wrap">
    <span><b class="num">${gbv(P.promised)}</b> <span class="soft">promised to apps out of the</span> <b class="num">${gbv(P.total)}</b> <span class="soft">pool ·</span> <b class="num">${gbv(P.unpromised)}</b> <span class="soft">unpromised</span></span>
    <span class="grow" style="min-width:160px">${meter(P.promised, P.total)}</span>
    <a href="#settings/pool">Pool settings</a>
  </div></section>
  <div class="grid g3">
    ${d.apps.map((a) => { const pct = a.used / (a.alloc || 1) * 100;
      return `<a class="card appcard" href="#app/${a.id}">
      <div class="row">${mark(a)}<div style="min-width:0"><h3>${esc(a.name)}</h3><span class="soft small">${a.is_family ? "Shared by family apps" : `${a.devices.length} device${a.devices.length === 1 ? "" : "s"}`} · <span class="mono">${esc(a.id)}</span></span></div></div>
      ${a.notes ? `<p class="soft small">${esc(a.notes)}</p>` : ""}
      <div>${a.status !== "active" ? '<span class="badge warn">Waiting for you</span> ' : ""}${a.family && !a.is_family ? '<span class="badge use">In the family</span> ' : ""}${a.errors ? `<span class="badge bad">${n(a.errors)} errors today</span>` : ""}</div>
      <div class="stack" style="gap:6px">${meter(a.used, a.alloc, a.warn_pct)}<div class="row between"><span class="num">${fb(a.used)} of ${gbv(a.alloc)}</span>${pct >= a.warn_pct ? `<span class="badge ${pct >= 100 ? "bad" : "warn"}">${pct >= 100 ? "Full" : "Filling up"}</span>` : `<span class="faint small">${ago(a.last_used)}</span>`}</div></div>
    </a>`; }).join("")}
    <a class="card appcard add" href="#connect"><h3>Connect a project</h3><p class="soft small">Get the few lines a project needs to use the phone.</p></a>
  </div>`);
  $("new-app").onclick = newApp;
};

// ---- one app
RENDER.app = async (args, ticket) => {
  const id = args[0];
  const a = await api("GET", "/api/apps/" + encodeURIComponent(id));
  if (stale(ticket)) return;
  const wantRules = ["retention", "publish", "notify", "changes"].some(has);
  const rules = wantRules ? await api("GET", `/api/apps/${a.id}/rules`).catch(() => null) : null;
  if (stale(ticket)) return;
  STATE.unpromised = a.unpromised;
  const pct = a.used / (a.alloc || 1) * 100;
  const creator = a.devices.find((d) => d.uid === a.created_by)?.name || a.created_by;
  paint(`
  ${crumb([["apps", "Apps"], [null, a.name]])}
  <div class="row wrap" style="margin-bottom:22px">${mark(a)}<div class="grow" style="min-width:200px"><h1 style="margin:0">${esc(a.name)}</h1>
    <span class="soft small"><span class="mono">${esc(a.id)}</span> · created ${dt(a.created_at)} ${a.created_by === "control panel" ? "in the control panel" : "when it first connected from " + esc(creator)}</span></div>
    <div class="actions"><a class="btn" href="#connect/${a.id}">Setup code</a><a class="btn" href="#phone/logs/${a.id}">Request log</a><a class="btn" href="#files/${a.id}">Browse files</a></div></div>
  ${a.status !== "active" ? `<p class="note info">This app is waiting for approval: it connected, but can't store anything yet. <button class="btn small primary" id="approve-app">Allow it</button></p>` : ""}
  ${pct >= a.warn_pct ? `<p class="note ${pct >= 100 ? "bad" : ""}">At ${pct.toFixed(0)}% of its ${gbv(a.alloc)}. ${a.at_limit === "refuse" ? `Uploads are refused at ${gbv(a.alloc)}.` : "Uploads continue past it (warn only)."} <button class="btn small" id="raise">Give it more space</button></p>` : ""}
  <div class="grid g4" style="margin-bottom:16px">
    ${stat("Used", fb(a.used), `of ${gbv(a.alloc)} · ${n(a.files)} files`)}
    ${stat("Shared space", fb(a.shared), a.devices.length === 1 ? "used by its one device" : a.devices.length === 2 ? "used by both devices" : a.devices.length ? `used by all ${a.devices.length} devices` : "no devices yet")}
    ${stat("Requests, 24 h", n(a.requests), `${n(a.errors)} errors`)}
    ${stat("Traffic, 24 h", fb(a.bytes_in + a.bytes_out), `${fb(a.bytes_in)} in · ${fb(a.bytes_out)} out`)}
  </div>
  <div class="grid g2" style="align-items:start">
    <section class="card span2">
      <h2>Space and settings</h2>
      <div class="form" id="app-form">
        ${frow("Display name", "", `<input type="text" id="f-name" value="${esc(a.name)}" style="width:260px">`)}
        ${frow("Notes", "What it keeps here", `<input type="text" id="f-notes" value="${esc(a.notes)}" style="width:100%">`)}
        <div data-ag data-used="${a.used}" data-devices="${a.devices.length || 1}">
          ${frow("Space", `Shared and private spaces together · up to ${gbv(a.alloc_max)} (this allocation + the pool's unpromised space)`, allocSlider(a))}
          ${frow("", "", `<span class="sl-preview soft small"></span>`)}
          ${frow("Warn at", "", slider({ min: 50, max: 95, step: 5, value: a.warn_pct, unit: "%", cls: "sl-warn", label: "Warn at" }))}
          ${frow("At 100%", "", `<select id="f-limit" aria-label="At 100%"><option value="refuse"${a.at_limit === "refuse" ? " selected" : ""}>Refuse uploads</option><option value="warn"${a.at_limit === "warn" ? " selected" : ""}>Warn only</option></select>`)}
          ${frow("Cap per device", "Limit on each private space · left end = none", slider({ min: 0, max: a.alloc / GB, step: 0.5, value: (a.device_cap || 0) / GB, unit: "GB", none: true, cls: "sl-cap", label: "Cap per device" }))}
          ${frow("Reserve per device", "Room each device can always use, whatever the others took · left end = none", slider({ min: 0, max: a.alloc / GB / (a.devices.length || 1), step: 0.25, value: (a.device_reserve || 0) / GB, unit: "GB", none: true, cls: "sl-res", label: "Reserve per device" }))}
        </div>
        ${frow("Move to SD card after", "Days without changes", slider({ min: 1, max: 60, value: a.move_days, unit: "days", cls: "sl-move", label: "Move to SD card after" }))}
        ${a.is_family ? "" : frow("Family bucket", "A third bucket, <span class='mono'>family</span>, shared by every app in the family", sw('id="f-family"', a.family, "Family bucket"))}
      </div>
      <div class="form-foot"><button class="btn primary" id="f-save">Save</button><button class="btn" id="f-cancel">Discard</button></div>
    </section>
    <section class="card">
      <div class="card-head"><h2>Devices</h2><span class="soft small">each has its own key and private space</span></div>
      <div class="list">${a.devices.length ? a.devices.map((d) => `<div class="row wrap"><span class="dot ${d.blocked ? "bad" : d.last_seen && Date.now() / 1000 - d.last_seen < 900 ? "" : "off"}"></span>
        <div class="grow" style="min-width:160px"><h3>${esc(d.name)}${d.blocked ? ' <span class="badge bad">Blocked</span>' : ""}</h3>
        ${s13(`Last seen ${ago(d.last_seen)} · <span class="mono">${esc(d.ip)}${d.ip_alt ? " · " + esc(d.ip_alt) : ""}</span> · key <span class="mono">${esc(d.key)}</span> · ${fb(d.private)} private · ${n(d.requests)} requests today`)}</div>
        <div class="actions"><button class="btn small" data-dev-key="${d.uid}">New key</button><button class="btn small danger" data-dev-rm="${d.uid}" data-name="${esc(d.name)}">Remove</button></div></div>`).join("")
        : `<p class="soft">No devices yet.</p>`}</div>
    </section>
    <section class="card">
      <h2>Spaces</h2>
      <div class="list">
        <div class="row between"><span><span class="mono">shared</span> <span class="soft">· all devices</span></span><span class="num">${fb(a.shared)}</span></div>
        ${a.devices.map((d) => `<div class="row between"><span><span class="mono">device</span> <span class="soft">· ${esc(d.name)}</span></span><span class="num">${fb(d.private)}</span></div>`).join("")}
        <div class="row between"><span class="soft">On internal storage / SD card</span><span class="num">${fb(a.int)} / ${fb(a.sd)}</span></div>
      </div>
    </section>
    ${rulesCards(a, rules)}
    <section class="card span2">
      <div class="card-head"><h2>Requests per day, by kind</h2><span class="soft small">last 7 days</span></div>
      <div class="chart" id="ch-app"></div>
    </section>
    <section class="card span2">
      <h2>Actions</h2>
      <div class="actions"><button class="btn" id="a-keys">New keys for all devices</button><button class="btn danger" id="a-empty">Empty app</button>${a.is_family ? "" : `<button class="btn danger" id="a-del">Delete app</button>`}</div>
    </section>
  </div>`);
  const s = $("view");
  refreshGroup(s.querySelector("[data-ag]"));
  chart("app", $("ch-app"), ["Reads", "Writes", "Listings", "Deletes"].map((l) => ({ label: l })),
    [a.days.map((d) => new Date(d + "T12:00:00").getTime() / 1000), a.series.reads, a.series.writes, a.series.listings, a.series.deletes], { daily: true });
  if ($("raise")) $("raise").onclick = () => openAlloc(a.id);
  $("f-save").onclick = async () => {
    const f = $("app-form");
    const moved = (cls) => f.querySelector(cls).dataset.moved ? f.querySelector(cls) : null;   // unmoved sliders aren't sent
    const body = { name: $("f-name").value, notes: $("f-notes").value, at_limit: $("f-limit").value };
    if (moved(".sl-alloc")) body.alloc = Math.round(slVal(moved(".sl-alloc")) * GB);
    if (moved(".sl-warn")) body.warn_pct = slVal(moved(".sl-warn"));
    if (moved(".sl-move")) body.move_days = slVal(moved(".sl-move"));
    if (moved(".sl-cap")) body.device_cap = Math.round(slVal(moved(".sl-cap")) * GB) || null;
    if (moved(".sl-res")) body.device_reserve = Math.round(slVal(moved(".sl-res")) * GB) || null;
    if ($("f-family") && swOn($("f-family")) !== a.family) body.family = swOn($("f-family"));
    if (await act(() => api("PATCH", "/api/apps/" + a.id, body), "Settings saved.")) route(STATE.route, true);
  };
  $("f-cancel").onclick = () => route(STATE.route, true);
  if ($("approve-app")) $("approve-app").onclick = async () => { if (await act(() => api("PATCH", "/api/apps/" + a.id, { status: "active" }), "App allowed.")) route(STATE.route, true); };
  s.querySelectorAll("[data-dev-key]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("New key", "The device gets a new key; the old one stops working. The Pocket library on that device picks up the new key by itself.", "Make new key"))
      if (await act(() => api("POST", `/api/apps/${a.id}/devices/${b.dataset.devKey}/rotate-key`), "New key made.")) route(STATE.route, true);
  });
  s.querySelectorAll("[data-dev-rm]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Remove device", `Remove <b>${esc(b.dataset.name)}</b> from ${esc(a.name)}? Its private files go into the undo. If the app runs there again, it enrolls as a new device.`, "Remove", true))
      if (await act(() => api("DELETE", `/api/apps/${a.id}/devices/${b.dataset.devRm}`), "Device removed.")) route(STATE.route, true);
  });
  $("a-keys").onclick = async () => { if (await confirmBox("New keys for all devices", "Every device of this app gets a new key; the libraries pick them up by themselves.", "Make new keys")) act(() => api("POST", `/api/apps/${a.id}/rotate-keys`), "New keys made."); };
  $("a-empty").onclick = async () => {
    if (await confirmBox("Empty app", `Delete all ${n(a.files)} files of <b>${esc(a.name)}</b>? They go into the undo first. The app and its devices stay.`, "Empty app", true))
      if (await act(() => api("POST", `/api/apps/${a.id}/empty`), (r) => `${n(r.files)} files moved to the undo.`)) route(STATE.route, true);
  };
  if ($("a-del")) $("a-del").onclick = async () => {
    if (await confirmBox("Delete app", `Delete <b>${esc(a.name)}</b>, its devices and keys? Its files go into the undo; the app itself can't be brought back. Its ${gbv(a.alloc)} return to the pool.`, "Delete app", true))
      if (await act(() => api("DELETE", "/api/apps/" + a.id), "App deleted.")) go("apps");
  };
  if (rules) bindRules(a, rules);
};

// ---- per-app rules (docs/app-api.md section 9): shown only for the features the server reports
function rulesCards(a, R) {
  if (!R) return "";
  const out = [];
  if (has("retention") && R.retention) {
    const buckets = a.is_family ? ["family"] : ["shared", "device"];
    out.push(`<section class="card span2" id="rules-ret">
      <div class="card-head"><h2>Keep only recent files</h2><span class="soft small">applied each night; removed files go into the undo</span></div>
      <div class="list" id="ret-list">${R.retention.map((r, i) => retRow(r, i, buckets)).join("")}</div>
      <p class="soft small" id="ret-empty"${R.retention.length ? " hidden" : ""}>No rules. Files stay until an app deletes them.</p>
      <div class="form-foot"><button class="btn" id="ret-add">Add a rule</button><button class="btn primary" id="ret-save">Save rules</button></div>
    </section>`);
  }
  const notifyOrPublish = [];
  if (has("publish") && R.publish) notifyOrPublish.push(`<div class="row"><div class="grow"><h3>Publish folder for updates</h3>
    ${s13(R.publish.enabled ? `Devices on your network read <span class="mono">releases/</span> without a key at <span class="mono">${esc(R.publish.url)}</span>` : "Devices on your network could read <span class='mono'>releases/</span> without a key")}
    ${R.publish.latest ? `<div class="soft small">Latest: <b>${esc(R.publish.latest.version)}</b>, published ${dt(R.publish.latest.published)}</div>` : ""}</div>
    ${sw('id="rule-publish" data-live="1"', R.publish.enabled, "Publish folder for updates")}</div>`);
  if (has("notify") && R.notify) notifyOrPublish.push(`<div class="row"><div class="grow"><h3>Let the app send its own messages</h3>
    ${s13(`Topic <span class="mono">${esc(R.notify.topic)}</span> · ${n(R.notify.sent_today)} of ${n(R.notify.limit_day)} sent today`)}</div>
    ${sw('id="rule-notify" data-live="1"', R.notify.enabled, "Let the app send its own messages")}</div>`);
  if (has("changes") && R.changes) notifyOrPublish.push(`<div class="row"><div class="grow"><h3>Change feed</h3>
    ${s13(`${n(R.changes.count)} changes kept for ${R.changes.keep_days} days, so devices can catch up`)}</div></div>`);
  if (notifyOrPublish.length) out.push(`<section class="card span2"><h2>Updates and notifications</h2><div class="list">${notifyOrPublish.join("")}</div></section>`);
  return out.join("");
}
function retRow(r, i, buckets) {
  const last = r.last_run ? `Last run ${dt(r.last_run)}: ${n(r.last_removed || 0)} removed, ${fb(r.last_freed || 0)} freed` : "Not run yet";
  return `<div class="row wrap" data-ret>
    <select data-f="bucket" aria-label="Space">${buckets.map((b) => `<option${b === r.bucket ? " selected" : ""}>${b}</option>`).join("")}</select>
    <input type="text" data-f="prefix" placeholder="backups/" value="${esc(r.prefix || "")}" aria-label="Folder" style="width:180px">
    <label class="soft small">keep last <input type="number" data-f="keep_last" min="1" max="1000" value="${r.keep_last ?? ""}" placeholder="—" style="width:76px"></label>
    <label class="soft small">or newer than <input type="number" data-f="keep_days" min="1" max="3650" value="${r.keep_days ?? ""}" placeholder="—" style="width:76px"> days</label>
    <span class="faint small grow" style="min-width:160px">${i == null ? "New rule" : last}</span>
    <button class="btn small danger" data-ret-rm>Remove</button></div>`;
}
function bindRules(a, R) {
  const list = $("ret-list");
  if (list) {
    const buckets = a.is_family ? ["family"] : ["shared", "device"];
    const empty = () => ($("ret-empty").hidden = !!list.children.length);
    list.addEventListener("click", (e) => { if (e.target.closest("[data-ret-rm]")) { e.target.closest("[data-ret]").remove(); empty(); } });
    $("ret-add").onclick = () => { list.insertAdjacentHTML("beforeend", retRow({ bucket: buckets[0], prefix: "", keep_last: 7, keep_days: null }, null, buckets)); empty(); };
    $("ret-save").onclick = async () => {
      const num = (el) => el.value === "" ? null : +el.value;
      const retention = [...list.querySelectorAll("[data-ret]")].map((row) => ({
        bucket: row.querySelector("[data-f=bucket]").value, prefix: row.querySelector("[data-f=prefix]").value.trim(),
        keep_last: num(row.querySelector("[data-f=keep_last]")), keep_days: num(row.querySelector("[data-f=keep_days]")) }));
      if (await act(() => api("PATCH", `/api/apps/${a.id}/rules`, { retention }), "Rules saved.")) route(STATE.route, true);
    };
  }
  for (const k of ["publish", "notify"]) {
    const el = $("rule-" + k);
    if (el) el.onclick = async () => {
      const on = !swOn(el);
      if (await act(() => api("PATCH", `/api/apps/${a.id}/rules`, { [k]: { enabled: on } }), on ? "Switched on." : "Switched off.")) route(STATE.route, true);
    };
  }
}

// ================================================================ CONNECT A PROJECT
const WIZ = { kind: "python", goals: new Set(["backup"]), app: "" };
const SNIP = {
  android: {
    setup: '// Kotlin-lite library (clients/kotlin-lite), no AWS SDK\nval pocket = Pocket.connect(context, app = "APP")',
    backup: '// Back up a file and keep the 7 newest copies\npocket.backup(File(filesDir, "ledger.db"), keep = 7)',
    sync: '// Two-way sync of a folder between your devices\npocket.sync(File(filesDir, "sync"), bucket = "shared")\npocket.changes().collect { change -> reload(change.key) }',
    updates: '// Read the newest release from the publish folder\nval latest = pocket.releases().latest()\nif (latest != null && latest.version != BuildConfig.VERSION_NAME) latest.download(File(cacheDir, "update.apk"))',
    notify: '// Send a message to your other devices\npocket.notify("6 new calls", title = "Evening calls are ready")',
  },
  python: {
    setup: '# pip install ./clients/python   (pocket_cloud)\nimport pocket_cloud as pocket\n\napp = pocket.app("APP")',
    backup: '# Back up a file and keep the 7 newest copies\napp.backup("data/station.db", keep=7)',
    sync: '# Two-way sync of a folder between your devices\napp.sync("data/research")\nfor change in app.changes():\n    reload(change.key)',
    updates: '# Publish a release for your other devices\napp.publish("dist/Setup-1.2.0.exe", version="1.2.0")',
    notify: '# Send a message to your phone\napp.notify("Video is ready to review", title="Village")',
  },
  node: {
    setup: '// npm install ./clients/node   (pocket-cloud)\nconst pocket = require("pocket-cloud");\n\nconst app = await pocket.app("APP");',
    backup: '// Back up a file and keep the 7 newest copies\nawait app.backup(path.join(userData, "layouts.json"), { keep: 7 });',
    sync: '// Two-way sync of a folder between your devices\nawait app.sync(path.join(userData, "recordings"));\napp.onChange((change) => reload(change.key));',
    updates: '// electron-updater: read releases from the publish folder\nautoUpdater.setFeedURL({ provider: "generic", url: app.releasesUrl() });',
    notify: '// Send a message to your other devices\nawait app.notify("Lights out in 10 minutes", { title: "RaceWall" });',
  },
};
RENDER.connect = async (args, ticket) => {
  if (args[0]) WIZ.app = args[0];
  const d = await api("GET", "/api/apps");
  if (stale(ticket)) return;
  const appId = (WIZ.app || "my-app").trim() || "my-app";
  const s = SNIP[WIZ.kind];
  const code = [s.setup].concat(["backup", "sync", "updates", "notify"].filter((g) => WIZ.goals.has(g)).map((g) => s[g])).join("\n\n").replace(/APP/g, appId);
  const chip = (group, key, label) => `<button class="chip" data-wiz="${group}:${key}" aria-pressed="${group === "kind" ? WIZ.kind === key : WIZ.goals.has(key)}">${label}</button>`;
  const known = d.apps.find((a) => a.id === appId);
  paint(`
  ${crumb([["apps", "Apps"], [null, "Connect a project"]])}
  <h1>Connect a project</h1>
  <p class="lede">Say what the project is and what you want from the phone. The code is everything the project needs; it shows up under Apps the first time it runs.</p>
  <div class="grid g2" style="align-items:start">
    <div class="stack" style="gap:16px">
      <section class="card"><h2>What kind of project?</h2><div class="row wrap">${chip("kind", "android", "Android app")}${chip("kind", "python", "Python")}${chip("kind", "node", "Electron / Node")}</div></section>
      <section class="card"><h2>What should it do?</h2><div class="row wrap">${chip("goal", "backup", "Back up its data")}${chip("goal", "sync", "Sync between devices")}${chip("goal", "updates", "Deliver updates")}${chip("goal", "notify", "Send notifications")}</div></section>
      <section class="card"><h2>Its name</h2>
        <label class="field" style="margin-top:0">App name: lowercase letters, digits and dashes<input type="text" id="wiz-app" value="${esc(WIZ.app)}" placeholder="my-app" list="wiz-apps"></label>
        <datalist id="wiz-apps">${d.apps.filter((a) => !a.is_family).map((a) => `<option value="${esc(a.id)}">`).join("")}</datalist>
        <p class="soft small" style="margin-top:8px">${known ? `<a href="#app/${known.id}">${esc(known.name)}</a> is already connected; this code reuses its space.` : "A new name makes a new app with the default space."}</p>
      </section>
      <section class="card"><h2>This machine</h2>
        <p class="soft">The project finds the phone through the machine setup file, saved once per PC or server. Copy it from <a href="#settings/setup">Settings › Set up another machine</a>. On Android, the app is given the address and enroll secret.</p>
      </section>
    </div>
    <section class="card">
      <div class="card-head"><h2>Paste this into the project</h2><button class="btn small" id="wiz-copy">Copy</button></div>
      <pre><code>${esc(code)}</code></pre>
      <p class="soft small" style="margin-top:12px">${STATE.features.length ? "" : '<span class="badge warn">Server update needed</span> '}These helpers are the Pocket Cloud v2 libraries in <span class="mono">clients/</span>; sync, updates and notifications need a v2 server. A project can still use a plain S3 client from <span class="mono">pocket.connect()</span>.</p>
    </section>
  </div>`);
  $("wiz-copy").onclick = () => copyText(code, "Code");
  $("wiz-app").onchange = (e) => { WIZ.app = e.target.value.trim(); route("connect", true); };
  $("view").querySelectorAll("[data-wiz]").forEach((b) => b.onclick = () => {
    const [group, key] = b.dataset.wiz.split(":");
    if (group === "kind") WIZ.kind = key; else WIZ.goals.has(key) ? WIZ.goals.delete(key) : WIZ.goals.add(key);
    route("connect", true);
  });
};

// ================================================================ FILES
const FILES = { app: null, space: "shared", path: "", tab: "browse", card: null, cardApp: null, cardSpace: "shared", cardPath: "" };
const filesTabs = () => `<div class="subtabs" role="tablist">${[["browse", "Files"], ["undo", "Deleted"], ["cards", "SD cards"]].map(([k, l]) =>
  `<a data-tab="${k}" role="tab" aria-selected="${FILES.tab === k}" class="${FILES.tab === k ? "on" : ""}">${l}</a>`).join("")}</div>`;
const filesHead = () => `<h1>Files</h1><p class="lede">Everything your apps keep on the phone, and which storage each file is on right now. Deleted and overwritten files can be brought back for a while.</p>` + filesTabs();
function bindFileTabs() { $("view").querySelectorAll("[data-tab]").forEach((t) => t.onclick = () => { FILES.tab = t.dataset.tab; route("files", true); }); }

RENDER.files = async (args, ticket) => {
  const arg = args[0];
  const list = await api("GET", "/api/apps");
  if (stale(ticket)) return;
  if (arg && list.apps.some((a) => a.id === arg)) { Object.assign(FILES, { app: arg, space: "shared", path: "", tab: "browse" }); setAddress("files", true); STATE.route = "files"; }
  if (!FILES.app || !list.apps.some((a) => a.id === FILES.app)) FILES.app = list.apps.find((a) => !a.is_family)?.id || list.apps[0]?.id || null;
  if (FILES.tab === "cards") return renderCardFiles(list, ticket);
  if (FILES.tab === "undo") return renderUndo(list, ticket);
  if (!FILES.app) { paint(filesHead() + `<section class="card"><p class="soft">No apps yet.</p></section>`); return bindFileTabs(); }
  const app = list.apps.find((a) => a.id === FILES.app);
  const spaces = [["shared", app.is_family ? "Family" : "Shared by its devices"], ...app.devices.map((d) => ["device:" + d.uid, d.name])];
  if (!spaces.some((x) => x[0] === FILES.space)) FILES.space = "shared";
  const q = new URLSearchParams({ app: FILES.app, space: FILES.space, path: FILES.path });
  const d = await api("GET", "/api/files?" + q);
  if (stale(ticket)) return;
  const parts = FILES.path.split("/").filter(Boolean);
  const dl = (key) => "/api/files/download?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key, token: TOKEN });
  const where = (x) => x.storages ? (x.storages.length > 1 ? "Internal + SD" : x.storages[0] === "sd" ? "SD card" : "Internal") : x.storage === "sd" ? "SD card" : "Internal";
  paint(filesHead() + `
  <div class="filters">
    <select id="f-app" aria-label="App">${list.apps.map((x) => `<option value="${x.id}"${x.id === FILES.app ? " selected" : ""}>${esc(x.name)}</option>`).join("")}</select>
    <div class="seg" role="group" aria-label="Space">${spaces.map(([v, l]) => `<button data-space="${esc(v)}" aria-pressed="${v === FILES.space}">${esc(l)}</button>`).join("")}</div>
    <span class="grow"></span><button class="btn" id="f-dir">New folder</button><button class="btn primary" id="f-up">Upload</button>
  </div>
  <div class="crumbs"><a data-path="">${esc(spaceLabel(FILES.space === "shared" ? "shared" : FILES.space))}</a>
    ${parts.map((p, i) => `<span>/</span><a data-path="${esc(parts.slice(0, i + 1).join("/"))}">${esc(p)}</a>`).join("")}</div>
  <section class="card">
    <div class="list">${d.items.length ? d.items.map((x) => x.type === "dir"
      ? `<div class="file dir" data-dir="${esc(x.name)}" tabindex="0" role="link"><span class="mono name">${esc(x.name)}/</span><span class="soft num size">${fb(x.size)}</span><span class="soft when">${n(x.files)} files</span><span class="soft where">${where(x)}</span><span></span></div>`
      : `<div class="file"><span class="mono name" title="${esc(x.key)}">${esc(x.name)}</span><span class="soft num size">${fb(x.size)}</span><span class="soft when">${dt(x.modified)}</span><span class="where"><span class="badge${x.storage === "sd" ? " warn" : " use"}">${where(x)}</span></span>
         <span class="actions" style="gap:6px"><a class="btn small" href="${dl(x.key)}" download>Download</a><button class="btn small danger" data-del="${esc(x.key)}">Delete</button></span></div>`).join("")
      : `<p class="soft">This folder is empty.</p>`}</div>
  </section>`);
  bindFileTabs();
  const v = $("view");
  $("f-app").onchange = (e) => { Object.assign(FILES, { app: e.target.value, space: "shared", path: "" }); route("files", true); };
  v.querySelectorAll("[data-space]").forEach((b) => b.onclick = () => { Object.assign(FILES, { space: b.dataset.space, path: "" }); route("files", true); });
  v.querySelectorAll("[data-path]").forEach((a) => a.onclick = () => { FILES.path = a.dataset.path; route("files", true); });
  v.querySelectorAll("[data-dir]").forEach((r) => { const open = () => { FILES.path = [...parts, r.dataset.dir].join("/"); route("files", true); };
    r.onclick = open; r.onkeydown = (e) => { if (e.key === "Enter") open(); }; });
  v.querySelectorAll("[data-del]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Delete file", `Delete <b>${esc(b.dataset.del)}</b>? It stays in the undo for a while.`, "Delete", true))
      if (await act(() => api("DELETE", "/api/files?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key: b.dataset.del })), "File deleted.")) route("files", true);
  });
  $("f-dir").onclick = async () => {
    const name = await promptBox("New folder", "Folder name", "", "Create");
    if (name && await act(() => api("POST", "/api/files/folder", { app: FILES.app, space: FILES.space, path: [...parts, name.trim()].join("/") }), "Folder created.")) route("files", true);
  };
  $("f-up").onclick = () => { const pick = $("file-pick"); pick.value = ""; pick.onchange = async () => {
    const f = pick.files[0]; if (!f) return;
    const key = [...parts, f.name].join("/");
    toast(`Uploading ${f.name}…`);
    if (await act(() => api("PUT", "/api/files/upload?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key }), undefined, f), `Uploaded ${f.name}.`)) route("files", true);
  }; pick.click(); };
};

async function renderUndo(list, ticket) {
  const u = await api("GET", "/api/undo");
  if (stale(ticket)) return;
  const names = Object.fromEntries(list.apps.map((a) => [a.id, a.name]));
  paint(filesHead() + `<p class="soft" style="margin-bottom:12px">Deleted and overwritten files are kept for ${u.undo_days} days.</p>
    <section class="card"><div class="tw"><table>
    <thead><tr><th>File</th><th>App</th><th>Space</th><th class="num">Size</th><th>Deleted</th><th>Why</th><th>By</th><th class="num">Kept for</th><th></th></tr></thead>
    <tbody>${u.items.length ? u.items.map((e, i) => `<tr><td class="mono">${esc(e.key)}</td><td>${esc(names[e.app] || e.app)}</td><td class="soft">${e.space === "shared" ? "shared" : "device"}</td>
      <td class="num">${fb(e.size)}</td><td class="soft">${dt(new Date(e.time).getTime() / 1000)}</td><td class="soft">${esc(e.reason)}</td><td class="soft">${esc(e.device_name || "")}</td>
      <td class="num ${e.days_left <= 3 ? "warn" : "soft"}">${e.days_left} days</td><td class="acts"><button class="btn small" data-restore="${i}">Restore</button></td></tr>`).join("")
      : `<tr><td colspan="9" class="soft">Nothing in the undo.</td></tr>`}</tbody></table></div></section>`);
  bindFileTabs();
  $("view").querySelectorAll("[data-restore]").forEach((b) => b.onclick = async () => {
    const e = u.items[+b.dataset.restore];
    if (await act(() => api("POST", "/api/undo/restore", { storage: e.storage, path: e.path }), `Restored ${e.key}.`)) route("files", true);
  });
}

// Files › SD cards: what is on shelved cards (from the list kept when each was shelved), restoring single files
// while that card is in the phone, and files of deleted apps or removed devices put aside on the card in use.
async function renderCardFiles(list, ticket) {
  const [cd, un] = await Promise.all([api("GET", "/api/cards"), api("GET", "/api/cards/unassigned")]);
  if (stale(ticket)) return;
  const names = Object.fromEntries(list.apps.map((a) => [a.id, a.name]));
  const cards = cd.shelf;
  if (!cards.some((c) => c.id === FILES.card)) Object.assign(FILES, { card: cards[0]?.id || null, cardApp: null, cardPath: "" });
  let browse = `<section class="card"><p class="soft">No cards on the shelf yet. A card goes on the shelf when you swap it out (<a href="#phone">Phone › SD cards</a>).</p></section>`;
  let d = null, parts = [], inserted = false;
  if (FILES.card) {
    const q = new URLSearchParams({ app: FILES.cardApp || "", space: FILES.cardSpace, path: FILES.cardPath });
    d = await api("GET", `/api/cards/${FILES.card}/files?` + q);
    if (stale(ticket)) return;
    const card = cards.find((c) => c.id === FILES.card);
    inserted = cd.slot && cd.slot.card && cd.slot.card.id === FILES.card && ["archive", "shelved_here"].includes(cd.slot.state);
    const pick = `<div class="filters"><select id="fc-card" aria-label="Card">${cards.map((c) => `<option value="${c.id}"${c.id === FILES.card ? " selected" : ""}>${esc(c.name)} · shelved ${dt(c.shelved)}</option>`).join("")}</select>
      <span class="badge ${inserted ? "ok" : ""}">${inserted ? "In the phone: files can be restored" : "On the shelf: put it in the phone to restore files"}</span></div>`;
    if (!FILES.cardApp) {
      browse = pick + `<section class="card"><div class="tw"><table><thead><tr><th>App</th><th>Space</th><th class="num">Files</th><th class="num">Size</th></tr></thead>
        <tbody>${d.spaces.length ? d.spaces.map((x, i) => `<tr class="click" data-sp="${i}"><td>${esc(x.name)}</td><td class="soft">${esc(spaceLabel(x.space))}</td><td class="num">${n(x.files)}</td><td class="num">${fb(x.size)}</td></tr>`).join("")
          : `<tr><td colspan="4" class="soft">No app files on ${esc(card.name)}.</td></tr>`}</tbody></table></div></section>`;
    } else {
      parts = FILES.cardPath.split("/").filter(Boolean);
      browse = pick + `<div class="crumbs"><a data-cpath="top">${esc(card.name)}</a><span>/</span><a data-cpath="">${esc(names[FILES.cardApp] || FILES.cardApp)} · ${esc(spaceLabel(FILES.cardSpace))}</a>
        ${parts.map((p, i) => `<span>/</span><a data-cpath="${esc(parts.slice(0, i + 1).join("/"))}">${esc(p)}</a>`).join("")}</div>
        <section class="card"><div class="tw"><table><thead><tr><th>Name</th><th class="num">Size</th><th>Modified</th><th>Type</th><th></th></tr></thead>
        <tbody>${d.items.length ? d.items.map((x) => x.type === "dir"
          ? `<tr class="click" data-cdir="${esc(x.name)}"><td class="mono">${esc(x.name)}/</td><td class="num soft">${fb(x.size)}</td><td class="soft">${n(x.files)} files</td><td></td><td></td></tr>`
          : `<tr><td class="mono">${esc(x.name)}</td><td class="num">${fb(x.size)}</td><td class="soft">${dt(x.modified)}</td><td class="soft">${KIND_LABEL[x.kind] || ""}</td>
             <td class="acts">${inserted ? `<button class="btn small" data-crestore="${esc(x.key)}">Restore</button>` : ""}</td></tr>`).join("")
          : `<tr><td colspan="5" class="soft">Empty.</td></tr>`}</tbody></table></div></section>`;
    }
  }
  paint(filesHead() + browse + unassignedPanel(un, list));
  bindFileTabs();
  const v = $("view");
  if ($("fc-card")) $("fc-card").onchange = (e) => { Object.assign(FILES, { card: e.target.value, cardApp: null, cardPath: "" }); route("files", true); };
  v.querySelectorAll("[data-sp]").forEach((r) => r.onclick = () => { const x = d.spaces[+r.dataset.sp]; Object.assign(FILES, { cardApp: x.app, cardSpace: x.space, cardPath: "" }); route("files", true); });
  v.querySelectorAll("[data-cpath]").forEach((a) => a.onclick = () => { if (a.dataset.cpath === "top") FILES.cardApp = null; FILES.cardPath = a.dataset.cpath === "top" ? "" : a.dataset.cpath; route("files", true); });
  v.querySelectorAll("[data-cdir]").forEach((r) => r.onclick = () => { FILES.cardPath = [...parts, r.dataset.cdir].join("/"); route("files", true); });
  v.querySelectorAll("[data-crestore]").forEach((b) => b.onclick = async () => {
    const r = await act(() => api("POST", `/api/cards/${FILES.card}/restore`, { app: FILES.cardApp, space: FILES.cardSpace, key: b.dataset.crestore }));
    if (r) toast(r.restored ? `Restored ${b.dataset.crestore} to ${names[FILES.cardApp] || FILES.cardApp}.` : r.reason);
  });
  v.querySelectorAll("[data-ua-give]").forEach((b) => b.onclick = async () => {
    const g = un.groups[+b.dataset.uaGive];
    openModal(`<h2>Give files to an app</h2><div class="body"><p>${n(g.files)} files of <b>${esc(g.app)}</b> (${esc(spaceLabel(g.space))}) go into the chosen app's shared space, under <span class="mono">unassigned/${esc(g.app)}/</span>.</p>
      <label class="field">App<select id="ua-to">${list.apps.map((a) => `<option value="${a.id}">${esc(a.name)}</option>`).join("")}</select></label></div>${foot("Give")}`);
    $("m-no").onclick = closeModal;
    $("m-yes").onclick = async () => {
      const to = $("ua-to").value; closeModal();
      if (await act(() => api("POST", "/api/cards/unassigned/assign", { app: g.app, space: g.space, to }), (r) => `${n(r.files)} files given to ${names[to] || to}.`)) route("files", true);
    };
  });
  v.querySelectorAll("[data-ua-del]").forEach((b) => b.onclick = async () => {
    const g = un.groups[+b.dataset.uaDel];
    if (await confirmBox("Delete unassigned files", `Delete ${n(g.files)} files of <b>${esc(g.app)}</b>? They go into the card's undo first.`, "Delete", true))
      if (await act(() => api("POST", "/api/cards/unassigned/delete", { app: g.app, space: g.space }), "Files deleted.")) route("files", true);
  });
}
function unassignedPanel(un, list) {
  if (!un.groups.length) return "";
  return `<h2 class="mt" style="margin-top:26px">Unassigned files on the card in use</h2><p class="soft" style="margin-bottom:12px">From a card put back to work: their app was deleted or their device removed while it was on the shelf. Apps don't see them.</p>
    <section class="card"><div class="tw"><table><thead><tr><th>Former app</th><th>Space</th><th class="num">Files</th><th class="num">Size</th><th></th></tr></thead>
    <tbody>${un.groups.map((g, i) => `<tr><td class="mono">${esc(g.app)}</td><td class="soft">${esc(spaceLabel(g.space))}</td><td class="num">${n(g.files)}</td><td class="num">${fb(g.size)}</td>
      <td class="acts"><button class="btn small" data-ua-give="${i}"${list.apps.length ? "" : " disabled"}>Give to an app</button><button class="btn small danger" data-ua-del="${i}">Delete</button></td></tr>`).join("")}</tbody></table></div></section>`;
}

// ================================================================ PHONE
// ---- SD cards: one in the phone at a time, full ones on the shelf (server: cards.py)
const cardSpeed = (c, slow) => c.write_mbs == null ? "speed not measured"
  : `<span class="${c.write_mbs < slow ? "warn" : ""}">write ${c.write_mbs} MB/s</span> · read ${c.read_mbs} MB/s${c.write_mbs < slow ? ` <span class="warn">(slow: under ${slow} MB/s)</span>` : ""}`;
function cardsCard(cd) {
  if (cd.mode !== "cards") return "";
  const A = cd.active, S = cd.slot, J = cd.job;
  const rows = [];
  if (J && J.running) {
    const pctDone = J.total ? Math.round(J.done / J.total * 100) : 0;
    rows.push(`<div><h3>${esc({ swap: "Swapping the SD card", use: "Setting up the new card", put_back: "Putting the card back to work" }[J.kind] || "Working")}</h3>
      <div class="progress"><i style="width:${J.total ? pctDone : 5}%"></i></div>${s13(`${esc(J.step)}${J.total ? ` · ${n(J.done)} of ${n(J.total)}` : ""}`)}</div>`);
  } else if (J && J.error && Date.now() / 1000 - J.finished < 3600) {
    rows.push(`<p class="note bad" style="margin:0">The last SD card job failed: ${esc(J.error)}</p>`);
  }
  if (A && A.present) {
    rows.push(`<div class="cardrow"><div class="grow"><h3>${esc(A.name)} <span class="badge use">In use</span></h3>
        ${s13(`${esc(A.volume || "")} · ${esc(A.fs || "")} · ${fb(A.total)}, ${fb(A.free)} free · ${cardSpeed(A, cd.slow_mbs)}`)}</div>
      <div class="actions"><button class="btn small" data-card-rename="${A.id}" data-name="${esc(A.name)}">Rename</button>
      <button class="btn primary" id="c-swap"${J && J.running ? " disabled" : ""}>Swap the SD card</button></div></div>`);
  } else if (A) {
    rows.push(`<p class="note bad" style="margin:0">${esc(A.name)} was removed without a swap. Its ${n(A.files)} files (${gbv(A.bytes)}, as of the last nightly copy) are not available until it is back. Everything else works on internal storage.</p>`);
  } else if (!S) {
    rows.push(`<p class="soft">No SD card in the phone: everything is on internal storage. Put a card in to add room and a nightly copy.</p>`);
  }
  if (S && S.state === "new") {
    rows.push(`<div class="cardrow"><div class="grow"><h3>A new SD card is in the phone</h3>${s13(`${esc(S.volume)} · ${esc(S.fs || "")} · ${fb(S.total)}, ${fb(S.free)} free`)}
      <div class="soft small">Setting it up checks its speed, sets the SD pool from its size and moves older files onto it.</div></div>
      <button class="btn primary" id="c-use"${J && J.running ? " disabled" : ""}>Use this card</button></div>`);
  } else if (S && S.state === "needs_setup") {
    rows.push(`<p class="note" style="margin:0">A card is in the phone (${esc(S.volume)}), but Termux has no folder on it yet. On the phone running Pocket Cloud, open Termux, type <span class="mono">termux-setup-storage</span>, press Enter and answer <b>y</b>. The card then shows here as a new card within a few seconds.</p>`);
  } else if (S && (S.state === "archive" || S.state === "shelved_here")) {
    const c = S.card;
    rows.push(`<div class="cardrow"><div class="grow">${S.state === "shelved_here"
        ? `<h3>${esc(c.name)} is ready to remove</h3>${s13("Unmount it in Android's settings (Settings › Device care › Storage › SD card › Unmount), then take it out. Then put in the next card: a new one or an archive card.")}`
        : `<h3>${esc(c.name)}, an archive card, is in the phone</h3>${s13(`Shelved ${dt(c.shelved)} · ${n(c.files)} files, ${gbv(c.bytes)}. Browse it and restore single files, or put it back to work as the card in use.`)}`}</div>
      <div class="actions"><button class="btn" data-card-browse="${c.id}">Browse</button>
      <button class="btn${S.state === "archive" ? " primary" : ""}" data-card-back="${c.id}"${J && J.running ? " disabled" : ""}>Put back to work</button></div></div>`);
  }
  const shelf = cd.shelf.length ? `<h3 style="margin:18px 0 6px">On the shelf</h3><div class="tw"><table>
      <thead><tr><th>Card</th><th>Shelved</th><th class="num">Files</th><th class="num">Size</th><th>Note</th><th></th></tr></thead>
      <tbody>${cd.shelf.map((c) => `<tr><td>${esc(c.name)} <span class="faint mono">${esc(c.id)}</span></td><td class="soft">${dt(c.shelved)}</td><td class="num">${n(c.files)}</td>
        <td class="num">${gbv(c.bytes)}</td><td class="soft">${esc(c.note || "")}</td>
        <td class="acts"><button class="btn small" data-card-browse="${c.id}">Browse</button><button class="btn small" data-card-rename="${c.id}" data-name="${esc(c.name)}">Rename</button><button class="btn small danger" data-card-forget="${c.id}" data-name="${esc(c.name)}">Forget</button></td></tr>`).join("")}</tbody></table></div>` : "";
  return `<section class="card span2"><div class="card-head"><h2>SD cards</h2><span class="soft small">one in the phone at a time; full cards go on the shelf</span></div>
    <div class="list">${rows.join("")}</div>${shelf}</section>`;
}
function bindCards(cd, again) {
  const v = $("view");
  const jobAction = (r) => { if (r) again(); };
  if ($("c-swap")) $("c-swap").onclick = () => swapDialog(again);
  if ($("c-use")) $("c-use").onclick = async () => {
    const name = await promptBox("Use this card", "Name for the card", `Card ${cd.shelf.length + (cd.active ? 2 : 1)}`, "Use this card");
    if (name !== null) jobAction(await act(() => api("POST", "/api/cards/use", { name }), "Setting up the card…"));
  };
  v.querySelectorAll("[data-card-back]").forEach((b) => b.onclick = () => putBackDialog(b.dataset.cardBack, again));
  v.querySelectorAll("[data-card-browse]").forEach((b) => b.onclick = () => { Object.assign(FILES, { tab: "cards", card: b.dataset.cardBrowse, cardApp: null, cardPath: "" }); go("files"); });
  v.querySelectorAll("[data-card-forget]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Forget card", `Remove <b>${esc(b.dataset.name)}</b> from the shelf? Use this when the card was formatted, lost or thrown away. Nothing on any card changes; if it turns up again, it is recognised from the marker it carries.`, "Forget", true))
      if (await act(() => api("DELETE", `/api/cards/${b.dataset.cardForget}`), "Card forgotten.")) again();
  });
  v.querySelectorAll("[data-card-rename]").forEach((b) => b.onclick = async () => {
    const name = await promptBox("Rename card", "Card name", b.dataset.name);
    if (name && await act(() => api("PATCH", `/api/cards/${b.dataset.cardRename}`, { name }), "Card renamed.")) again();
  });
  // follow a running job, unless a dialog is open
  clearTimeout(bindCards.timer);
  if (cd.job && cd.job.running) bindCards.timer = setTimeout(() => { if (STATE.page === "phone" && $("modal").hidden) again(); }, 1500);
}
async function swapDialog(again) {
  const plan = await act(() => api("GET", "/api/cards/swap"));
  if (!plan) return;
  const G = plan.groups, M = plan.mirror;
  const totals = () => {
    const c = G.filter((g) => g.carry), st = G.filter((g) => !g.carry);
    const need = c.reduce((a, g) => a + g.bytes, 0);
    $("sw-tot").innerHTML = `Keep using (copied to internal storage): <b>${n(c.reduce((a, g) => a + g.files, 0))} files, ${fb(need)}</b> ·
      Stay on the card: <b>${n(st.reduce((a, g) => a + g.files, 0))} files, ${fb(st.reduce((a, g) => a + g.bytes, 0))}</b><br>
      ${need > plan.room ? `<span class="bad">Internal storage has room for ${fb(plan.room)} only. Leave more on the card, or make room first.</span>` : `<span class="soft">Internal storage has room for ${fb(plan.room)}.</span>`}`;
    $("sw-go").disabled = need > plan.room || !(M.ok || ($("sw-override") && $("sw-override").checked));
  };
  const mirrorLine = M.ok ? `<span class="soft">A mirror reported a copy ${ago(M.reported)}, after the card's last change.</span>`
    : `<span class="bad">${M.reported ? `The last mirror report (${ago(M.reported)}) is older than the card's last change${M.last_write ? ` (${ago(M.last_write)})` : ""}` : "No mirror has ever reported a copy"}: files that stay on the card would have no second copy.</span>
       <label class="check"><input type="checkbox" id="sw-override"> Swap anyway</label>`;
  openModal(`<h2>Swap the SD card</h2><div class="body">
    <p class="soft">Ticked rows are files apps keep using: they are copied to internal storage, checked byte for byte, and go onto the next card.
      The rest stays on this card, which goes on the shelf; its files are hidden from apps until it is back. Your choices are remembered for the next swap.
      Files that stay on the card have only that one copy.</p>
    ${G.length ? `<div class="tw"><table><thead><tr><th>Keep using</th><th>App</th><th>Space</th><th>Folder</th><th>Type</th><th class="num">Files</th><th class="num">Size</th></tr></thead>
      <tbody>${G.map((g, i) => `<tr><td><input type="checkbox" data-g="${i}"${g.carry ? " checked" : ""} aria-label="Keep using"></td><td>${esc(g.app_name)}</td><td class="soft">${esc(spaceLabel(g.space))}</td>
        <td class="mono">${esc(g.folder || "(top level)")}</td><td>${esc(g.type || "(none)")} <span class="faint">${KIND_LABEL[g.kind]}${g.carry !== g.default ? " · your choice" : ""}</span></td>
        <td class="num">${n(g.files)}</td><td class="num">${fb(g.bytes)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="soft">The card holds no app files.</p>`}
    <p id="sw-tot" style="margin-top:14px"></p><p id="sw-mirror" style="margin-top:10px">${mirrorLine}</p></div>
    ${foot("Start the swap", false, "sw-go")}`, true);
  $("modal-box").querySelectorAll("[data-g]").forEach((cb) => cb.onchange = () => { G[+cb.dataset.g].carry = cb.checked; totals(); });
  if ($("sw-override")) $("sw-override").onchange = totals;
  totals();
  $("m-no").onclick = closeModal;
  $("sw-go").onclick = async () => {
    closeModal();
    if (await act(() => api("POST", "/api/cards/swap", { groups: G, override: !M.ok }), "Swap started.")) again();
  };
}
async function putBackDialog(cardId, again) {
  const c = await act(() => api("GET", `/api/cards/${cardId}/compare`));
  if (!c) return;
  const list = (items, cols, max = 50) => `<div class="tw"><table><thead><tr>${cols.map((x) => `<th>${x[0]}</th>`).join("")}</tr></thead>
    <tbody>${items.slice(0, max).map((f) => `<tr>${cols.map((x) => `<td class="${x[2] || ""}">${x[1](f)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>
    ${items.length > max ? `<p class="soft">… and ${n(items.length - max)} more.</p>` : ""}`;
  openModal(`<h2>Put ${esc(c.card.name)} back to work</h2><div class="body">
    <p><b>${n(c.rejoin_files)} files (${fb(c.rejoin_bytes)})</b> rejoin their apps.</p>
    ${c.conflict_count ? `<h3>Same file on the card and in use: ${n(c.conflict_count)}</h3><p class="soft">The newer one stays; the other goes to the undo.</p>
      ${list(c.conflicts, [["File", (f) => `<span class="mono">${esc(f.app)}/${esc(f.key)}</span>`], ["On the card", (f) => dt(f.card_modified), "soft"],
        ["In use", (f) => dt(f.current_modified), "soft"], ["Kept", (f) => f.keep === "card" ? "the card's" : "the one in use"]])}` : ""}
    ${c.unassigned_count ? `<h3>No app or device for them any more: ${n(c.unassigned_count)} files, ${fb(c.unassigned_bytes)}</h3>
      <p class="soft">They stay on the card, hidden from apps, under Files › SD cards › Unassigned, where you can give them to an app or delete them.</p>
      ${list(c.unassigned, [["File", (f) => `<span class="mono">${esc(f.app)}/${esc(f.key)}</span>`], ["Why", (f) => esc(f.why), "soft"]])}` : ""}
    ${c.overruns.length ? `<h3>Apps that would go over their space</h3>${c.overruns.map((o) => `<label class="check"><input type="checkbox" data-raise="${esc(o.app)}" checked>
      <span>Raise <b>${esc(o.name)}</b> from ${gbv(o.alloc)} to ${gbv(o.raise_to)} (it would use ${fb(o.would_use)})</span></label>`).join("")}` : ""}
    <p class="soft" style="margin-top:12px">The SD pool will be ${c.sd_pool_gb} GB, from this card's size.</p></div>
    ${foot("Put back to work", false, "pb-go")}`, true);
  $("m-no").onclick = closeModal;
  $("pb-go").onclick = async () => {
    const raise = [...$("modal-box").querySelectorAll("[data-raise]")].filter((x) => x.checked).map((x) => x.dataset.raise);
    closeModal();
    if (await act(() => api("POST", `/api/cards/${cardId}/activate`, { raise }), "Putting the card back to work…")) again();
  };
}

const RES = { ok: ["ok", "OK"], warn: ["warn", "Warning"], fail: ["bad", "Failed"] };
const resDot = (r) => r === "ok" ? "" : r === "warn" ? "warn" : r === "fail" ? "bad" : "off";
const took = (j) => j.finished && j.started ? (j.finished - j.started < 60 ? `${(j.finished - j.started).toFixed(0)} s` : dur(j.finished - j.started)) : "";
function runJobButtons(again) {
  const here = STATE.route;
  $("view").querySelectorAll("[data-run]").forEach((b) => b.onclick = async () => {
    if (await act(() => api("POST", `/api/jobs/${b.dataset.run}/run`), "Started.")) { again(); setTimeout(() => STATE.route === here && $("modal").hidden && again(), 2500); }
  });
}
const logLine = (r, names) => `${hms(r.ts).padEnd(9)} ${String(names[r.app] || r.app || "").slice(0, 16).padEnd(16)} ${String(r.op || "").padEnd(14)} ${String(r.key || "").slice(0, 48).padEnd(48)} ${r.status}`;

RENDER.phone = async (args, ticket) => {
  const sub = args[0];
  if (sub === "storage") return renderStorage(ticket);
  if (sub === "jobs") return renderJobs(ticket);
  if (sub === "alerts") return renderAlerts(ticket);
  if (sub === "logs") return renderLogs(args[1] || "", ticket);
  const [o, st, cd, jobs, logs] = await Promise.all([api("GET", "/api/overview"), api("GET", "/api/storage"), api("GET", "/api/cards"), api("GET", "/api/jobs"), api("GET", "/api/logs?limit=8")]);
  if (stale(ticket)) return;
  noteOverview(o);
  const P = st.pool, ph = o.phone;
  const names = Object.fromEntries(o.allocation.map((a) => [a.id, a.name]));
  paint(`
  <h1>Phone</h1>
  <p class="lede">The phone itself: its storage, its SD cards, the services it runs and the housekeeping it does each night.${ph && ph.battery != null ? ` Battery ${ph.battery}%${ph.plugged ? ", " + esc(ph.plugged) : ""}${ph.temp_c != null ? ", " + ph.temp_c.toFixed(1) + " °C" : ""}.` : ""}</p>
  <div class="grid g2" style="align-items:start">
    <section class="card"><div class="card-head"><h2>Storage</h2><a href="#phone/storage">Details</a></div>
      <div class="stack" style="gap:16px">
        <div class="stack" style="gap:6px"><div class="row between"><h3>Internal storage</h3><span class="num">${fb(P.int_used)} of ${gbv(P.int_limit)}</span></div>${meter(P.int_used, P.int_limit, 80)}${s13(`Recent files. ${gbv(P.reserve)} is always left free for Android.${P.internal_full ? ' <span class="warn">Full: new files go to the SD card.</span>' : ""}`)}</div>
        ${st.sd.present ? `<div class="stack" style="gap:6px"><div class="row between"><h3>SD card</h3><span class="num">${fb(P.sd_used)} of ${gbv(P.sd_limit)}</span></div>${meter(P.sd_used, P.sd_limit, 80)}${s13("Older files, the nightly copy and the undo.")}</div>`
          : `<p class="soft">No SD card in use.</p>`}
        <div class="row between"><span class="soft">Promised to apps</span><span class="num">${gbv(P.promised)} of ${gbv(P.total)}</span></div>
      </div>
    </section>
    <section class="card"><h2>Services</h2>
      <div class="list">${o.services.map((v) => `<div class="row"><span class="dot ${!v.ok ? "bad" : v.warn ? "warn" : ""}"></span><div class="grow"><span>${esc(v.name)}</span><div class="faint small">${esc(v.detail)}</div></div><span class="${!v.ok ? "bad" : v.warn ? "warn" : "soft"}">${esc(v.state)}</span></div>`).join("")}</div>
      <div class="actions mt"><button class="btn" id="srv-restart">Restart the storage server</button></div>
    </section>
    ${cardsCard(cd)}
    <section class="card"><div class="card-head"><h2>Jobs</h2><a href="#phone/jobs">History</a></div>
      <div class="list">${jobs.jobs.map((j) => `<div class="row"><span class="dot ${j.running ? "info" : j.last ? resDot(j.last.result) : "off"}"></span><div class="grow"><h3>${esc(j.name)}</h3>
        ${s13(`${esc(j.schedule)} · ${j.running ? "running…" : j.last ? `last ${dt(j.last.started)}${j.last.detail ? " · " + esc(j.last.detail) : ""}` : "never run"}`)}</div>
        <button class="btn small" data-run="${j.key}"${j.running ? " disabled" : ""}>Run now</button></div>`).join("")}</div>
    </section>
    <section class="card"><div class="card-head"><h2>Alerts</h2><a href="#phone/alerts">Rules and history</a></div>
      <div class="list">${o.alerts.length ? o.alerts.map(alertRow).join("") : `<div class="row"><span class="dot"></span><span class="soft">No active alerts.</span></div>`}</div>
    </section>
    <section class="card span2"><div class="card-head"><h2>Log</h2><a href="#phone/logs">Open the full log</a></div>
      <pre>${logs.items.length ? esc(logs.items.map((r) => logLine(r, names)).join("\n")) : "No requests yet."}</pre>
    </section>
  </div>`);
  const again = () => route(STATE.route, true);
  bindCards(cd, again);
  runJobButtons(again);
  $("srv-restart").onclick = async () => { if (await confirmBox("Restart the storage server", "Running requests finish first; new ones wait a moment.", "Restart")) act(() => api("POST", "/api/server/restart"), "Storage server restarted."); };
};

async function renderStorage(ticket) {
  const d = await api("GET", "/api/storage");
  if (stale(ticket)) return;
  const { internal: I, sd: S, pool: P } = d;
  const pct = (x, cap) => (x / cap * 100).toFixed(2);
  const left = d.growth_per_day > 0 ? (P.sd_limit - P.sd_used) / d.growth_per_day : null;
  const c = { other: css("--faint"), data: css("--brand"), sys: css("--soft"), copy: css("--warn"), undo: css("--bad"), free: css("--sunk"), lim: css("--ink") };
  const prow = (label, limit, used) => `<tr><td>${label}</td><td class="num">${gbv(limit)}</td><td class="num">${fb(used)}</td><td style="min-width:140px">${meter(used, limit, 80)}</td><td class="num">${fb(Math.max(0, limit - used))}</td></tr>`;
  paint(`
  ${crumb([["phone", "Phone"], [null, "Storage"]])}
  <h1>Storage</h1><p class="lede">New files land on internal storage. Files unchanged for an app's move age go to the SD card each night.</p>
  <div class="grid g2" style="align-items:start">
    <section class="card span2"><div class="card-head"><h2>Storage pool</h2><a href="#settings/pool">Pool settings</a></div><div class="tw"><table>
      <thead><tr><th>Storage</th><th class="num">Pool limit</th><th class="num">Used by Pocket</th><th>Pool use</th><th class="num">Room left</th></tr></thead>
      <tbody>${prow("Internal storage", P.int_limit, P.int_used)}
        ${S.present ? prow("SD card", P.sd_limit, P.sd_used) : `<tr><td>SD card</td><td colspan="4" class="soft">no card in use</td></tr>`}
        ${prow("<b>Total</b>", P.total, P.used)}</tbody></table></div>
      <p class="soft small" style="margin-top:14px">${gbv(P.promised)} of the pool is promised to apps and ${gbv(P.unpromised)} is unpromised. ${gbv(P.reserve)} of internal storage is always left free for Android.${P.internal_full ? ' <span class="warn">Internal pool is full: new files go to the SD card.</span>' : ""}</p>
    </section>
    <section class="card"><div class="card-head"><h2>Internal storage</h2><span class="soft num">${fb(I.total)}</span></div>
      <div class="stackw"><div class="stackbar"><i style="width:${pct(I.other, I.total)}%;background:${c.other}"></i><i style="width:${pct(I.data, I.total)}%;background:${c.data}"></i><i style="width:${pct(I.system, I.total)}%;background:${c.sys}"></i></div><b class="lim" style="left:${Math.min(100, (I.other + P.int_limit) / I.total * 100)}%"></b></div>
      ${legend(c.other, "Android and other apps", fb(I.other))}${legend(c.data, "Pocket data (recent files)", fb(I.data))}${legend(c.sys, "Pocket logs, statistics and undo", fb(I.system))}
      ${legend(c.free, "Free", fb(I.free))}${legend(c.lim, "Pocket pool limit (the line)", gbv(P.int_limit))}
      <p class="soft small" style="margin-top:10px">Early move starts when the internal pool is ${d.early_move_pct}% full.</p>
    </section>
    ${!S.present ? `<section class="card"><h2>SD card</h2><p class="soft">No SD card in use. See <a href="#phone">Phone › SD cards</a>.</p></section>` : `
    <section class="card"><div class="card-head"><h2>SD card</h2><span class="soft num">${fb(S.total)}</span></div>
      <div class="stackw"><div class="stackbar"><i style="width:${pct(S.other, S.total)}%;background:${c.other}"></i><i style="width:${pct(S.data, S.total)}%;background:${c.data}"></i><i style="width:${pct(S.copy, S.total)}%;background:${c.copy}"></i><i style="width:${pct(S.undo, S.total)}%;background:${c.undo}"></i></div><b class="lim" style="left:${Math.min(100, (S.other + P.sd_limit) / S.total * 100)}%"></b></div>
      ${legend(c.other, "Other files on the card", fb(S.other))}${legend(c.data, "Pocket data (moved files)", fb(S.data))}${legend(c.copy, "Nightly copy of recent files", fb(S.copy))}
      ${legend(c.undo, "Undo", fb(S.undo))}${legend(c.free, "Free", fb(S.free))}${legend(c.lim, "Pocket pool limit (the line)", gbv(P.sd_limit))}
      <p class="soft small" style="margin-top:10px">${left ? `At the recent growth (${fb(d.growth_per_day)} a day), Pocket's SD card pool fills in about ${left > 730 ? (left / 365).toFixed(0) + " years" : Math.round(left) + " days"}.` : "Not enough history yet to estimate when the SD card pool fills."}</p>
    </section>`}
    <section class="card span2"><div class="card-head"><h2>Pocket data over time</h2><span class="soft small">last 30 days</span></div>${d.growth.length < 2
      ? `<p class="soft">${d.growth.length ? `One day of history so far: ${fb(d.growth[0].sd)} on the SD card, ${fb(d.growth[0].int)} on internal storage. The chart starts after the second day.` : "No history yet."}</p>`
      : `<div class="chart" id="ch-growth"></div>`}</section>
    <section class="card span2"><h2>Where each app's data is</h2><div class="tw"><table>
      <thead><tr><th>App</th><th class="num">Internal</th><th class="num">SD card</th><th class="num">Total</th><th class="num">Files</th><th class="num">Move after</th><th>On internal</th></tr></thead>
      <tbody>${d.apps.length ? d.apps.map((a) => `<tr class="click" data-go="app/${a.id}"><td>${esc(a.name)}</td><td class="num">${fb(a.int)}</td><td class="num">${fb(a.sd)}</td><td class="num">${fb(a.int + a.sd)}</td><td class="num">${n(a.files)}</td><td class="num">${a.move_days} days</td>
        <td style="min-width:120px">${meter(a.int, a.int + a.sd || 1)}</td></tr>`).join("") : `<tr><td class="soft" colspan="7">No apps yet.</td></tr>`}</tbody>
    </table></div></section>
  </div>`);
  $("view").querySelectorAll("[data-go]").forEach((r) => r.onclick = () => go(r.dataset.go));
  if (d.growth.length >= 2) {
    const ts = d.growth.map((g) => new Date(g.day + "T12:00:00").getTime() / 1000);
    chart("growth", $("ch-growth"), [{ label: "Internal", fill: true }, { label: "SD card", fill: true }],
      [ts, d.growth.map((g) => g.int / GB), d.growth.map((g) => g.sd / GB)], { fmt: (v) => v.toFixed(2) + " GB", axisFmt: (v) => +v.toFixed(2) + " GB" });
  }
}

async function renderJobs(ticket) {
  const d = await api("GET", "/api/jobs");
  if (stale(ticket)) return;
  const res = (r) => { const [c, t] = RES[r] || ["soft", r || "running"]; return `<span class="${c}">${t}</span>`; };
  paint(`
  ${crumb([["phone", "Phone"], [null, "Jobs"]])}
  <h1>Jobs</h1><p class="lede">Scheduled work the phone does by itself. Times are set in <a href="#settings/jobs">Settings</a>.</p>
  <section class="card"><div class="tw"><table>
    <thead><tr><th>Job</th><th>Schedule</th><th>Last run</th><th>Result</th><th class="num">Took</th><th>Next run</th><th></th></tr></thead>
    <tbody>${d.jobs.map((j) => `<tr><td class="wrap" style="min-width:200px">${esc(j.name)}<div class="faint small">${esc(j.what)}</div></td><td class="soft">${esc(j.schedule)}</td>
      <td class="soft">${j.last ? dt(j.last.started) : "never"}</td>
      <td class="wrap" style="min-width:160px">${j.running ? '<span class="soft">running…</span>' : j.last ? `${res(j.last.result)} <span class="soft">· ${esc(j.last.detail || "")}</span>` : ""}</td>
      <td class="num soft">${j.last ? took(j.last) : ""}</td><td class="soft">${dt(j.next)}</td>
      <td class="acts"><button class="btn small" data-run="${j.key}"${j.running ? " disabled" : ""}>Run now</button></td></tr>`).join("")}</tbody>
  </table></div></section>
  <h2 style="margin-top:26px">History</h2>
  <section class="card"><div class="tw"><table>
    <thead><tr><th>Started</th><th>Job</th><th>Result</th><th class="num">Took</th></tr></thead>
    <tbody>${d.history.length ? d.history.map((j) => `<tr><td class="soft">${dt(j.started)}</td><td>${esc(j.job)}</td><td class="wrap">${res(j.result)} <span class="soft">· ${esc(j.detail || "")}</span></td><td class="num soft">${took(j)}</td></tr>`).join("")
      : `<tr><td colspan="4" class="soft">No jobs have run yet.</td></tr>`}</tbody>
  </table></div></section>`);
  runJobButtons(() => route(STATE.route, true));
}

async function renderAlerts(ticket) {
  const d = await api("GET", "/api/alerts");
  if (stale(ticket)) return;
  paint(`
  ${crumb([["phone", "Phone"], [null, "Alerts"]])}
  <h1>Alerts</h1><p class="lede">Alerts arrive as notifications on the phone (and in the Android app) and are listed here.</p>
  <div class="grid" style="gap:16px">
    <section class="card"><h2>Active</h2><div class="list">${d.active.length ? d.active.map(alertRow).join("") : `<div class="row"><span class="dot"></span><span class="soft">No active alerts.</span></div>`}</div></section>
    <section class="card"><h2>Rules</h2>
      <div class="list">${d.rules.map((r) => `<div class="row wrap"><div class="grow" style="min-width:200px"><h3>${esc(r.label)}</h3>${r.threshold ? "" : s13(esc(r.text))}</div>
        ${r.threshold ? `<div class="thr"><span>${r.threshold.key.includes("low") || r.threshold.key.includes("free") ? "below" : "above"}</span>${slider({ min: r.threshold.min, max: r.threshold.max, step: r.threshold.step, value: r.threshold.value, unit: r.threshold.unit, compact: true, key: r.threshold.key, label: r.label })}</div>` : ""}
        ${sw(`data-rule="${r.kind}"`, r.enabled, r.label)}</div>`).join("")}</div>
      <div class="form-foot"><button class="btn primary" id="al-save">Save rules</button></div>
    </section>
    <section class="card"><h2>History</h2><div class="tw"><table>
      <thead><tr><th></th><th>When</th><th>Alert</th><th>Status</th></tr></thead>
      <tbody>${d.history.length ? d.history.map((a) => `<tr><td style="width:24px"><span class="dot ${a.level === "bad" ? "bad" : a.level === "warn" ? "warn" : "info"}"></span></td><td class="soft">${dt(a.raised)}</td><td class="wrap">${esc(a.text)}</td><td class="${a.cleared ? "soft" : "warn"}">${a.cleared ? (a.cleared - a.raised < 1 ? "" : "cleared " + hm(a.cleared)) : "active"}</td></tr>`).join("")
        : `<tr><td colspan="4" class="soft">No alerts yet.</td></tr>`}</tbody>
    </table></div></section>
  </div>`);
  $("al-save").onclick = async () => {
    const enabled = {}, thresholds = {};
    $("view").querySelectorAll("[data-rule]").forEach((c) => (enabled[c.dataset.rule] = swOn(c)));
    $("view").querySelectorAll(".sl[data-key]").forEach((el) => (thresholds[el.dataset.key] = slVal(el)));
    if (await act(() => api("PATCH", "/api/alerts", { enabled, thresholds }), "Alert rules saved.")) route(STATE.route, true);
  };
}

const LOGF = { app: "", device: "", op: "", status: "", q: "", tab: "req" };
async function renderLogs(arg, ticket) {
  if (arg) { Object.assign(LOGF, { app: arg, tab: "req" }); setAddress("phone/logs", true); STATE.route = "phone/logs"; }
  const head = `${crumb([["phone", "Phone"], [null, "Log"]])}<h1>Log</h1><p class="lede">Every request to the server is recorded. Detailed logs are kept for the period set in <a href="#settings/logs">Settings</a>.</p>
    <div class="subtabs"><a data-tab="req" class="${LOGF.tab === "req" ? "on" : ""}">Requests</a><a data-tab="sys" class="${LOGF.tab === "sys" ? "on" : ""}">System</a></div>`;
  const tabs = () => $("view").querySelectorAll("[data-tab]").forEach((t) => t.onclick = () => { LOGF.tab = t.dataset.tab; route("phone/logs", true); });
  if (LOGF.tab === "sys") {
    const d = await api("GET", "/api/logs/system?lines=500");
    if (stale(ticket)) return;
    paint(head + `<pre class="log">${esc(d.lines.join("\n")) || "Nothing logged yet."}</pre>`);
    return tabs();
  }
  const q = new URLSearchParams(Object.fromEntries(Object.entries(LOGF).filter(([k, v]) => v && k !== "tab")));
  q.set("limit", "300");
  const [d, apps, devs] = await Promise.all([api("GET", "/api/logs?" + q), api("GET", "/api/apps"), api("GET", "/api/devices")]);
  if (stale(ticket)) return;
  const names = Object.fromEntries(apps.apps.map((a) => [a.id, a.name]));
  const sel = (k, label, opts) => `<select data-f="${k}" aria-label="${label}">${opts.map(([v, l]) => `<option value="${esc(v)}"${LOGF[k] === v ? " selected" : ""}>${esc(l)}</option>`).join("")}</select>`;
  paint(head + `
    <div class="filters">
      ${sel("app", "App", [["", "All apps"], ...apps.apps.map((a) => [a.id, a.name])])}
      ${sel("device", "Device", [["", "All devices"], ...devs.devices.map((x) => [x.uid, x.name])])}
      ${sel("op", "Operation", [["", "All operations"], ...d.ops.map((o) => [o, o])])}
      ${sel("status", "Result", [["", "All results"], ["ok", "Successful"], ["err", "Errors"]])}
      <input type="text" placeholder="Search file name" id="lq" aria-label="Search file name" value="${esc(LOGF.q)}">
      <span class="soft small">${d.items.length >= 300 ? "latest 300 shown" : d.items.length + " shown"}</span>
    </div>
    <section class="card"><div class="tw"><table>
      <thead><tr><th>Time</th><th>App</th><th>Device</th><th>Operation</th><th>File</th><th class="num">Size</th><th class="num">Result</th><th class="num">Took</th></tr></thead>
      <tbody>${d.items.length ? d.items.map((r) => `<tr><td class="mono soft">${dt(r.ts)}</td><td>${esc(names[r.app] || r.app || "")}</td><td class="soft">${esc(r.device_name || "")}</td>
        <td class="mono">${esc(r.op)}</td><td class="mono">${esc(r.key || "")}</td><td class="num">${r.bytes_in || r.bytes_out ? fb((r.bytes_in || 0) + (r.bytes_out || 0)) : "—"}</td>
        <td class="num ${r.status >= 400 && r.status !== 404 ? "warn" : "soft"}" title="${esc(r.note || "")}">${r.status}</td><td class="num soft">${r.ms != null ? Math.round(r.ms) + " ms" : ""}</td></tr>`).join("")
        : `<tr><td colspan="8" class="soft">No requests match.</td></tr>`}</tbody>
    </table></div></section>`);
  tabs();
  $("view").querySelectorAll("[data-f]").forEach((el) => el.onchange = () => { LOGF[el.dataset.f] = el.value; route("phone/logs", true); });
  $("lq").onchange = (e) => { LOGF.q = e.target.value; route("phone/logs", true); };
}

// ================================================================ SETTINGS
const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
RENDER.settings = async (args, ticket) => {
  const [d, dv] = await Promise.all([api("GET", "/api/settings"), api("GET", "/api/devices")]);
  if (stale(ticket)) return;
  const S = d.settings, P = d.pool;
  STATE.pool = P;
  const ssw = (key, on, label) => sw(`data-key="${key}"`, on, label);
  paint(`
  <h1>Settings</h1>
  <p class="lede">How your machines reach the phone, who is allowed in, and every setting Pocket Cloud has. Changes apply when saved; the storage server restarts by itself only if a change needs it.</p>
  <div class="grid g2" style="align-items:start">
    <section class="card" id="sec-setup"><h2>Set up another machine</h2>
      <p class="soft" style="margin-bottom:12px">Save this as <span class="mono">%USERPROFILE%\\.pocket\\config</span> (Windows) or <span class="mono">~/.pocket/config</span> on a new machine; the Pocket library reads it. Apps use the enroll secret to enroll themselves.</p>
      <pre id="mcfg">${esc(d.machine_config)}</pre>
      <div class="actions" style="margin-top:12px"><button class="btn" id="sec-show">Show the secret</button><button class="btn" id="cfg-copy">Copy</button><button class="btn" id="ci-copy">Copy as CI secrets</button><button class="btn" id="sec-copy">Copy the secret only</button><button class="btn danger" id="sec-new">Replace the secret</button></div>
      <p class="soft small" style="margin-top:10px">Enroll secret: <span class="mono" id="sec">••••••••••••••••••••••••</span></p>
    </section>
    <section class="card"><h2>Connection</h2>
      <div class="kv"><span>Storage address</span><span class="mono">${esc(d.addresses.storage)}</span><span>Panel address</span><span class="mono">${esc(d.addresses.panel)}</span>
        <span>Home Wi-Fi address</span><span class="mono">${esc(S.lan_address || "—")}</span><span>SD card folder</span><span class="mono">${esc(S.sd_root)}</span></div>
    </section>
    <section class="card span2" id="sec-devices"><div class="card-head"><h2>Devices</h2><span class="soft small">identified by their address on your private network; a device's keys only work from that address</span></div>
      <div class="tw"><table>
      <thead><tr><th>Device</th><th>Address</th><th>Apps</th><th class="num">Requests 24 h</th><th class="num">In / out 24 h</th><th>Last seen</th><th></th></tr></thead>
      <tbody>${dv.devices.length ? dv.devices.map((x) => `<tr><td><span class="dot ${x.blocked ? "bad" : x.last_seen && Date.now() / 1000 - x.last_seen < 900 ? "" : "off"}" style="margin-right:8px"></span>${esc(x.name)}${x.blocked ? ' <span class="badge bad">Blocked</span>' : ""}<div class="faint mono">${esc(x.uid)}</div></td>
        <td class="mono soft">${esc(x.ip)}${x.ip_alt ? ` · ${esc(x.ip_alt)}` : ""}</td>
        <td class="wrap">${x.apps.map((ap) => `<a href="#app/${ap.id}">${esc(ap.name)}</a>`).join(", ")}</td>
        <td class="num">${n(x.requests)}</td><td class="num">${fb(x.bytes_in)} / ${fb(x.bytes_out)}</td><td class="soft">${ago(x.last_seen)}</td>
        <td class="acts"><button class="btn small" data-ren="${x.uid}" data-name="${esc(x.name)}">Rename</button><button class="btn small" data-blk="${x.uid}" data-b="${x.blocked ? 0 : 1}">${x.blocked ? "Unblock" : "Block"}</button><button class="btn small danger" data-rm="${x.uid}" data-name="${esc(x.name)}">Remove</button></td></tr>`).join("")
        : `<tr><td colspan="7" class="soft">No devices yet.</td></tr>`}</tbody>
      </table></div>
    </section>
    <section class="card span2" id="sec-pool"><h2>Storage pool</h2><div class="form"><div data-pool>
      ${frow("Internal storage for Pocket", "Recent files, logs and statistics", slider({ min: Math.ceil(P.int_used / GB), max: Math.max(Math.ceil(P.int_used / GB), Math.floor(P.int_max / GB), Math.round(P.int_limit / GB)), value: Math.round(P.int_limit / GB), unit: "GB", cls: "sl-pint", key: "pool.internal_gb", label: "Internal storage for Pocket", marks: [{ v: P.int_used / GB, label: "in use " + fb(P.int_used), color: css("--soft") }] }))}
      ${frow("SD card for Pocket", "Older files, the nightly copy and the undo", slider({ min: Math.ceil(P.sd_used / GB), max: Math.max(Math.ceil(P.sd_used / GB), Math.floor(P.sd_max / GB), Math.round(P.sd_limit / GB)), value: Math.round(P.sd_limit / GB), unit: "GB", cls: "sl-psd", key: "pool.sd_gb", label: "SD card for Pocket", marks: [{ v: P.sd_used / GB, label: "in use " + fb(P.sd_used), color: css("--soft") }] }))}
      ${frow("Always leave free for Android", "Keeps the phone itself from running out of space", slider({ min: 2, max: 20, value: S.pool.reserve_gb, unit: "GB", cls: "sl-res", key: "pool.reserve_gb", label: "Always leave free for Android" }))}
      ${frow("", "", `<span class="pool-preview soft small"></span>`)}
      ${frow("When the pool is full", "Applies to every app", `<span class="soft">New uploads are refused with a "pool full" error, and you are alerted</span>`)}
    </div></div></section>
    <section class="card"><h2>New apps</h2><div class="form">
      ${frow("Create apps automatically", "Otherwise new apps wait for you on the Apps page", ssw("new_apps.auto_create", S.new_apps.auto_create, "Create apps automatically"))}
      ${frow("Default space", "If the pool has less unpromised, a new app gets what is left", slider({ min: 0.5, max: 20, step: 0.5, value: S.new_apps.alloc_gb, unit: "GB", key: "new_apps.alloc_gb", compact: true, label: "Default space" }))}
      ${frow("Warn at", "", slider({ min: 50, max: 95, step: 5, value: S.new_apps.warn_pct, unit: "%", key: "new_apps.warn_pct", compact: true, label: "Warn at" }))}
      ${frow("At 100%", "", `<select data-key="new_apps.at_limit" aria-label="At 100%"><option value="refuse"${S.new_apps.at_limit === "refuse" ? " selected" : ""}>Refuse uploads</option><option value="warn"${S.new_apps.at_limit === "warn" ? " selected" : ""}>Warn only</option></select>`)}
      ${frow("Move to SD card after", "", slider({ min: 1, max: 60, value: S.new_apps.move_days, unit: "days", key: "new_apps.move_days", compact: true, label: "Move to SD card after" }))}
    </div></section>
    <section class="card" id="sec-jobs"><h2>Nightly jobs</h2><div class="form">
      ${frow("Nightly jobs start at", "", `<input type="time" data-key="jobs.nightly_time" value="${S.jobs.nightly_time}" aria-label="Nightly jobs start at">`)}
      ${frow("Move early when the internal pool is", "Full to this level · oldest files first", slider({ min: 50, max: 100, step: 5, value: S.pool.early_move_pct, unit: "% full", key: "pool.early_move_pct", compact: true, label: "Move early at" }))}
      ${frow("Never move files changed in the last", "Protects files still being written", slider({ min: 1, max: 72, value: S.jobs.skip_changed_hours, unit: "hours", key: "jobs.skip_changed_hours", compact: true, label: "Never move files changed in the last" }))}
      ${frow("Keep deleted and overwritten files for", "The undo period", slider({ min: 1, max: 60, value: S.jobs.undo_days, unit: "days", key: "jobs.undo_days", compact: true, label: "Undo period" }))}
      ${frow("Integrity check", "", `<select data-key="jobs.integrity_weekday" aria-label="Integrity check day">${DAYS.map((x, i) => `<option value="${i}"${i === S.jobs.integrity_weekday ? " selected" : ""}>${x}</option>`).join("")}</select> at <input type="time" data-key="jobs.integrity_time" value="${S.jobs.integrity_time}" aria-label="Integrity check time">`)}
    </div></section>
    <section class="card" id="sec-logs"><h2>Logs and statistics</h2><div class="form">
      ${frow("Keep detailed request logs for", "", slider({ min: 7, max: 365, value: S.logs.request_days, unit: "days", key: "logs.request_days", compact: true, label: "Keep request logs for" }))}
      ${frow("Keep daily totals", "", `<span class="soft">Forever</span>`)}
      ${frow("Record file names in logs", "Off keeps only app, device, size and result", ssw("logs.record_names", S.logs.record_names, "Record file names in logs"))}
    </div></section>
    <section class="card"><h2>Health checks</h2><div class="form">
      ${frow("Run every", "", slider({ min: 1, max: 30, value: S.health.every_min, unit: "minutes", key: "health.every_min", compact: true, label: "Run health checks every" }))}
      ${frow("Alert thresholds", "Per alert", `<a href="#phone/alerts">Edit alert rules</a>`)}
    </div></section>
    <section class="card span2"><h2>Server</h2><div class="form">
      ${frow("Read-only mode", "Apps can read but not write or delete", ssw("server.read_only", S.server.read_only, "Read-only mode"))}
      ${frow("Share links", "Apps can make links to single files for people outside Tailscale. Only GET /s/&lt;link&gt; is reachable through the Cloudflare tunnel (installed by setup.sh); a quick tunnel's address changes at every restart, a named tunnel keeps one.", ssw("share.enabled", S.share.enabled, "Share links"))}
      ${frow("Push notices to apps", "The phone's built-in push server (port 2586, Tailscale only, ntfy-style): apps learn of changes at once instead of polling.", ssw("push.enabled", S.push.enabled, "Push notices to apps"))}
      ${frow("Also listen on the home Wi-Fi", `Faster for devices at home${S.lan_address ? ` (${esc(S.lan_address)})` : " · no Wi-Fi address known yet"}. Anyone on the Wi-Fi can reach the storage port; every request still needs a device key. The control panel stays on Tailscale only.`, ssw("lan.enabled", S.lan.enabled, "Also listen on the home Wi-Fi"))}
      ${frow("Request timeout", "", slider({ min: 10, max: 300, step: 10, value: S.server.timeout_s, unit: "seconds", key: "server.timeout_s", label: "Request timeout" }))}
      ${frow("Large upload buffer", "Memory for uploads sent in parts", slider({ min: 16, max: 256, step: 16, value: S.server.upload_buffer_mb, unit: "MB", key: "server.upload_buffer_mb", label: "Large upload buffer" }))}
      ${frow("Unfinished uploads expire after", "", slider({ min: 12, max: 336, step: 12, value: S.server.multipart_expiry_h, unit: "hours", key: "server.multipart_expiry_h", label: "Unfinished uploads expire after" }))}
      ${frow("Directory cache", "Refreshed automatically after each nightly move", slider({ min: 5, max: 300, step: 5, value: S.server.dir_cache_s, unit: "seconds", key: "server.dir_cache_s", label: "Directory cache" }))}
      ${frow("Restart the storage server", "Running requests finish first", `<button class="btn" id="srv-restart">Restart</button>`)}
    </div></section>
    <section class="card"><h2>Security</h2><div class="list">
      <div class="row wrap"><div class="grow"><h3>Admin password</h3>${s13("Changing it signs every browser and phone out")}</div><button class="btn small" id="pw-change">Change</button></div>
      <div class="row wrap"><div class="grow"><h3>Settings backup</h3>${s13("All settings, apps and device keys in one file")}</div><div class="actions"><button class="btn small" id="bk-get">Download</button><button class="btn small" id="bk-put">Restore from file</button></div></div>
      <div class="row wrap"><div class="grow"><h3>This browser</h3>${s13("Signed in to the control panel")}</div><button class="btn small" id="so-here">Sign out</button></div>
    </div></section>
    <section class="card"><h2>Appearance</h2>
      <div class="row wrap between"><span class="soft">Theme</span><div class="seg theme-seg-here" role="group" aria-label="Theme">${themeSeg()}</div></div>
      <p class="soft small" style="margin-top:12px">Pocket Cloud ${esc(STATE.overview?.version || "")} · panel at <span class="mono">${esc(location.host)}</span></p>
    </section>
  </div>
  <div class="form-foot save-bar"><button class="btn primary" id="st-save">Save changes</button><button class="btn" id="st-cancel">Discard</button></div>`);
  const v = $("view");
  refreshPool(v.querySelector("[data-pool]"));
  const anchor = { setup: "sec-setup", devices: "sec-devices", pool: "sec-pool", jobs: "sec-jobs", logs: "sec-logs" }[args[0]];
  if (anchor) $(anchor)?.scrollIntoView({ block: "start" });
  // enroll secret and machine setup file
  let secret = null;
  const getSecret = async () => secret || (secret = await act(() => api("GET", "/api/settings/enroll-secret")));
  $("sec-show").onclick = async () => { const r = await getSecret(); if (r) { $("sec").textContent = r.enroll_secret; $("mcfg").textContent = r.machine_config; } };
  $("sec-copy").onclick = async () => { const r = await getSecret(); if (r) copyText(r.enroll_secret, "Enroll secret"); };
  $("cfg-copy").onclick = async () => { const r = await getSecret(); if (r) copyText(r.machine_config, "Machine setup file"); };
  $("ci-copy").onclick = async () => { const r = await getSecret(); if (r) copyText(Object.entries(r.ci).map(([k, val]) => `${k}=${val}`).join("\n"), "CI secrets"); };
  $("sec-new").onclick = async () => {
    if (await confirmBox("Replace the enroll secret", "New machines then need the new secret. Apps already enrolled keep working.", "Replace", true)) {
      secret = await act(() => api("POST", "/api/settings/enroll-secret/rotate"), "Enroll secret replaced.");
      if (secret) { $("sec").textContent = secret.enroll_secret; $("mcfg").textContent = secret.machine_config; }
    }
  };
  // devices
  v.querySelectorAll("[data-ren]").forEach((b) => b.onclick = async () => {
    const name = await promptBox("Rename device", "Name shown in the control panel", b.dataset.name);
    if (name && await act(() => api("POST", `/api/devices/${b.dataset.ren}/rename`, { name }), "Device renamed.")) route("settings/devices", true);
  });
  v.querySelectorAll("[data-blk]").forEach((b) => b.onclick = async () => {
    const blocking = b.dataset.b === "1";
    if (!blocking || await confirmBox("Block device", "Every app on this device is refused until you unblock it.", "Block", true))
      if (await act(() => api("POST", `/api/devices/${b.dataset.blk}/block`, { blocked: blocking }), blocking ? "Device blocked." : "Device unblocked.")) route("settings/devices", true);
  });
  v.querySelectorAll("[data-rm]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Remove device", `Remove <b>${esc(b.dataset.name)}</b> from every app? Its private files go into the undo.`, "Remove", true))
      if (await act(() => api("DELETE", `/api/devices/${b.dataset.rm}`), "Device removed.")) route("settings/devices", true);
  });
  // server, password, backup
  $("srv-restart").onclick = async () => { if (await confirmBox("Restart the storage server", "Running requests finish first; new ones wait a moment.", "Restart")) act(() => api("POST", "/api/server/restart"), "Storage server restarted."); };
  $("so-here").onclick = signOut;
  $("pw-change").onclick = () => {
    openModal(`<h2>Change admin password</h2><div class="body">
      <label class="field">Current password<input type="password" id="pw-old" autocomplete="current-password"></label>
      <label class="field">New password (at least 8 characters)<input type="password" id="pw-new" autocomplete="new-password"></label>
      <label class="field">New password again<input type="password" id="pw-new2" autocomplete="new-password"></label></div>${foot("Change", false, "pw-go")}`);
    $("m-no").onclick = closeModal;
    $("pw-go").onclick = async () => {
      if ($("pw-new").value !== $("pw-new2").value) return toast("The two new passwords are different.", true);
      const r = await act(() => api("POST", "/api/password", { old: $("pw-old").value, new: $("pw-new").value }), "Password changed. Other browsers and phones are signed out.");
      if (r) { TOKEN = r.token; store.set("pocketToken", TOKEN); closeModal(); }
    };
  };
  $("bk-get").onclick = async () => {
    const b = await act(() => api("GET", "/api/settings/backup"));
    if (!b) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(b, null, 2)], { type: "application/json" }));
    const a = document.createElement("a"); a.href = url; a.download = `pocket-cloud-backup-${new Date().toISOString().slice(0, 10)}.json`; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  $("bk-put").onclick = () => { const pick = $("file-pick"); pick.value = ""; pick.onchange = async () => {
    const f = pick.files[0]; if (!f) return;
    let data; try { data = JSON.parse(await f.text()); } catch { return toast("That file is not a Pocket Cloud backup.", true); }
    if (await confirmBox("Restore settings", "Replace every setting, app and device key with the ones in this backup? Files are not touched.", "Restore", true))
      if (await act(() => api("POST", "/api/settings/restore", data), (r) => `Restored ${r.apps} apps and ${r.devices} devices.`)) route("settings", true);
  }; pick.click(); };
  // save: only what changed (and only sliders that were moved)
  $("st-cancel").onclick = () => route("settings", true);
  $("st-save").onclick = async () => {
    const body = {};
    const put = (key, value, old) => { if (value === old) return; const [a, b] = key.split("."); (body[a] ||= {})[b] = value; };
    const get = (key) => key.split(".").reduce((o, k) => o?.[k], S);
    v.querySelectorAll("[data-key]").forEach((el) => {
      const key = el.dataset.key;
      if (el.classList.contains("sl")) {
        // A slider shows a stored value clamped to what the disk allows; saving it unmoved would quietly
        // change the setting (that shrank a pool once). So a slider counts only when it was moved.
        if (!el.dataset.moved) return;
        const val = slVal(el);
        if (key === "pool.internal_gb") return put(key, val, P.int_limit / GB);
        if (key === "pool.sd_gb") return put(key, val, P.sd_limit / GB);
        return put(key, val, get(key));
      }
      if (el.classList.contains("sw")) return put(key, swOn(el), get(key));
      if (key === "jobs.integrity_weekday") return put(key, +el.value, get(key));
      put(key, el.value, get(key));
    });
    if (!Object.keys(body).length) return toast("Nothing changed.");
    if (await act(() => api("PATCH", "/api/settings", body), "Settings saved.")) route("settings", true);
  };
};

boot();
