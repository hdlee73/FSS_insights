// FISIS(금융통계정보시스템) 공용 로직. 서버(worker.js)와 배포할 때 자료를 미리 만드는 build-fisis-data.mjs가 함께 쓴다.
// 금융권역코드(lrgDiv/partDiv): A 국내은행, F 증권사, G 자산운용사, H 생명보험, I 손해보험, E 상호저축은행, C 신용카드사.

export const FISIS_API = "https://fisis.fss.or.kr/openapi";
export const FISIS_SECTORS = ["F", "G", "A", "H", "I", "E", "C"];
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
export const ACCOUNT_NOISE = /비율|률$|증감|구성비/;
export const FIN_METRICS = [
  { key: "assets", label: "총자산", tables: /재무상태|대차대조|자산/, pats: [/^자산\s*총계$/, /^총\s*자산$/, /자산\s*총계|총\s*자산|자산\s*합계/, /^자산$/] },
  { key: "liab", label: "총부채", tables: /재무상태|대차대조|부채/, pats: [/^부채\s*총계$/, /^총\s*부채$/, /부채\s*총계|총\s*부채|부채\s*합계/, /^부채$/] },
  { key: "equity", label: "자기자본", tables: /재무상태|대차대조|자본/, pats: [/^자본\s*총계$/, /^자기자본$/, /자본\s*총계|총\s*자본|자본\s*합계/, /^자본$/] },
  { key: "capital", label: "자본금", tables: /재무상태|대차대조|자본/, pats: [/^자본금$/, /^납입\s*자본금$/, /자본금/] },
  { key: "revenue", label: "영업수익", tables: /손익|포괄|영업/, pats: [/^영업\s*수익$/, /영업\s*수익/, /^총\s*수익$/, /수익\s*(합계|총계)/, /^수익$/, /이자\s*수익/] },
  { key: "expense", label: "영업비용", tables: /손익|포괄|영업/, pats: [/^영업\s*비용$/, /영업\s*비용/, /^총\s*비용$/, /비용\s*(합계|총계)/, /이자\s*비용/] },
  { key: "netincome", label: "당기순이익", tables: /손익|포괄|영업/, pats: [/^당기\s*순\s*(이익|손익)$/, /당기\s*순\s*(이익|손익)/, /^순\s*(이익|손익)$/] },
];
export const RATIO_SPECS = {
  F: [
    { key: "ncr", label: "순자본비율", pats: [/순자본비율|영업용\s*순자본비율|NCR/] },
    { key: "lev", label: "레버리지비율", pats: [/레버리지/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율|유동성/] },
  ],
  G: [
    { key: "ncr", label: "영업용순자본비율", pats: [/영업용\s*순자본비율|순자본비율|NCR/] },
    { key: "eqr", label: "자기자본비율", pats: [/자기자본\s*비율/] },
  ],
  A: [
    { key: "bis", label: "BIS 자기자본비율", pats: [/BIS/, /총자본\s*비율/, /자기자본\s*비율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/고정이하\s*여신\s*비율/, /부실\s*채권\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "lcr", label: "유동성커버리지비율", pats: [/유동성\s*커버리지|LCR/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율/] },
  ],
  E: [
    { key: "bis", label: "BIS 자기자본비율", pats: [/BIS/, /자기자본\s*비율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/고정이하\s*여신\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율/] },
  ],
  C: [
    { key: "adj", label: "조정자기자본비율", pats: [/조정\s*자기자본\s*비율/, /자기자본\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "npl", label: "고정이하채권비율", pats: [/고정이하\s*(채권|여신)\s*비율/, /부실\s*채권\s*비율/] },
    { key: "lev", label: "레버리지배율", pats: [/레버리지/] },
  ],
  H: [
    { key: "solv", label: "지급여력비율(K-ICS)", pats: [/K-?ICS|킥스/, /지급여력\s*비율/] },
    { key: "per13", label: "13회차 유지율", pats: [/13\s*회차/] },
  ],
  I: [
    { key: "solv", label: "지급여력비율(K-ICS)", pats: [/K-?ICS|킥스/, /지급여력\s*비율/] },
    { key: "loss", label: "손해율", pats: [/손해율/] },
    { key: "opex", label: "사업비율", pats: [/사업비율/] },
  ],
};
const ANY_ACCT = /^(합\s*계|계|총\s*계|전체|펀드\s*합계|전체\s*펀드)$/;
export const FUND_SPECS = [
  { key: "fund_all", label: "펀드 설정원본", col: /설정\s*원본/, pats: [ANY_ACCT, /합계|총계/, /설정\s*원본/] },
  { key: "nav_all", label: "펀드 순자산", col: /순자산/, pats: [ANY_ACCT, /합계|총계/, /순자산/] },
  { key: "fund_pub", label: "공모펀드 설정원본", col: /설정\s*원본/, pats: [/공모/] },
  { key: "nav_pub", label: "공모펀드 순자산", col: /순자산/, pats: [/공모/] },
  { key: "fund_priv", label: "사모펀드 설정원본", col: /설정\s*원본/, pats: [/사모/] },
  { key: "nav_priv", label: "사모펀드 순자산", col: /순자산/, pats: [/사모/] },
];
export const INFO_SPECS = [
  { key: "ceo", label: "대표이사", pats: [/대표\s*이사|대표자|^대표/] },
  { key: "holder", label: "최대주주", pats: [/^최대\s*주주$|최대\s*주주\s*(명|성명)?$/, /최대\s*주주/] },
  { key: "holderPct", label: "최대주주 지분율", pats: [/최대\s*주주.*(지분|비율)/, /지분율/] },
];

/** 패턴 순서대로, 선호하는 통계표 순으로 훑어 처음 맞는 (통계표, 계정)을 돌려준다. */
export function pickAccount(tables, accountsOf, spec, { skipNoise = false } = {}) {
  const ordered = spec.tables ? [...tables.filter((t) => spec.tables.test(t.listNm)), ...tables.filter((t) => !spec.tables.test(t.listNm))] : tables;
  for (const pat of spec.pats) {
    for (const t of ordered) {
      const a = (accountsOf.get(t.listNo) || []).find((x) => pat.test(x.name) && !(skipNoise && ACCOUNT_NOISE.test(x.name)));
      if (a) return { key: spec.key, label: spec.label, listNo: t.listNo, listNm: t.listNm, accountCd: a.code, accountNm: a.name, col: spec.col ? spec.col.source : "" };
    }
  }
  return null;
}

async function inChunks(items, size, fn) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(...(await Promise.all(items.slice(i, i + size).map(fn))));
  return out;
}

/**
 * 권역의 통계표·계정 목록에서 재무·비율·펀드·일반현황 항목이 어느 통계표의 어느 계정인지 찾는다.
 * api = { lists(sector) → [{sml, listNo, listNm}], accounts(listNo) → [{code, name}] }
 */
export async function resolveSpec(api, sector) {
  const lists = await api.lists(sector);
  const of = (re) => lists.filter((l) => re.test(l.sml));
  const fin = of(/재무현황/);
  const ratio = of(/경영지표/);
  const info = of(/일반현황/);
  const fund = sector === "G" ? of(/영업활동|재무현황/).filter((l) => /펀드|설정|순자산|수탁|운용/.test(l.listNm)) : [];
  const wanted = [...new Map([...fin, ...ratio, ...info, ...fund].map((l) => [l.listNo, l])).values()];
  const accountsOf = new Map();
  await inChunks(wanted, 6, async (l) => accountsOf.set(l.listNo, await api.accounts(l.listNo).catch(() => [])));
  const pickAll = (tables, specs, opt) => specs.map((sp) => pickAccount(tables, accountsOf, sp, opt)).filter(Boolean);
  const basis = {};
  const separate = fin.filter((l) => !/연결/.test(l.listNm));
  const connected = fin.filter((l) => /연결/.test(l.listNm));
  basis["별도"] = pickAll(separate.length ? separate : fin, FIN_METRICS, { skipNoise: true });
  if (connected.length) basis["연결"] = pickAll(connected, FIN_METRICS, { skipNoise: true });
  return {
    basis,
    ratios: pickAll(ratio, RATIO_SPECS[sector] || []),
    funds: pickAll(fund, FUND_SPECS),
    info: pickAll(info, INFO_SPECS),
    terms: {},
    tables: { fin: fin.map((l) => l.listNm), ratio: ratio.map((l) => l.listNm), info: info.map((l) => l.listNm), fund: fund.map((l) => l.listNm) },
  };
}

/** spec에 쓰인 서로 다른 통계표 번호. */
export function specListNos(spec) {
  const all = [...Object.values(spec.basis).flat(), ...spec.ratios, ...spec.funds, ...spec.info];
  return [...new Set(all.map((m) => m.listNo))];
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
  for (const a of Object.values(accounts)) a.points.sort((x, y) => x.month.localeCompare(y.month));
  return { term, unit: String(desc.unit || ""), name: String((rows[0] && rows[0].finance_nm) || ""), columns, accounts };
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

/** 열 이름 패턴이 있으면 그 열, 없으면 첫 열. 없으면 null. */
export function pickColumn(table, colSource) {
  if (!colSource) return table.columns[0] || null;
  const re = new RegExp(colSource);
  return table.columns.find((c) => re.test(c.name)) || null;
}

/** spec과 받아 온 통계표들(listNo → 표 또는 null)로 화면에 보낼 지표 목록을 만든다. */
export function buildMetrics(spec, tables) {
  const requests = [
    ...Object.entries(spec.basis).flatMap(([basis, list]) => list.map((m) => ({ ...m, group: "fin", basis }))),
    ...spec.ratios.map((m) => ({ ...m, group: "ratio", basis: "" })),
    ...spec.funds.map((m) => ({ ...m, group: "fund", basis: "" })),
    ...spec.info.map((m) => ({ ...m, group: "info", basis: "" })),
  ];
  let name = "";
  let term = "";
  const metrics = [];
  for (const m of requests) {
    const t = tables[m.listNo];
    if (!t) continue;
    name = name || t.name;
    const account = t.accounts[m.accountCd];
    const column = pickColumn(t, m.col);
    if (!account || !column) continue;
    const text = m.group === "info";
    const points = account.points
      .map((p) => ({ month: p.month, value: p.v[column.id], text: text ? p.raw[column.id] : undefined }))
      .filter((p) => (text ? p.text : p.value !== null));
    if (!points.length) continue;
    if (m.group === "fin" && !term) term = t.term;
    metrics.push({
      key: m.key, label: m.label, group: m.group, basis: m.basis, unit: t.unit, term: t.term,
      column: t.columns.length > 1 ? column.name : "", source: `${m.listNm} · ${m.accountNm}`, points,
    });
  }
  return { name, term, basisOptions: Object.keys(spec.basis), metrics };
}
