// 운용사·증권사별 펀드 순자산총액(공모/사모). GitHub Actions가 금융투자협회 종합통계 포털(FreeSIS) '회사별설정규모'를
// 매일 모아 fund-data 브랜치에 올리는 fund-aum.json을 받는다. 앱의 FundAum.kt를 옮겼다. 받은 자료는 브라우저 캐시에 6시간 보관한다.
import { errorText, h, loading, message } from "../core.js";

export const DATA_URL = "https://raw.githubusercontent.com/hdlee73/FSS_insights/fund-data/fund-aum.json";
const REFRESH_MS = 6 * 3600 * 1000;
const CACHE = "fss-data";

const noise = /주식회사|유한회사|\(주\)|㈜|\(유\)|\s|[.,·]/g;
const key = (n) => String(n || "").replace(noise, "").toLowerCase();
const stem = (n) => key(n).replace(/(자산운용|투자운용|투자자문|자산관리|운용|증권)$/, "");

function parse(o) {
  const three = (a) => [0, 1, 2].map((i) => +(a && a[i]) || 0);
  const a = o.asOf || {};
  return {
    generatedAt: o.generatedAt || "",
    asOf: { recent: a.recent || "", quarter: a.quarter || "", year: a.year || "" },
    companies: (o.companies || []).map((c) => ({ name: String(c.n || ""), pub: three(c.pub), pri: three(c.pri) })),
  };
}

let memo = null;
/** 자료 받기: 브라우저 캐시가 6시간 안이면 그것을, 아니면 새로 받는다(실패하면 예전 것). */
export async function loadFundAum(force = false) {
  if (memo && !force) return memo;
  let cache = null;
  try {
    cache = await caches.open(CACHE);
  } catch {
    /* 캐시를 못 쓰는 환경 */
  }
  const old = cache ? await cache.match(DATA_URL) : null;
  const at = +(old?.headers.get("X-Saved-At") || 0);
  if (old && !force && Date.now() - at < REFRESH_MS) {
    try {
      return (memo = parse(await old.clone().json()));
    } catch {
      /* 깨진 캐시는 새로 받는다 */
    }
  }
  try {
    const res = await fetch(DATA_URL, { cache: "no-cache" });
    if (!res.ok) throw new Error("서버 응답 " + res.status);
    const text = await res.text();
    const data = parse(JSON.parse(text));
    if (cache) cache.put(DATA_URL, new Response(text, { headers: { "Content-Type": "application/json", "X-Saved-At": String(Date.now()) } })).catch(() => {});
    return (memo = data);
  } catch (e) {
    if (old) return (memo = parse(await old.json()));
    throw new Error(`펀드 순자산 자료를 받지 못했습니다. 인터넷 연결을 확인해 주세요. (${e.message})`);
  }
}

/** 금융회사 주요 현황의 회사 이름으로 찾는다. 증권사(sector "F")는 이름이 똑같은 경우만(끝의 '증권'을 떼어 같은 계열
 *  자산운용사 수치를 잘못 붙이지 않도록), 운용사는 이름이 같거나 끝의 자산운용 등을 뗀 이름이 증권사 아닌 한 곳으로 맞을 때만. */
export function findCompany(data, company, sector = "G") {
  const k = key(company);
  if (!k) return null;
  const exact = data.companies.find((c) => key(c.name) === k);
  if (exact || sector === "F" || /증권$/.test(k)) return exact || null;
  const s = stem(company);
  if (s.length < 2) return null;
  const hits = data.companies.filter((c) => !/증권$/.test(key(c.name)) && stem(c.name) === s);
  return hits.length === 1 ? hits[0] : null;
}

/** 억원 숫자를 "1조 2,345억"처럼. 0은 "-". */
export function eokText(v) {
  if (!v) return "-";
  if (v >= 10000) {
    const r = v % 10000;
    return `${Math.floor(v / 10000).toLocaleString("ko-KR")}조${r ? ` ${r.toLocaleString("ko-KR")}억` : ""}`;
  }
  return `${v.toLocaleString("ko-KR")}억`;
}

export const dateLabel = (s) => (s && s.length === 8 ? `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6)}` : s || "");

/** 금융회사 주요 현황 → '펀드 순자산' 탭. */
export async function fundAumPane(box, company, sector = "G") {
  box.replaceChildren(loading("펀드 순자산 자료를 불러오는 중…"));
  let data;
  try {
    data = await loadFundAum();
  } catch (e) {
    return box.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => fundAumPane(box, company, sector) }));
  }
  const c = findCompany(data, company, sector);
  if (!c) return box.replaceChildren(message(`금융투자협회 회사별 펀드 설정규모에서 ‘${company}’ 이름으로 된 자료를 찾지 못했습니다.\n(펀드를 설정·운용하지 않는 회사이거나 회사명 표기가 다를 수 있습니다.)`));
  const heads = [["최근일자", data.asOf.recent], ["최직근 분기말", data.asOf.quarter], ["최직근 연말", data.asOf.year]];
  const row = (label, vals, bold) => h("tr", null, h("th", { scope: "row", style: bold ? { color: "var(--text)" } : null }, label), vals.map((v) => h("td", { class: "num", style: bold ? { fontWeight: "700" } : null }, eokText(v))));
  const total = [0, 1, 2].map((i) => c.pub[i] + c.pri[i]);
  box.replaceChildren(
    h("div", { class: "section-label" }, "펀드 순자산총액 · 단위 억원"),
    h("div", { class: "tbl-wrap" }, h("table", { class: "tbl fund" },
      h("thead", null, h("tr", null, h("th", null, ""), heads.map(([l, d]) => h("th", { class: "num" }, l, h("div", { class: "note" }, dateLabel(d)))))),
      h("tbody", null, row("공모", c.pub), row("사모", c.pri), row("합계", total, true)))),
    h("p", { class: "note", style: { marginTop: "12px" } },
      "자료출처: 금융투자협회(FreeSIS) 종합통계 포털 ‘회사별설정규모’(펀드, 순자산총액, 투자지역 전체). 기관전용 사모펀드는 제외한 값이며 단위는 억원입니다. 최직근 분기말·연말은 그 일자의 값입니다. " +
      `자료를 매일 자동으로 모아 보여 주므로 원문과 다를 수 있습니다. 갱신: ${String(data.generatedAt).slice(0, 16).replace("T", " ")}`));
}
