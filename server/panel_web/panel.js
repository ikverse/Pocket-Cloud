"use strict";
/* Pocket Cloud web panel. Everything shown comes from the control API (/api/...); every button calls it. */

// ---------------------------------------------------------------- helpers
const $ = (id) => document.getElementById(id);
const C = { green: "#73bf69", yellow: "#f2cc0c", blue: "#5794f2", orange: "#ff9830", red: "#f2495c", purple: "#b877d9",
            muted: "#8e9099", line: "#2a2e35", grey: "#5c6370", free: "#2f333b", white: "#d8d9da" };
const SERIES = [C.green, C.yellow, C.blue, C.orange, C.purple, C.red, "#8ab8ff", "#5ac8c8"];
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
const pad = (x) => String(x).padStart(2, "0");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const dt = (ts) => ts ? new Date(ts * 1000).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";
const hm = (ts) => ts ? new Date(ts * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "—";
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
const barColor = (pct, warn) => pct >= 100 ? C.red : pct >= warn ? C.yellow : C.green;
const allocCell = (pct, warn) => `<div class="alloc"><div class="bar"><i style="width:${Math.min(pct, 100)}%;background:${barColor(pct, warn)}"></i></div><span class="${pct >= 100 ? "bad" : pct >= warn ? "warn" : ""}">${pct.toFixed(0)}%</span></div>`;
const lvlColor = (l) => l === "bad" ? C.red : l === "warn" ? C.yellow : C.blue;
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} },
};

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

// ---------------------------------------------------------------- dialogs
function openModal(html, wide) { $("modal-box").className = "panel modal-box" + (wide ? " wide" : ""); $("modal-box").innerHTML = html; $("modal").hidden = false; }
function closeModal() { $("modal").hidden = true; $("modal-box").innerHTML = ""; }
$("modal").addEventListener("click", (e) => { if (e.target.id === "modal") closeModal(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });
function confirmBox(title, text, ok = "OK", danger = false) {
  return new Promise((resolve) => {
    openModal(`<h3>${esc(title)}</h3><div class="body">${text}</div>
      <div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn ${danger ? "danger" : "primary"}" id="m-yes">${esc(ok)}</button></div>`);
    $("m-no").onclick = () => { closeModal(); resolve(false); };
    $("m-yes").onclick = () => { closeModal(); resolve(true); };
  });
}
function promptBox(title, label, value = "", ok = "Save", type = "text") {
  return new Promise((resolve) => {
    openModal(`<h3>${esc(title)}</h3><div class="body"><label class="field">${esc(label)}<input type="${type}" id="m-in" value="${esc(value)}"></label></div>
      <div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn primary" id="m-yes">${esc(ok)}</button></div>`);
    const input = $("m-in"); input.focus(); input.select();
    const done = (v) => { closeModal(); resolve(v); };
    $("m-no").onclick = () => done(null);
    $("m-yes").onclick = () => done(input.value);
    input.onkeydown = (e) => { if (e.key === "Enter") done(input.value); };
  });
}

// ---------------------------------------------------------------- sliders (number box beside; either one moves the other)
const markLeft = (v, min, max) => { const P = (v - min) / (max - min || 1) * 100; return `calc(${P}% + ${7 - P * 0.14}px)`; };
const markEdge = (P) => P < 12 ? "--tx:0%;--tick:0%" : P > 88 ? "--tx:-100%;--tick:100%" : "--tx:-50%;--tick:50%";
function slider({ min, max, step = 1, value, unit = "", none = false, marks = null, cls = "", compact = false, key = "" }) {
  if (max < min) max = min;
  const v = Math.min(max, Math.max(min, value));
  const p = (v - min) / (max - min || 1) * 100;
  const mk = (m) => { const mv = Math.min(max, Math.max(min, m.v));
    return `<span class="mk${m.k === "warn" ? " up" : ""}" data-k="${m.k || ""}" style="left:${markLeft(mv, min, max)};${markEdge((mv - min) / (max - min || 1) * 100)}"><i style="background:${m.color}"></i>${m.label}</span>`; };
  return `<div class="sl ${cls}${compact ? " compact" : ""}${marks ? " has-marks" : ""}"${key ? ` data-key="${key}"` : ""}>
    <div class="sl-track"><input type="range" class="sl-range" min="${min}" max="${max}" step="${step}" value="${v}" style="--p:${p}%"${none ? ' data-none="1"' : ""}>
      ${marks ? `<div class="sl-marks">${marks.map(mk).join("")}</div>` : ""}</div>
    <input type="number" class="sl-num" min="${min}" max="${max}" step="${step}" value="${none && v === min ? "" : v}"${none ? ' placeholder="none"' : ""}><span class="unit">${unit}</span></div>`;
}
function paint(r) { r.style.setProperty("--p", ((+r.value - +r.min) / (+r.max - +r.min || 1) * 100) + "%"); }
const slVal = (el) => +el.querySelector(".sl-range").value;
function refreshGroup(g) {                 // allocation slider + warning slider + per-device cap of one app
  if (!g) return;
  const ar = g.querySelector(".sl-alloc .sl-range");
  const alloc = +ar.value * GB;
  const warnEl = g.querySelector(".sl-warn .sl-range");
  const warn = warnEl ? +warnEl.value : +g.dataset.warn;
  const used = +g.dataset.used;
  const pct = used / alloc * 100;
  g.querySelector(".sl-preview").innerHTML =
    `${fb(used)} of ${gbv(alloc)} used <span class="${pct >= 100 ? "bad" : pct >= warn ? "warn" : ""}">(${pct.toFixed(0)}%)</span>, warning at ${gbv(alloc * warn / 100)}`;
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
    paint(cr);
  }
  const res = g.querySelector(".sl-res");          // the reserve times the devices must fit in the allocation
  if (res) {
    const rr = res.querySelector(".sl-range"), rn = res.querySelector(".sl-num");
    const top = +ar.value / (+g.dataset.devices || 1);
    rr.max = rn.max = top;
    if (+rr.value > top) { rr.value = top; if (rn.value !== "") rn.value = top; }
    paint(rr);
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
  paint(ir);
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
  paint(r);
  refreshGroup(box.closest("[data-ag]"));
  refreshPool(box.closest("[data-pool]"));
});
document.addEventListener("change", (e) => {
  const t = e.target, box = t.closest(".sl");
  if (box && t.classList.contains("sl-num") && t.value !== "") t.value = box.querySelector(".sl-range").value;
});

// ---------------------------------------------------------------- charts
const CHARTS = {};
function chart(key, el, series, data, opts = {}) {
  if (CHARTS[key]) CHARTS[key].destroy();
  if (!el) return;
  const axis = { stroke: C.muted, grid: { stroke: C.line, width: 1 }, ticks: { stroke: C.line, width: 1 }, font: "11px Inter, Roboto, sans-serif" };
  CHARTS[key] = new uPlot({
    width: (el.clientWidth || 600) - 12, height: opts.height || 220,
    cursor: { points: { size: 6 } }, scales: { x: { time: true } },
    series: [{ label: "Time", value: (u, v) => v == null ? "" : new Date(v * 1000).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) },
      ...series.map((s) => ({ label: s.label, stroke: s.color, width: 1.5, fill: s.fill, points: { show: false },
        value: (u, v) => v == null ? "" : (opts.fmt ? opts.fmt(v) : n(v)) }))],
    axes: [opts.daily ? { ...axis, splits: () => data[0], values: (u, vals) => vals.map((v) => new Date(v * 1000).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })) } : axis,
      { ...axis, size: 60, values: (u, vals) => vals.map((v) => opts.axisFmt ? opts.axisFmt(v) : n(v)) }],
  }, data, el);
}
window.addEventListener("resize", () => Object.values(CHARTS).forEach((c) => { const el = c.root.parentElement; if (el?.clientWidth) c.setSize({ width: el.clientWidth - 12, height: c.height }); }));

// ---------------------------------------------------------------- navigation (in script: works even where the address can't change)
const PAGES = [["overview", "Overview"], ["storage", "Storage"], ["apps", "Apps"], ["devices", "Devices"], ["files", "Files"],
               ["jobs", "Jobs"], ["alerts", "Alerts"], ["settings", "Settings"], ["logs", "Logs"]];
const STATE = { page: "overview", counts: {}, pool: null };
let TICKET = 0;
function setAddress(h, replace) { try { history[replace ? "replaceState" : "pushState"](null, "", "#" + h); } catch {} }
function go(h) { setAddress(h); route(h); }
document.addEventListener("click", (e) => {
  const a = e.target.closest("a[href^='#']");
  if (!a || e.defaultPrevented) return;
  e.preventDefault();
  const h = a.getAttribute("href").slice(1);
  if (h) go(h);
});
window.addEventListener("popstate", () => route());
function drawNav() {
  const c = STATE.counts;
  $("nav").innerHTML = PAGES.map(([id, label]) => {
    let count = "";
    if (id === "apps" && c.apps != null) count = `<span class="count">${c.apps}</span>`;
    if (id === "devices" && c.devices != null) count = `<span class="count">${c.devices}</span>`;
    if (id === "alerts" && c.alerts) count = `<span class="count ${c.alertLevel}">${c.alerts}</span>`;
    return `<a href="#${id}" data-p="${id}" class="${STATE.page === id ? "on" : ""}">${label}${count}</a>`;
  }).join("");
}
function route(target) {
  const h = target ?? (location.hash.slice(1) || "overview");
  const [p, ...rest] = h.split("/");
  const page = PAGES.some((x) => x[0] === p) ? p : "overview";
  STATE.page = page;
  drawNav();
  document.querySelectorAll("main > section").forEach((s) => (s.hidden = s.id !== "p-" + page));
  const ticket = ++TICKET;
  const sec = $("p-" + page);
  if (!sec.innerHTML) sec.innerHTML = `<p class="loading">Loading…</p>`;
  Promise.resolve(RENDER[page](rest.length ? decodeURIComponent(rest.join("/")) : "", ticket))
    .catch((e) => { if (e.status !== 401 && ticket === TICKET) sec.innerHTML = `<h1>${PAGES.find((x) => x[0] === page)[1]}</h1><p class="bad">${esc(e.message)}</p>`; });
  window.scrollTo(0, 0);
}
const stale = (ticket) => ticket !== TICKET;
const RENDER = {};

// ---------------------------------------------------------------- sign-in
function gate(html) { $("shell").hidden = true; $("gate").hidden = false; $("gate").innerHTML = `<div class="panel"><div class="body">${html}</div></div>`; }
function signedOut() { TOKEN = null; store.set("pocketToken", null); showLogin(); }
function showLogin() {
  gate(`<h1>Pocket Cloud</h1><p class="sub">Sign in to the control panel.</p>
    <label>Admin password<input type="password" id="g-pw" autocomplete="current-password"></label>
    <div class="err" id="g-err"></div><button class="btn primary" id="g-go">Sign in</button>`);
  const go_ = async () => {
    try { const r = await api("POST", "/api/login", { password: $("g-pw").value, device: "Web panel" }); TOKEN = r.token; store.set("pocketToken", TOKEN); start(); }
    catch (e) { $("g-err").textContent = e.message; }
  };
  $("g-go").onclick = go_; $("g-pw").onkeydown = (e) => { if (e.key === "Enter") go_(); }; $("g-pw").focus();
}
function showSetup() {
  gate(`<h1>Welcome to Pocket Cloud</h1><p class="sub">Choose the admin password for this control panel and the Android app. At least 8 characters.</p>
    <label>Admin password<input type="password" id="g-pw" autocomplete="new-password"></label>
    <label>Type it again<input type="password" id="g-pw2" autocomplete="new-password"></label>
    <div class="err" id="g-err"></div><button class="btn primary" id="g-go">Set password</button>`);
  $("g-go").onclick = async () => {
    if ($("g-pw").value !== $("g-pw2").value) { $("g-err").textContent = "The two passwords are different."; return; }
    try { const r = await api("POST", "/api/setup", { password: $("g-pw").value, device: "Web panel" }); TOKEN = r.token; store.set("pocketToken", TOKEN); start(); }
    catch (e) { $("g-err").textContent = e.message; }
  };
}
$("signout").onclick = async () => { await api("POST", "/api/logout", {}).catch(() => {}); signedOut(); };

