// Сервер-посредник для программы «Тривога».
// Токен alerts.in.ua хранится только здесь (секрет ALERTS_TOKEN), пользователи его не видят.
// Все запросы приложений сходятся в один Durable Object, который ходит к источникам
// (alerts.in.ua — раз в 20 с, NEPTUN — раз в 5 с), сколько бы ни было пользователей.
//
// GET /status  ->  {
//   ok: true,
//   updated: "2026-10-20T05:00:00.000Z",     // когда данные взяты у alerts.in.ua
//   air: "NNNANNP...",        // або null, якщо alerts.in.ua недоступний                       // символ с индексом uid: A — тревога, P — частичная, N — нет
//   neptun: { r: [["Одеська область","Одеський район","<since>"]], o: [["АР Крим","<since>"]] },  // або null
//   src: { alerts_in_ua: "ok" | "немає токена" | "<помилка>", neptun: "ok" | "<помилка>" },
//   alerts: [ { u: 22, o: 22, r: "Харківський район", s: "2026-10-20T04:10:00.000Z",
//               l: "red", t: ["ballistic_missiles", "drones"] }, ... ]   // u — uid місця, o — області, s — початок, l — рівень (red/yellow)
// }

const UPSTREAM_DEFAULT = "https://api.alerts.in.ua";
const NEPTUN_DEFAULT = "https://neptun.in.ua";
const AIN_MS = 20000;          // alerts.in.ua: 2 запити раз на 20 с = 6 на хвилину (їхній ліміт 8–10)
const NEP_MS = 5000;           // NEPTUN: не частіше ніж раз на 5 с (їхня умова)
const STALE_MS = 120000;       // дані джерела старші за 2 хв вважаємо недійсними
const EDGE_CACHE_S = 2;        // кеш на краю Cloudflare, щоб не будити Durable Object на кожен запит
const POLL_S = 10;             // як часто програми опитують сервер (секунд); змінюється тут, без оновлення програм
// UkraineAlarm (офіційний API, ключ у секреті UA_TOKEN). Ліміт — 1 запит на хвилину, тому основне — webhook:
// UkraineAlarm сам надсилає кожну зміну на /ua-hook/<секрет>, а повний список звіряємо раз на 90 с.
const UA_BASE = "https://api.ukrainealarm.com/api/v3";
const UA_MS = 90000;           // повна звірка /alerts
const UA_GAP_MS = 62000;       // не частіше одного запиту на хвилину
const UA_STALE_MS = 300000;    // без звірки й webhook 5 хв — дані UkraineAlarm недійсні
const HB_MS = 15000;           // heartbeat у потоці /stream

