// FISIS(금융통계정보시스템) 공용 로직. 서버(worker.js)와 배포할 때 자료를 미리 만드는 build-fisis-data.mjs가 함께 쓴다.
// 금융권역코드(lrgDiv/partDiv): A 국내은행, F 증권사, G 자산운용사, H 생명보험, I 손해보험, E 상호저축은행, C 신용카드사.

export const FISIS_API = "https://fisis.fss.or.kr/openapi";
// 선물사(W)는 화면에서 증권사(F)에 포함해 보여 주지만 통계표는 따로 있어 권역으로 따로 만든다.
export const FISIS_SECTORS = ["F", "W", "G", "A", "H", "I", "E", "C"];
export const FISIS_TERMS = ["Q", "H", "Y"];
export const CLOSED = /\[폐\]|\(폐\)|［폐］/; // 폐업한 회사는 이름 끝에 [폐]가 붙는다.

export const rowsOf = (v) => (Array.isArray(v) ? v : v && Array.isArray(v.row) ? v.row : v && typeof v === "object" ? [v] : []);
export const num = (x) => {
  if (x === null || x === undefined || String(x).trim() === "" || String(x).trim() === "-") return null;
  const n = Number(String(x).replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
};

/** FISIS Open API 한 번 호출. 오류 코드는 사유와 함께 Error로 던진다(인증키는 메시지에 넣지 않는다). */
export async function fisisFetch(key, name, params, fetchImpl = fetch) {
  if (!key) throw new Error("FISIS 인증키(FISIS_API_KEY)가 아직 설정되지 않았습니다.");
  const q = new URLSearchParams({ lang: "kr", auth: key, ...params });
  const res = await fetchImpl(`${FISIS_API}/${name}.json?${q}`);
  if (res.status === 429) throw Object.assign(new Error("FISIS 일일 호출 한도를 넘었습니다. 내일 다시 시도해 주세요."), { quota: true });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`FISIS 응답을 읽지 못했습니다 (HTTP ${res.status})`);
  }
  const result = body.result || body;
  const code = String(result.err_cd ?? "000");
  if (code === "020") throw Object.assign(new Error("FISIS 일일 호출 한도를 넘었습니다. 내일 다시 시도해 주세요."), { quota: true });
  if (code !== "000" && code !== "100") throw new Error(`FISIS 오류 ${code}: ${result.err_msg || ""}`.trim()); // 100: 조회 결과 없음
  return result;
}

// ---- 통계표·계정 찾기: 통계표 이름·계정 이름으로 필요한 항목을 찾는다(통계표 번호를 코드에 박아 두지 않는다) ----
// 계정 이름은 앞의 번호("Ⅰ.", "1)")와 공백을 떼고 견줘서 "자산 총계", "Ⅰ. 자산총계" 같은 표기 변형도 잡는다.
export const normName = (s) =>
  String(s || "")
    .replace(/[\s\u00a0]/g, "")
    .replace(/^([\dⅠ-Ⅻ]{1,3}|[가-하]|[A-Za-z]|\(\d+\)|\([가-하]\))[.)\]、]+/, "")
    .replace(/^[-·ㆍ∙▶▷○●※]+/, "");
