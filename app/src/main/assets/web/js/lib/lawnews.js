// 최근 금융관련 법령 개정 소식: 주요 금융 법령의 제·개정 이력(공포일·시행일·개정 구분)을 국가법령정보 연혁에서 읽는다.
// 오늘의 브리핑 '법령 개정 소식' 카드와 '최근 금융관련 법령개정 소식' 화면이 함께 쓴다.
import { repo } from "./law-core.js";


export const KEY_LAWS = [
  "자본시장과 금융투자업에 관한 법률", "은행법", "보험업법", "금융소비자 보호에 관한 법률", "전자금융거래법", "여신전문금융업법",
  "가상자산 이용자 보호 등에 관한 법률", "상호저축은행법", "특정 금융거래정보의 보고 및 이용 등에 관한 법률", "금융위원회의 설치 등에 관한 법률",
  "금융지주회사법", "신용정보의 이용 및 보호에 관한 법률", "대부업 등의 등록 및 금융이용자 보호에 관한 법률", "예금자보호법",
  "주식회사 등의 외부감사에 관한 법률", "금융실명거래 및 비밀보장에 관한 법률", "한국은행법", "금융회사의 지배구조에 관한 법률",
  "채권의 공정한 추심에 관한 법률", "온라인투자연계금융업 및 이용자 보호에 관한 법률",
];

// 법률과 함께 볼 감독규정·시행세칙(행정규칙). 국가법령정보에서 이름이 같은 현행본을 찾는다.
export const KEY_ADMIN_RULES = [
  "금융투자업규정", "금융투자업규정 시행세칙", "증권 인수업무 등에 관한 규정", "보험업감독규정", "보험업감독업무시행세칙",
  "은행업감독규정", "은행업감독업무시행세칙", "여신전문금융업감독규정", "여신전문금융업감독업무시행세칙",
  "상호저축은행업감독규정", "상호저축은행업감독업무시행세칙", "금융소비자보호에 관한 감독규정", "전자금융감독규정",
  "금융지주회사감독규정", "신용정보업감독규정", "금융회사 지배구조 감독규정", "특정 금융거래정보의 보고 및 이용 등에 관한 감독규정",
  "가상자산 이용자 보호 등에 관한 감독규정", "주식회사 등의 외부감사에 관한 규정",
];
export const KINDS = ["법률", "시행령", "시행규칙", "감독규정", "시행세칙"];
const compact = (s) => (s || "").replace(/\s+/g, "");

/** 법령 하나의 최근 제·개정 [{name, kind, promulgationDate, effectiveDate, type, mst}] (공포일 최신순, 같은 공포일·구분은 한 건으로). */
async function versionsOf(name, per, kind = "법률") {
  const vs = await repo.lawVersions({ name });
  const seen = new Set();
  return vs
    .sort((a, b) => (b.promulgationDate || "").localeCompare(a.promulgationDate || "") || (b.effectiveDate || "").localeCompare(a.effectiveDate || ""))
    .filter((v) => !seen.has(v.promulgationDate + v.revisionType) && seen.add(v.promulgationDate + v.revisionType))
    .slice(0, per)
    .map((v) => ({ name: v.name, kind, promulgationDate: v.promulgationDate, effectiveDate: v.effectiveDate, type: v.revisionType, mst: v.mst }));
}

/** 행정규칙(감독규정·시행세칙)의 현행본 한 건 [{name, kind, adm, promulgationDate, effectiveDate, type}]. */
async function adminOf(name) {
  const hit = (await repo.searchAdminRules(name)).find((r) => compact(r.name) === compact(name));
  if (!hit) return [];
  return [{ name: hit.name, kind: /시행세칙|업무시행세칙/.test(hit.name) ? "시행세칙" : "감독규정", adm: hit.serialNo, promulgationDate: hit.promulgationDate, effectiveDate: hit.effectiveDate, type: hit.revisionType }];
}

async function pool(tasks, size = 6) {
  const out = [];
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(size, tasks.length) }, async () => {
    while (i < tasks.length) {
      const k = i++;
      out[k] = await tasks[k]().catch(() => []);
    }
  }));
  return out;
}

/** 주요 금융 법령들의 최근 제·개정을 공포일 최신순으로 합친다. per = 법령당 건수, limit = 전체 상한.
 *  sub = true 이면 시행령·시행규칙과 감독규정·시행세칙(행정규칙)도 함께 읽는다. */
export async function loadLawNews({ per = 1, limit = 3, names = KEY_LAWS, sub = false } = {}) {
  if (!sub) {
    const rows = await Promise.all(names.map((n) => versionsOf(n, per).catch(() => [])));
    return rows.flat().sort((a, b) => (b.promulgationDate || "").localeCompare(a.promulgationDate || "")).slice(0, limit);
  }
  const tasks = [
    ...names.map((n) => () => versionsOf(n, per, "법률")),
    ...names.map((n) => () => versionsOf(n + " 시행령", 2, "시행령")),
    ...names.map((n) => () => versionsOf(n + " 시행규칙", 1, "시행규칙")),
    ...KEY_ADMIN_RULES.map((n) => () => adminOf(n)),
  ];
  const rows = (await pool(tasks)).flat();
  return rows.sort((a, b) => (b.promulgationDate || "").localeCompare(a.promulgationDate || "")).slice(0, limit);
}

/** 한 건의 개정 내용 화면 주소. */
export function docHref(x) {
  const p = new URLSearchParams({ name: x.name, kind: x.kind || "", pd: x.promulgationDate || "", ef: x.effectiveDate || "", type: x.type || "" });
  if (x.adm) p.set("adm", x.adm);
  else p.set("mst", x.mst);
  return "#/lawdoc?" + p.toString();
}