// Секретна частина адреси webhook: перші 24 символи sha256 від ключа (знають лише сервер і workflow реєстрації)
async function hookSecret(key) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("trevoga-hook:" + key));
  return Array.from(new Uint8Array(h)).map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 24);
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS, ...extra },
  });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    // Потік змін (WebSocket) для програм: формат як у потоку NEPTUN, джерело — UkraineAlarm
    if (url.pathname === "/stream") {
      if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") return json({ ok: false, error: "websocket only" }, 426);
      return env.ALERTS.get(env.ALERTS.idFromName("ua")).fetch(request);
    }
    // Webhook UkraineAlarm
    if (url.pathname.startsWith("/ua-hook/") && request.method === "POST") {
      if (!env.UA_TOKEN || url.pathname !== "/ua-hook/" + (await hookSecret(env.UA_TOKEN))) return json({ ok: false }, 404);
      const body = await request.text();
      if (body.length > 200000) return json({ ok: false, error: "too big" }, 413);
      return env.ALERTS.get(env.ALERTS.idFromName("ua")).fetch("https://do/ua-hook", { method: "POST", body });
    }

    // Звіти програм про спрацювання (тривога/відбій/звук із часом) і їх перегляд
    if (url.pathname === "/report" && request.method === "POST") {
      const body = await request.text();
      if (body.length > 4096) return json({ ok: false, error: "too big" }, 413);
      try { JSON.parse(body); } catch { return json({ ok: false, error: "json" }, 400); }
      const id = env.ALERTS.idFromName("ua");
      const r = await env.ALERTS.get(id).fetch("https://do/report", { method: "POST", body });
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });
    }
    if (url.pathname === "/ua-hooks") {
      const r = await env.ALERTS.get(env.ALERTS.idFromName("ua")).fetch("https://do/ua-hooks");
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });
    }
    if (url.pathname === "/reports") {
      const id = env.ALERTS.idFromName("ua");
      const r = await env.ALERTS.get(id).fetch("https://do/reports" + url.search);
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });
    }
    if (request.method !== "GET") return json({ ok: false, error: "method" }, 405);

    if (url.pathname === "/log") {
      // журнал: коли сервер дізнався про початок/кінець тривоги з кожного джерела
      const id = env.ALERTS.idFromName("ua");
      const r = await env.ALERTS.get(id).fetch("https://do/log" + url.search);
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });
    }
    if (url.pathname === "/" || url.pathname === "/health") {
      // состояние сервера: когда последний раз удалось получить данные и последняя ошибка
      const id = env.ALERTS.idFromName("ua");
      const r = await env.ALERTS.get(id).fetch("https://do/health");
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });
    }
    if (url.pathname !== "/status") return json({ ok: false, error: "not found" }, 404);

    // 1) Кэш на краю
    const cache = caches.default;
    const key = new Request(url.origin + "/status", { method: "GET" });
    const hit = await cache.match(key);
    if (hit) return hit;

    // 2) Один на всех Durable Object
    const id = env.ALERTS.idFromName("ua");
    const resp = await env.ALERTS.get(id).fetch("https://do/status");
    const body = await resp.text();
    const out = new Response(body, {
      status: resp.status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": `public, max-age=${EDGE_CACHE_S}`,
        ...CORS,
      },
    });
    if (resp.status === 200) ctx.waitUntil(cache.put(key, out.clone()));
    return out;
  },
};

function toInt(v) {
  if (v === null || v === undefined || v === "") return -1;
  const n = parseInt(String(v), 10);
  return Number.isFinite(n) ? n : -1;
}