export const ACCOUNT_NOISE = /비율|률$|증감|구성비|전년|전기/;
const BS = /재무상태/;
const IS = /손익/;
export const FIN_METRICS = [
  { key: "assets", label: "자산총계", req: BS, pats: [/^자산총계$/, /^총자산$/, /^자산총액$/, /^자산합계$/, /^자산계$/, /자산총계|총자산|자산합계|자산총액/, /^자산$/] },
  { key: "liab", label: "부채총계", req: BS, pats: [/^부채총계$/, /^총부채$/, /^부채총액$/, /^부채합계$/, /^부채계$/, /부채총계|총부채|부채합계|부채총액/, /^부채$/] },
  { key: "equity", label: "자기자본", req: BS, pats: [/^자본총계$/, /^자기자본$/, /^자기자본총계$/, /^자본총액$/, /^자본합계$/, /^자본계$/, /자본총계|총자본|자본합계|자본총액|자기자본총계/, /^자본$/] },
  { key: "revenue", label: "영업수익", req: IS, pats: [/^영업수익$/, /^영업수익\(?합계\)?$/, /영업수익/, /^총수익$/, /수익(합계|총계)/, /^수익$/, /^이자수익$/, /이자수익/] },
  { key: "expense", label: "영업비용", req: IS, pats: [/^영업비용$/, /^영업비용\(?합계\)?$/, /영업비용/, /^총비용$/, /비용(합계|총계)/, /^비용$/, /^이자비용$/, /이자비용/] },
  { key: "opinc", label: "영업이익", req: IS, pats: [/^영업이익(\(손실\))?$/, /^영업(이익|손익)$/, /영업(이익|손익)/] },
  { key: "netincome", label: "당기(분반기)순이익", req: IS, pats: [/^당기순(이익|손익)$/, /^분기순(이익|손익)$/, /^반기순(이익|손익)$/, /^당(분|반)?기순(이익|손익)/, /당기순(이익|손익)/, /^순(이익|손익)$/, /^당기순이익\(손실\)$/] },
];
// 비율 항목: 이름에 "비율/율/배율/NCR"이 든 계정만 후보로 삼는다(금액 계정이 비율로 잡히는 것을 막는다). exclude: 이 이름이면 제외.
export const RATIO_SPECS = {
  F: [
    { key: "ncr", label: "순자본비율(개별)", pats: [/^순자본비율/, /순자본비율|NCR/], pool: "sep" },
    { key: "ncr_c", label: "순자본비율(연결)", pats: [/^순자본비율/, /순자본비율|NCR/], pool: "con" },
    { key: "lev", label: "레버리지비율", pats: [/^레버리지비율/, /레버리지/] },
    { key: "liq", label: "유동성비율", pats: [/^유동성비율/, /^(잔존만기)?3개월이내유동성비율/, /유동성비율/], exclude: /외화|자산|부채/ },
  ],
  G: [],
  A: [
    { key: "bis", label: "BIS기준 자기자본비율", pats: [/^(총자본|BIS)(자기자본)?비율/, /BIS/, /총자본비율/, /자기자본비율/] },
    { key: "delinq", label: "총대출채권 연체율", pats: [/총대출채권.*연체율/, /^총대출.*연체율/, /연체율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/^고정이하여신비율/, /고정이하여신비율/, /고정이하(채권|대출)비율/, /부실채권비율/] },
    { key: "lcr", label: "유동성커버리지비율", pats: [/유동성커버리지/, /LCR/] },
  ],
  E: [
    { key: "bis", label: "BIS자기자본비율", pats: [/BIS/, /총자본비율/, /자기자본비율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/^고정이하여신비율/, /고정이하여신비율/, /고정이하(채권|대출)비율/, /부실채권비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "liq", label: "유동성비율", pats: [/^유동성비율/, /유동성비율/], exclude: /외화|자산|부채|커버리지/ },
  ],
  C: [
    { key: "adj", label: "조정자기자본비율", pats: [/^조정자기자본비율/, /조정자기자본비율/, /자기자본비율/] },
    { key: "npl", label: "고정이하채권비율", pats: [/고정이하(채권|여신|자산)비율/, /부실채권비율/] },
  ],
  H: [{ key: "solv", label: "지급여력비율", pats: [/K-?ICS|킥스|신지급여력/, /지급여력비율/] }],
  I: [{ key: "solv", label: "지급여력비율", pats: [/K-?ICS|킥스|신지급여력/, /지급여력비율/] }],
};
RATIO_SPECS.W = RATIO_SPECS.F;
const ANY_ACCT = /^(합계|계|총계|전체|펀드합계|전체펀드)$/;
export const FUND_SPECS = [
  { key: "fund_all", label: "펀드 설정원본", col: /설정원본/, pats: [ANY_ACCT, /합계|총계/, /설정원본/] },
  { key: "nav_all", label: "펀드 순자산", col: /순자산/, pats: [ANY_ACCT, /합계|총계/, /순자산/] },
  { key: "fund_pub", label: "공모펀드 설정원본", col: /설정원본/, pats: [/공모/] },
  { key: "nav_pub", label: "공모펀드 순자산", col: /순자산/, pats: [/공모/] },
  { key: "fund_priv", label: "사모펀드 설정원본", col: /설정원본/, pats: [/사모/] },
  { key: "nav_priv", label: "사모펀드 순자산", col: /순자산/, pats: [/사모/] },
];

const RATIO_NAME = /비율|율|배율|NCR|ICS/;
const AMOUNT_NAME = /잔액$|금액|액$|자산$|부채$|채권$/;
const BAD_SCOPE = /가계|기업|중소|대기업|개인|주택|신용대출|카드론|현금서비스|할부|리스|PF|부동산|건설|신탁|전월|증감|전년|구성/;

/** 후보 점수: 앞선 패턴일수록, 합계·전체 성격일수록 높고, 일부 부문(가계·기업 등)이나 증감은 낮다. */
function scoreOf(spec, table, acct, patIdx, ratio) {
  const nm = normName(acct.name);
  let s = 1000 - patIdx * 50 - nm.length * 0.5;
  if (spec.tables && spec.tables.test(table.listNm)) s += 20;
  if (/이전|구\)/.test(table.listNm)) s -= 150; // 예전 양식 통계표
  if (/이후/.test(table.listNm)) s += 10;
  if (/^(총|전체|합계)|합계|총대출|전체|계$/.test(nm)) s += 30;
  if (/합계|전체|총/.test(table.listNm)) s += 15;
  if (ratio && BAD_SCOPE.test(nm + table.listNm) && !(spec.pats.some((p) => p.test(nm)) && BAD_SCOPE.test(spec.label))) s -= 200;
  if (ratio && /연결/.test(table.listNm)) s -= 5;
  return s;
}

