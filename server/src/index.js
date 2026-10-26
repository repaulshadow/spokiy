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

    // Звіти програм про спрацювання (тривога/відбій/звук із часом) і їх перегляд
    if (url.pathname === "/report" && request.method === "POST") {
      const body = await request.text();
      if (body.length > 4096) return json({ ok: false, error: "too big" }, 413);
      try { JSON.parse(body); } catch { return json({ ok: false, error: "json" }, 400); }
      const id = env.ALERTS.idFromName("ua");
      const r = await env.ALERTS.get(id).fetch("https://do/report", { method: "POST", body });
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
    this.pending = null;     // спільний запит, якщо кілька прийшли одночасно
  }

  async load() {
    if (this.loaded) return;
    const m = await this.state.storage.get(["ain", "nep"]);
    this.events = [];
    this.seen = {};
    if (m.get("ain")) this.ain = m.get("ain");
    if (m.get("nep")) this.nep = m.get("nep");
    const lg = await this.state.storage.get(["events", "seen"]);
    this.seen = lg.get("seen") || {};
    // Журнал подій — таблиця SQLite (зберігаємо 21 день)
    const sql = this.state.storage.sql;
    sql.exec("CREATE TABLE IF NOT EXISTS events (t TEXT, src TEXT, k TEXT, e TEXT)");
    sql.exec("CREATE INDEX IF NOT EXISTS events_t ON events (t)");
    sql.exec("CREATE TABLE IF NOT EXISTS reports (t TEXT, id TEXT, body TEXT)");
    sql.exec("CREATE INDEX IF NOT EXISTS reports_t ON reports (t)");
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
        },
      });
    }
    return this.reply();
  }

  reply() {
    const now = Date.now();
    const a = this.fresh(this.ain, now), n = this.fresh(this.nep, now);
    if (!a && !n) {
      return json({ ok: false, error: "alerts.in.ua: " + (this.ain.err || "немає даних") + "; NEPTUN: " + (this.nep.err || "немає даних") }, 502);
    }
    const body = JSON.stringify({
      ok: true,
      poll: Number(this.env.POLL_S) || POLL_S,
      updated: new Date(Math.max(this.ain.okAt, this.nep.okAt)).toISOString(),
      air: a ? a.air : null,
      alerts: a ? a.alerts : [],
      neptun: n,
      src: { alerts_in_ua: a ? "ok" : this.ain.err || "немає даних", neptun: n ? "ok" : this.nep.err || "немає даних" },
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
    await Promise.all(jobs);
    if (d.ain && toSave.ain && !this.ain.err) this.track("alerts.in.ua", this.keysAin());
    if (d.nep && toSave.nep && !this.nep.err) this.track("NEPTUN", this.keysNep());
    if (this.dirtyLog) { toSave.seen = this.seen; this.dirtyLog = false; }
    try { await this.state.storage.put(toSave); } catch (e) { /* не критично */ }
  }

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
