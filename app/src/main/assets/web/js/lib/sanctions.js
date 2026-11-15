// 금융회사 제재공시 세부내역(2011년 이후). GitHub Actions가 매일 정리해 sanction-data 브랜치에 올리는 sanctions.json을 받는다.
// 앱의 SanctionData.kt·SanctionExport.kt를 옮겼다. 받은 자료는 브라우저 캐시에 12시간 보관한다.
import { xlsx } from "./xlsx.js";

export const DATA_URL = "https://raw.githubusercontent.com/hdlee73/FSS_insights/sanction-data/sanctions.json?r=3"; // r: 자료 형식이 바뀔 때 올려 예전에 저장한 캐시를 버린다
export const FIRST_YEAR = 2011;
// FISIS 금융권역을 준용한다(금융지주·부신사=부동산신탁사 포함). 유사투자자문업자는 기타.
export const SECTORS = ["은행", "금융지주", "보험", "증권", "운용", "자문일임", "부신사", "저축은행", "상호금융", "여신전문", "대부", "기타"];
export const FIELDS = [
  ["all", "전체", "회사명·지적제목·법조문 검색"],
  ["company", "회사명", "회사명 검색 (예: 국민은행)"],
  ["title", "지적제목", "지적제목 검색 (예: 설명의무)"],
  ["law", "법조문", "법조문 검색 (예: 보험업법 제95조의2)"],
];
const REFRESH_MS = 12 * 3600 * 1000;
const CACHE = "fss-data";

export const NOTICE = "2011년 이후 금융감독원 '금융회사 제재공시'의 공개 문서를 자동으로 읽어 정리한 참고용 자료입니다. " +
  "원문과 다르거나 누락·오분류가 있을 수 있으며, 권역은 회사명·담당부서로 추정했습니다. " +
  "과태료·과징금은 공시(건) 단위 금액이며, 스캔 이미지 문서는 글자 인식(OCR)으로 읽어 일부 숫자·법조문이 다를 수 있고, 읽지 못한 공시는 해당 통계에 반영되지 않습니다. 업무에 인용할 때는 반드시 금감원 원문을 확인하세요.";

const WHY_SHORT = { missing: "금감원 사이트에 첨부 파일 없음(사이트 오류)", download: "첨부 문서 내려받기 실패", nofile: "첨부 문서 없음", scan: "스캔 문서(글자 인식 불가)", format: "문서 형식 자동 인식 불가" };
const WHY_TEXT = {
  missing: "금감원 공시 사이트에서 첨부 문서가 열리지 않아(‘해당 파일이 존재하지 않습니다’ 오류) 지적사항을 읽지 못했습니다. 사이트가 고치면 자동으로 채워집니다.",
  download: "금감원 사이트에서 첨부 문서를 받지 못했습니다(일시 오류). 다음 갱신 때 다시 읽습니다.",
  nofile: "공시에 첨부 문서가 없어 지적사항을 읽지 못했습니다.",
  scan: "첨부 문서가 흐린 스캔 이미지라 글자 인식(OCR)으로도 읽지 못했습니다. 이 공시는 지적사항·법률·조항 통계에 반영되지 않습니다.",
};
export const whyShort = (c) => WHY_SHORT[c.why] || "원문 참조";
export const whyText = (c) => (WHY_TEXT[c.why] || "문서 형식이 달라 지적사항을 자동으로 나누지 못했습니다.") + " 원문보기로 확인해 주세요.";

function parse(o) {
  const cases = [];
  for (const c of o.cases || []) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(c.date || "")) continue;
    const s = (v) => (v && v !== "null" ? String(v) : "");
    cases.push({
      id: String(c.id), company: s(c.co), date: c.date, year: +c.date.slice(0, 4), ym: c.date.slice(0, 7).replace("-", ""),
      dept: s(c.dept), sector: s(c.sector) || "기타", viewUrl: s(c.view), fileUrl: s(c.file) || null,
      inst: s(c.inst), exec: s(c.exec), staff: s(c.staff),
      instKinds: c.instK || [], execKinds: c.execK || [], staffKinds: c.staffK || [],
      fine: +c.fine || 0, penalty: +c.penalty || 0,
      items: (c.items || []).map((it) => ({ title: s(it.t), category: s(it.cat) || null, laws: it.laws || [] })),
      parsed: !!c.ok, why: s(c.why) || null, ocr: !!c.ocr, amountUnknown: c.amt === "?",
    });
  }
  const years = [...new Set(cases.map((c) => c.year))].sort((a, b) => b - a);
  return { generatedAt: o.generatedAt || "", fromYear: o.from || FIRST_YEAR, cases, years };
}

