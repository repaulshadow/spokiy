// Сервер-посредник для программы «Тривога».
// Токен alerts.in.ua хранится только здесь (секрет ALERTS_TOKEN), пользователи его не видят.
// Все запросы приложений сходятся в один Durable Object, который ходит к alerts.in.ua
// не чаще раза в REFRESH_MS — сколько бы ни было пользователей.
//
// GET /status  ->  {
//   ok: true,
//   updated: "2026-10-20T05:00:00.000Z",     // когда данные взяты у alerts.in.ua
//   air: "NNNANNP...",                       // символ с индексом uid: A — тревога, P — частичная, N — нет
//   alerts: [ { u: 22, o: 22, r: "Харківський район", s: "2026-10-20T04:10:00.000Z",
//               t: ["ballistic_missiles", "drones"] }, ... ]   // u — uid місця, o — області, s — початок
// }

const UPSTREAM_DEFAULT = "https://api.alerts.in.ua";
const REFRESH_MS = 20000;      // 2 запроса к alerts.in.ua раз в 20 с = 6 в минуту (лимит у них 8–10)
const EDGE_CACHE_S = 10;       // кэш на краю Cloudflare, чтобы не будить Durable Object на каждый запрос

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
    this.loaded = false;     // данные переживают перезапуск объекта: храним их и в его хранилище
    this.data = null;        // последний удачный ответ (строка JSON)
    this.fetchedAt = 0;
    this.okAt = 0;
    this.pending = null;     // общий запрос, если несколько пришли одновременно
    this.lastError = null;
  }

  async load() {
    if (this.loaded) return;
    const m = await this.state.storage.get(["data", "fetchedAt", "okAt"]);
    this.data = m.get("data") || null;
    this.fetchedAt = m.get("fetchedAt") || 0;
    this.okAt = m.get("okAt") || 0;
    this.loaded = true;
  }

  async save() {
    try {
      await this.state.storage.put({ data: this.data, fetchedAt: this.fetchedAt, okAt: this.okAt });
    } catch (e) { /* не критично */ }
  }

  async fetch(request) {
    await this.load();
    const now = Date.now();
    if (new URL(request.url).pathname === "/health") {
      return json({
        ok: !!this.data,
        service: "trevoga",
        token_set: !!this.env.ALERTS_TOKEN,
        data_age_s: this.data ? Math.round((now - this.okAt) / 1000) : null,
        last_error: this.lastError,
      });
    }
    if (this.data && now - this.fetchedAt < REFRESH_MS) return this.reply();
    if (!this.pending) {
      this.pending = this.refresh().finally(() => { this.pending = null; });
    }
    await this.pending;
    return this.reply();
  }

  reply() {
    if (this.data) return new Response(this.data, { headers: { "Content-Type": "application/json; charset=utf-8" } });
    return json({ ok: false, error: this.lastError || "no data" }, 502);
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
    if (r.status === 401) throw new Error("alerts.in.ua: неверный токен (401)");
    if (r.status === 429) throw new Error("alerts.in.ua: слишком частые запросы (429)");
    if (!r.ok) throw new Error("alerts.in.ua: ответ " + r.status);
    return r.text();
  }

  async refresh() {
    if (!this.env.ALERTS_TOKEN) {
      this.lastError = "на сервере не задан ALERTS_TOKEN";
      return;
    }
    try {
      const [airRaw, activeRaw] = await Promise.all([
        this.upstream("/v1/iot/active_air_raid_alerts.json"),
        this.upstream("/v1/alerts/active.json"),
      ]);
      let air = airRaw.trim();
      if (air.startsWith('"')) air = JSON.parse(air);       // ответ — JSON-строка в кавычках
      if (typeof air !== "string" || air.length < 32) throw new Error("неожиданный формат статусов");

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
          t,
        });
      }

      this.data = JSON.stringify({
        ok: true,
        updated: new Date().toISOString(),
        air,
        alerts,
      });
      this.fetchedAt = Date.now();
      this.okAt = this.fetchedAt;
      this.lastError = null;
      await this.save();
    } catch (e) {
      this.lastError = String(e && e.message ? e.message : e);
      // если старые данные есть и им меньше 2 минут — отдаём их, иначе ошибку
      if (this.data && Date.now() - this.okAt > 120000) this.data = null;
      // следующая попытка — не раньше чем через 10 с
      this.fetchedAt = Date.now() - REFRESH_MS + 10000;
      await this.save();
    }
  }
}
