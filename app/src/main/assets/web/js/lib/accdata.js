// 회계 자료(기준서 목록·게시판 색인·감리 조치) 읽기. 앱과 같은 순서: ① 매일 갱신되는 원격 최신본 → ② 웹에 같이 올린 내장본.
import { cached, h } from "../core.js";

const LOCAL_ONLY = new Set(["standards"]);
const REMOTE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/accounting-data/";

async function getJson(url, ms) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const res = await fetch(url, { signal: ctl.signal, cache: "no-cache" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return await res.json();
  } finally {
    clearTimeout(t);
  }
}

/** name: board_index | audit_actions | disclosure_sanctions | standards. 실패하면 내장본, 그것도 없으면 예외. */
export function loadData(name) {
  return cached("accdata:" + name, 30 * 60 * 1000, async () => {
    // 기준서 목록(standards)은 매일 수집하는 자료가 아니라 원격 파일이 없다(404). 괜한 요청 없이 내장본을 쓴다.
    if (!LOCAL_ONLY.has(name)) {
      try {
        const d = await getJson(REMOTE + name + ".json", 6000);
        if (Array.isArray(d?.items) && d.items.length) return { ...d, from: "최신" };
      } catch {
        /* 원격 자료가 아직 없거나 막힘: 내장본 사용 */
      }
    }
    const d = await getJson("data/" + name + ".json", 30000);
    return { ...d, items: Array.isArray(d?.items) ? d.items : [], from: "내장" };
  });
}

export const terms = (q) => q.trim().split(/\s+/).filter(Boolean).map((t) => t.toLowerCase());
export const matchAll = (hay, ts) => {
  const s = hay.toLowerCase();
  return ts.every((t) => s.includes(t));
};

/** 기준서 출처: 원본 제정기관 / 수집 경로 */
export function standardSource(group) {
  switch (group) {
    case "K-IFRS":
    case "일반기업회계기준":
      return "한국회계기준원 / 회계위키 경유";
    case "회계감사기준":
      return "한국공인회계사회 / 회계위키 경유";
    case "내부회계관리제도":
      return "내부회계관리제도운영위원회 / 회계위키 경유";
    case "기타 기준서":
      return "한국회계기준원(KASB·KSSB) / 직접 연결(kasb.or.kr)";
    default:
      return "회계위키 경유";
  }
}

/** 회사명 → DART 검색어: ㈜·(주)·주식회사와 '(現 …)' 같은 괄호 설명을 뗀다. 가려진 이름(○○○)이면 빈 문자열. */
export function dartQuery(raw) {
  const s = String(raw || "").replace(/\([^)]*\)/g, " ").replace(/㈜|주식회사/g, "").replace(/\s+/g, " ").trim();
  return /[0-9A-Za-z가-힣]/.test(s.replace(/[○◯◌●□■◎☆★△▲◇◆]/g, "")) ? s : "";
}

/** 목록 항목의 명시적 버튼: 「기업개황 등」(DART 검색, 가려진 이름이면 숨김)과 「세부내용」. 회사명 자체는 글자로만 둔다. */
export function caseButtons(company, open) {
  const q = dartQuery(company);
  return h("div", { class: "case-btns" },
    q ? h("a", { class: "btn sm ghost", href: "#/dart?q=" + encodeURIComponent(q), title: "기업 개황 및 공시(DART)에서 찾기" }, "기업개황 등") : null,
    h("button", { class: "btn sm", type: "button", onclick: open }, "세부내용"));
}
