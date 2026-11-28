// 펀드 순자산(운용사·증권사별 공모/사모) 공유 저장소.
// 금융투자협회 FreeSIS가 해외 IP를 막아 서버(GitHub Actions)가 못 받으므로, 한국 망에 있는 앱이 받은 자료를
// POST /funds/aum 으로 올리고 웹·앱은 GET /funds/aum 으로 읽는다. D1(env.DB)에 최신 1건과 직전 1건만 둔다.
// 올리기는 앱 토큰이 있는 요청만 받고(웹 출처 요청은 worker.js 가 막는다), 그래도 위조·오염을 막으려고 모양·규모·날짜를 검증하고
// 알려진 필드만 다시 직렬화해 저장한다.

const MAX_BODY = 1024 * 1024;
const MIN_COMPANIES = 100;
const MIN_GAP_MS = 10 * 60 * 1000; // 같은 자료를 거푸 올리는 것을 막는다
const TOTAL_RATIO = [0.5, 1.5]; // 직전 자료 합계 대비 허용 범위

const json = (data, status = 200, extra = {}) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...extra } });

let ready = null;
async function ensure(db) {
  ready ||= db
    .prepare("CREATE TABLE IF NOT EXISTS fund_aum (id INTEGER PRIMARY KEY, body TEXT NOT NULL, generated_at TEXT, recent TEXT, total INTEGER, uploaded_at INTEGER)")
    .run()
    .catch((e) => {
      ready = null;
      throw e;
    });
  await ready;
}

const three = (a) => {
  const out = [0, 0, 0];
  if (!Array.isArray(a)) return out;
  for (let i = 0; i < 3; i++) {
    const v = Number(a[i]);
    out[i] = Number.isFinite(v) && v >= 0 && v < 1e9 ? Math.round(v) : 0; // 억원, 1e9억(=100경)이면 확실히 오류
  }
  return out;
};

/** 올라온 본문을 검증하고 정리한다. 반환: { ok, body(문자열), recent, total, generatedAt } 또는 { ok:false, status, error }. */
export function validateUpload(obj, current) {
  const bad = (status, error) => ({ ok: false, status, error });
  if (!obj || typeof obj !== "object") return bad(400, "JSON 객체가 아닙니다.");
  const asOf = obj.asOf || {};
  for (const k of ["recent", "quarter", "year"]) if (!/^\d{8}$/.test(String(asOf[k] || ""))) return bad(400, `기준일(${k})이 올바르지 않습니다.`);
  if (!Array.isArray(obj.companies) || obj.companies.length < MIN_COMPANIES || obj.companies.length > 2000) return bad(400, "회사 수가 올바르지 않습니다.");
  const companies = [];
  const seen = new Set();
  let total = 0;
  for (const c of obj.companies) {
    const n = c && typeof c.n === "string" ? c.n.trim() : "";
    if (!n || n.length > 60 || seen.has(n)) return bad(400, "회사 항목이 올바르지 않습니다.");
    seen.add(n);
    const pub = three(c.pub);
    const pri = three(c.pri);
    total += pub[0] + pri[0];
    companies.push({ n, pub, pri });
  }
  if (total <= 0) return bad(400, "합계가 0입니다.");
  const generatedAt = /^\d{4}-\d{2}-\d{2}T[\d:.+Z-]{6,12}$/.test(String(obj.generatedAt || "")) ? String(obj.generatedAt) : new Date().toISOString();
  const recent = String(asOf.recent);
  if (current) {
    if (recent < current.recent) return bad(409, "저장된 자료보다 기준일이 이전입니다.");
    if (current.total > 0) {
      const r = total / current.total;
      if (r < TOTAL_RATIO[0] || r > TOTAL_RATIO[1]) return bad(422, "직전 자료와 합계 차이가 너무 큽니다.");
    }
    if (recent === current.recent && generatedAt <= current.generatedAt) return { ok: true, stored: false, reason: "이미 같거나 더 새 자료가 있습니다." };
  }
  const body = JSON.stringify({
    generatedAt,
    unit: "억원",
    asOf: { recent, quarter: String(asOf.quarter), year: String(asOf.year) },
    source: "금융투자협회 종합통계 포털 '회사별설정규모'(앱이 기기에서 직접 수집, 펀드 순자산총액, 기관전용 사모펀드 제외)",
    companies: companies.sort((a, b) => (a.n < b.n ? -1 : 1)),
  });
  return { ok: true, stored: true, body, recent, total, generatedAt };
}

async function readCurrent(db) {
  const row = await db.prepare("SELECT body, generated_at, recent, total, uploaded_at FROM fund_aum WHERE id = 1").first();
  return row || null;
}

/** GET /funds/aum, POST /funds/aum. 앱 토큰·웹 출처 검사는 worker.js 가 먼저 한다(웹 출처의 POST는 거절). */
export async function handleFunds(request, env, fromWeb, now = Date.now()) {
  const db = env.DB;
  if (!db) return json({ error: "D1(DB) 바인딩이 연결되지 않았습니다." }, 503);
  await ensure(db);
  if (request.method === "GET") {
    const row = await readCurrent(db);
    if (!row) return json({ error: "아직 올라온 자료가 없습니다." }, 404);
    return new Response(row.body, { headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=300" } });
  }
  if (request.method !== "POST") return new Response("Method Not Allowed", { status: 405 });
  if (fromWeb) return json({ error: "웹에서는 올릴 수 없습니다." }, 403);
  const len = Number(request.headers.get("Content-Length") || 0);
  if (len > MAX_BODY) return json({ error: "본문이 너무 큽니다." }, 413);
  const text = await request.text();
  if (text.length > MAX_BODY) return json({ error: "본문이 너무 큽니다." }, 413);
  let obj;
  try {
    obj = JSON.parse(text);
  } catch {
    return json({ error: "JSON을 읽지 못했습니다." }, 400);
  }
  const row = await readCurrent(db);
  const current = row ? { recent: row.recent || "", total: row.total || 0, generatedAt: row.generated_at || "" } : null;
  if (row && now - (row.uploaded_at || 0) < MIN_GAP_MS) return json({ stored: false, reason: "방금 올라온 자료가 있어 잠시 뒤 다시 올려 주세요." }, 429);
  const v = validateUpload(obj, current);
  if (!v.ok) return json({ error: v.error }, v.status);
  if (!v.stored) return json({ stored: false, reason: v.reason });
  const stmts = [];
  if (row) stmts.push(db.prepare("INSERT OR REPLACE INTO fund_aum (id, body, generated_at, recent, total, uploaded_at) VALUES (2, ?, ?, ?, ?, ?)").bind(row.body, row.generated_at, row.recent, row.total, row.uploaded_at));
  stmts.push(db.prepare("INSERT OR REPLACE INTO fund_aum (id, body, generated_at, recent, total, uploaded_at) VALUES (1, ?, ?, ?, ?, ?)").bind(v.body, v.generatedAt, v.recent, v.total, now));
  await db.batch(stmts);
  return json({ stored: true, recent: v.recent, companies: JSON.parse(v.body).companies.length });
}