async function boot() {
  let st;
  try { st = await api("GET", "/api/status"); }
  catch (e) { gate(`<h1>Pocket Cloud</h1><p class="bad">${esc(e.message)}</p>`); return; }
  if (!st.password_set) return showSetup();
  if (!TOKEN || !st.signed_in) return showLogin();
  start();
}
function start() {
  $("gate").hidden = true; $("shell").hidden = false;
  $("host").textContent = location.host;
  route();
  refreshTop();
  clearInterval(start.timer);
  // keep the overview live, but never redraw under an open dialog
  start.timer = setInterval(() => { refreshTop(); if (STATE.page === "overview" && $("modal").hidden) RENDER.overview("", TICKET, true); }, 15000);
}
async function refreshTop() {
  try {
    const o = await api("GET", "/api/overview");
    STATE.overview = o;
    const worst = o.alerts.some((a) => a.level === "bad") ? "bad" : o.alerts.some((a) => a.level === "warn") ? "warn" : "";
    const okAll = o.services.every((s) => s.ok);
    $("health").innerHTML = `<span class="dot" style="background:${!okAll || worst === "bad" ? C.red : worst ? C.yellow : C.green}"></span>${!okAll ? "Problem" : worst === "bad" ? "Needs attention" : worst ? "Warning" : "Healthy"}`;
    $("updated").textContent = "Updated " + hm(o.now);
    STATE.counts = { apps: o.app_count, devices: o.device_count, alerts: o.alerts.filter((a) => a.level !== "info").length, alertLevel: worst };
    drawNav();
  } catch {}
}

// ---------------------------------------------------------------- pages
function stat(l, v, s) { return `<div class="panel stat"><div class="l">${l}</div><div class="v">${v}</div><div class="s">${s}</div></div>`; }
function alertLine(a) { return `<div class="alert-line"><span class="dot" style="background:${lvlColor(a.level)}"></span><div>${esc(a.text)}</div><span class="when">since ${dt(a.raised)}</span></div>`; }
function legend(color, label, value) { return `<div class="legend-row"><span><span class="sw" style="background:${color}"></span>${label}</span><span class="num">${value}</span></div>`; }
function frow(label, hint, control) { return `<div class="row"><div>${label}${hint ? `<div class="h">${hint}</div>` : ""}</div><div class="c">${control}</div></div>`; }

RENDER.overview = async (arg, ticket, quiet) => {
  const o = await api("GET", "/api/overview");
  if (stale(ticket) && !quiet) return;
  STATE.overview = o;
  const s = $("p-overview");
  const p = o.pool, ph = o.phone;
  const names = Object.fromEntries(o.allocation.map((a) => [a.id, a.name]));
  const apps = Object.keys(o.hourly);
  s.innerHTML = `
    <h1>Overview</h1><p class="sub">Everything on Pocket Cloud at a glance.</p>
    <div class="grid g4">
      ${stat("Pocket data", fb(o.apps_used), `${n(o.files)} files in ${o.app_count} apps`)}
      ${stat("Storage pool", `${(p.used / p.total * 100).toFixed(0)}% used`, `${fb(p.used)} of ${gbv(p.total)} (${gbv(p.int_limit)} internal + ${gbv(p.sd_limit)} SD)`)}
      ${stat("Promised to apps", gbv(p.promised), `${gbv(p.unpromised)} of the pool unpromised`)}
      ${stat("Requests, last 24 h", n(o.traffic.requests), `${n(o.traffic.errors)} errors${o.traffic.requests ? ` (${(o.traffic.errors / o.traffic.requests * 100).toFixed(2)}%)` : ""}`)}
      ${stat("Traffic, last 24 h", fb(o.traffic.bytes_in + o.traffic.bytes_out), `${fb(o.traffic.bytes_in)} in · ${fb(o.traffic.bytes_out)} out`)}
      ${stat("Apps · devices", `${o.app_count} · ${o.device_count}`, o.apps_over_warning ? `${o.apps_over_warning} app${o.apps_over_warning > 1 ? "s" : ""} above the warning level` : "all apps below their warning level")}
      ${stat("Battery", ph && ph.battery != null ? ph.battery + "%" : "—", ph ? `${esc(ph.plugged || "")}${ph.temp_c != null ? " · " + ph.temp_c.toFixed(1) + " °C" : ""}` : "no phone readings (Termux:API)")}
      ${stat("Uptime", dur(o.now - o.started_at), "since " + dt(o.started_at))}
    </div>
    <div class="grid g2" style="margin-top:12px">
      <div class="panel span2"><h3>Requests per hour, by app <span class="r">last 24 h</span></h3>${apps.length ? `<div class="chart" id="ch-req"></div>` : `<div class="empty">No requests in the last 24 hours.</div>`}</div>
      <div class="panel"><h3>Services</h3><div class="tw"><table><tbody>
        ${o.services.map((v) => { const c = !v.ok ? ["bad", C.red] : v.warn ? ["warn", C.yellow] : ["ok", C.green];
          return `<tr><td><span class="dot" style="background:${c[1]}"></span>${esc(v.name)}</td><td class="${c[0]}">${v.warn ? `<a href="#alerts" class="warn">${esc(v.state)}</a>` : esc(v.state)}</td><td class="muted wrap">${esc(v.detail)}</td></tr>`; }).join("")}
      </tbody></table></div></div>
      <div class="panel"><h3>Allocation use <span class="r"><a href="#apps">All apps</a></span></h3><div class="tw"><table><tbody>
        ${o.allocation.length ? o.allocation.slice().sort((x, y) => y.used / (y.alloc || 1) - x.used / (x.alloc || 1)).map((a) => { const pct = a.used / (a.alloc || 1) * 100;
          return `<tr class="click" onclick="go('apps/${a.id}')"><td>${esc(a.name)}</td><td class="num muted">${fb(a.used)} of ${gbv(a.alloc)}</td><td style="width:40%">${allocCell(pct, a.warn_pct)}</td></tr>`; }).join("")
          : `<tr><td class="muted">No apps yet. They appear when they first connect.</td></tr>`}
      </tbody></table></div></div>
      <div class="panel span2"><h3>Active alerts <span class="r"><a href="#alerts">All alerts</a></span></h3>
        ${o.alerts.length ? o.alerts.map(alertLine).join("") : `<div class="empty">No active alerts.</div>`}</div>
    </div>`;
  if (apps.length) chart("req", $("ch-req"), apps.map((a, i) => ({ label: names[a] || a, color: SERIES[i % SERIES.length] })), [o.hours, ...apps.map((a) => o.hourly[a])]);
};

// ---- SD cards: one in the phone at a time, full ones on the shelf (server: cards.py)
const KIND_LABEL = { video: "Video", photo: "Photo", audio: "Audio", archive: "Archive", other: "Other" };
const cardSpeed = (c, slow) => c.write_mbs == null ? "speed not measured"
  : `<span class="${c.write_mbs < slow ? "warn" : ""}">write ${c.write_mbs} MB/s</span> · read ${c.read_mbs} MB/s${c.write_mbs < slow ? ` <span class="warn">(slow: under ${slow} MB/s)</span>` : ""}`;
const spaceLabel = (sp) => sp === "shared" ? "shared" : "device " + sp.slice(7);

