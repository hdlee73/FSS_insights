// node server/news-proxy/fund-lib.test.mjs — 펀드 순자산 공유(/funds/aum) 검증
import assert from "node:assert/strict";
import { handleFunds, validateUpload } from "./fund-lib.js";
import { categorize } from "./calls-lib.js";

const sample = (n = 120, base = 1000, recent = "20261126", gen = "2026-11-28T10:00:00+09:00") => ({
  generatedAt: gen,
  asOf: { recent, quarter: "20260930", year: "20251231" },
  companies: Array.from({ length: n }, (_, i) => ({ n: "운용사" + i, pub: [base + i, 5, 6], pri: [10, 20, 30], evil: "<script>" })),
});

// 가짜 D1: fund_aum 한 테이블만 흉내 낸다.
function fakeDb() {
  const rows = new Map();
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    async run() { return {}; },
    async first() { return rows.get(1) || null; },
    _apply() {
      if (sql.startsWith("INSERT OR REPLACE")) {
        const [body, generated_at, recent, total, uploaded_at] = args;
        const id = sql.includes("VALUES (2") ? 2 : 1;
        rows.set(id, { body, generated_at, recent, total, uploaded_at });
      }
    },
  });
  return { rows, prepare: (sql) => stmt(sql), async batch(list) { list.forEach((s) => s._apply()); } };
}
const post = (obj, extra = {}) => new Request("https://x/funds/aum", { method: "POST", body: JSON.stringify(obj), ...extra });
const get = () => new Request("https://x/funds/aum");

// 1) 검증: 정상·모양 오류·회사 부족·알 수 없는 필드 제거
let v = validateUpload(sample(), null);
assert.ok(v.ok && v.stored);
assert.ok(!v.body.includes("evil") && !v.body.includes("script"));
assert.equal(validateUpload(sample(50), null).ok, false);
assert.equal(validateUpload({ ...sample(), asOf: { recent: "x" } }, null).status, 400);
assert.equal(validateUpload({ ...sample(), companies: [...sample().companies, sample().companies[0]] }, null).status, 400); // 중복 이름

// 2) 직전 자료와 비교: 이전 기준일 409, 합계 급변 422, 같은 자료는 저장 안 함
const cur = { recent: "20261126", total: validateUpload(sample(), null).total, generatedAt: "2026-11-28T10:00:00+09:00" };
assert.equal(validateUpload(sample(120, 1000, "20261125"), cur).status, 409);
assert.equal(validateUpload(sample(120, 100000), cur).status, 422);
assert.equal(validateUpload(sample(), cur).stored, false);
assert.equal(validateUpload(sample(120, 1000, "20261127", "2026-11-28T11:00:00+09:00"), cur).stored, true);

// 3) 서버 처리: 없으면 404, 올리면 읽힘, 웹 출처는 403, 직후 재업로드 429, 직전 자료 보관
const db = fakeDb();
const env = { DB: db };
assert.equal((await handleFunds(get(), env, true)).status, 404);
assert.equal((await handleFunds(post(sample()), env, true, 1000)).status, 403);
let r = await handleFunds(post(sample()), env, false, 1_000_000);
assert.equal(r.status, 200);
assert.equal((await r.json()).stored, true);
r = await handleFunds(get(), env, true);
assert.equal(r.status, 200);
assert.equal((await r.json()).asOf.recent, "20261126");
r = await handleFunds(post(sample(120, 1000, "20261127", "2026-11-29T10:00:00+09:00")), env, false, 1_000_000 + 60_000);
assert.equal(r.status, 429);
r = await handleFunds(post(sample(120, 1000, "20261127", "2026-11-29T10:00:00+09:00")), env, false, 1_000_000 + 11 * 60_000);
assert.equal((await r.json()).stored, true);
assert.equal(db.rows.get(2).recent, "20261126"); // 직전 자료 보관
assert.equal(db.rows.get(1).recent, "20261127");
assert.equal((await handleFunds(new Request("https://x/funds/aum", { method: "POST", body: "{" }), env, false, 9e12)).status, 400);
assert.equal((await handleFunds(get(), {}, true)).status, 503);

// 4) 경로 집계
assert.equal(categorize("GET", "/funds/aum", new URLSearchParams()), "funds");
console.log("fund-lib OK");
