// 기업 개황 및 공시(DART, /dart): 금융감독원 전자공시(DART) Open API 중계.
// DART 인증키는 서버 비밀값(DART_API_KEY)으로만 존재한다.
import dartCorps from "./dart-corps.json";

const DART = "https://opendart.fss.or.kr/api";

/** 오류 문구에 인증키가 섞여 나가지 않게 crtfc_key 값을 가린다. */
export const redact = (text) => String(text || "").replace(/crtfc_key=[^&\s"'<>]*/gi, "crtfc_key=***");

// DART는 User-Agent가 없는 요청을 오류 페이지(error1.html)로 되돌려 보내므로(무한 리다이렉트) 항상 보낸다.
const DART_HEADERS = { "User-Agent": "Mozilla/5.0 (compatible; FSSInsights/2.1; +https://github.com/hdlee73/FSS_insights)", Accept: "application/json", "Accept-Language": "ko-KR,ko;q=0.9" };

const norm = (s) => String(s || "").replace(/\(\s*주\s*\)|㈜|주식회사|\s+/g, "").toLowerCase();

let index = null;
function corpIndex() {
  if (!index) index = (dartCorps.corps || []).map(([code, name, stock, cls]) => ({ code, name, stock: stock || "", cls: cls || (stock ? "Y" : "E"), key: norm(name) }));
  return index;
}

/** 회사명 또는 종목코드 검색. 이름이 일치할수록, 상장사일수록 앞에 둔다. */
export function searchCorps(query, limit = 15) {
  const q = norm(query);
  if (!q) return [];
  const isCode = /^\d{6}$/.test(q);
  const scored = [];
  for (const c of corpIndex()) {
    let score = -1;
    if (isCode) score = c.stock === q ? 100 : -1;
    else if (c.key === q) score = 90;
    else if (c.key.startsWith(q)) score = 70;
    else if (c.key.includes(q)) score = 50;
    if (score < 0) continue;
    scored.push({ c, score: score + (c.stock ? 5 : 0) - Math.min(c.key.length, 30) / 100 });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(({ c }) => ({ corp: c.code, name: c.name, stock: c.stock, cls: c.cls }));
}

/** 검색 결과의 시장 구분을 DART 기업개황(corp_cls) 기준으로 맞춘다. 실패한 곳은 목록의 값을 그대로 둔다. */
export async function withDartClass(items, lookup) {
  return Promise.all(items.map(async (it) => {
    try {
      const c = await lookup(it.corp);
      return { ...it, cls: c.cls, stock: c.stock || it.stock };
    } catch {
      return it;
    }
  }));
}

export const corpCount = () => corpIndex().length;

async function dartGet(env, name, params) {
  if (!env.DART_API_KEY) {
    const e = new Error("DART 인증키가 서버에 등록되지 않았습니다. 관리자가 DART_API_KEY를 등록하고 Deploy news proxy를 다시 실행해야 합니다.");
    e.status = 503;
    throw e;
  }
  const qs = new URLSearchParams({ crtfc_key: env.DART_API_KEY, ...params });
  let body;
  try {
    const res = await fetch(`${DART}/${name}.json?${qs}`, { headers: DART_HEADERS });
    if (!res.ok) throw new Error(`DART 서버 응답 ${res.status}`);
    body = await res.json();
  } catch (e) {
    throw new Error(redact(e.message || "DART 서버에 연결하지 못했습니다."));
  }
  // 000 정상, 013 조회된 데이터 없음. 그 밖은 오류(020 사용한도 초과 등).
  if (body.status === "000" || body.status === "013") return body;
  throw new Error(redact(`DART 오류 ${body.status}: ${body.message || ""}`));
}

const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, "");
const yearsAgo = (n) => {
  const d = new Date();
  d.setUTCFullYear(d.getUTCFullYear() - n);
  return ymd(d);
};

const CLS = { Y: "유가증권", K: "코스닥", N: "코넥스", E: "기타법인" };

/** 기업개황. 종목코드가 있으면 주가를 붙일 수 있도록 시장(KS/KQ)도 돌려준다. */
export async function company(env, corp) {
  const r = await dartGet(env, "company", { corp_code: corp });
  if (r.status === "013" || !r.corp_name) throw new Error("회사 정보를 찾지 못했습니다.");
  const stock = (r.stock_code || "").trim();
  return {
    corp,
    name: r.corp_name,
    nameEn: r.corp_name_eng || "",
    stock,
    cls: CLS[r.corp_cls] ? r.corp_cls : "E",
    marketName: CLS[r.corp_cls] || CLS.E,
    ceo: r.ceo_nm || "",
    corpNo: r.jurir_no || "",
    bizNo: r.bizr_no || "",
    address: r.adres || "",
    homepage: r.hm_url || "",
    ir: r.ir_url || "",
    phone: r.phn_no || "",
    industryCode: r.induty_code || "",
    established: r.est_dt || "",
    accountMonth: r.acc_mt || "",
  };
}

const REPORTS = [
  ["11011", "사업보고서"],
  ["11014", "3분기보고서"],
  ["11012", "반기보고서"],
  ["11013", "1분기보고서"],
];

/** 최대주주 및 특수관계인 지분. 같은 사람의 보통주·우선주는 한 줄로 묶는다. 가장 최근에 제출된 정기보고서 기준(최대 7번 조회). */
export async function holders(env, corp) {
  const now = new Date().getUTCFullYear();
  const tries = [];
  for (const [code, label] of REPORTS.slice(1)) tries.push([now, code, label]);
  tries.push([now - 1, "11011", "사업보고서"]);
  for (const [code, label] of REPORTS.slice(1)) tries.push([now - 1, code, label]);
  tries.push([now - 2, "11011", "사업보고서"]);
  let used = 0;
  for (const [year, code, label] of tries) {
    if (++used > 7) break;
    const r = await dartGet(env, "hyslrSttus", { corp_code: corp, bsns_year: String(year), reprt_code: code });
    const rows = r.list || [];
    if (!rows.length) continue;
    const num = (v) => {
      const n = parseFloat(String(v || "").replace(/,/g, ""));
      return Number.isFinite(n) ? n : null;
    };
    const slot = (x) => ({ shares: num(x.trmend_posesn_stock_co), ratio: num(x.trmend_posesn_stock_qota_rt) });
    const isPref = (x) => /우선/.test(String(x.stock_knd || ""));
    const totals = { common: null, pref: null };
    const byKey = new Map();
    for (const x of rows) {
      const name = String(x.nm || "").trim();
      if (name === "계" || name === "합계") {
        totals[isPref(x) ? "pref" : "common"] = slot(x).ratio;
        continue;
      }
      const relation = String(x.relate || "").trim();
      const key = `${name}|${relation}`;
      if (!byKey.has(key)) byKey.set(key, { name, relation, common: null, pref: null });
      byKey.get(key)[isPref(x) ? "pref" : "common"] = slot(x);
    }
    const items = [...byKey.values()];
    const sum = (k) => {
      const v = items.map((i) => (i[k] && i[k].ratio) || 0).reduce((p, q) => p + q, 0);
      return Math.round(v * 100) / 100;
    };
    return {
      basis: `${year} ${label}`,
      items,
      totalCommon: totals.common ?? (items.some((i) => i.common) ? sum("common") : null),
      totalPref: totals.pref ?? (items.some((i) => i.pref) ? sum("pref") : null),
    };
  }
  return { basis: "", items: [], totalCommon: null, totalPref: null };
}

const PBLNTF = { a: ["A"], m: ["B"], e: ["D", "E", "I"] };
const DART_VIEW = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

const ymdOk = (v) => /^\d{8}$/.test(String(v || ""));

/** 조회 기간(yyyymmdd)을 확인하고, 시작이 끝보다 10년 넘게 앞서면 10년으로 줄인다. */
export function dateRange(bgn, end) {
  const today = ymd(new Date());
  const to = ymdOk(end) && end <= today ? end : today;
  const earliest = String(Number(to.slice(0, 4)) - 10) + to.slice(4);
  let from = ymdOk(bgn) ? bgn : yearsAgo(1);
  if (from > to) from = to;
  if (from < earliest) from = earliest;
  return { from, to };
}

/** 공시 목록. type: a 정기보고서, m 주요사항보고서, e 기타공시(발행·지분·기타·거래소공시). 최대 400건. */
export async function filings(env, corp, type, bgn, end) {
  const kinds = PBLNTF[type];
  if (!kinds) throw new Error("공시 종류가 올바르지 않습니다.");
  const { from, to } = dateRange(bgn, end);
  const maxPages = type === "a" ? 2 : 3;
  const page = (ty, n) => dartGet(env, "list", { corp_code: corp, bgn_de: from, end_de: to, pblntf_ty: ty, page_no: String(n), page_count: "100", sort: "date", sort_mth: "desc" });
  const parts = await Promise.all(
    kinds.map(async (ty) => {
      const first = await page(ty, 1);
      const total = Math.min(Number(first.total_page) || 1, maxPages);
      const rest = await Promise.all(Array.from({ length: total - 1 }, (_, i) => page(ty, i + 2)));
      return [first, ...rest].flatMap((p) => p.list || []);
    })
  );
  const seen = new Set();
  const items = parts
    .flat()
    .filter((x) => (seen.has(x.rcept_no) ? false : seen.add(x.rcept_no)))
    .map((x) => ({ id: x.rcept_no, title: String(x.report_nm || "").trim(), date: x.rcept_dt, by: String(x.flr_nm || "").trim(), note: String(x.rm || "").trim(), url: DART_VIEW + x.rcept_no, src: "DART" }))
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
    .slice(0, 400);
  return { items, from, to };
}

// ───────── 재무손익 현황 ─────────

const amt = (v) => {
  const n = parseFloat(String(v ?? "").replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
};
const toEok = (n) => (n == null ? null : Math.round(n / 1e6) / 100); // 원 → 억원(소수 둘째 자리)
const squash = (s) => String(s || "").replace(/\s+/g, "");

const ACCOUNTS = {
  assets: [/^자산총계$/],
  liab: [/^부채총계$/],
  equity: [/^자본총계$/],
  revenue: [/^매출액$/, /^수익\(매출액\)$/, /^영업수익$/, /^매출$/, /^순영업수익$/, /^보험수익$/, /^수익$/],
  op: [/^영업이익/, /^영업손익/],
  net: [/^당기순이익/, /^당기순손익/, /^(분기|반기)순이익/, /^(분기|반기)순손익/],
};

/** 주요계정(fnlttSinglAcnt) 한 보고서. 없으면 빈 목록. */
async function acnt(env, corp, year, code) {
  const r = await dartGet(env, "fnlttSinglAcnt", { corp_code: corp, bsns_year: String(year), reprt_code: code });
  return r.list || [];
}

/** 연결(CFS)·별도(OFS)·재무제표 구분(BS/IS)에서 계정 이름이 맞는 첫 행. */
function pick(rows, fs, sj, key) {
  const inSj = rows.filter((x) => x.fs_div === fs && (x.sj_div === sj || (sj === "IS" && x.sj_div === "CIS")));
  for (const re of ACCOUNTS[key]) {
    const hit = inSj.find((x) => re.test(squash(x.account_nm)));
    if (hit) return hit;
  }
  return null;
}
const field = (row, f) => (row ? toEok(amt(row[f])) : null);

const QUARTER = { "11013": ["1분기", "01", "03", "01"], "11012": ["반기", "04", "06", "01"], "11014": ["3분기", "07", "09", "01"] };

/** 기업의 재무상태·손익·5개년 추이·주당이익. 단위는 억원. accMonth는 결산월(12가 아니면 월 표기를 생략). */
export async function finance(env, corp, accMonth) {
  const now = new Date().getUTCFullYear();
  const dec = !accMonth || String(accMonth) === "12";
  const [q3, half, q1, a1, a2] = await Promise.all([
    acnt(env, corp, now, "11014"),
    acnt(env, corp, now, "11012"),
    acnt(env, corp, now, "11013"),
    acnt(env, corp, now - 1, "11011"),
    acnt(env, corp, now - 2, "11011"),
  ]);
  const annualRows = a1.length ? a1 : a2;
  const A = a1.length ? now - 1 : now - 2;
  if (!annualRows.length) return { basis: "", unit: "억원", balance: [], income: [], trend: [], eps: {}, note: "사업보고서 재무 자료를 찾지 못했습니다." };
  const older = await acnt(env, corp, A - 3, "11011");
  const quarter = q3.length ? ["11014", q3] : half.length ? ["11012", half] : q1.length ? ["11013", q1] : null;
  const fs = annualRows.some((x) => x.fs_div === "CFS") ? "CFS" : "OFS";
  const basis = fs === "CFS" ? "연결" : "별도";

  const bs = (rows, f) => ({ assets: field(pick(rows, fs, "BS", "assets"), f), liab: field(pick(rows, fs, "BS", "liab"), f), equity: field(pick(rows, fs, "BS", "equity"), f) });
  const is = (rows, f) => ({ revenue: field(pick(rows, fs, "IS", "revenue"), f), op: field(pick(rows, fs, "IS", "op"), f), net: field(pick(rows, fs, "IS", "net"), f) });
  const yearEnd = (y) => (dec ? `${y}.12` : `${y}`);

  const balance = [];
  const income = [];
  if (quarter) {
    const [code, rows] = quarter;
    const [qName, m1, m2, mStart] = QUARTER[code];
    const label = dec ? `${now}.${m2} ${qName}말` : `${now} ${qName}말`;
    balance.push({ label, ...bs(rows, "thstrm_amount") });
    const cur = is(rows, "thstrm_amount");
    const cum = is(rows, "thstrm_add_amount");
    const useCum = cum.revenue != null || cum.op != null || cum.net != null ? cum : cur;
    income.push({ label: "분기", sub: dec ? `${now}.${m1}~${m2}` : qName, ...cur });
    income.push({ label: "누적", sub: dec ? `${now}.${mStart}~${m2}` : `${qName}까지`, ...useCum });
    income.push({ label: `${A}년 연간`, sub: "전년", ...is(annualRows, "thstrm_amount") });
    income.push({ label: `${A - 1}년 연간`, sub: "전전년", ...is(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A), ...bs(annualRows, "thstrm_amount") });
    balance.push({ label: yearEnd(A - 1), ...bs(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A - 2), ...bs(annualRows, "bfefrmtrm_amount") });
  } else {
    balance.push({ label: yearEnd(A), ...bs(annualRows, "thstrm_amount") });
    balance.push({ label: yearEnd(A - 1), ...bs(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A - 2), ...bs(annualRows, "bfefrmtrm_amount") });
    if (older.length) balance.push({ label: yearEnd(A - 3), ...bs(older, "thstrm_amount") });
    income.push({ label: `${A}년 연간`, sub: "", ...is(annualRows, "thstrm_amount") });
    income.push({ label: `${A - 1}년 연간`, sub: "", ...is(annualRows, "frmtrm_amount") });
    income.push({ label: `${A - 2}년 연간`, sub: "", ...is(annualRows, "bfefrmtrm_amount") });
  }

  // 5개년 추이: 사업보고서 두 개(A, A-3)가 각각 3개 연도를 담고 있다.
  const points = new Map();
  const put = (y, rows, f) => {
    const b = bs(rows, f);
    const i = is(rows, f);
    points.set(y, { year: y, ...i, debt: b.equity ? Math.round((b.liab / b.equity) * 1000) / 10 : null });
  };
  put(A, annualRows, "thstrm_amount");
  put(A - 1, annualRows, "frmtrm_amount");
  put(A - 2, annualRows, "bfefrmtrm_amount");
  if (older.length) {
    put(A - 3, older, "thstrm_amount");
    put(A - 4, older, "frmtrm_amount");
  }
  const trend = [...points.values()].sort((x, y) => x.year - y.year).slice(-5);

  // 주당이익(EPS): 전체 재무제표의 손익계산서에서 "기본주당이익"류 계정을 찾는다. 실패해도 나머지는 그대로 돌려준다.
  const eps = {};
  const epsOf = async (year) => {
    const r = await dartGet(env, "fnlttSinglAcntAll", { corp_code: corp, bsns_year: String(year), reprt_code: "11011", fs_div: fs });
    const rows = (r.list || []).filter((x) => x.sj_div === "IS" || x.sj_div === "CIS");
    const cands = rows.filter((x) => /주당(순)?(이익|손익)|주당순이익/.test(squash(x.account_nm)));
    const hit = cands.find((x) => /기본/.test(squash(x.account_nm))) || cands[0];
    if (!hit) return;
    const set = (y, v) => {
      const n = amt(v);
      if (n != null && eps[y] == null) eps[y] = n;
    };
    set(year, hit.thstrm_amount);
    set(year - 1, hit.frmtrm_amount);
    set(year - 2, hit.bfefrmtrm_amount);
  };
  await Promise.all([epsOf(A), older.length ? epsOf(A - 3) : Promise.resolve()]).catch(() => {});

  return {
    basis,
    unit: "억원",
    annualYear: A,
    quarterLabel: quarter ? `${now} ${QUARTER[quarter[0]][0]}` : "",
    balance,
    income,
    trend,
    eps,
    note: "",
  };
}