function cardsPanel(cd) {
  if (cd.mode !== "cards") return "";
  const A = cd.active, S = cd.slot, J = cd.job;
  let rows = [];
  if (J && J.running) {
    const pctDone = J.total ? Math.round(J.done / J.total * 100) : 0;
    rows.push(`<div><b>${esc({ swap: "Swapping the SD card", use: "Setting up the new card", put_back: "Putting the card back to work" }[J.kind] || "Working")}</b>
      <div class="progress"><i style="width:${J.total ? pctDone : 5}%"></i></div><span class="muted">${esc(J.step)}${J.total ? ` · ${n(J.done)} of ${n(J.total)}` : ""}</span></div>`);
  } else if (J && J.error && Date.now() / 1000 - J.finished < 3600) {
    rows.push(`<div class="bad">The last SD card job failed: ${esc(J.error)}</div>`);
  }
  if (A && A.present) {
    rows.push(`<div class="cardrow"><div class="grow"><b>${esc(A.name)}</b> <span class="muted">· in use · ${esc(A.volume || "")} · ${esc(A.fs || "")}</span>
        <div class="muted">${fb(A.total)}, ${fb(A.free)} free · ${cardSpeed(A, cd.slow_mbs)}</div></div>
      <button class="btn" data-card-rename="${A.id}" data-name="${esc(A.name)}">Rename</button>
      <button class="btn primary" id="c-swap"${J && J.running ? " disabled" : ""}>Swap SD card</button></div>`);
  } else if (A) {
    rows.push(`<div class="bad">${esc(A.name)} was removed without a swap. Its ${n(A.files)} files (${gbv(A.bytes)}, as of the last nightly copy) are not available until it is back. Everything else works on internal storage.</div>`);
  } else if (!S) {
    rows.push(`<div class="muted">No SD card in the phone: everything is on internal storage. Put a card in to add room and a nightly copy.</div>`);
  }
  if (S && S.state === "new") {
    rows.push(`<div class="cardrow"><div class="grow"><b>A new SD card is in the phone</b> <span class="muted">· ${esc(S.volume)} · ${esc(S.fs || "")} · ${fb(S.total)}, ${fb(S.free)} free</span>
      <div class="muted">Setting it up checks its speed, sets the SD pool from its size and moves older files onto it.</div></div>
      <button class="btn primary" id="c-use"${J && J.running ? " disabled" : ""}>Use this card</button></div>`);
  } else if (S && S.state === "needs_setup") {
    rows.push(`<div class="warn">A card is in the phone (${esc(S.volume)}), but Termux has no folder on it yet. On the phone running Pocket Cloud, open Termux, type <span class="mono">termux-setup-storage</span>, press Enter and answer <b>y</b>. The card then shows here as a new card within a few seconds.</div>`);
  } else if (S && (S.state === "archive" || S.state === "shelved_here")) {
    const c = S.card;
    rows.push(`<div class="cardrow"><div class="grow">${S.state === "shelved_here"
        ? `<b>${esc(c.name)} is ready to remove.</b><div class="muted">Unmount it in Android's settings (Settings › Device care › Storage › SD card › Unmount), then take it out. Then put in the next card: a new one or an archive card.</div>`
        : `<b>${esc(c.name)}, an archive card, is in the phone</b> <span class="muted">· shelved ${dt(c.shelved)} · ${n(c.files)} files, ${gbv(c.bytes)}</span><div class="muted">Browse it and restore single files, or put it back to work as the card in use.</div>`}</div>
      <button class="btn" data-card-browse="${c.id}">Browse</button>
      <button class="btn${S.state === "archive" ? " primary" : ""}" data-card-back="${c.id}"${J && J.running ? " disabled" : ""}>Put back to work</button></div>`);
  }
  const shelf = cd.shelf.length ? `<div class="tw"><table>
      <thead><tr><th>Card on the shelf</th><th>Shelved</th><th class="num">Files</th><th class="num">Size</th><th>Note</th><th></th></tr></thead>
      <tbody>${cd.shelf.map((c) => `<tr><td>${esc(c.name)} <span class="faint mono">${esc(c.id)}</span></td><td class="muted">${dt(c.shelved)}</td><td class="num">${n(c.files)}</td>
        <td class="num">${gbv(c.bytes)}</td><td class="muted">${esc(c.note || "")}</td>
        <td class="actions"><button class="btn sm" data-card-browse="${c.id}">Browse</button><button class="btn sm" data-card-rename="${c.id}" data-name="${esc(c.name)}">Rename</button><button class="btn sm danger" data-card-forget="${c.id}" data-name="${esc(c.name)}">Forget</button></td></tr>`).join("")}</tbody></table></div>` : "";
  return `<div class="panel span2"><h3>SD cards <span class="r">one in the phone at a time; full cards go on the shelf</span></h3>
    <div class="body">${rows.join('<div style="height:10px"></div>')}</div>${shelf}</div>`;
}

function bindCards(s, cd, again) {
  const jobAction = (r) => { if (r) again(); };
  $("c-swap") && ($("c-swap").onclick = () => swapDialog(again));
  $("c-use") && ($("c-use").onclick = async () => {
    const name = await promptBox("Use this card", "Name for the card", `Card ${cd.shelf.length + (cd.active ? 2 : 1)}`, "Use this card");
    if (name !== null) jobAction(await act(() => api("POST", "/api/cards/use", { name }), "Setting up the card…"));
  });
  s.querySelectorAll("[data-card-back]").forEach((b) => b.onclick = () => putBackDialog(b.dataset.cardBack, again));
  s.querySelectorAll("[data-card-browse]").forEach((b) => b.onclick = () => { Object.assign(FILES, { tab: "cards", card: b.dataset.cardBrowse, cardApp: null, cardPath: "" }); go("files"); });
  s.querySelectorAll("[data-card-forget]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Forget card", `Remove <b>${esc(b.dataset.name)}</b> from the shelf? Use this when the card was formatted, lost or thrown away. Nothing on any card changes; if it turns up again, it is recognised from the marker it carries.`, "Forget", true))
      if (await act(() => api("DELETE", `/api/cards/${b.dataset.cardForget}`), "Card forgotten.")) again();
  });
  s.querySelectorAll("[data-card-rename]").forEach((b) => b.onclick = async () => {
    const name = await promptBox("Rename card", "Card name", b.dataset.name);
    if (name && await act(() => api("PATCH", `/api/cards/${b.dataset.cardRename}`, { name }), "Card renamed.")) again();
  });
  // follow a running job, unless a dialog is open
  clearTimeout(bindCards.timer);
  if (cd.job && cd.job.running) bindCards.timer = setTimeout(() => { if (STATE.page === "storage" && $("modal").hidden) again(); }, 1500);
}

async function swapDialog(again) {
  const plan = await act(() => api("GET", "/api/cards/swap"));
  if (!plan) return;
  const G = plan.groups;
  const totals = () => {
    const c = G.filter((g) => g.carry), st = G.filter((g) => !g.carry);
    const need = c.reduce((a, g) => a + g.bytes, 0);
    $("sw-tot").innerHTML = `Keep using (copied to internal storage): <b>${n(c.reduce((a, g) => a + g.files, 0))} files, ${fb(need)}</b> ·
      Stay on the card: <b>${n(st.reduce((a, g) => a + g.files, 0))} files, ${fb(st.reduce((a, g) => a + g.bytes, 0))}</b><br>
      ${need > plan.room ? `<span class="bad">Internal storage has room for ${fb(plan.room)} only. Leave more on the card, or make room first.</span>` : `<span class="muted">Internal storage has room for ${fb(plan.room)}.</span>`}`;
    $("sw-go").disabled = need > plan.room || !(plan.mirror.ok || $("sw-override").checked);
  };
  const M = plan.mirror;
  const mirrorLine = M.ok ? `<span class="muted">A mirror reported a copy ${ago(M.reported)}, after the card's last change.</span>`
    : `<span class="bad">${M.reported ? `The last mirror report (${ago(M.reported)}) is older than the card's last change${M.last_write ? ` (${ago(M.last_write)})` : ""}` : "No mirror has ever reported a copy"}: files that stay on the card would have no second copy.</span>
       <label style="display:block;margin-top:6px"><input type="checkbox" id="sw-override"> Swap anyway</label>`;
  openModal(`<h3>Swap SD card</h3><div class="body">
    <p class="muted" style="margin-top:0">Ticked rows are files apps keep using: they are copied to internal storage, checked byte for byte, and go onto the next card.
      The rest stays on this card, which goes on the shelf; its files are hidden from apps until it is back. Your choices are remembered for the next swap.
      Files that stay on the card have only that one copy.</p>
    ${G.length ? `<div class="tw"><table><thead><tr><th>Keep using</th><th>App</th><th>Space</th><th>Folder</th><th>Type</th><th class="num">Files</th><th class="num">Size</th></tr></thead>
      <tbody>${G.map((g, i) => `<tr><td><input type="checkbox" data-g="${i}"${g.carry ? " checked" : ""}></td><td>${esc(g.app_name)}</td><td class="muted">${esc(spaceLabel(g.space))}</td>
        <td class="mono">${esc(g.folder || "(top level)")}</td><td>${esc(g.type || "(none)")} <span class="faint">${KIND_LABEL[g.kind]}${g.carry !== g.default ? " · your choice" : ""}</span></td>
        <td class="num">${n(g.files)}</td><td class="num">${fb(g.bytes)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="muted">The card holds no app files.</p>`}
    <p id="sw-tot"></p><p id="sw-mirror">${mirrorLine}</p></div>
    <div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn primary" id="sw-go">Start the swap</button></div>`, true);
  $("modal-box").querySelectorAll("[data-g]").forEach((cb) => cb.onchange = () => { G[+cb.dataset.g].carry = cb.checked; totals(); });
  if (!M.ok) $("sw-override").onchange = totals; else $("sw-override") || ($("sw-mirror").insertAdjacentHTML("beforeend", `<input type="checkbox" id="sw-override" hidden>`));
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
    ${items.length > max ? `<p class="muted">… and ${n(items.length - max)} more.</p>` : ""}`;
  openModal(`<h3>Put ${esc(c.card.name)} back to work</h3><div class="body">
    <p style="margin-top:0"><b>${n(c.rejoin_files)} files (${fb(c.rejoin_bytes)})</b> rejoin their apps.</p>
    ${c.conflict_count ? `<h2>Same file on the card and in use: ${n(c.conflict_count)}</h2><p class="muted">The newer one stays; the other goes to the undo.</p>
      ${list(c.conflicts, [["File", (f) => `<span class="mono">${esc(f.app)}/${esc(f.key)}</span>`], ["On the card", (f) => dt(f.card_modified), "muted"],
        ["In use", (f) => dt(f.current_modified), "muted"], ["Kept", (f) => f.keep === "card" ? "the card's" : "the one in use"]])}` : ""}
    ${c.unassigned_count ? `<h2>No app or device for them any more: ${n(c.unassigned_count)} files, ${fb(c.unassigned_bytes)}</h2>
      <p class="muted">They stay on the card, hidden from apps, under Files › SD cards › Unassigned, where you can give them to an app or delete them.</p>
      ${list(c.unassigned, [["File", (f) => `<span class="mono">${esc(f.app)}/${esc(f.key)}</span>`], ["Why", (f) => esc(f.why), "muted"]])}` : ""}
    ${c.overruns.length ? `<h2>Apps that would go over their allocation</h2>${c.overruns.map((o, i) => `<label class="field"><input type="checkbox" data-raise="${esc(o.app)}" checked>
      Raise <b>${esc(o.name)}</b> from ${gbv(o.alloc)} to ${gbv(o.raise_to)} (it would use ${fb(o.would_use)})</label>`).join("")}` : ""}
    <p class="muted">The SD pool will be ${c.sd_pool_gb} GB, from this card's size.</p></div>
    <div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn primary" id="pb-go">Put back to work</button></div>`, true);
  $("m-no").onclick = closeModal;
  $("pb-go").onclick = async () => {
    const raise = [...$("modal-box").querySelectorAll("[data-raise]")].filter((x) => x.checked).map((x) => x.dataset.raise);
    closeModal();
    if (await act(() => api("POST", `/api/cards/${cardId}/activate`, { raise }), "Putting the card back to work…")) again();
  };
}

RENDER.storage = async (arg, ticket) => {
  const [d, cd] = await Promise.all([api("GET", "/api/storage"), api("GET", "/api/cards")]);
  if (stale(ticket)) return;
  const { internal: I, sd: S, pool: P } = d;
  const pct = (x, cap) => (x / cap * 100).toFixed(2);
  const left = d.growth_per_day > 0 ? (P.sd_limit - P.sd_used) / d.growth_per_day : null;
  $("p-storage").innerHTML = `
    <h1>Storage</h1><p class="sub">New files land on internal storage. Files unchanged for an app's move age go to the SD card each night.</p>
    <div class="grid g2">
      ${cardsPanel(cd)}
      <div class="panel span2"><h3>Storage pool <span class="r"><a href="#settings">Pool settings</a></span></h3><div class="tw"><table>
        <thead><tr><th>Storage</th><th class="num">Pool limit</th><th class="num">Used by Pocket</th><th style="width:30%">Pool use</th><th class="num">Room left in pool</th></tr></thead>
        <tbody>
          <tr><td>Internal storage</td><td class="num">${gbv(P.int_limit)}</td><td class="num">${fb(P.int_used)}</td><td>${allocCell(P.int_used / P.int_limit * 100, 80)}</td><td class="num">${fb(Math.max(0, P.int_limit - P.int_used))}</td></tr>
          ${S.present ? `<tr><td>SD card</td><td class="num">${gbv(P.sd_limit)}</td><td class="num">${fb(P.sd_used)}</td><td>${allocCell(P.sd_used / P.sd_limit * 100, 80)}</td><td class="num">${fb(Math.max(0, P.sd_limit - P.sd_used))}</td></tr>`
            : `<tr><td>SD card</td><td colspan="4" class="muted">no card in use</td></tr>`}
          <tr><td><b>Total</b></td><td class="num"><b>${gbv(P.total)}</b></td><td class="num"><b>${fb(P.used)}</b></td><td>${allocCell(P.used / P.total * 100, 80)}</td><td class="num"><b>${fb(Math.max(0, P.total - P.used))}</b></td></tr>
        </tbody></table></div>
        <div class="body muted" style="border-top:1px solid var(--line)">${gbv(P.promised)} of the pool is promised to apps and ${gbv(P.unpromised)} is unpromised. ${gbv(P.reserve)} of internal storage is always left free for Android.${P.internal_full ? ` <span class="warn">Internal pool is full: new files go to the SD card.</span>` : ""}</div>
      </div>
      <div class="panel"><h3>Internal storage <span class="r">${fb(I.total)}</span></h3><div class="body">
        <div class="segw"><div class="seg"><i style="width:${pct(I.other, I.total)}%;background:${C.grey}"></i><i style="width:${pct(I.data, I.total)}%;background:${C.blue}"></i><i style="width:${pct(I.system, I.total)}%;background:${C.purple}"></i></div><b class="lim" style="left:${Math.min(100, (I.other + P.int_limit) / I.total * 100)}%"></b></div>
        ${legend(C.grey, "Android and other apps", fb(I.other))}
        ${legend(C.blue, "Pocket data (recent files)", fb(I.data))}
        ${legend(C.purple, "Pocket logs, statistics and undo", fb(I.system))}
        ${legend(C.free, "Free", fb(I.free))}
        ${legend(C.white, "Pocket pool limit (white line)", gbv(P.int_limit))}
        <p class="muted" style="margin:10px 0 0">Early move starts when the internal pool is ${d.early_move_pct}% full.</p>
      </div></div>
      ${!S.present ? `<div class="panel"><h3>SD card</h3><div class="body muted">No SD card in use. See SD cards above.</div></div>` : `
      <div class="panel"><h3>SD card <span class="r">${fb(S.total)}</span></h3><div class="body">
        <div class="segw"><div class="seg"><i style="width:${pct(S.other, S.total)}%;background:${C.grey}"></i><i style="width:${pct(S.data, S.total)}%;background:${C.green}"></i><i style="width:${pct(S.copy, S.total)}%;background:${C.yellow}"></i><i style="width:${pct(S.undo, S.total)}%;background:${C.orange}"></i></div><b class="lim" style="left:${Math.min(100, (S.other + P.sd_limit) / S.total * 100)}%"></b></div>
        ${legend(C.grey, "Other files on the card", fb(S.other))}
        ${legend(C.green, "Pocket data (moved files)", fb(S.data))}
        ${legend(C.yellow, "Nightly copy of recent files", fb(S.copy))}
        ${legend(C.orange, "Undo", fb(S.undo))}
        ${legend(C.free, "Free", fb(S.free))}
        ${legend(C.white, "Pocket pool limit (white line)", gbv(P.sd_limit))}
        <p class="muted" style="margin:10px 0 0">${left ? `At the recent growth (${fb(d.growth_per_day)} a day), Pocket's SD card pool fills in about ${left > 730 ? (left / 365).toFixed(0) + " years" : Math.round(left) + " days"}.` : "Not enough history yet to estimate when the SD card pool fills."}</p>
      </div></div>`}
      <div class="panel span2"><h3>Pocket data over time <span class="r">last 30 days</span></h3>${d.growth.length < 2
        ? `<p class="muted">${d.growth.length ? `One day of history so far: ${fb(d.growth[0].sd)} on the SD card, ${fb(d.growth[0].int)} on internal storage. The chart starts after the second day.` : "No history yet."}</p>`
        : `<div class="chart" id="ch-growth"></div>`}</div>
      <div class="panel span2"><h3>Where each app's data is</h3><div class="tw"><table>
        <thead><tr><th>App</th><th class="num">Internal</th><th class="num">SD card</th><th class="num">Total</th><th class="num">Files</th><th class="num">Move after</th><th style="width:22%">On internal</th></tr></thead>
        <tbody>${d.apps.length ? d.apps.map((a) => `<tr class="click" onclick="go('apps/${a.id}')"><td>${esc(a.name)}</td><td class="num">${fb(a.int)}</td><td class="num">${fb(a.sd)}</td><td class="num">${fb(a.int + a.sd)}</td><td class="num">${n(a.files)}</td><td class="num">${a.move_days} days</td>
          <td><div class="bar"><i style="width:${((a.int / (a.int + a.sd || 1)) * 100).toFixed(1)}%;background:${C.blue}"></i></div></td></tr>`).join("") : `<tr><td class="muted">No apps yet.</td></tr>`}</tbody>
      </table></div></div>
    </div>`;
  bindCards($("p-storage"), cd, () => RENDER.storage("", TICKET));
  const ts = d.growth.map((g) => new Date(g.day + "T12:00:00").getTime() / 1000);
  chart("growth", $("ch-growth"), [{ label: "SD card", color: C.green, fill: "rgba(115,191,105,0.08)" }, { label: "Internal", color: C.blue, fill: "rgba(87,148,242,0.08)" }],
    [ts, d.growth.map((g) => g.sd / GB), d.growth.map((g) => g.int / GB)], { fmt: (v) => v.toFixed(2) + " GB", axisFmt: (v) => +v.toFixed(2) + " GB" });
};

// ---- apps
function allocSlider(a) {
  const min = Math.max(0.5, Math.ceil(a.used / GB * 2) / 2);
  const max = Math.max(min, Math.floor((a.alloc_max ?? a.alloc + STATE.unpromised) / GB * 2) / 2);
  return slider({ min, max, step: 0.5, value: a.alloc / GB, unit: "GB", cls: "sl-alloc",
    marks: [{ v: a.used / GB, label: "in use " + fb(a.used), color: C.muted }, { k: "warn", v: a.alloc / GB * a.warn_pct / 100, label: "warning", color: C.yellow }] });
}
async function openAlloc(id) {
  const a = await act(() => api("GET", "/api/apps/" + id));
  if (!a) return;
  STATE.unpromised = a.unpromised;
  openModal(`<h3>Allocation · ${esc(a.name)}</h3>
    <div class="body" data-ag data-used="${a.used}" data-warn="${a.warn_pct}">${allocSlider(a)}
      <p class="sl-preview" style="margin:10px 0 0"></p>
      <p class="faint" style="margin:6px 0 0">Up to ${gbv(a.alloc_max)}: this app's allocation plus the ${gbv(a.unpromised)} of the pool not yet promised to any app. It can't go below what the app already holds.</p></div>
    <div class="foot"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" id="m-save">Save</button></div>`);
  refreshGroup($("modal-box").querySelector("[data-ag]"));
  $("m-save").onclick = async () => {
    const alloc = Math.round(slVal($("modal-box").querySelector(".sl-alloc")) * GB);
    if (await act(() => api("PATCH", "/api/apps/" + id, { alloc }), "Allocation saved.")) { closeModal(); route(); }
  };
}
RENDER.apps = async (id, ticket) => {
  if (id) return renderApp(id, ticket);
  const d = await api("GET", "/api/apps");
  if (stale(ticket)) return;
  STATE.unpromised = d.pool.unpromised;
  const P = d.pool;
  $("p-apps").innerHTML = `
    <h1>Apps</h1><p class="sub">Apps appear here the first time they connect from any device. New apps get ${d.defaults.alloc_gb} GB, a warning at ${d.defaults.warn_pct}% and ${d.defaults.at_limit === "refuse" ? "refused uploads" : "a warning only"} at 100%. Click an allocation bar to change it.</p>
    <div class="panel" style="margin-bottom:12px"><div class="body" style="display:flex;gap:28px;flex-wrap:wrap;align-items:center">
      <span><b>${gbv(P.promised)}</b> <span class="muted">promised to apps out of the</span> <b>${gbv(P.total)}</b> <span class="muted">pool</span></span>
      <span><b>${gbv(P.unpromised)}</b> <span class="muted">unpromised</span></span>
      <span style="flex:1;min-width:160px"><div class="bar"><i style="width:${(P.promised / P.total * 100).toFixed(1)}%;background:${C.blue}"></i></div></span>
      <a href="#settings">Pool settings</a> <button class="btn" id="new-app">New app</button>
    </div></div>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>App</th><th>Devices</th><th class="num">Used</th><th style="width:18%">Allocation</th><th class="num">Requests 24 h</th><th class="num">In / out 24 h</th><th class="num">Errors</th><th>Last used</th></tr></thead>
      <tbody>${d.apps.length ? d.apps.map((a) => { const pct = a.used / (a.alloc || 1) * 100;
        return `<tr class="click" onclick="go('apps/${a.id}')">
        <td>${esc(a.name)}${a.status !== "active" ? ' <span class="warn">waiting</span>' : ""}<div class="faint mono">${a.id}</div></td>
        <td class="wrap">${a.devices.map((x) => esc(x.name)).join(", ") || '<span class="muted">none yet</span>'}</td>
        <td class="num">${fb(a.used)}<div class="faint">of ${gbv(a.alloc)}</div></td>
        <td><div class="alloc-click" title="Change allocation" onclick="event.stopPropagation();openAlloc('${a.id}')">${allocCell(pct, a.warn_pct)}</div></td>
        <td class="num">${n(a.requests)}</td><td class="num">${fb(a.bytes_in)} / ${fb(a.bytes_out)}</td>
        <td class="num ${a.errors ? "warn" : "muted"}">${n(a.errors)}</td><td class="muted">${ago(a.last_used)}</td></tr>`; }).join("")
        : `<tr><td colspan="8" class="muted">No apps yet. An app appears here the first time it connects, or create one with New app.</td></tr>`}</tbody>
    </table></div></div>`;
  $("new-app").onclick = async () => {
    const id = await promptBox("New app", "App name (lowercase letters, digits and dashes)", "", "Create");
    if (id && await act(() => api("POST", "/api/apps", { id: id.trim(), name: id.trim() }), "App created.")) go("apps/" + id.trim());
  };
};

async function renderApp(id, ticket) {
  const a = await api("GET", "/api/apps/" + id);
  if (stale(ticket)) return;
  STATE.unpromised = a.unpromised;
  const pct = a.used / (a.alloc || 1) * 100;
  const s = $("p-apps");
  const creator = a.devices.find((d) => d.uid === a.created_by)?.name || a.created_by;
  s.innerHTML = `
    <a class="back" href="#apps">← Apps</a>
    <h1>${esc(a.name)}</h1>
    <p class="sub"><span class="mono">${a.id}</span> · created ${dt(a.created_at)} ${a.created_by === "control panel" ? "in the control panel" : "when it first connected from " + esc(creator)}${a.notes ? " · " + esc(a.notes) : ""}</p>
    ${a.status !== "active" ? `<div class="panel" style="border-color:#6b5a12;margin-bottom:12px"><div class="body"><span class="warn">This app is waiting for approval.</span> <button class="btn sm primary" id="approve-app">Allow it</button></div></div>` : ""}
    ${pct >= a.warn_pct ? `<div class="panel" style="border-color:#6b5a12;margin-bottom:12px"><div class="body"><span class="${pct >= 100 ? "bad" : "warn"}">At ${pct.toFixed(0)}% of its ${gbv(a.alloc)} allocation.</span> ${a.at_limit === "refuse" ? `Uploads are refused at ${gbv(a.alloc)}.` : "Uploads continue past the allocation (warn only)."} <a onclick="openAlloc('${a.id}')">Raise the allocation</a></div></div>` : ""}
    <div class="grid g4">
      ${stat("Used", fb(a.used), `of ${gbv(a.alloc)} · ${n(a.files)} files`)}
      ${stat("Shared space", fb(a.shared), a.devices.length === 1 ? "used by its one device" : a.devices.length === 2 ? "used by both devices" : a.devices.length ? `used by all ${a.devices.length} devices` : "no devices yet")}
      ${stat("Requests, last 24 h", n(a.requests), `${n(a.errors)} errors`)}
      ${stat("Traffic, last 24 h", fb(a.bytes_in + a.bytes_out), `${fb(a.bytes_in)} in · ${fb(a.bytes_out)} out`)}
    </div>
    <div class="grid g2" style="margin-top:12px">
      <div class="panel span2"><h3>Devices <span class="r">each has its own key and private <span class="mono">device</span> space</span></h3><div class="tw"><table>
        <thead><tr><th>Device</th><th>Address</th><th>Key</th><th class="num">Private space</th><th class="num">Requests 24 h</th><th>Last seen</th><th></th></tr></thead>
        <tbody>${a.devices.length ? a.devices.map((d) => `<tr><td>${esc(d.name)}${d.blocked ? ' <span class="bad">blocked</span>' : ""}</td><td class="mono muted">${esc(d.ip)}${d.ip_alt ? ` · ${esc(d.ip_alt)}` : ""}</td><td class="mono">${esc(d.key)}</td>
          <td class="num">${fb(d.private)}</td><td class="num">${n(d.requests)}</td><td class="muted">${ago(d.last_seen)}</td>
          <td class="actions"><button class="btn sm" data-dev-key="${d.uid}">New key</button><button class="btn sm danger" data-dev-rm="${d.uid}" data-name="${esc(d.name)}">Remove</button></td></tr>`).join("")
          : `<tr><td colspan="7" class="muted">No devices yet.</td></tr>`}</tbody>
      </table></div></div>
      <div class="panel span2"><h3>Requests per day, by kind <span class="r">last 7 days</span></h3><div class="chart" id="ch-app"></div></div>
      <div class="panel span2"><h3>Settings for this app</h3><div class="form" id="app-form">
        ${frow("Display name", "", `<input type="text" id="f-name" value="${esc(a.name)}" style="width:220px">`)}
        ${frow("Notes", "", `<input type="text" id="f-notes" value="${esc(a.notes)}" style="width:100%">`)}
        <div data-ag data-used="${a.used}" data-devices="${a.devices.length || 1}">
        ${frow("Allocation", `Shared and private spaces together · up to ${gbv(a.alloc_max)} (this allocation + the pool's unpromised space)`, allocSlider(a))}
        <div class="row"><span></span><div class="c"><div class="sl-preview"></div></div></div>
        ${frow("Warn at", "", slider({ min: 50, max: 95, step: 5, value: a.warn_pct, unit: "%", cls: "sl-warn" }))}
        ${frow("At 100%", "", `<select id="f-limit"><option value="refuse"${a.at_limit === "refuse" ? " selected" : ""}>Refuse uploads</option><option value="warn"${a.at_limit === "warn" ? " selected" : ""}>Warn only</option></select>`)}
        ${frow("Cap per device", "Limit on each private space · left end = none", slider({ min: 0, max: a.alloc / GB, step: 0.5, value: (a.device_cap || 0) / GB, unit: "GB", none: true, cls: "sl-cap" }))}
        ${frow("Reserve per device", "Room each device can always use, whatever the others took · left end = none", slider({ min: 0, max: a.alloc / GB / (a.devices.length || 1), step: 0.25, value: (a.device_reserve || 0) / GB, unit: "GB", none: true, cls: "sl-res" }))}
        </div>
        ${frow("Move to SD card after", "Days without changes", slider({ min: 1, max: 60, value: a.move_days, unit: "days", cls: "sl-move" }))}
        ${a.is_family ? "" : frow("Family bucket", "A third bucket, <span class='mono'>family</span>, shared by every app that is in the family", `<input type="checkbox" id="f-family"${a.family ? " checked" : ""}>`)}
        <div class="row"><span></span><div class="c"><button class="btn primary" id="f-save">Save</button><button class="btn" id="f-cancel">Cancel</button></div></div>
      </div></div>
      <div class="panel span2"><h3>Spaces</h3><div class="tw"><table>
        <thead><tr><th>Bucket seen by the app</th><th>Where</th><th class="num">Size</th></tr></thead>
        <tbody><tr><td class="mono">shared</td><td class="muted">all devices</td><td class="num">${fb(a.shared)}</td></tr>
        ${a.devices.map((d) => `<tr><td class="mono">device</td><td class="muted">${esc(d.name)}</td><td class="num">${fb(d.private)}</td></tr>`).join("")}</tbody>
      </table></div>
      <h3 style="border-top:1px solid var(--line)">Actions</h3><div class="body actions">
        <a class="btn" href="#files/${a.id}">Browse files</a><a class="btn" href="#logs/${a.id}">Request log</a>
        <button class="btn" id="a-keys">New keys for all devices</button><button class="btn danger" id="a-empty">Empty app</button>${a.is_family ? "" : `<button class="btn danger" id="a-del">Delete app</button>`}
      </div></div>
    </div>`;
  refreshGroup(s.querySelector("[data-ag]"));
  chart("app", $("ch-app"), [["Reads", "reads", C.green], ["Writes", "writes", C.blue], ["Listings", "listings", C.yellow], ["Deletes", "deletes", C.red]].map(([l, , c]) => ({ label: l, color: c })),
    [a.days.map((d) => new Date(d + "T12:00:00").getTime() / 1000), a.series.reads, a.series.writes, a.series.listings, a.series.deletes], { daily: true });
  $("f-save").onclick = async () => {
    const f = s.querySelector("#app-form");
    const moved = (cls) => f.querySelector(cls).dataset.moved ? f.querySelector(cls) : null;   // unmoved sliders aren't sent
    const body = { name: $("f-name").value, notes: $("f-notes").value, at_limit: $("f-limit").value };
    if (moved(".sl-alloc")) body.alloc = Math.round(slVal(moved(".sl-alloc")) * GB);
    if (moved(".sl-warn")) body.warn_pct = slVal(moved(".sl-warn"));
    if (moved(".sl-move")) body.move_days = slVal(moved(".sl-move"));
    if (moved(".sl-cap")) body.device_cap = Math.round(slVal(moved(".sl-cap")) * GB) || null;
    if (moved(".sl-res")) body.device_reserve = Math.round(slVal(moved(".sl-res")) * GB) || null;
    if ($("f-family") && $("f-family").checked !== a.family) body.family = $("f-family").checked;
    if (await act(() => api("PATCH", "/api/apps/" + a.id, body), "Settings saved.")) route();
  };
  $("f-cancel").onclick = () => route();
  if ($("approve-app")) $("approve-app").onclick = async () => { if (await act(() => api("PATCH", "/api/apps/" + a.id, { status: "active" }), "App allowed.")) route(); };
  s.querySelectorAll("[data-dev-key]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("New key", "The device gets a new key; the old one stops working. The Pocket library on that device picks up the new key by itself.", "Make new key"))
      if (await act(() => api("POST", `/api/apps/${a.id}/devices/${b.dataset.devKey}/rotate-key`), "New key made.")) route();
  });
  s.querySelectorAll("[data-dev-rm]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Remove device", `Remove <b>${b.dataset.name}</b> from ${esc(a.name)}? Its private files go into the undo. If the app runs there again, it enrolls as a new device.`, "Remove", true))
      if (await act(() => api("DELETE", `/api/apps/${a.id}/devices/${b.dataset.devRm}`), "Device removed.")) route();
  });
  $("a-keys").onclick = async () => { if (await confirmBox("New keys for all devices", "Every device of this app gets a new key; the libraries pick them up by themselves.", "Make new keys")) act(() => api("POST", `/api/apps/${a.id}/rotate-keys`), "New keys made."); };
  $("a-empty").onclick = async () => {
    if (await confirmBox("Empty app", `Delete all ${n(a.files)} files of <b>${esc(a.name)}</b>? They go into the undo first. The app and its devices stay.`, "Empty app", true))
      if (await act(() => api("POST", `/api/apps/${a.id}/empty`), (r) => `${n(r.files)} files moved to the undo.`)) route();
  };
  if ($("a-del")) $("a-del").onclick = async () => {
    if (await confirmBox("Delete app", `Delete <b>${esc(a.name)}</b>, its devices and keys? Its files go into the undo; the app itself can't be brought back. Its ${gbv(a.alloc)} return to the pool.`, "Delete app", true))
      if (await act(() => api("DELETE", "/api/apps/" + a.id), "App deleted.")) go("apps");
  };
}

// ---- devices
RENDER.devices = async (arg, ticket) => {
  const d = await api("GET", "/api/devices");
  if (stale(ticket)) return;
  const s = $("p-devices");
  s.innerHTML = `
    <h1>Devices</h1><p class="sub">Every device that has connected, identified by its address on your private network. A device's keys only work from that address.</p>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>Device</th><th>Address</th><th>Apps</th><th class="num">Requests 24 h</th><th class="num">In / out 24 h</th><th>Last seen</th><th></th></tr></thead>
      <tbody>${d.devices.length ? d.devices.map((x) => `<tr><td><span class="dot" style="background:${x.blocked ? C.red : x.last_seen && Date.now() / 1000 - x.last_seen < 900 ? C.green : C.grey}"></span>${esc(x.name)}${x.blocked ? ' <span class="bad">blocked</span>' : ""}<div class="faint mono">${esc(x.uid)}</div></td>
        <td class="mono muted">${esc(x.ip)}${x.ip_alt ? ` · ${esc(x.ip_alt)}` : ""}</td>
        <td class="wrap">${x.apps.map((ap) => `<a href="#apps/${ap.id}">${esc(ap.name)}</a>`).join(", ")}</td>
        <td class="num">${n(x.requests)}</td><td class="num">${fb(x.bytes_in)} / ${fb(x.bytes_out)}</td><td class="muted">${ago(x.last_seen)}</td>
        <td class="actions"><button class="btn sm" data-ren="${x.uid}" data-name="${esc(x.name)}">Rename</button><button class="btn sm" data-blk="${x.uid}" data-b="${x.blocked ? 0 : 1}">${x.blocked ? "Unblock" : "Block"}</button><button class="btn sm danger" data-rm="${x.uid}" data-name="${esc(x.name)}">Remove</button></td></tr>`).join("")
        : `<tr><td colspan="7" class="muted">No devices yet.</td></tr>`}</tbody>
    </table></div></div>`;
  s.querySelectorAll("[data-ren]").forEach((b) => b.onclick = async () => {
    const name = await promptBox("Rename device", "Name shown in the control panel", b.dataset.name);
    if (name && await act(() => api("POST", `/api/devices/${b.dataset.ren}/rename`, { name }), "Device renamed.")) route();
  });
  s.querySelectorAll("[data-blk]").forEach((b) => b.onclick = async () => {
    const blocking = b.dataset.b === "1";
    if (!blocking || await confirmBox("Block device", "Every app on this device is refused until you unblock it.", "Block", true))
      if (await act(() => api("POST", `/api/devices/${b.dataset.blk}/block`, { blocked: blocking }), blocking ? "Device blocked." : "Device unblocked.")) route();
  });
  s.querySelectorAll("[data-rm]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Remove device", `Remove <b>${b.dataset.name}</b> from every app? Its private files go into the undo.`, "Remove", true))
      if (await act(() => api("DELETE", `/api/devices/${b.dataset.rm}`), "Device removed.")) route();
  });
};