/** 패턴에 맞는 (통계표, 계정) 후보를 점수 높은 순으로 돌려준다. */
export function pickCandidates(tables, accountsOf, spec, { ratio = false, skipNoise = false } = {}) {
  const found = [];
  const seen = new Set();
  spec.pats.forEach((pat, patIdx) => {
    for (const t of tables) {
      if (spec.req && !spec.req.test(t.listNm)) continue;
      for (const a of accountsOf.get(t.listNo) || []) {
        const nm = normName(a.name);
        if (!pat.test(nm)) continue;
        if (spec.exclude && spec.exclude.test(nm)) continue;
        if (skipNoise && ACCOUNT_NOISE.test(nm)) continue;
        if (ratio && (!RATIO_NAME.test(nm) || AMOUNT_NAME.test(nm))) continue;
        const id = `${t.listNo}/${a.code}`;
        if (seen.has(id)) continue;
        seen.add(id);
        const label = spec.key === "revenue" && /이자수익/.test(nm) && !/영업/.test(nm) ? "이자수익" : spec.key === "expense" && /이자비용/.test(nm) && !/영업/.test(nm) ? "이자비용" : spec.label;
        found.push({ score: scoreOf(spec, t, a, patIdx, ratio), key: spec.key, label, listNo: t.listNo, listNm: t.listNm, accountCd: a.code, accountNm: a.name, col: spec.col ? spec.col.source : "" });
      }
    }
  });
  return found.sort((x, y) => y.score - x.score);
}

/** 가장 알맞은 후보 하나(나머지는 alts에 남겨 배포 때 실제 자료로 검증한다). */
export function pickAccount(tables, accountsOf, spec, opt = {}) {
  let cands = pickCandidates(tables, accountsOf, spec, opt);
  if (!cands.length && spec.req) cands = pickCandidates(tables, accountsOf, { ...spec, req: null }, opt); // 통계표 이름이 예상과 달라도 계정 이름으로 찾는다
  const [best, ...rest] = cands;
  if (!best) return null;
  delete best.score;
  best.alts = rest.slice(0, 4).map(({ score, ...r }) => r);
  return best;
}

async function inChunks(items, size, fn) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(...(await Promise.all(items.slice(i, i + size).map(fn))));
  return out;
}

/**
 * 권역의 통계표·계정 목록에서 재무·비율·펀드 항목이 어느 통계표의 어느 계정인지 찾는다.
 * api = { lists(sector) → [{sml, listNo, listNm}], accounts(listNo) → [{code, name}] }
 */
