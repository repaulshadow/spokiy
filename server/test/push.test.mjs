// Автотести push-модуля (node server/test/push.test.mjs): зрізи по областях, що змінилося, підпис JWT, надсилання.
import { webcrypto, generateKeyPairSync, createVerify } from "node:crypto";
if (!globalThis.crypto) globalThis.crypto = webcrypto;
const { Pusher, slices, signatures, changed, messageData, oblastTopic, oblastKey, signJwt } = await import("../src/push.js");

let passed = 0, failed = 0;
const check = (c, n) => { if (c) passed++; else { failed++; console.log("FAIL: " + n); } };
const eq = (e, a, n) => check(JSON.stringify(e) === JSON.stringify(a), n + " — очікувалось " + JSON.stringify(e) + ", отримано " + JSON.stringify(a));

const OD = "Одеська область", KY = "м. Київ";
const p1 = { o: [[KY, "2026-11-13T10:00:00Z", "red", ["drones"]]], r: [[OD, "Одеський район", "2026-11-13T10:01:00Z", "yellow", []]], c: [] };
const s1 = slices(p1);
eq(["київ", "одеська область"], Object.keys(s1).sort(), "зрізи: дві області");
eq(1, s1["одеська область"].d.r.length, "зріз Одеської: один район");
eq("київ", oblastKey("м. Київ"), "ключ: м. Київ");
eq("крим", oblastKey("АР Крим"), "ключ: Крим");
const t = await oblastTopic(OD);
check(/^o_[0-9a-f]{12}$/.test(t), "тема: формат " + t);
eq(t, await oblastTopic("  одеська ОБЛАСТЬ "), "тема: не залежить від регістру й пробілів");
check(t !== (await oblastTopic(KY)), "тема: різні області — різні теми");

const g1 = signatures(s1);
eq([], changed(g1, signatures(slices(p1))), "без змін — нічого");
const p2 = { o: [], r: p1.r, c: [] };   // відбій у Києві
eq(["київ"], changed(g1, signatures(slices(p2))), "зникла область — змінилась (відбій)");
const p3 = { o: p1.o, r: [[OD, "Одеський район", "2026-11-13T10:01:00Z", "red", []]], c: [] };
eq(["одеська область"], changed(g1, signatures(slices(p3))), "змінився рівень — лише ця область");
const pa = { o: [], r: [[OD, "Б", null, "red", []], [OD, "А", null, "red", []]], c: [] }, pb = { o: [], r: [pa.r[1], pa.r[0]], c: [] };
eq([], changed(signatures(slices(pa)), signatures(slices(pb))), "порядок районів не важить");

const md = messageData(OD, s1["одеська область"].d, "T");
check(md.d && JSON.parse(md.d).r.length === 1 && md.v === "1" && md.o === OD, "повідомлення: дані зрізу");
const big = { r: [], o: [], c: Array.from({ length: 400 }, (_, i) => [OD, "Район", "Громада номер " + i, "2026-11-13T10:00:00Z", "red", []]) };
const mb = messageData(OD, big, "T");
check(mb.poke === "1" && !mb.d, "завеликий зріз — лише poke");

// JWT і надсилання (несправжній Google)
const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = { project_id: "demo", client_email: "x@demo.iam.gserviceaccount.com", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) };
const jwt = await signJwt(sa, 1800000000);
const [h, c, sg] = jwt.split(".");
const v = createVerify("RSA-SHA256"); v.update(h + "." + c);
check(v.verify(publicKey, Buffer.from(sg, "base64url")), "JWT: підпис перевіряється");
eq("https://www.googleapis.com/auth/firebase.messaging", JSON.parse(Buffer.from(c, "base64url")).scope, "JWT: область доступу");

const calls = [];
let failNext = false;
const fake = async (url, opt) => {
  calls.push({ url, body: opt.body, auth: opt.headers.Authorization });
  if (url.includes("oauth2")) return new Response(JSON.stringify({ access_token: "tok", expires_in: 3600 }));
  if (failNext) { failNext = false; return new Response("boom", { status: 500 }); }
  return new Response("{}");
};
check(!new Pusher({}, fake).enabled, "без секрету — вимкнено");
check(!new Pusher({ FCM_SA: "не json" }, fake).enabled, "зіпсований секрет — вимкнено, без винятку");
const off = new Pusher({}, fake);
await off.pushChanges({}, g1, s1, {}, Date.now());
eq(0, calls.length, "вимкнено — жодного запиту");

const pu = new Pusher({ FCM_SA: JSON.stringify(sa) }, fake);
check(pu.enabled, "із секретом — увімкнено");
let names = await pu.pushChanges({}, g1, s1, {}, Date.now());
const sends = calls.filter((x) => x.url.includes("messages:send"));
eq(2, sends.length, "дві області — два повідомлення");
eq(1, calls.filter((x) => x.url.includes("oauth2")).length, "токен отримано один раз");
check(sends.every((x) => x.auth === "Bearer tok" && x.url.includes("/projects/demo/")), "авторизація й проєкт");
const m0 = JSON.parse(sends[0].body).message;
check(m0.android.priority === "HIGH" && m0.topic.startsWith("o_") && !m0.notification, "високий пріоритет, тема, лише дані");
eq({ "київ": KY, "одеська область": OD }, names, "назви областей запам'ятано");
calls.length = 0;
const g2 = signatures(slices(p2));
names = await pu.pushChanges(g1, g2, slices(p2), names, Date.now());
const s2 = calls.filter((x) => x.url.includes("messages:send"));
eq(1, s2.length, "відбій у Києві — одне повідомлення");
eq({ r: [], o: [], c: [] }, JSON.parse(JSON.parse(s2[0].body).message.data.d), "відбій — порожній зріз");
eq(0, calls.filter((x) => x.url.includes("oauth2")).length, "токен узято з кешу");
failNext = true;
await pu.pushChanges(g2, g1, s1, names, Date.now());
check(pu.stats.failed === 1 && /500/.test(pu.stats.lastErr), "помилка FCM — порахована, без винятку");

console.log(`Пройдено: ${passed}, помилок: ${failed}`);
process.exit(failed ? 1 : 0);