// ---- files
const FILES = { app: null, space: "shared", path: "", tab: "browse", card: null, cardApp: null, cardSpace: "shared", cardPath: "" };
RENDER.files = async (arg, ticket) => {
  const list = await api("GET", "/api/apps");
  if (stale(ticket)) return;
  if (arg && list.apps.some((a) => a.id === arg)) { Object.assign(FILES, { app: arg, space: "shared", path: "", tab: "browse" }); setAddress("files", true); }
  if (!FILES.app || !list.apps.some((a) => a.id === FILES.app)) FILES.app = list.apps[0]?.id || null;
  const s = $("p-files");
  const head = `<h1>Files</h1><p class="sub">Browse any app's spaces. Files show which storage they are on right now.</p>
    <div class="tabs"><a data-tab="browse" class="${FILES.tab === "browse" ? "on" : ""}">Browse</a><a data-tab="undo" class="${FILES.tab === "undo" ? "on" : ""}">Undo</a><a data-tab="cards" class="${FILES.tab === "cards" ? "on" : ""}">SD cards</a></div>`;
  const tabs = () => s.querySelectorAll("[data-tab]").forEach((t) => t.onclick = () => { FILES.tab = t.dataset.tab; route("files"); });
  if (FILES.tab === "cards") { await renderCardFiles(s, head, tabs, list, ticket); return; }
  if (FILES.tab === "undo") {
    const u = await api("GET", "/api/undo");
    if (stale(ticket)) return;
    const names = Object.fromEntries(list.apps.map((a) => [a.id, a.name]));
    s.innerHTML = head + `<p class="muted">Deleted and overwritten files are kept for ${u.undo_days} days.</p><div class="panel"><div class="tw"><table>
      <thead><tr><th>App</th><th>Space</th><th>File</th><th class="num">Size</th><th>Deleted</th><th>Why</th><th>By</th><th class="num">Kept for</th><th></th></tr></thead>
      <tbody>${u.items.length ? u.items.map((e, i) => `<tr><td>${esc(names[e.app] || e.app)}</td><td class="mono muted">${e.space === "shared" ? "shared" : "device"}</td><td class="mono">${esc(e.key)}</td>
        <td class="num">${fb(e.size)}</td><td class="muted">${dt(new Date(e.time).getTime() / 1000)}</td><td class="muted">${esc(e.reason)}</td><td class="muted">${esc(e.device_name || "")}</td>
        <td class="num ${e.days_left <= 3 ? "warn" : "muted"}">${e.days_left} days</td><td><button class="btn sm" data-restore="${i}">Restore</button></td></tr>`).join("")
        : `<tr><td colspan="9" class="muted">Nothing in the undo.</td></tr>`}</tbody></table></div></div>`;
    tabs();
    s.querySelectorAll("[data-restore]").forEach((b) => b.onclick = async () => {
      const e = u.items[+b.dataset.restore];
      if (await act(() => api("POST", "/api/undo/restore", { storage: e.storage, path: e.path }), `Restored ${e.key}.`)) route("files");
    });
    return;
  }
  if (!FILES.app) { s.innerHTML = head + `<p class="muted">No apps yet.</p>`; tabs(); return; }
  const app = list.apps.find((a) => a.id === FILES.app);
  const spaces = [["shared", "shared · all devices"], ...app.devices.map((d) => ["device:" + d.uid, "device · " + d.name])];
  if (!spaces.some((x) => x[0] === FILES.space)) FILES.space = "shared";
  const q = new URLSearchParams({ app: FILES.app, space: FILES.space, path: FILES.path });
  const d = await api("GET", "/api/files?" + q);
  if (stale(ticket)) return;
  const parts = FILES.path.split("/").filter(Boolean);
  const dl = (key) => "/api/files/download?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key, token: TOKEN });
  s.innerHTML = head + `
    <div class="filters">
      <select id="f-app">${list.apps.map((x) => `<option value="${x.id}"${x.id === FILES.app ? " selected" : ""}>${esc(x.name)}</option>`).join("")}</select>
      <select id="f-space">${spaces.map(([v, l]) => `<option value="${v}"${v === FILES.space ? " selected" : ""}>${esc(l)}</option>`).join("")}</select>
      <span style="flex:1"></span><button class="btn" id="f-up">Upload</button><button class="btn" id="f-dir">New folder</button>
    </div>
    <div class="crumbs"><a data-path="">${FILES.space === "shared" ? "shared" : "device"}</a>
      ${parts.map((p, i) => `<span>/</span><a data-path="${esc(parts.slice(0, i + 1).join("/"))}">${esc(p)}</a>`).join("")}</div>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>Name</th><th class="num">Size</th><th>Modified</th><th>Stored on</th><th></th></tr></thead>
      <tbody>${d.items.length ? d.items.map((x) => x.type === "dir"
        ? `<tr class="click" data-dir="${esc(x.name)}"><td>${esc(x.name)}/</td><td class="num muted">${fb(x.size)}</td><td class="muted">${n(x.files)} files</td><td class="muted">${x.storages.length > 1 ? "Internal + SD card" : x.storages[0] === "sd" ? "SD card" : "Internal"}</td><td></td></tr>`
        : `<tr><td>${esc(x.name)}</td><td class="num">${fb(x.size)}</td><td class="muted">${dt(x.modified)}</td>
           <td><span class="sw" style="background:${x.storage === "sd" ? C.green : C.blue}"></span>${x.storage === "sd" ? "SD card" : "Internal"}</td>
           <td class="actions"><a class="btn sm" href="${dl(x.key)}" download>Download</a><button class="btn sm danger" data-del="${esc(x.key)}">Delete</button></td></tr>`).join("")
        : `<tr><td colspan="5" class="muted">This folder is empty.</td></tr>`}</tbody>
    </table></div></div>`;
  tabs();
  $("f-app").onchange = (e) => { Object.assign(FILES, { app: e.target.value, space: "shared", path: "" }); route("files"); };
  $("f-space").onchange = (e) => { Object.assign(FILES, { space: e.target.value, path: "" }); route("files"); };
  s.querySelectorAll("[data-path]").forEach((a) => a.onclick = () => { FILES.path = a.dataset.path; route("files"); });
  s.querySelectorAll("[data-dir]").forEach((r) => r.onclick = () => { FILES.path = [...parts, r.dataset.dir].join("/"); route("files"); });
  s.querySelectorAll("[data-del]").forEach((b) => b.onclick = async () => {
    if (await confirmBox("Delete file", `Delete <b>${esc(b.dataset.del)}</b>? It stays in the undo for a while.`, "Delete", true))
      if (await act(() => api("DELETE", "/api/files?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key: b.dataset.del })), "File deleted.")) route("files");
  });
  $("f-dir").onclick = async () => {
    const name = await promptBox("New folder", "Folder name", "", "Create");
    if (name && await act(() => api("POST", "/api/files/folder", { app: FILES.app, space: FILES.space, path: [...parts, name.trim()].join("/") }), "Folder created.")) route("files");
  };
  $("f-up").onclick = () => { const pick = $("file-pick"); pick.value = ""; pick.onchange = async () => {
    const f = pick.files[0]; if (!f) return;
    const key = [...parts, f.name].join("/");
    toast(`Uploading ${f.name}…`);
    if (await act(() => api("PUT", "/api/files/upload?" + new URLSearchParams({ app: FILES.app, space: FILES.space, key }), undefined, f), `Uploaded ${f.name}.`)) route("files");
  }; pick.click(); };
};

// Files › SD cards: what is on shelved cards (from the list kept when each was shelved), restoring single files
// while that card is in the phone, and files of deleted apps or removed devices put aside on the card in use.
async function renderCardFiles(s, head, tabs, list, ticket) {
  const [cd, un] = await Promise.all([api("GET", "/api/cards"), api("GET", "/api/cards/unassigned")]);
  if (stale(ticket)) return;
  const names = Object.fromEntries(list.apps.map((a) => [a.id, a.name]));
  const cards = cd.shelf;
  if (!cards.some((c) => c.id === FILES.card)) Object.assign(FILES, { card: cards[0]?.id || null, cardApp: null, cardPath: "" });
  let browse = `<p class="muted">No cards on the shelf yet. A card goes on the shelf when you swap it out (Storage › SD cards).</p>`;
  if (FILES.card) {
    const q = new URLSearchParams({ app: FILES.cardApp || "", space: FILES.cardSpace, path: FILES.cardPath });
    const d = await api("GET", `/api/cards/${FILES.card}/files?` + q);
    if (stale(ticket)) return;
    const card = cards.find((c) => c.id === FILES.card);
    const inserted = cd.slot && cd.slot.card && cd.slot.card.id === FILES.card && ["archive", "shelved_here"].includes(cd.slot.state);
    const pick = `<div class="filters"><select id="fc-card">${cards.map((c) => `<option value="${c.id}"${c.id === FILES.card ? " selected" : ""}>${esc(c.name)} · shelved ${dt(c.shelved)}</option>`).join("")}</select>
      <span class="muted">${inserted ? "This card is in the phone: files can be restored." : "This card is on the shelf: put it in the phone to restore files."}</span></div>`;
    if (!FILES.cardApp) {
      browse = pick + `<div class="panel"><div class="tw"><table><thead><tr><th>App</th><th>Space</th><th class="num">Files</th><th class="num">Size</th></tr></thead>
        <tbody>${d.spaces.length ? d.spaces.map((x, i) => `<tr class="click" data-sp="${i}"><td>${esc(x.name)}</td><td class="muted">${esc(spaceLabel(x.space))}</td><td class="num">${n(x.files)}</td><td class="num">${fb(x.size)}</td></tr>`).join("")
          : `<tr><td colspan="4" class="muted">No app files on ${esc(card.name)}.</td></tr>`}</tbody></table></div></div>`;
      s.innerHTML = head;
      s.insertAdjacentHTML("beforeend", browse + unassignedPanel(un, list));
      s.querySelectorAll("[data-sp]").forEach((r) => r.onclick = () => { const x = d.spaces[+r.dataset.sp]; Object.assign(FILES, { cardApp: x.app, cardSpace: x.space, cardPath: "" }); route("files"); });
    } else {
      const parts = FILES.cardPath.split("/").filter(Boolean);
      browse = pick + `<div class="crumbs"><a data-cpath="top">${esc(card.name)}</a><span>/</span><a data-cpath="">${esc(names[FILES.cardApp] || FILES.cardApp)} · ${esc(spaceLabel(FILES.cardSpace))}</a>
        ${parts.map((p, i) => `<span>/</span><a data-cpath="${esc(parts.slice(0, i + 1).join("/"))}">${esc(p)}</a>`).join("")}</div>
        <div class="panel"><div class="tw"><table><thead><tr><th>Name</th><th class="num">Size</th><th>Modified</th><th>Type</th><th></th></tr></thead>
        <tbody>${d.items.length ? d.items.map((x) => x.type === "dir"
          ? `<tr class="click" data-cdir="${esc(x.name)}"><td>${esc(x.name)}/</td><td class="num muted">${fb(x.size)}</td><td class="muted">${n(x.files)} files</td><td></td><td></td></tr>`
          : `<tr><td>${esc(x.name)}</td><td class="num">${fb(x.size)}</td><td class="muted">${dt(x.modified)}</td><td class="muted">${KIND_LABEL[x.kind] || ""}</td>
             <td class="actions">${inserted ? `<button class="btn sm" data-crestore="${esc(x.key)}">Restore</button>` : ""}</td></tr>`).join("")
          : `<tr><td colspan="5" class="muted">Empty.</td></tr>`}</tbody></table></div></div>`;
      s.innerHTML = head;
      s.insertAdjacentHTML("beforeend", browse + unassignedPanel(un, list));
      s.querySelectorAll("[data-cpath]").forEach((a) => a.onclick = () => { if (a.dataset.cpath === "top") FILES.cardApp = null; FILES.cardPath = a.dataset.cpath === "top" ? "" : a.dataset.cpath; route("files"); });
      s.querySelectorAll("[data-cdir]").forEach((r) => r.onclick = () => { FILES.cardPath = [...parts, r.dataset.cdir].join("/"); route("files"); });
      s.querySelectorAll("[data-crestore]").forEach((b) => b.onclick = async () => {
        const r = await act(() => api("POST", `/api/cards/${FILES.card}/restore`, { app: FILES.cardApp, space: FILES.cardSpace, key: b.dataset.crestore }));
        if (r) toast(r.restored ? `Restored ${b.dataset.crestore} to ${names[FILES.cardApp] || FILES.cardApp}.` : r.reason);
      });
    }
    $("fc-card").onchange = (e) => { Object.assign(FILES, { card: e.target.value, cardApp: null, cardPath: "" }); route("files"); };
  } else {
    s.innerHTML = head;
    s.insertAdjacentHTML("beforeend", browse + unassignedPanel(un, list));
  }
  tabs();
  s.querySelectorAll("[data-ua-give]").forEach((b) => b.onclick = async () => {
    const g = un.groups[+b.dataset.uaGive];
    openModal(`<h3>Give files to an app</h3><div class="body"><p>${n(g.files)} files of <b>${esc(g.app)}</b> (${esc(spaceLabel(g.space))}) go into the chosen app's shared space, under <span class="mono">unassigned/${esc(g.app)}/</span>.</p>
      <label class="field">App<select id="ua-to">${list.apps.map((a) => `<option value="${a.id}">${esc(a.name)}</option>`).join("")}</select></label></div>
      <div class="foot"><button class="btn" id="m-no">Cancel</button><button class="btn primary" id="m-yes">Give</button></div>`);
    $("m-no").onclick = closeModal;
    $("m-yes").onclick = async () => {
      const to = $("ua-to").value; closeModal();
      if (await act(() => api("POST", "/api/cards/unassigned/assign", { app: g.app, space: g.space, to }), (r) => `${n(r.files)} files given to ${names[to] || to}.`)) route("files");
    };
  });
  s.querySelectorAll("[data-ua-del]").forEach((b) => b.onclick = async () => {
    const g = un.groups[+b.dataset.uaDel];
    if (await confirmBox("Delete unassigned files", `Delete ${n(g.files)} files of <b>${esc(g.app)}</b>? They go into the card's undo first.`, "Delete", true))
      if (await act(() => api("POST", "/api/cards/unassigned/delete", { app: g.app, space: g.space }), "Files deleted.")) route("files");
  });
}

function unassignedPanel(un, list) {
  if (!un.groups.length) return "";
  return `<h2>Unassigned files on the card in use</h2><p class="muted">From a card put back to work: their app was deleted or their device removed while it was on the shelf. Apps don't see them.</p>
    <div class="panel"><div class="tw"><table><thead><tr><th>Former app</th><th>Space</th><th class="num">Files</th><th class="num">Size</th><th></th></tr></thead>
    <tbody>${un.groups.map((g, i) => `<tr><td class="mono">${esc(g.app)}</td><td class="muted">${esc(spaceLabel(g.space))}</td><td class="num">${n(g.files)}</td><td class="num">${fb(g.size)}</td>
      <td class="actions"><button class="btn sm" data-ua-give="${i}"${list.apps.length ? "" : " disabled"}>Give to an app</button><button class="btn sm danger" data-ua-del="${i}">Delete</button></td></tr>`).join("")}</tbody></table></div></div>`;
}

// ---- jobs
const RES = { ok: ["ok", "OK"], warn: ["warn", "Warning"], fail: ["bad", "Failed"] };
RENDER.jobs = async (arg, ticket) => {
  const d = await api("GET", "/api/jobs");
  if (stale(ticket)) return;
  const res = (r) => { const [c, t] = RES[r] || ["muted", r || "running"]; return `<span class="${c}">${t}</span>`; };
  const took = (j) => j.finished && j.started ? (j.finished - j.started < 60 ? `${(j.finished - j.started).toFixed(0)} s` : dur(j.finished - j.started)) : "";
  const s = $("p-jobs");
  s.innerHTML = `
    <h1>Jobs</h1><p class="sub">Scheduled work the phone does by itself. Times are set in Settings.</p>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>Job</th><th>Schedule</th><th>Last run</th><th>Result</th><th class="num">Took</th><th>Next run</th><th></th></tr></thead>
      <tbody>${d.jobs.map((j) => `<tr><td>${esc(j.name)}<div class="faint wrap">${esc(j.what)}</div></td><td class="muted">${esc(j.schedule)}</td>
        <td class="muted">${j.last ? dt(j.last.started) : "never"}</td>
        <td class="wrap">${j.running ? '<span class="muted">running…</span>' : j.last ? `${res(j.last.result)} <span class="muted">· ${esc(j.last.detail || "")}</span>` : ""}</td>
        <td class="num muted">${j.last ? took(j.last) : ""}</td><td class="muted">${dt(j.next)}</td>
        <td><button class="btn sm" data-run="${j.key}"${j.running ? " disabled" : ""}>Run now</button></td></tr>`).join("")}</tbody>
    </table></div></div>
    <h2>History</h2>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>Started</th><th>Job</th><th>Result</th><th class="num">Took</th></tr></thead>
      <tbody>${d.history.length ? d.history.map((j) => `<tr><td class="muted">${dt(j.started)}</td><td>${esc(j.job)}</td><td class="wrap">${res(j.result)} <span class="muted">· ${esc(j.detail || "")}</span></td><td class="num muted">${took(j)}</td></tr>`).join("")
        : `<tr><td colspan="4" class="muted">No jobs have run yet.</td></tr>`}</tbody>
    </table></div></div>`;
  s.querySelectorAll("[data-run]").forEach((b) => b.onclick = async () => {
    if (await act(() => api("POST", `/api/jobs/${b.dataset.run}/run`), "Started.")) { route("jobs"); setTimeout(() => STATE.page === "jobs" && route("jobs"), 2500); }
  });
};

// ---- alerts
RENDER.alerts = async (arg, ticket) => {
  const d = await api("GET", "/api/alerts");
  if (stale(ticket)) return;
  const s = $("p-alerts");
  s.innerHTML = `
    <h1>Alerts</h1><p class="sub">Alerts arrive as notifications on the phone (and in the Android app) and are listed here.</p>
    <div class="panel"><h3>Active</h3>${d.active.length ? d.active.map(alertLine).join("") : `<div class="empty">No active alerts.</div>`}</div>
    <h2>Rules</h2>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>On</th><th>Alert when</th><th>Threshold</th></tr></thead>
      <tbody>${d.rules.map((r) => `<tr><td style="width:40px"><input type="checkbox" data-rule="${r.kind}"${r.enabled ? " checked" : ""}></td><td>${esc(r.label)}</td>
        <td class="muted" style="width:46%">${r.threshold ? `<div class="thr"><span>${r.threshold.key.includes("low") || r.threshold.key.includes("free") ? "below" : "above"}</span>${slider({ min: r.threshold.min, max: r.threshold.max, step: r.threshold.step, value: r.threshold.value, unit: r.threshold.unit, compact: true, key: r.threshold.key })}</div>` : esc(r.text)}</td></tr>`).join("")}</tbody>
    </table></div><div class="body actions" style="border-top:1px solid var(--line)"><button class="btn primary" id="al-save">Save rules</button></div></div>
    <h2>History</h2>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th></th><th>When</th><th>Alert</th><th>Status</th></tr></thead>
      <tbody>${d.history.length ? d.history.map((a) => `<tr><td style="width:24px"><span class="dot" style="background:${lvlColor(a.level)}"></span></td><td class="muted">${dt(a.raised)}</td><td class="wrap">${esc(a.text)}</td><td class="${a.cleared ? "muted" : "warn"}">${a.cleared ? (a.cleared - a.raised < 1 ? "" : "cleared " + hm(a.cleared)) : "active"}</td></tr>`).join("")
        : `<tr><td colspan="4" class="muted">No alerts yet.</td></tr>`}</tbody>
    </table></div></div>`;
  $("al-save").onclick = async () => {
    const enabled = {}, thresholds = {};
    s.querySelectorAll("[data-rule]").forEach((c) => (enabled[c.dataset.rule] = c.checked));
    s.querySelectorAll(".sl[data-key]").forEach((el) => (thresholds[el.dataset.key] = slVal(el)));
    if (await act(() => api("PATCH", "/api/alerts", { enabled, thresholds }), "Alert rules saved.")) route("alerts");
  };
};

// ---- settings
const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
RENDER.settings = async (arg, ticket) => {
  const d = await api("GET", "/api/settings");
  if (stale(ticket)) return;
  const S = d.settings, P = d.pool;
  STATE.pool = P; STATE.settings = S;
  const s = $("p-settings");
  const sw = (key, on) => `<input type="checkbox" data-key="${key}"${on ? " checked" : ""}>`;
  s.innerHTML = `
    <h1>Settings</h1><p class="sub">Every setting Pocket Cloud has. Changes apply when saved; the storage server restarts by itself only if a change needs it.</p>
    <div class="panel"><h3>Connection</h3><div class="form">
      ${frow("Storage address", "What apps connect to", `<span class="mono">${esc(d.addresses.storage)}</span>`)}
      ${frow("Control panel address", "", `<span class="mono">${esc(d.addresses.panel)}</span>`)}
      ${frow("Enrollment secret", "Saved once on each machine; apps use it to enroll themselves", `<span class="mono" id="sec">••••••••••••••••••••••••</span><button class="btn sm" id="sec-show">Show</button><button class="btn sm" id="sec-copy">Copy</button><button class="btn sm danger" id="sec-new">Replace</button>`)}
      <div class="row"><div>Machine setup file<div class="h">Save as <span class="mono">%USERPROFILE%\\.pocket\\config</span> (Windows) or <span class="mono">~/.pocket/config</span> on a new machine; the Pocket library reads it</div></div>
        <div class="c" style="display:block"><pre class="block" id="mcfg">${esc(d.machine_config)}</pre><div style="margin-top:6px"><button class="btn sm" id="cfg-copy">Copy</button> <button class="btn sm" id="ci-copy">Copy as CI secrets</button></div></div></div>
    </div></div>
    <h2>Storage pool</h2>
    <div class="panel"><div class="form" data-pool>
      ${frow("Internal storage for Pocket", "Recent files, logs and statistics", slider({ min: Math.ceil(P.int_used / GB), max: Math.max(Math.ceil(P.int_used / GB), Math.floor(P.int_max / GB), Math.round(P.int_limit / GB)), value: Math.round(P.int_limit / GB), unit: "GB", cls: "sl-pint", key: "pool.internal_gb", marks: [{ v: P.int_used / GB, label: "in use " + fb(P.int_used), color: C.muted }] }))}
      ${frow("SD card for Pocket", "Older files, the nightly copy and the undo", slider({ min: Math.ceil(P.sd_used / GB), max: Math.max(Math.ceil(P.sd_used / GB), Math.floor(P.sd_max / GB), Math.round(P.sd_limit / GB)), value: Math.round(P.sd_limit / GB), unit: "GB", cls: "sl-psd", key: "pool.sd_gb", marks: [{ v: P.sd_used / GB, label: "in use " + fb(P.sd_used), color: C.muted }] }))}
      ${frow("Always leave free for Android", "Keeps the phone itself from running out of space", slider({ min: 2, max: 20, value: S.pool.reserve_gb, unit: "GB", cls: "sl-res", key: "pool.reserve_gb" }))}
      <div class="row"><span></span><div class="c"><div class="pool-preview muted"></div></div></div>
      ${frow("When the pool is full", "Applies to every app", `<span class="muted">New uploads are refused with a "pool full" error, and you are alerted</span>`)}
    </div></div>
    <h2>New apps</h2>
    <div class="panel"><div class="form">
      ${frow("Create apps automatically", "Otherwise new apps wait for you on the Apps page", sw("new_apps.auto_create", S.new_apps.auto_create))}
      ${frow("Default allocation", "If the pool has less unpromised, a new app gets what is left", slider({ min: 0.5, max: 20, step: 0.5, value: S.new_apps.alloc_gb, unit: "GB", key: "new_apps.alloc_gb" }))}
      ${frow("Warn at", "", slider({ min: 50, max: 95, step: 5, value: S.new_apps.warn_pct, unit: "%", key: "new_apps.warn_pct" }))}
      ${frow("At 100%", "", `<select data-key="new_apps.at_limit"><option value="refuse"${S.new_apps.at_limit === "refuse" ? " selected" : ""}>Refuse uploads</option><option value="warn"${S.new_apps.at_limit === "warn" ? " selected" : ""}>Warn only</option></select>`)}
      ${frow("Default move to SD card after", "", slider({ min: 1, max: 60, value: S.new_apps.move_days, unit: "days", key: "new_apps.move_days" }))}
    </div></div>
    <h2>Storage and nightly jobs</h2>
    <div class="panel"><div class="form">
      ${frow("Nightly jobs start at", "", `<input type="time" data-key="jobs.nightly_time" value="${S.jobs.nightly_time}">`)}
      ${frow("Move early when the internal pool is", "Full to this level · oldest files first", slider({ min: 50, max: 100, step: 5, value: S.pool.early_move_pct, unit: "% full", key: "pool.early_move_pct" }))}
      ${frow("Never move files changed in the last", "Protects files still being written", slider({ min: 1, max: 72, value: S.jobs.skip_changed_hours, unit: "hours", key: "jobs.skip_changed_hours" }))}
      ${frow("Keep deleted and overwritten files for", "The undo period", slider({ min: 1, max: 60, value: S.jobs.undo_days, unit: "days", key: "jobs.undo_days" }))}
      ${frow("Integrity check", "", `<select data-key="jobs.integrity_weekday">${DAYS.map((x, i) => `<option value="${i}"${i === S.jobs.integrity_weekday ? " selected" : ""}>${x}</option>`).join("")}</select> at <input type="time" data-key="jobs.integrity_time" value="${S.jobs.integrity_time}">`)}
      ${frow("SD card folder", "Where Pocket keeps its files on the card", `<span class="mono muted">${esc(S.sd_root)}</span>`)}
    </div></div>
    <h2>Logs and statistics</h2>
    <div class="panel"><div class="form">
      ${frow("Keep detailed request logs for", "", slider({ min: 7, max: 365, value: S.logs.request_days, unit: "days", key: "logs.request_days" }))}
      ${frow("Keep daily totals", "", `<span class="muted">Forever</span>`)}
      ${frow("Record file names in logs", "Off keeps only app, device, size and result", sw("logs.record_names", S.logs.record_names))}
    </div></div>
    <h2>Health checks</h2>
    <div class="panel"><div class="form">
      ${frow("Run every", "", slider({ min: 1, max: 30, value: S.health.every_min, unit: "minutes", key: "health.every_min" }))}
      ${frow("Alert thresholds", "Per alert, on the Alerts page", `<a href="#alerts">Edit alert rules</a>`)}
    </div></div>
    <h2>Server</h2>
    <div class="panel"><div class="form">
      ${frow("Read-only mode", "Apps can read but not write or delete", sw("server.read_only", S.server.read_only))}
      ${frow("Share links", "Apps can make links to single files for people outside Tailscale. Only GET /s/&lt;link&gt; is reachable through the Cloudflare tunnel (installed by setup.sh); a quick tunnel's address changes at every restart, a named tunnel keeps one.", sw("share.enabled", S.share.enabled))}
      ${frow("Push notices to apps", "The phone's built-in push server (port 2586, Tailscale only, ntfy-style): apps learn of changes at once instead of polling.", sw("push.enabled", S.push.enabled))}
      ${frow("Also listen on the home Wi-Fi", `Faster for devices at home${S.lan_address ? ` (${esc(S.lan_address)})` : " · no Wi-Fi address known yet"}. Anyone on the Wi-Fi can reach the storage port; every request still needs a device key. The control panel stays on Tailscale only.`, sw("lan.enabled", S.lan.enabled))}
      ${frow("Request timeout", "", slider({ min: 10, max: 300, step: 10, value: S.server.timeout_s, unit: "seconds", key: "server.timeout_s" }))}
      ${frow("Large upload buffer", "Memory for uploads sent in parts", slider({ min: 16, max: 256, step: 16, value: S.server.upload_buffer_mb, unit: "MB", key: "server.upload_buffer_mb" }))}
      ${frow("Unfinished uploads expire after", "", slider({ min: 12, max: 336, step: 12, value: S.server.multipart_expiry_h, unit: "hours", key: "server.multipart_expiry_h" }))}
      ${frow("Directory cache", "Refreshed automatically after each nightly move", slider({ min: 5, max: 300, step: 5, value: S.server.dir_cache_s, unit: "seconds", key: "server.dir_cache_s" }))}
      ${frow("Restart the storage server", "Running requests finish first", `<button class="btn" id="srv-restart">Restart</button>`)}
    </div></div>
    <h2>Control panel</h2>
    <div class="panel"><div class="form">
      ${frow("Admin password", "Changing it signs every browser and phone out", `<button class="btn" id="pw-change">Change password</button>`)}
      ${frow("Settings backup", "All settings, apps and device keys in one file", `<button class="btn" id="bk-get">Download backup</button><button class="btn" id="bk-put">Restore from file</button>`)}
    </div></div>
    <div class="actions" style="margin-top:16px"><button class="btn primary" id="st-save">Save changes</button><button class="btn" id="st-cancel">Discard</button></div>`;
  refreshPool(s.querySelector("[data-pool]"));
  let secret = null;
  const getSecret = async () => secret || (secret = await act(() => api("GET", "/api/settings/enroll-secret")));
  $("sec-show").onclick = async () => { const r = await getSecret(); if (r) { $("sec").textContent = r.enroll_secret; $("mcfg").textContent = r.machine_config; } };
  const copy = async (text, what) => { try { await navigator.clipboard.writeText(text); toast(what + " copied."); } catch { toast("Copying isn't allowed here; use Show and copy by hand.", true); } };
  $("sec-copy").onclick = async () => { const r = await getSecret(); if (r) copy(r.enroll_secret, "Enrollment secret"); };
  $("cfg-copy").onclick = async () => { const r = await getSecret(); if (r) copy(r.machine_config, "Machine setup file"); };
  $("ci-copy").onclick = async () => { const r = await getSecret(); if (r) copy(Object.entries(r.ci).map(([k, v]) => `${k}=${v}`).join("\n"), "CI secrets"); };
  $("sec-new").onclick = async () => {
    if (await confirmBox("Replace enrollment secret", "New machines then need the new secret. Apps already enrolled keep working.", "Replace", true)) {
      secret = await act(() => api("POST", "/api/settings/enroll-secret/rotate"), "Enrollment secret replaced.");
      if (secret) { $("sec").textContent = secret.enroll_secret; $("mcfg").textContent = secret.machine_config; }
    }
  };
  $("srv-restart").onclick = async () => { if (await confirmBox("Restart the storage server", "Running requests finish first; new ones wait a moment.", "Restart")) act(() => api("POST", "/api/server/restart"), "Storage server restarted."); };
  $("pw-change").onclick = () => {
    openModal(`<h3>Change admin password</h3><div class="body">
      <label class="field">Current password<input type="password" id="pw-old"></label>
      <label class="field">New password (at least 8 characters)<input type="password" id="pw-new"></label>
      <label class="field">New password again<input type="password" id="pw-new2"></label></div>
      <div class="foot"><button class="btn" onclick="closeModal()">Cancel</button><button class="btn primary" id="pw-go">Change</button></div>`);
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
      if (await act(() => api("POST", "/api/settings/restore", data), (r) => `Restored ${r.apps} apps and ${r.devices} devices.`)) route("settings");
  }; pick.click(); };
  $("st-cancel").onclick = () => route("settings");
  $("st-save").onclick = async () => {
    const body = {};
    const put = (key, value, old) => { if (value === old) return; const [a, b] = key.split("."); (body[a] ||= {})[b] = value; };
    const get = (key) => key.split(".").reduce((o, k) => o?.[k], S);
    s.querySelectorAll("[data-key]").forEach((el) => {
      const key = el.dataset.key;
      if (el.classList.contains("sl")) {
        // A slider shows a stored value clamped to what the disk allows; saving it unmoved would quietly
        // change the setting (that shrank a pool once). So a slider counts only when it was moved.
        if (!el.dataset.moved) return;
        const v = slVal(el);
        if (key === "pool.internal_gb") return put(key, v, P.int_limit / GB);
        if (key === "pool.sd_gb") return put(key, v, P.sd_limit / GB);
        return put(key, v, get(key));
      }
      if (el.type === "checkbox") return put(key, el.checked, get(key));
      if (key === "jobs.integrity_weekday") return put(key, +el.value, get(key));
      put(key, el.value, get(key));
    });
    if (!Object.keys(body).length) return toast("Nothing changed.");
    if (await act(() => api("PATCH", "/api/settings", body), "Settings saved.")) route("settings");
  };
};

// ---- logs
const LOGF = { app: "", device: "", op: "", status: "", q: "", tab: "req" };
RENDER.logs = async (arg, ticket) => {
  if (arg) { Object.assign(LOGF, { app: arg, tab: "req" }); setAddress("logs", true); }
  const s = $("p-logs");
  const head = `<h1>Logs</h1><p class="sub">Every request to the server is recorded. Detailed logs are kept for the period set in Settings.</p>
    <div class="tabs"><a data-tab="req" class="${LOGF.tab === "req" ? "on" : ""}">Requests</a><a data-tab="sys" class="${LOGF.tab === "sys" ? "on" : ""}">System</a></div>`;
  const tabs = () => s.querySelectorAll("[data-tab]").forEach((t) => t.onclick = () => { LOGF.tab = t.dataset.tab; route("logs"); });
  if (LOGF.tab === "sys") {
    const d = await api("GET", "/api/logs/system?lines=500");
    if (stale(ticket)) return;
    s.innerHTML = head + `<pre class="block log">${esc(d.lines.join("\n")) || "Nothing logged yet."}</pre>`;
    return tabs();
  }
  const q = new URLSearchParams(Object.fromEntries(Object.entries(LOGF).filter(([k, v]) => v && k !== "tab")));
  q.set("limit", "300");
  const [d, apps, devs] = await Promise.all([api("GET", "/api/logs?" + q), api("GET", "/api/apps"), api("GET", "/api/devices")]);
  if (stale(ticket)) return;
  const names = Object.fromEntries(apps.apps.map((a) => [a.id, a.name]));
  const sel = (k, opts) => `<select data-f="${k}">${opts.map(([v, l]) => `<option value="${esc(v)}"${LOGF[k] === v ? " selected" : ""}>${esc(l)}</option>`).join("")}</select>`;
  s.innerHTML = head + `
    <div class="filters">
      ${sel("app", [["", "All apps"], ...apps.apps.map((a) => [a.id, a.name])])}
      ${sel("device", [["", "All devices"], ...devs.devices.map((x) => [x.uid, x.name])])}
      ${sel("op", [["", "All operations"], ...d.ops.map((o) => [o, o])])}
      ${sel("status", [["", "All results"], ["ok", "Successful"], ["err", "Errors"]])}
      <input type="text" placeholder="Search file name" id="lq" value="${esc(LOGF.q)}">
      <span class="muted">${d.items.length >= 300 ? "latest 300 shown" : d.items.length + " shown"}</span>
    </div>
    <div class="panel"><div class="tw"><table>
      <thead><tr><th>Time</th><th>App</th><th>Device</th><th>Operation</th><th>File</th><th class="num">Size</th><th class="num">Result</th><th class="num">Took</th></tr></thead>
      <tbody>${d.items.length ? d.items.map((r) => `<tr><td class="mono muted">${dt(r.ts)}</td><td>${esc(names[r.app] || r.app || "")}</td><td class="muted">${esc(r.device_name || "")}</td>
        <td class="mono">${esc(r.op)}</td><td class="mono">${esc(r.key || "")}</td><td class="num">${r.bytes_in || r.bytes_out ? fb((r.bytes_in || 0) + (r.bytes_out || 0)) : "—"}</td>
        <td class="num ${r.status >= 400 && r.status !== 404 ? "warn" : "muted"}" title="${esc(r.note || "")}">${r.status}</td><td class="num muted">${r.ms != null ? Math.round(r.ms) + " ms" : ""}</td></tr>`).join("")
        : `<tr><td colspan="8" class="muted">No requests match.</td></tr>`}</tbody>
    </table></div></div>`;
  tabs();
  s.querySelectorAll("[data-f]").forEach((el) => el.onchange = () => { LOGF[el.dataset.f] = el.value; route("logs"); });
  $("lq").onchange = (e) => { LOGF.q = e.target.value; route("logs"); };
};

boot();