export async function resolveSpec(api, sector) {
  const lists = await api.lists(sector);
  const of = (re) => lists.filter((l) => re.test(l.sml));
  const ratio = of(/경영지표/);
  // 재무: 요약재무상태표·요약손익계산서·연결재무상태표·연결손익계산서만 쓴다(은행은 은행계정 통계표만).
  // 이름이 예상과 다르면 재무현황 분류 전체로 넓힌다.
  const named = lists.filter((l) => /재무상태표|손익계산서/.test(l.listNm) && /요약|연결/.test(l.listNm));
  let fin = named.length ? named : lists.filter((l) => /재무현황/.test(l.sml) || /재무상태|대차대조|손익|포괄/.test(l.listNm));
  if (sector === "A" && fin.some((l) => /은행계정/.test(l.listNm))) fin = fin.filter((l) => /은행계정/.test(l.listNm) || /연결/.test(l.listNm));
  const fund = sector === "G" ? of(/영업활동|재무현황/).filter((l) => /펀드|설정|순자산|수탁|운용/.test(l.listNm)) : [];
  // 일반 비율 지표가 경영지표 분류 밖에 있을 수도 있어 이름에 지표·비율이 든 통계표도 후보에 넣는다.
  const ratioPool = [...new Map([...ratio, ...lists.filter((l) => /지표|비율|유동성|자본적정|건전성|연체/.test(l.listNm))].map((l) => [l.listNo, l])).values()];
  const wanted = [...new Map([...fin, ...ratioPool, ...fund].map((l) => [l.listNo, l])).values()];
  const accountsOf = new Map();
  await inChunks(wanted, 6, async (l) => accountsOf.set(l.listNo, await api.accounts(l.listNo).catch(() => [])));
  const pickAll = (tables, specs, opt) => specs.map((sp) => pickAccount(tables, accountsOf, sp, opt)).filter(Boolean);
  const ratioCon = ratioPool.filter((l) => /연결/.test(l.listNm));
  const ratioSep = ratioPool.filter((l) => !/연결/.test(l.listNm));
  const ratioSpecs = RATIO_SPECS[sector] || [];
  const basis = {};
  const separate = fin.filter((l) => !/연결/.test(l.listNm));
  const connected = fin.filter((l) => /연결/.test(l.listNm));
  basis["별도"] = pickAll(separate.length ? separate : fin, FIN_METRICS, { skipNoise: true });
  if (connected.length) basis["연결"] = pickAll(connected, FIN_METRICS, { skipNoise: true });
  return {
    basis,
    ratios: ratioSpecs.map((sp) => pickAccount(sp.pool === "con" ? ratioCon : ratioSep.length ? ratioSep : ratioPool, accountsOf, sp, { ratio: true })).filter(Boolean),
    funds: pickAll(fund, FUND_SPECS),
    terms: {},
    tables: { fin: fin.map((l) => l.listNm), ratio: ratioPool.map((l) => l.listNm), fund: fund.map((l) => l.listNm) },
  };
}

/** spec에 쓰인 서로 다른 통계표 번호(대체 후보 포함). */
export function specListNos(spec) {
  const all = [...Object.values(spec.basis).flat(), ...spec.ratios, ...spec.funds];
  return [...new Set(all.flatMap((m) => [m.listNo, ...(m.alts || []).map((a) => a.listNo)]))];
}

/**
 * 표본 회사로 실제 자료를 받아 보며 각 항목의 첫 번째로 "값이 나오는" 후보를 확정한다(나머지 후보는 버린다).
 * probe(code, listNo, accountCd) → parseTable 결과(또는 null).
 */
export async function validateSpec(spec, samples, probe, log = () => {}) {
  const groups = [
    ...Object.entries(spec.basis).map(([b, list]) => ["fin", b, list]),
    ["ratio", "", spec.ratios],
    ["fund", "", spec.funds],
  ];
  for (const [group, basis, list] of groups) {
    for (let i = 0; i < list.length; i++) {
      const m = list[i];
      const cands = [m, ...(m.alts || [])];
      let chosen = null;
      for (const c of cands) {
        for (const code of samples) {
          const t = await probe(code, c.listNo, c.accountCd).catch(() => null);
          if (t && metricFromTable({ ...c, group, basis }, t)) {
            chosen = c;
            break;
          }
        }
        if (chosen) break;
      }
      const picked = chosen || m;
      const { alts, ...clean } = picked;
      list[i] = clean;
      log(`${group}${basis ? "/" + basis : ""} ${m.label}: ${chosen ? "" : "(표본 자료 없음) "}${clean.listNm} · ${clean.accountNm}`);
    }
  }
  return spec;
}

