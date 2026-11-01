// 기업현황 및 공시(/dart): 금융감독원 전자공시(DART) Open API 중계와 KIND 공시 목록 읽기.
// DART 인증키는 서버 비밀값(DART_API_KEY)으로만 존재한다. KIND는 키가 없는 공개 웹 목록을 읽는다.
import dartCorps from "./dart-corps.json";

const DART = "https://opendart.fss.or.kr/api";
const KIND = "https://kind.krx.co.kr";

/** 오류 문구에 인증키가 섞여 나가지 않게 crtfc_key 값을 가린다. */
export const redact = (text) => String(text || "").replace(/crtfc_key=[^&\s"'<>]*/gi, "crtfc_key=***");

// DART는 User-Agent가 없는 요청을 오류 페이지(error1.html)로 되돌려 보내므로(무한 리다이렉트) 항상 보낸다.
const DART_HEADERS = { "User-Agent": "Mozilla/5.0 (compatible; FSSInsights/2.1; +https://github.com/hdlee73/FSS_insights)", Accept: "application/json", "Accept-Language": "ko-KR,ko;q=0.9" };

const norm = (s) => String(s || "").replace(/\(\s*주\s*\)|㈜|주식회사|\s+/g, "").toLowerCase();

let index = null;
function corpIndex() {
  if (!index) index = (dartCorps.corps || []).map(([code, name, stock]) => ({ code, name, stock: stock || "", key: norm(name) }));
  return index;
}

/** 회사명 또는 종목코드 검색. 이름이 일치할수록, 상장사일수록 앞에 둔다. */
export function searchCorps(query, limit = 30) {
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
  return scored.slice(0, limit).map(({ c }) => ({ corp: c.code, name: c.name, stock: c.stock }));
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

const CLS = { Y: "유가증권", K: "코스닥", N: "코넥스", E: "기타(비상장)" };

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
    market: r.corp_cls === "Y" ? "KS" : r.corp_cls === "K" ? "KQ" : "",
    marketName: CLS[r.corp_cls] || "",
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

/** 최대주주 및 특수관계인 지분. 가장 최근에 제출된 정기보고서 기준으로 찾는다(최대 6번 조회). */
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
    const items = rows.map((x) => ({
      name: String(x.nm || "").trim(),
      relation: String(x.relate || "").trim(),
      kind: String(x.stock_knd || "").trim(),
      shares: num(x.trmend_posesn_stock_co),
      ratio: num(x.trmend_posesn_stock_qota_rt),
    }));
    return { basis: `${year} ${label}`, items };
  }
  return { basis: "", items: [] };
}

const PBLNTF = { a: ["A"], f: ["F"], e: ["B", "D", "E", "I"] };
const DART_VIEW = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

/** 공시 목록. type: a 정기보고서, f 감사보고서(외부감사관련), e 기타공시(주요사항·지분·기타·거래소공시). */
export async function filings(env, corp, type) {
  const kinds = PBLNTF[type];
  if (!kinds) throw new Error("공시 종류가 올바르지 않습니다.");
  const bgn = yearsAgo(type === "e" ? 2 : 5);
  const parts = await Promise.all(
    kinds.map((ty) => dartGet(env, "list", { corp_code: corp, bgn_de: bgn, pblntf_ty: ty, page_count: "40", sort: "date", sort_mth: "desc" }))
  );
  const seen = new Set();
  const items = parts
    .flatMap((p) => p.list || [])
    .filter((x) => (seen.has(x.rcept_no) ? false : seen.add(x.rcept_no)))
    .map((x) => ({ id: x.rcept_no, title: String(x.report_nm || "").trim(), date: x.rcept_dt, by: String(x.flr_nm || "").trim(), note: String(x.rm || "").trim(), url: DART_VIEW + x.rcept_no, src: "DART" }))
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
    .slice(0, 40);
  return { items };
}

const decode = (s) => s.replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\s+/g, " ").trim();

/**
 * KIND(한국거래소) 공시 목록: 회사명으로 최근 2년을 찾는다. KIND는 인증키 방식의 공식 API가 없어 공개 웹 목록의 표를 읽는다.
 * 사이트 구조가 바뀌면 빈 목록을 돌려주며, 이때도 DART 공시는 그대로 쓸 수 있다.
 */
export async function kindFilings(name) {
  const to = new Date();
  const fmt = (d) => d.toISOString().slice(0, 10);
  const body = new URLSearchParams({
    method: "searchDetailsSub",
    currentPageSize: "40",
    pageIndex: "1",
    orderMode: "1",
    orderStat: "D",
    forward: "details_sub",
    searchCodeType: "",
    searchCorpName: name,
    reportNm: "",
    bfrDsclsType: "on",
    fromDate: yearsAgo(2).replace(/(\d{4})(\d{2})(\d{2})/, "$1-$2-$3"),
    toDate: fmt(to),
  });
  const res = await fetch(`${KIND}/disclosure/details.do`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", "User-Agent": DART_HEADERS["User-Agent"], Referer: `${KIND}/disclosure/details.do?method=searchDetailsMain` },
    body,
  });
  if (!res.ok) throw new Error(`KIND 응답 ${res.status}`);
  const html = await res.text();
  const items = [];
  for (const row of html.split(/<tr[\s>]/i).slice(1)) {
    const acpt = /openDisclsViewer\(\s*'(\d{8,})'/.exec(row);
    if (!acpt) continue;
    const date = /(\d{4}-\d{2}-\d{2})/.exec(row);
    const link = new RegExp(`<a[^>]*openDisclsViewer\\(\\s*'${acpt[1]}'[^>]*>([\\s\\S]*?)</a>`, "i").exec(row);
    const corp = /<a[^>]*id="companysum"[^>]*>([\s\S]*?)<\/a>/i.exec(row);
    const title = link ? decode(link[1]) : "";
    if (!title) continue;
    const who = corp ? decode(corp[1]) : "";
    if (who && norm(who) !== norm(name)) continue; // 비슷한 이름의 다른 회사 제외
    items.push({ id: acpt[1], title, date: date ? date[1].replace(/-/g, "") : "", by: who, note: "", url: `${KIND}/common/disclsviewer.do?method=search&acptno=${acpt[1]}`, src: "KIND" });
  }
  return { items: items.slice(0, 40) };
}