let memo = null;
/** 자료 받기: 브라우저 캐시가 12시간 안이면 그것을, 아니면 새로 받는다(실패하면 예전 것). */
export async function loadSanctions(force = false) {
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
    throw new Error(`제재공시 세부내역 자료를 받지 못했습니다. 인터넷 연결을 확인해 주세요. (${e.message})`);
  }
}

// ---- 조치 원문 다듬기 ----
export function actions(raw) {
  const parts = String(raw || "").split(/\s*\/\s*|\n/).map((p) => p.trim());
  const joined = [];
  for (const p of parts) {
    const last = joined[joined.length - 1];
    if (last == null) joined.push(p);
    else if (last.endsWith("·") || last.endsWith("ㆍ")) joined[joined.length - 1] = last + p;
    else if ((last.match(/\(/g) || []).length > (last.match(/\)/g) || []).length || last.endsWith("및")) joined[joined.length - 1] = last + " " + p;
    else joined.push(p);
  }
  const out = [];
  for (const p of joined) {
    const note = p.startsWith("*") && p.replace(/^[* ]+/, "").length > 0;
    const t = p.replace(/^[\s·ㆍ•∙*\-]+/, "").replace(/^\(\d+\s*명\)\s*/, "").trim();
    if (!t || /^\(\d+\s*명\)$/.test(t)) continue;
    const v = note ? "※ " + t : t;
    if (!out.includes(v)) out.push(v);
  }
  return out;
}
export const actionLine = (raw) => actions(raw).join(" / ");

// ---- 회사 이름 맞추기 ----
const nameKey = (n) => String(n || "").replace(/주식회사|유한회사|\(주\)|㈜|\(유\)|\s|[.,·]/g, "").toLowerCase();
export function casesOf(data, company) {
  const k = nameKey(company);
  if (!k) return [];
  const exact = data.cases.filter((c) => nameKey(c.company) === k);
  if (exact.length || k.length < 4) return exact;
  return data.cases.filter((c) => {
    const o = nameKey(c.company);
    return o.length >= 4 && (o.startsWith(k) || k.startsWith(o));
  });
}

// ---- 기간 ----
const nowYm = () => {
  const d = new Date();
  return d.getFullYear() * 100 + d.getMonth() + 1;
};
const addMonths = (ym, delta) => {
  const t = Math.floor(ym / 100) * 12 + (ym % 100) - 1 + delta;
  return Math.floor(t / 12) * 100 + (t % 12) + 1;
};
export const PERIODS = [["all", "전체"], ["year", "올해"], ["1y", "최근 1년"], ["3y", "최근 3년"], ["5y", "최근 5년"]];
export function periodRange(p) {
  const now = nowYm();
  if (p === "year") return [Math.floor(now / 100) * 100 + 1, now];
  const m = { "1y": 12, "3y": 36, "5y": 60 }[p];
  return m ? [addMonths(now, -(m - 1)), now] : [null, null];
}
export function presetOf(f) {
  return PERIODS.find(([k]) => {
    const [a, b] = periodRange(k);
    return a === (f.from ?? null) && b === (f.to ?? null);
  })?.[0] || null;
}
const ymText = (ym) => `${Math.floor(ym / 100)}.${String(ym % 100).padStart(2, "0")}`;
export function periodLabel(f, fromYear = FIRST_YEAR) {
  if (f.from == null && f.to == null) return `${fromYear}년 이후 전체`;
  if (f.from && f.to && Math.floor(f.from / 100) === Math.floor(f.to / 100) && f.from % 100 === 1 && f.to % 100 === 12) return `${Math.floor(f.from / 100)}년`;
  return `${f.from ? ymText(f.from) : fromYear + ".01"}~${f.to ? ymText(f.to) : "현재"}`;
}
export function scopeLabel(f, data) {
  return [`기간 ${periodLabel(f, data.fromYear)}`, f.sector || "전 권역", f.q?.trim() ? `${FIELDS.find(([k]) => k === f.field)?.[1] || "전체"} 검색 ‘${f.q.trim()}’` : null].filter(Boolean).join(" · ");
}

