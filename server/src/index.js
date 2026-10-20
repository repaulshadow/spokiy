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
const EDGE_CACHE_S = 3;        // кеш на краю Cloudflare, щоб не будити Durable Object на кожен запит

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
    if (request.method !== "GET") return json({ ok: false, error: "method" }, 405);

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
    if (m.get("ain")) this.ain = m.get("ain");
    if (m.get("nep")) this.nep = m.get("nep");
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
    let now = Date.now();
    const d0 = this.due(now);
    if (d0.ain || d0.nep) {
      if (!this.pending) this.pending = this.refresh().finally(() => { this.pending = null; });
      await this.pending;
      now = Date.now();
    }
    if (new URL(request.url).pathname === "/health") {
      return json({
        ok: !!(this.fresh(this.ain, now) || this.fresh(this.nep, now)),
        service: "trevoga",
        token_set: !!this.env.ALERTS_TOKEN,
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
    return {
      // [область, район, початок]
      r: raions.map((x) => [x.oblast || "", x.name || "", x.since || null]),
      // [область, початок]
      o: oblasts.map((x) => [x.name || "", x.since || null]),
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
    try { await this.state.storage.put(toSave); } catch (e) { /* не критично */ }
  }
}