export function monthsBack(term, now = new Date()) {
  const y = now.getUTCFullYear();
  const end = `${y}${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
  const span = { Q: 3, H: 5, Y: 8 }[term];
  return { start: `${y - span}${end.slice(4)}`, end };
}

/** statisticsInfoSearch 응답을 계정별 시계열로 바꾼다. 자료가 없으면 null. */
export function parseTable(result, term) {
  const rows = rowsOf(result.list);
  if (!rows.length) return null;
  const desc = result.description || {};
  const cols = rowsOf(desc.column ?? desc).map((c) => ({ id: String(c.column_id || ""), name: String(c.column_nm || "") })).filter((c) => c.id);
  const columns = cols.length ? cols : [{ id: "a", name: "" }];
  const accounts = {};
  for (const row of rows) {
    const key = String(row.account_cd ?? "");
    const a = (accounts[key] ||= { name: String(row.account_nm || ""), points: [] });
    const v = {};
    const raw = {};
    for (const c of columns) {
      v[c.id] = num(row[c.id]);
      raw[c.id] = row[c.id] == null ? "" : String(row[c.id]).trim();
    }
    a.points.push({ month: String(row.base_month || ""), v, raw });
  }
  // 시점별로 표 안 가장 큰 값(단위 판단용). 시점마다 단위가 다르게 들어오는 자료가 있어 시점별로 따로 본다.
  const monthMax = {};
  let max = 0;
  for (const a of Object.values(accounts)) {
    a.points.sort((x, y) => x.month.localeCompare(y.month));
    for (const p of a.points) {
      for (const c of columns) {
        const v = p.v[c.id];
        if (v === null) continue;
        monthMax[p.month] = Math.max(monthMax[p.month] || 0, Math.abs(v));
        max = Math.max(max, Math.abs(v));
      }
    }
  }
  return { term, unit: String(desc.unit || ""), name: String((rows[0] && rows[0].finance_nm) || ""), columns, accounts, max, monthMax };
}

/** 한 회사·한 통계표를 [firstTerm부터] 분기→반기→연 순으로 받는다. call(params) → statisticsInfoSearch 결과. */
export async function fetchTable(call, finance, listNo, firstTerm = "Q", accountCd = "") {
  const order = FISIS_TERMS.slice(Math.max(0, FISIS_TERMS.indexOf(firstTerm)));
  for (const term of order) {
    const { start, end } = monthsBack(term);
    const params = { financeCd: finance, listNo, term, startBaseMm: start, endBaseMm: end };
    if (accountCd) params.accountCd = accountCd;
    const table = parseTable(await call(params), term);
    if (table) return table;
  }
  return null;
}

// 손익 항목은 당분기 열과 누계 열이 따로 있다.
const PL_KEYS = /^(revenue|expense|opinc|netincome)$/;
const CUM_COL = /누계|누적|연초/;
const AMOUNT_COL = /금액|잔액|채권|여신|대출|원본|자산|부채|규모|액$/;

/** 열 고르기: 열 이름 패턴이 있으면 그 열. 비율은 "…율" 열을, 손익은 누적 열을, 그 밖은 첫 열을 쓴다. */
export function pickColumn(table, colSource, group = "", key = "", cum = false) {
  if (cum) return table.columns.find((c) => CUM_COL.test(c.name)) || null;
  if (colSource) return table.columns.find((c) => new RegExp(colSource).test(c.name.replace(/\s/g, ""))) || null;
  const cols = table.columns;
  if (!cols.length) return null;
  if (PL_KEYS.test(key) && cols.length > 1) {
    const cum = cols.find((c) => CUM_COL.test(c.name));
    return cum ? cols.find((c) => c !== cum && !CUM_COL.test(c.name)) || cols[0] : cols[0];
  }
  if (group === "ratio" && cols.length > 1) return cols.find((c) => /비율|율|%/.test(c.name)) || cols.find((c) => !AMOUNT_COL.test(c.name)) || cols[0];
  return cols[0];
}

/** 단위 글자 → 원 단위 배수(모르면 null). */
export function unitMult(unit) {
  const u = String(unit || "").replace(/\s/g, "");
  if (/조/.test(u)) return 1e12;
  if (/억/.test(u)) return 1e8;
  if (/백만/.test(u)) return 1e6;
  if (/천만/.test(u)) return 1e7;
  if (/만원|^만$/.test(u)) return 1e4;
  if (/천원|^천$/.test(u)) return 1e3;
  if (/원/.test(u)) return 1;
  return null;
}

/** 한 항목(m)과 받아 온 표(t)로 화면용 지표 하나를 만든다. 자료가 없거나 값이 말이 안 되면 null. */
export function metricFromTable(m, t) {
  const account = t.accounts[m.accountCd];
  const column = pickColumn(t, m.col, m.group, m.key, m.cum);
  if (!account || !column) return null;
  let points = account.points.map((p) => ({ month: p.month, value: p.v[column.id] })).filter((p) => p.value !== null);
  if (!points.length) return null;
  let unit = t.unit;
  if (m.group === "ratio") {
    if (points.some((p) => Math.abs(p.value) > 100000)) return null; // 금액이 비율로 잡힌 것
    unit = "%";
  } else {
    // 금액은 모두 억원으로 바꿔 보낸다. FISIS Open API는 통계표 단위 글자(백만원 등)와 달리 값을 원 단위로 돌려주는 경우가
    // 있어(외국계 증권사 지점 등), 그 시점의 표 안 값이 아주 크면 단위 글자와 상관없이 원으로 본다.
    // 작은 금액(소규모 회사)이 0억원으로 사라지지 않도록 소수 둘째 자리까지 남긴다.
    points = points.map((p) => {
      const mult = (t.monthMax[p.month] ?? t.max) >= 1e7 ? 1 : (unitMult(t.unit) ?? 1e6);
      return { month: p.month, value: Math.round(((p.value * mult) / 1e8) * 100) / 100 };
    });
    unit = "억원";
  }
  return {
    key: m.key, label: m.label, group: m.group, basis: m.basis, unit, term: t.term,
    column: t.columns.length > 1 ? column.name : "", source: `${m.listNm} · ${m.accountNm}`, points,
  };
}

/** spec과 받아 온 통계표들(listNo → 표 또는 null)로 화면에 보낼 지표 목록을 만든다. */
export function buildMetrics(spec, tables) {
  const requests = [
    ...Object.entries(spec.basis).flatMap(([basis, list]) => list.map((m) => ({ ...m, group: "fin", basis }))),
    ...spec.ratios.map((m) => ({ ...m, group: "ratio", basis: "" })),
    ...spec.funds.map((m) => ({ ...m, group: "fund", basis: "" })),
  ];
  let name = "";
  let term = "";
  const metrics = [];
  for (const m of requests) {
    const t = tables[m.listNo];
    if (!t) continue;
    name = name || t.name;
    const metric = metricFromTable(m, t);
    if (!metric) continue;
    if (m.group === "fin" && !term) term = t.term;
    metrics.push(metric);
    if (m.group === "fin" && PL_KEYS.test(m.key)) {
      const cum = metricFromTable({ ...m, cum: true, key: `${m.key}_cum` }, t);
      if (cum) metrics.push(cum);
    }
  }
  deriveMissing(metrics);
  return { name, term, basisOptions: Object.keys(spec.basis), metrics };
}

/** 계정이 없어도 다른 항목으로 계산되는 것은 채운다(자산 = 부채 + 자본, 영업이익 = 영업수익 − 영업비용). */
const IDENTITIES = [
  ["opinc", "revenue", "expense", "영업이익"], // opinc = revenue - expense
  ["revenue", "opinc", "expense", "영업수익"], // revenue = opinc + expense
  ["expense", "revenue", "opinc", "영업비용"], // expense = revenue - opinc
  ["liab", "assets", "equity", "부채총계"], // liab = assets - equity
  ["equity", "assets", "liab", "자기자본"], // equity = assets - liab
  ["assets", "liab", "equity", "자산총계"], // assets = liab + equity
];
const IDENTITY_SIGN = { opinc: -1, revenue: 1, expense: -1, liab: -1, equity: -1, assets: 1 };
export function deriveMissing(metrics) {
  for (const basis of ["별도", "연결"]) {
    for (const suffix of ["", "_cum"]) {
      const find = (key) => metrics.find((m) => m.group === "fin" && m.basis === basis && m.key === key + suffix);
      for (const [target, a, b, label] of IDENTITIES) {
        if (suffix && !PL_KEYS.test(target)) continue; // 누계는 손익 항목만
        if (find(target)) continue;
        const x = find(a);
        const y = find(b);
        if (!x || !y) continue;
        const sign = IDENTITY_SIGN[target];
        const byMonth = new Map(y.points.map((p) => [p.month, p.value]));
        const points = x.points.filter((p) => byMonth.has(p.month)).map((p) => ({ month: p.month, value: p.value + sign * byMonth.get(p.month) }));
        if (!points.length) continue;
        metrics.push({ key: target + suffix, label, group: "fin", basis, unit: x.unit, term: x.term, column: "", source: "계산", points });
      }
    }
  }
}