// ---- 거르기·통계 ----
export function rowsOf(cases) {
  return cases.flatMap((c) => (c.items.length ? c.items.map((it, i) => ({ c, item: it, i })) : [{ c, item: null, i: 0 }]));
}
const rowTitle = (r) => r.item?.title ?? `지적사항 미분류 · ${whyShort(r.c)}`;
const rowLaws = (r) => r.item?.laws || [];

function matchesRow(r, q, field) {
  q = q.trim();
  if (!q) return true;
  const compact = q.replace(/ /g, "").toLowerCase();
  const hay = field === "company" ? [r.c.company] : field === "title" ? [r.item?.title || ""] : field === "law" ? rowLaws(r) : [r.c.company, rowTitle(r), r.c.dept, rowLaws(r).join(" ")];
  const low = hay.map((x) => x.toLowerCase());
  return q.toLowerCase().split(/\s+/).every((w) => low.some((x) => x.includes(w))) || low.some((x) => x.replace(/ /g, "").includes(compact));
}

/** f = { from, to (yyyymm 숫자 또는 null), sector, q, field } */
export function filterRows(data, f) {
  const cases = data.cases.filter((c) => (f.from == null || +c.ym >= f.from) && (f.to == null || +c.ym <= f.to) && (!f.sector || c.sector === f.sector));
  return rowsOf(cases).filter((r) => matchesRow(r, f.q || "", f.field || "all"));
}
export const casesFromRows = (rows) => {
  const seen = new Set();
  return rows.map((r) => r.c).filter((c) => !seen.has(c.id) && seen.add(c.id));
};

function count(xs) {
  const m = new Map();
  for (const x of xs) m.set(x, (m.get(x) || 0) + 1);
  return [...m].sort((a, b) => b[1] - a[1]);
}
export function summarize(rows, data, f) {
  const cases = casesFromRows(rows);
  const laws = rows.flatMap((r) => [...new Set(rowLaws(r))]);
  return {
    caseCount: cases.length,
    itemCount: rows.filter((r) => r.item).length,
    fine: cases.reduce((s, c) => s + c.fine, 0),
    penalty: cases.reduce((s, c) => s + c.penalty, 0),
    byYear: data.years.filter((y) => (f.from == null || y >= Math.floor(f.from / 100)) && (f.to == null || y <= Math.floor(f.to / 100))).sort().map((y) => [y, cases.filter((c) => c.year === y).length]),
    bySector: SECTORS.map((s) => [s, cases.filter((c) => c.sector === s).length]).filter(([, n]) => n > 0),
    instKinds: count(cases.flatMap((c) => c.instKinds)),
    execKinds: count(cases.flatMap((c) => c.execKinds)),
    staffKinds: count(cases.flatMap((c) => c.staffKinds)),
    topLaws: count(laws).slice(0, 20),
    topLawNames: count(laws.map((l) => (l.lastIndexOf(" 제") > 0 ? l.slice(0, l.lastIndexOf(" 제")) : l))).slice(0, 10),
    topCompanies: count(cases.map((c) => c.company)).slice(0, 10),
    topMoney: cases.filter((c) => c.fine + c.penalty > 0).sort((a, b) => b.fine + b.penalty - (a.fine + a.penalty)).slice(0, 10),
    unparsedCount: cases.filter((c) => !c.items.length).length,
    noActionCount: cases.filter((c) => !c.inst && !c.exec && !c.staff).length,
    ocrCount: cases.filter((c) => c.ocr).length,
    amountUnknownCount: cases.filter((c) => c.amountUnknown).length,
  };
}

