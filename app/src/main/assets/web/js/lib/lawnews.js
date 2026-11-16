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

/** 법령 하나의 최근 제·개정 [{name, promulgationDate, effectiveDate, type, mst}] (공포일 최신순, 같은 공포일·구분은 한 건으로). */
async function versionsOf(name, per) {
  const vs = await repo.lawVersions({ name });
  const seen = new Set();
  return vs
    .sort((a, b) => (b.promulgationDate || "").localeCompare(a.promulgationDate || "") || (b.effectiveDate || "").localeCompare(a.effectiveDate || ""))
    .filter((v) => !seen.has(v.promulgationDate + v.revisionType) && seen.add(v.promulgationDate + v.revisionType))
    .slice(0, per)
    .map((v) => ({ name: v.name, promulgationDate: v.promulgationDate, effectiveDate: v.effectiveDate, type: v.revisionType, mst: v.mst }));
}

/** 주요 금융 법령들의 최근 제·개정을 공포일 최신순으로 합친다. per = 법령당 건수, limit = 전체 상한. */
export async function loadLawNews({ per = 1, limit = 3, names = KEY_LAWS } = {}) {
  const rows = await Promise.all(names.map((n) => versionsOf(n, per).catch(() => [])));
  return rows.flat().sort((a, b) => (b.promulgationDate || "").localeCompare(a.promulgationDate || "")).slice(0, limit);
}
