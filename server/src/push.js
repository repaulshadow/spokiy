// Push-сповіщення через Firebase Cloud Messaging (для версії з Google Play).
// Сервер на кожну зміну тривог в області надсилає ОДНЕ повідомлення на «тему» цієї області — Google сам доставляє
// його всім телефонам, що підписані. Навантаження на сервер не залежить від кількості телефонів.
// Вимкнено, доки немає секрету FCM_SA (JSON сервісного облікового запису Firebase), — тоді нічого не робить.
// Програми для ПК, Mac і Android із сайту цим не користуються: для них усе лишається як було (/status і /stream).

export const PUSH_V = 1;
const MAX_DATA = 3400;   // межа FCM — 4096 байт на повідомлення; із запасом

/** Назва області → стабільний ключ (як norm() у програмах: нижній регістр, без «м. », Крим). */
export function oblastKey(name) {
  let t = String(name || "").trim().toLowerCase().replace(/[’ʼ`]/g, "'");
  if (t.startsWith("м. ")) t = t.slice(3);
  if (t === "ар крим" || t === "автономна республіка крим") t = "крим";
  return t;
}

/** Тема FCM для області: «o_» + 12 hex від SHA-1 ключа (у темі дозволені лише [a-zA-Z0-9-_.~%]). */
export async function oblastTopic(name) {
  const d = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(oblastKey(name)));
  return "o_" + Array.from(new Uint8Array(d)).slice(0, 6).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Зріз даних UkraineAlarm по областях: ключ області → { n: назва, d: {r, o, c} } (формат як у /status.ua). */
export function slices(p) {
  const out = {};
  const get = (n) => (out[oblastKey(n)] ||= { n, d: { r: [], o: [], c: [] } });
  for (const x of p.o || []) get(x[0]).d.o.push(x);
  for (const x of p.r || []) get(x[0]).d.r.push(x);
  for (const x of p.c || []) get(x[0]).d.c.push(x);
  for (const s of Object.values(out)) {   // стабільний порядок — щоб підпис не змінювався від перестановок
    s.d.r.sort((a, b) => String(a[1]).localeCompare(String(b[1])));
    s.d.c.sort((a, b) => (String(a[1]) + a[2]).localeCompare(String(b[1]) + b[2]));
  }
  return out;
}

/** Підписи зрізів: ключ області → рядок. Зміна підпису = треба надіслати push. */
export function signatures(sl) {
  const out = {};
  for (const [k, s] of Object.entries(sl)) out[k] = JSON.stringify(s.d);
  return out;
}

/**
 * Які області змінилися між двома наборами підписів. Область, що зникла (усі тривоги скасовано), теж змінилась —
 * їй надсилаємо порожній зріз (відбій). names — ключ → назва (з попереднього разу, щоб знати тему зниклої області).
 */
export function changed(prev, cur) {
  const out = [];
  for (const k of Object.keys(cur)) if (prev[k] !== cur[k]) out.push(k);
  for (const k of Object.keys(prev)) if (!(k in cur)) out.push(k);
  return out;
}

/** Дані повідомлення для області. Якщо зріз завеликий для FCM — лише «poke»: телефон сам запитає /status. */
export function messageData(name, d, ts) {
  const body = JSON.stringify(d);
  const base = { v: String(PUSH_V), o: name, ts };
  if (new TextEncoder().encode(body).length > MAX_DATA) return { ...base, poke: "1" };
  return { ...base, d: body };
}

// ---------- авторизація Google (сервісний обліковий запис → токен OAuth) ----------

const b64u = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64uText = (s) => b64u(new TextEncoder().encode(s));

export async function signJwt(sa, nowS) {
  const head = b64uText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64uText(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri || "https://oauth2.googleapis.com/token", iat: nowS, exp: nowS + 3600,
  }));
  const pem = String(sa.private_key).replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(head + "." + claims));
  return head + "." + claims + "." + b64u(sig);
}

/** Надсилач push. state: { token, exp } — кеш токена між викликами. fetchFn — для тестів. */
export class Pusher {
  constructor(env, fetchFn) {
    this.env = env;
    this.fetch = fetchFn || ((...a) => fetch(...a));
    this.token = null;
    this.exp = 0;
    this.stats = { sent: 0, failed: 0, lastAt: 0, lastErr: null };
    try { this.sa = env.FCM_SA ? JSON.parse(env.FCM_SA) : null; } catch { this.sa = null; this.stats.lastErr = "FCM_SA: не JSON"; }
  }

  get enabled() { return !!(this.sa && this.sa.project_id && this.sa.client_email && this.sa.private_key); }

  async accessToken(now) {
    if (this.token && now < this.exp - 120000) return this.token;
    if (!this.pending) {   // кілька повідомлень одночасно — один запит токена
      this.pending = (async () => {
        const jwt = await signJwt(this.sa, Math.floor(now / 1000));
        const r = await this.fetch(this.sa.token_uri || "https://oauth2.googleapis.com/token", {
          method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + jwt,
        });
        if (!r.ok) throw new Error("OAuth: відповідь " + r.status);
        const j = await r.json();
        this.token = j.access_token;
        this.exp = now + (Number(j.expires_in) || 3600) * 1000;
        return this.token;
      })().finally(() => { this.pending = null; });
    }
    return this.pending;
  }

  /** Одне повідомлення на тему області. Лише дані (без «notification») — показ і звук робить сама програма. */
  async send(topic, data, now) {
    const token = await this.accessToken(now);
    const base = this.env.FCM_BASE || "https://fcm.googleapis.com";
    const r = await this.fetch(`${base}/v1/projects/${this.sa.project_id}/messages:send`, {
      method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify({ message: {
        topic, data,
        // високий пріоритет — доставка одразу, навіть коли телефон «спить»; ttl 5 хв — стару тривогу не доставляти
        android: { priority: "HIGH", ttl: "300s", collapse_key: topic },
      } }),
    });
    if (r.status === 401) this.token = null;
    if (!r.ok) throw new Error("FCM: відповідь " + r.status + " " + (await r.text()).slice(0, 200));
  }

  /**
   * Надіслати зміни: prevSig/curSig — підписи зрізів, sl — поточні зрізи, names — ключ → назва області.
   * Повертає оновлені names. Помилки не кидає (push — додатковий канал, сервер від нього не залежить).
   */
  async pushChanges(prevSig, curSig, sl, names, now) {
    if (!this.enabled) return names;
    const ts = new Date(now).toISOString();
    const jobs = [];
    for (const k of changed(prevSig, curSig)) {
      const name = (sl[k] && sl[k].n) || names[k];
      if (!name) continue;
      names[k] = name;
      const d = sl[k] ? sl[k].d : { r: [], o: [], c: [] };
      jobs.push((async () => {
        try { await this.send(await oblastTopic(name), messageData(name, d, ts), now); this.stats.sent++; }
        catch (e) { this.stats.failed++; this.stats.lastErr = String(e && e.message ? e.message : e).slice(0, 300); }
      })());
    }
    if (jobs.length) { await Promise.all(jobs); this.stats.lastAt = now; }
    return names;
  }
}