export class AlertsHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.loaded = false;     // дані переживають перезапуск об'єкта: зберігаємо їх і в його сховищі
    // по кожному джерелу: значення, час останньої спроби, час останньої вдалої, помилка
    this.ain = { val: null, at: 0, okAt: 0, err: null };
    this.nep = { val: null, at: 0, okAt: 0, err: null };
    // UkraineAlarm: regions — активні тривоги за regionId; okAt — остання звірка/webhook
    this.ua = { regions: {}, at: 0, okAt: 0, hookAt: 0, hooks: 0, err: null, lastReq: 0 };
    this.uaDir = null;       // довідник регіонів: id -> { n: назва, t: тип, o: назва області, d: назва району }
    this.pending = null;     // спільний запит, якщо кілька прийшли одночасно
  }

  async load() {
    if (this.loaded) return;
    const m = await this.state.storage.get(["ain", "nep", "ua", "uaDir"]);
    this.events = [];
    this.seen = {};
    if (m.get("ain")) this.ain = m.get("ain");
    if (m.get("nep")) this.nep = m.get("nep");
    if (m.get("ua")) this.ua = m.get("ua");
    if (m.get("uaDir")) this.uaDir = m.get("uaDir");
    const lg = await this.state.storage.get(["events", "seen"]);
    this.seen = lg.get("seen") || {};
    // Журнал подій — таблиця SQLite (зберігаємо 21 день)
    const sql = this.state.storage.sql;
    sql.exec("CREATE TABLE IF NOT EXISTS events (t TEXT, src TEXT, k TEXT, e TEXT)");
    sql.exec("CREATE INDEX IF NOT EXISTS events_t ON events (t)");
    sql.exec("CREATE TABLE IF NOT EXISTS reports (t TEXT, id TEXT, body TEXT)");
    sql.exec("CREATE INDEX IF NOT EXISTS reports_t ON reports (t)");
    sql.exec("CREATE TABLE IF NOT EXISTS uahook (t TEXT, body TEXT)");
    const old = lg.get("events");
    if (old && old.length) {   // перенесення старого журналу-масиву
      for (const e of old) sql.exec("INSERT INTO events VALUES (?, ?, ?, ?)", e.t, e.src, e.k, e.e);
      await this.state.storage.delete("events");
    }
    this.lastPrune = 0;
    this.loaded = true;
  }

  fresh(src, now) {
    return src.val && now - src.okAt < STALE_MS ? src.val : null;
  }

  due(now) {
    return { ain: now - this.ain.at >= AIN_MS, nep: now - this.nep.at >= NEP_MS };
  }

  async fetch(request) {
    await this.load();
    const path = new URL(request.url).pathname;
    if (path === "/stream") {
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      try { pair[1].send(this.uaStreamMessage()); } catch {}
      await this.ensureAlarm();
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (path === "/ua-hook") {
      const body = await request.text();
      await this.onUaHook(body);
      return json({ ok: true });
    }
    if (path === "/ua-hooks") {   // останні сирі webhook (для налагодження формату)
      const rows = this.state.storage.sql.exec("SELECT t, body FROM uahook ORDER BY t DESC LIMIT 50").toArray();
      return json({ ok: true, hooks: rows });
    }
    if (path === "/report") {
      const sql = this.state.storage.sql;
      const body = await request.text();
      let devId = "";
      try { devId = String(JSON.parse(body).id || "").slice(0, 32); } catch {}
      const today = new Date().toISOString().slice(0, 10);
      const n = sql.exec("SELECT COUNT(*) AS n FROM reports WHERE id = ? AND t >= ?", devId, today).toArray()[0].n;
      if (n > 3000) return json({ ok: false, error: "limit" }, 429);   // захист від зациклення
      sql.exec("INSERT INTO reports VALUES (?, ?, ?)", new Date().toISOString(), devId, body);
      if (Math.random() < 0.01) sql.exec("DELETE FROM reports WHERE t < ?", new Date(Date.now() - 120 * 86400e3).toISOString());
      return json({ ok: true });
    }
    if (path === "/reports") {
      const p = new URL(request.url).searchParams;
      const since = p.get("since") || new Date(Date.now() - 7 * 86400e3).toISOString();
      let rows = this.state.storage.sql.exec("SELECT t, id, body FROM reports WHERE t >= ? ORDER BY t", since).toArray();
      if (p.get("id")) rows = rows.filter((r) => r.id === p.get("id"));
      return json({ ok: true, now: new Date().toISOString(), reports: rows.slice(-20000).map((r) => ({ srv: r.t, ...JSON.parse(r.body) })) });
    }
    let now = Date.now();
    const d0 = this.due(now);
    if (d0.ain || d0.nep) {
      if (!this.pending) this.pending = this.refresh().finally(() => { this.pending = null; });
      await this.pending;
      now = Date.now();
    }
    const reqUrl = new URL(request.url);
    if (reqUrl.pathname === "/log") {
      // /log?q=частина назви&since=ISO-час&limit=N
      const q = (reqUrl.searchParams.get("q") || "").toLowerCase();
      const since = reqUrl.searchParams.get("since") || new Date(now - 24 * 3600e3).toISOString();
      const limit = Math.min(50000, parseInt(reqUrl.searchParams.get("limit") || "5000", 10) || 5000);
      let ev = this.state.storage.sql.exec("SELECT t, src, k, e FROM events WHERE t >= ? ORDER BY t", since).toArray();
      if (q) ev = ev.filter((e) => e.k.toLowerCase().includes(q));
      const first = this.state.storage.sql.exec("SELECT MIN(t) AS t FROM events").toArray()[0];
      return json({ ok: true, now: new Date(now).toISOString(), oldest: first && first.t, events: ev.slice(-limit) });
    }
    if (reqUrl.pathname === "/health") {
      return json({
        ok: !!(this.fresh(this.ain, now) || this.fresh(this.nep, now)),
        service: "trevoga",
        token_set: !!this.env.ALERTS_TOKEN,
        neptun_fields: this.nepKeys || null,
        neptun_sample: this.nepSample || null,
        sources: {
          alerts_in_ua: { ok: !!this.fresh(this.ain, now), age_s: this.ain.okAt ? Math.round((now - this.ain.okAt) / 1000) : null, error: this.ain.err },
          neptun: { ok: !!this.fresh(this.nep, now), age_s: this.nep.okAt ? Math.round((now - this.nep.okAt) / 1000) : null, error: this.nep.err },
          ukrainealarm: {
            ok: this.uaFresh(now), key_set: !!this.env.UA_TOKEN, regions_known: this.uaDir ? Object.keys(this.uaDir).length : 0,
            active: Object.keys(this.ua.regions || {}).length,
            age_s: this.ua.okAt ? Math.round((now - this.ua.okAt) / 1000) : null,
            last_hook_s: this.ua.hookAt ? Math.round((now - this.ua.hookAt) / 1000) : null, hooks: this.ua.hooks || 0,
            stream_clients: this.state.getWebSockets().length, error: this.ua.err,
          },
        },
      });
    }
    return this.reply();
  }

  reply() {
    const now = Date.now();
    const a = this.fresh(this.ain, now), n = this.fresh(this.nep, now);
    if (!a && !n && !this.uaFresh(now)) {
      return json({ ok: false, error: "alerts.in.ua: " + (this.ain.err || "немає даних") + "; NEPTUN: " + (this.nep.err || "немає даних") }, 502);
    }
    const body = JSON.stringify({
      ok: true,
      poll: Number(this.env.POLL_S) || POLL_S,
      updated: new Date(Math.max(this.ain.okAt, this.nep.okAt)).toISOString(),
      air: a ? a.air : null,
      alerts: a ? a.alerts : [],
      neptun: n,
      ua: this.uaFresh(now) ? this.uaPayload() : null,
      src: { alerts_in_ua: a ? "ok" : this.ain.err || "немає даних", neptun: n ? "ok" : this.nep.err || "немає даних",
             ukrainealarm: this.uaFresh(now) ? "ok" : this.ua.err || "немає даних" },
    });
    return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
  }

  async upstream(path) {
    const base = this.env.UPSTREAM || UPSTREAM_DEFAULT;
    const r = await fetch(base + path, {
      headers: {
        Authorization: "Bearer " + this.env.ALERTS_TOKEN,
        "User-Agent": "Trevoga-proxy/1.0",
        Accept: "application/json",
      },
    });
    if (r.status === 401) throw new Error("alerts.in.ua: невірний токен (401)");
    if (r.status === 429) throw new Error("alerts.in.ua: забагато запитів (429)");
    if (!r.ok) throw new Error("alerts.in.ua: відповідь " + r.status);
    return r.text();
  }

  // Джерело 1: alerts.in.ua (потрібен токен) — статуси всіх місць + типи загроз
  async fromAlertsInUa() {
    if (!this.env.ALERTS_TOKEN) throw new Error("немає токена");
    const [airRaw, activeRaw] = await Promise.all([
      this.upstream("/v1/iot/active_air_raid_alerts.json"),
      this.upstream("/v1/alerts/active.json"),
    ]);
    let air = airRaw.trim();
    if (air.startsWith('"')) air = JSON.parse(air);       // відповідь — JSON-рядок у лапках
    if (typeof air !== "string" || air.length < 32) throw new Error("alerts.in.ua: неочікуваний формат");

    const active = JSON.parse(activeRaw);
    const alerts = [];
    for (const a of active.alerts || []) {
      if (a.finished_at) continue;
      const t = [];
      if (a.alert_type && a.alert_type !== "air_raid") t.push(a.alert_type);
      for (const th of a.threats || []) {
        if (th && th.threat_type && !t.includes(th.threat_type)) t.push(th.threat_type);
      }
      alerts.push({
        u: toInt(a.location_uid),
        o: toInt(a.location_oblast_uid),
        r: a.location_raion || null,
        on: a.location_oblast || null,   // назва області (location_oblast_uid у районних тривог буває некоректним)
        s: a.started_at || null,
        l: a.alert_level || null,     // рівень: "red" | "yellow"
        t,
      });
    }
    return { air, alerts };
  }

  // Джерело 2: NEPTUN (neptun.in.ua, без ключа; їхнє джерело тривог — офіційний API UkraineAlarm).
  // Умова використання: видиме посилання на NEPTUN у програмах.
  async fromNeptun() {
    const base = this.env.NEPTUN || NEPTUN_DEFAULT;
    const r = await fetch(base + "/api/v1/alerts", {
      headers: { "User-Agent": "Trevoga/1.0 (+https://github.com/repaulshadow/spokiy)", Accept: "application/json" },
    });
    if (!r.ok) throw new Error("NEPTUN: відповідь " + r.status);
    const j = await r.json();
    const raions = Array.isArray(j.raions) ? j.raions : [];
    const oblasts = Array.isArray(j.oblasts) ? j.oblasts : [];
    if (!Array.isArray(j.raions) && !Array.isArray(j.oblasts)) throw new Error("NEPTUN: неочікуваний формат");
    const lvl = (x) => {
      const v = x.level ?? x.alertLevel ?? x.alert_level ?? x.threatLevel ?? x.severity ?? x.color ?? null;
      return typeof v === "string" ? v.toLowerCase() : v;
    };
    // «reasons» (текст, напр. «Дронова загроза (жовтий рівень)») → типи загроз як у alerts.in.ua
    const types = (x) => {
      const txt = String(x.reasons || "").toLowerCase();
      const t = [];
      if (/баліст/.test(txt)) t.push("ballistic_missiles");
      if (/крилат/.test(txt)) t.push("cruise_missiles");
      if (/ракет/.test(txt) && !t.length) t.push("unspecified_missiles");
      if (/міг|mig/.test(txt)) t.push("mig31k_departure");
      if (/каб|авіабомб|керован/.test(txt)) t.push("guided_aerial_bombs");
      if (/дрон|бпла|шахед|безпілот/.test(txt)) t.push("drones");
      if (/артобстріл|артилер/.test(txt)) t.push("artillery_shelling");
      return t;
    };
    this.nepKeys = raions[0] ? Object.keys(raions[0]) : oblasts[0] ? Object.keys(oblasts[0]) : [];
    this.nepSample = raions[0] || oblasts[0] || null;
    return {
      // [область, район, початок, рівень, типи загроз]
      r: raions.map((x) => [x.oblast || "", x.name || "", x.since || null, lvl(x), types(x)]),
      // [область, початок, рівень, типи загроз]
      o: oblasts.map((x) => [x.name || "", x.since || null, lvl(x), types(x)]),
    };
  }

  async refresh() {
    const now = Date.now();
    const d = this.due(now);
    const jobs = [];
    const toSave = {};
    const run = async (src, key, fn) => {
      src.at = Date.now();
      try {
        src.val = await fn();
        src.okAt = Date.now();
        src.err = null;
      } catch (e) {
        src.err = String(e && e.message ? e.message : e);
      }
      toSave[key] = src;
    };
    if (d.ain) jobs.push(run(this.ain, "ain", () => this.fromAlertsInUa()));
    if (d.nep) jobs.push(run(this.nep, "nep", () => this.fromNeptun()));
    jobs.push(this.uaRefresh().catch(() => {}));
    await Promise.all(jobs);
    if (d.ain && toSave.ain && !this.ain.err) this.track("alerts.in.ua", this.keysAin());
    if (d.nep && toSave.nep && !this.nep.err) this.track("NEPTUN", this.keysNep());
    if (this.dirtyLog) { toSave.seen = this.seen; this.dirtyLog = false; }
    try { await this.state.storage.put(toSave); } catch (e) { /* не критично */ }
  }

  // ---------------- UkraineAlarm ----------------
  uaFresh(now) {
    return !!this.env.UA_TOKEN && now - Math.max(this.ua.okAt || 0, this.ua.hookAt || 0) < UA_STALE_MS && !!this.uaDir;
  }

  async uaGet(path) {
    this.ua.lastReq = Date.now();
    const r = await fetch((this.env.UA_BASE || UA_BASE) + path, { headers: { Authorization: this.env.UA_TOKEN, Accept: "application/json", "User-Agent": "Trevoga/1.0" } });
    if (r.status === 429) throw new Error("UkraineAlarm: ліміт запитів (429)");
    if (r.status === 401) throw new Error("UkraineAlarm: невірний ключ (401)");
    if (!r.ok) throw new Error("UkraineAlarm: відповідь " + r.status);
    return r.json();
  }

  // Звірка: спершу довідник регіонів (раз на тиждень), далі повний список тривог раз на 90 с; не частіше 1 запиту/хв
  async uaRefresh() {
    if (!this.env.UA_TOKEN) return;
    const now = Date.now();
    if (now - (this.ua.lastReq || 0) < UA_GAP_MS) return;
    try {
      if (!this.uaDir || now - (this.uaDir._at || 0) > 7 * 86400e3) {
        const d = await this.uaGet("/regions");
        const dir = { _at: Date.now() };
        const walk = (x, obl, dist) => {
          const t = x.regionType;
          const o = t === "State" ? x.regionName : obl;
          const ds = t === "District" ? x.regionName : dist;
          dir[String(x.regionId)] = { n: x.regionName, t, o, d: ds };
          for (const c of x.regionChildIds || []) walk(c, o, ds);
        };
        for (const st of d.states || []) walk(st, null, null);
        this.uaDir = dir;
        await this.state.storage.put("uaDir", dir);
        this.ua.err = null;
        return;
      }
      if (now - (this.ua.at || 0) < UA_MS) return;
      this.ua.at = now;
      const list = await this.uaGet("/alerts");
      const regions = {};
      for (const r of list || []) {
        const v = this.uaRegion(r);
        if (v) regions[String(r.regionId)] = v;
      }
      this.ua.regions = regions;
      this.ua.okAt = Date.now();
      this.ua.err = null;
      this.uaChanged("звірка");
    } catch (e) {
      this.ua.err = String(e && e.message ? e.message : e);
    }
    try { await this.state.storage.put("ua", this.ua); } catch {}
  }

  // Регіон з UkraineAlarm (з /alerts або webhook) -> { a: [{type, level, since}] } або null, якщо тривог немає
  uaRegion(r) {
    const alerts = (r.activeAlerts || []).map((x) => {
      let lv = null, since = x.lastUpdate || r.lastUpdate || null;
      for (const l of x.activeAlertLevels || []) {
        const v = String(l.alertLevel || "").toLowerCase();
        if (v === "red" || (v === "yellow" && lv !== "red")) lv = v;
        if (l.createdAt && (!since || l.createdAt < since)) since = l.createdAt;
      }
      return { type: x.type || "AIR", level: lv, since };
    });
    if (!alerts.length) return null;
    return { a: alerts, n: r.regionName || null, t: r.regionType || null };
  }

  async onUaHook(body) {
    const now = Date.now();
    try { this.state.storage.sql.exec("INSERT INTO uahook VALUES (?, ?)", new Date(now).toISOString(), body.slice(0, 20000)); } catch {}
    if (Math.random() < 0.02) this.state.storage.sql.exec("DELETE FROM uahook WHERE t < ?", new Date(now - 14 * 86400e3).toISOString());
    let j;
    try { j = JSON.parse(body); } catch { return; }
    const items = Array.isArray(j) ? j : [j];
    for (const r of items) {
      if (!r || r.regionId === undefined) continue;
      const v = this.uaRegion(r);
      if (v) this.ua.regions[String(r.regionId)] = v;
      else delete this.ua.regions[String(r.regionId)];
    }
    this.ua.hookAt = now;
    this.ua.hooks = (this.ua.hooks || 0) + 1;
    this.uaChanged("webhook");
    try { await this.state.storage.put("ua", this.ua); } catch {}
  }

  // Формат для програм: як у NEPTUN — r: [область, район, початок, рівень, типи], o: [область, ...], c: громади
  uaPayload() {
    const out = { r: [], o: [], c: [] };
    const types = (a) => {
      const map = { ARTILLERY: "artillery_shelling", URBAN_FIGHTS: "urban_fights", CHEMICAL: "chemical", NUCLEAR: "nuclear" };
      return a.map((x) => map[x.type]).filter(Boolean);
    };
    for (const [id, v] of Object.entries(this.ua.regions || {})) {
      const d = (this.uaDir && this.uaDir[id]) || { n: v.n, t: v.t };
      let since = null, lv = null;
      for (const a of v.a) {
        if (a.since && (!since || a.since < since)) since = a.since;
        if (a.level === "red" || (a.level === "yellow" && lv !== "red")) lv = a.level;
      }
      const t = d.t || v.t;
      if (t === "State") out.o.push([d.n || v.n, since, lv, types(v.a)]);
      else if (t === "District") out.r.push([d.o || "", d.n || v.n, since, lv, types(v.a)]);
      else out.c.push([d.o || "", d.d || "", d.n || v.n, since, lv, types(v.a)]);
    }
    return out;
  }

  // Повідомлення потоку /stream у форматі NEPTUN («alerts»), щоб програма розбирала його тим самим кодом
  uaStreamMessage() {
    const p = this.uaFresh(Date.now()) ? this.uaPayload() : null;
    const reason = (ts) => ts.map((t) => ({ artillery_shelling: "Артобстріл", urban_fights: "Вуличні бої", chemical: "Хімічна загроза", nuclear: "Радіаційна загроза" }[t] || ""));
    return JSON.stringify({
      type: p ? "alerts" : "heartbeat", source: "ukrainealarm", ts: new Date().toISOString(),
      data: p ? {
        raions: p.r.map((x) => ({ key: String(x[1]).toLowerCase(), name: x[1], oblast: x[0], since: x[2], level: x[3], reasons: reason(x[4]) })),
        oblasts: p.o.map((x) => ({ key: String(x[0]).toLowerCase(), name: x[0], oblast: x[0], since: x[1], level: x[2], reasons: reason(x[3]) })),
        communities: p.c.map((x) => ({ oblast: x[0], raion: x[1], name: x[2], since: x[3], level: x[4] })),
      } : null,
    });
  }

  uaChanged(how) {
    const msg = this.uaStreamMessage();
    for (const ws of this.state.getWebSockets()) { try { ws.send(msg); } catch {} }
    const keys = new Set();
    const p = this.uaPayload();
    for (const r of p.r) keys.add(r[1] + " · " + r[0] + " · " + (r[3] || ""));
    for (const o of p.o) keys.add(o[0] + " · " + (o[2] || ""));
    this.track("UkraineAlarm", keys);
    if (this.dirtyLog) { this.state.storage.put("seen", this.seen).catch(() => {}); this.dirtyLog = false; }
  }

  // Будильник: heartbeat для потоку і звірка UkraineAlarm навіть коли ніхто не опитує /status
  async ensureAlarm() {
    const cur = await this.state.storage.getAlarm();
    if (!cur) await this.state.storage.setAlarm(Date.now() + HB_MS);
  }

  async alarm() {
    await this.load();
    await this.uaRefresh().catch(() => {});
    const hb = JSON.stringify({ type: "heartbeat", ts: new Date().toISOString() });
    const sockets = this.state.getWebSockets();
    for (const ws of sockets) { try { ws.send(hb); } catch {} }
    await this.state.storage.setAlarm(Date.now() + (sockets.length ? HB_MS : 60000));
  }

  webSocketMessage(ws, msg) { /* клієнти нічого не надсилають; ping/pong обробляє платформа */ }
  webSocketClose(ws, code) { try { ws.close(code, "bye"); } catch {} }
  webSocketError() {}

  // Ключі активних тривог для журналу
  keysAin() {
    const out = new Set();
    const air = this.ain.val && this.ain.val.air;
    if (air) for (let i = 0; i < air.length; i++) {
      const c = air[i];
      if (c === "A" || c === "P") out.add("uid " + i + (c === "P" ? " (частково)" : ""));
    }
    for (const a of (this.ain.val && this.ain.val.alerts) || []) {
      if (a.r) out.add(a.r + " · " + (a.on || "") + " · " + (a.l || ""));
    }
    return out;
  }

  keysNep() {
    const out = new Set();
    const n = this.nep.val;
    if (!n) return out;
    for (const r of n.r || []) out.add(r[1] + " · " + r[0] + " · " + (r[3] || ""));
    for (const o of n.o || []) out.add(o[0] + " · " + (o[2] || ""));
    return out;
  }

  // Порівнюємо з попереднім станом джерела і записуємо початок/кінець
  track(src, keys) {
    const prev = new Set(this.seen[src] || []);
    const now = new Date().toISOString();
    const first = !this.seen[src];
    if (!first) {
      const sql = this.state.storage.sql;
      for (const k of keys) if (!prev.has(k)) sql.exec("INSERT INTO events VALUES (?, ?, ?, ?)", now, src, k, "тривога");
      for (const k of prev) if (!keys.has(k)) sql.exec("INSERT INTO events VALUES (?, ?, ?, ?)", now, src, k, "відбій");
      if (Date.now() - this.lastPrune > 3600e3) {
        this.lastPrune = Date.now();
        sql.exec("DELETE FROM events WHERE t < ?", new Date(Date.now() - 21 * 86400e3).toISOString());
      }
    }
    const arr = Array.from(keys);
    if (first || arr.length !== prev.size || arr.some((k) => !prev.has(k))) {
      this.seen[src] = arr;
      this.dirtyLog = true;
    }
  }
}