/** 통계에 다 반영되지 않은 공시가 있을 때 보여 줄 안내(없으면 null). */
export function coverageNote(s) {
  const p = [];
  if (s.unparsedCount > 0) p.push(`지적사항을 읽지 못한 ${s.unparsedCount}건은 지적사항·위반 법률·조항 통계에 반영되지 않았습니다(공시 건수·권역·회사 통계에는 포함)`);
  if (s.noActionCount > 0) p.push(`조치 내용을 읽지 못한 ${s.noActionCount}건은 조치 종류·금전제재 통계에서 빠졌습니다`);
  if (s.amountUnknownCount > 0) p.push(`스캔 문서의 과태료·과징금 금액을 확실히 읽지 못한 ${s.amountUnknownCount}건은 금전제재 금액 합계에서 빠졌습니다`);
  if (s.ocrCount > 0) p.push(`스캔 문서를 글자 인식(OCR)으로 읽은 ${s.ocrCount}건은 숫자·법조문이 원문과 다를 수 있습니다`);
  return p.length ? p.join(". ") + ". 정확한 내용은 원문보기로 확인하세요." : null;
}

/** 원 → "17억 3,050만원" */
export function won(v) {
  if (!(v > 0)) return "-";
  const eok = Math.floor(v / 1e8);
  const man = Math.floor((v % 1e8) / 1e4);
  const g = (n) => n.toLocaleString("ko-KR");
  if (eok > 0 && man > 0) return `${g(eok)}억 ${g(man)}만원`;
  if (eok > 0) return `${g(eok)}억원`;
  if (man > 0) return `${g(man)}만원`;
  return `${g(v)}원`;
}

// ---- 엑셀 ----
export function exportXlsx(rows, s, scope, generatedAt) {
  const merges = [];
  let start = 0;
  const detail = rows.map((r, i) => {
    const first = i === 0 || rows[i - 1].c.id !== r.c.id;
    if (first) start = i;
    const last = i === rows.length - 1 || rows[i + 1].c.id !== r.c.id;
    if (last && i > start) for (const col of [4, 5, 6]) merges.push([col, start, i]);
    return [r.c.sector, r.c.company, r.c.date, rowTitle(r), first ? actionLine(r.c.inst) : null, first ? actionLine(r.c.exec) : null, first ? actionLine(r.c.staff) : null, rowLaws(r).join(", "), r.c.dept];
  });
  const st = [];
  const block = (title, pairs) => {
    st.push([title, null], ...pairs.map(([k, v]) => [String(k), v]), [null, null]);
  };
  st.push(["범위(기간·조건)", scope], ["자료 기준", generatedAt], ["공시 건수", s.caseCount], ["지적사항 건수", s.itemCount], ["과태료 합계(원)", s.fine], ["과징금 합계(원)", s.penalty], [null, null]);
  block("권역별 공시 건수", s.bySector);
  block("기관 조치 종류별 공시 건수", s.instKinds);
  block("임원 조치 종류별 공시 건수", s.execKinds);
  block("직원 조치 종류별 공시 건수", s.staffKinds);
  block("위반 법률 상위(지적 건수)", s.topLawNames);
  block("위반 조항 상위(지적 건수)", s.topLaws);
  block("제재 공시가 많은 회사", s.topCompanies);
  block("금전제재 상위(원)", s.topMoney.map((c) => [`${c.company} (${c.date})`, c.fine + c.penalty]));
  st.push(["미반영 안내", coverageNote(s) || "모든 공시를 읽었습니다."], ["유의사항", NOTICE]);
  return xlsx([
    { name: "세부내역", header: ["권역", "회사명", "조치요구일", "지적제목", "기관 조치", "임원 조치", "직원 조치", "위반 법률·조항", "담당부서"], rows: detail, widths: [9, 24, 11, 48, 32, 32, 32, 40, 16], merges },
    { name: "통계", header: ["항목", "값"], rows: st, widths: [46, 60] },
  ]);
}
