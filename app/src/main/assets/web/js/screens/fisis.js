// 금융회사 주요 현황(FISIS): 권역 → 총자산 순 회사 목록(검색·비교 선택) → 회사 상세(대시보드·개황·재무손익·주요경영지표·최근 제재내역) / 비교.
// 앱의 FisisScreen.kt·FisisApi.kt를 옮겼다. 자료는 서버(/fisis)가 금융통계정보시스템 Open API에서 받아 준다.
import { setHeader } from "../app.js";
import { api, cached, dialog, errorText, h, icon, loading, message, store, tabs, toast } from "../core.js";
import { withTableTools } from "../lib/tabletools.js";

const SOURCE = "자료출처: 금융감독원 금융통계정보시스템";
const NOTICE = "원본 오류로 인해 일부 수치가 잘못 표시되어 있을 수 있습니다.";
const MAX_COMPARE = 3;
const FIRST_MONTH = "202401";
const BASES = ["별도", "연결"];

export const SECTORS = [
  ["F", "증권"], ["G", "운용"], ["A", "은행"], ["H", "생보"], ["I", "손보"], ["C", "카드"], ["E", "저축은행"], ["M", "부신사"], ["L", "금융지주"],
];
// 투자자문·일임사(ADV)는 서버가 FISIS에서 회사별 자료를 찾아 둔 경우에만 권역 목록에 더한다.
const sectorsReady = api("/fisis/sectors").then((r) => {
  if ((r.sectors || []).includes("ADV") && !SECTORS.some(([c]) => c === "ADV")) SECTORS.push(["ADV", "자문일임"]);
}).catch(() => {});
const CARD_PL_LABELS = { revenue: "수익합계", expense: "비용합계", netincome: "당기순이익(손실)" };
const DEFAULT_PL_KEYS = ["revenue", "expense", "opinc", "netincome"];
const CARD_PL_KEYS = ["revenue", "expense", "netincome"];
const FINANCE_LABELS = { assets: "자산총계", liab: "부채총계", equity: "자기자본", revenue: "영업수익", expense: "영업비용", opinc: "영업이익", netincome: "당기순이익" };
const FOREIGN_BROKER = /지점|골드만|모간스탠리|모건스탠리|메릴린치|비엔피|BNP|씨엘에스에이|CLSA|크레디|노무라|맥쿼리|유비에스|UBS|제이피모간|JP모간|도이치|소시에테|나틱시스|다이와|미즈호|HSBC|에이치에스비씨|바클레이|스탠다드차타드|씨티|ING|아이엔지|SMBC|라자드|이스트스프링|아이씨엠|ICM|CMS|씨엠에스|에스지|SG|CGS|CIMB|씨아이엠비|글로벌마켓|알비에스|RBS|스미토모|미쓰비시|도쿄|SBI증권/;
const RATIO_ROWS = {
  F: [["ncr", "순자본비율(개별)"], ["ncr_c", "순자본비율(연결)"], ["lev", "레버리지비율"], ["liq", "유동성비율"]],
  A: [["bis", "BIS기준 자기자본비율"], ["delinq", "총대출채권 연체율"], ["npl", "고정이하여신비율"], ["lcr", "유동성커버리지비율"]],
  H: [["solv", "지급여력비율"]],
  I: [["solv", "지급여력비율"]],
  E: [["bis", "BIS자기자본비율"], ["npl", "고정이하여신비율"], ["delinq", "연체율"], ["liq", "유동성비율"]],
  C: [["adj", "조정자기자본비율"], ["npl", "고정이하채권비율"]],
};
const HOMEPAGES = [
  ["국민은행", "https://www.kbstar.com"], ["신한은행", "https://www.shinhan.com"], ["하나은행", "https://www.kebhana.com"], ["우리은행", "https://www.wooribank.com"],
  ["중소기업은행", "https://www.ibk.co.kr"], ["농협은행", "https://www.nhbank.com"], ["산업은행", "https://www.kdb.co.kr"], ["씨티은행", "https://www.citibank.co.kr"],
  ["SC제일은행", "https://www.standardchartered.co.kr"], ["카카오뱅크", "https://www.kakaobank.com"], ["케이뱅크", "https://www.kbank.com"], ["토스뱅크", "https://www.tossbank.com"],
  ["수협은행", "https://www.suhyup-bank.com"], ["부산은행", "https://www.busanbank.co.kr"], ["아이엠뱅크", "https://www.imbank.co.kr"], ["iM뱅크", "https://www.imbank.co.kr"],
  ["대구은행", "https://www.imbank.co.kr"], ["경남은행", "https://www.knbank.co.kr"], ["광주은행", "https://www.kjbank.com"], ["전북은행", "https://www.jbbank.co.kr"], ["제주은행", "https://www.jejubank.co.kr"],
  ["미래에셋증권", "https://securities.miraeasset.com"], ["삼성증권", "https://www.samsungpop.com"], ["한국투자증권", "https://www.koreainvestment.com"], ["NH투자증권", "https://www.nhqv.com"],
  ["KB증권", "https://www.kbsec.com"], ["신한투자증권", "https://www.shinhansec.com"], ["하나증권", "https://www.hanaw.com"], ["메리츠증권", "https://www.imeritz.com"],
  ["키움증권", "https://www.kiwoom.com"], ["대신증권", "https://www.daishin.com"], ["유안타증권", "https://www.yuantasecurities.co.kr"], ["한화투자증권", "https://www.hanwhawm.com"],
  ["교보증권", "https://www.iprovest.com"], ["현대차증권", "https://www.hmsec.com"], ["SK증권", "https://www.sks.co.kr"], ["유진투자증권", "https://www.eugenefn.com"],
  ["부국증권", "https://www.bookook.co.kr"], ["IBK투자증권", "https://www.ibks.com"], ["BNK투자증권", "https://www.bnkfn.co.kr"], ["카카오페이증권", "https://www.kakaopaysec.com"],
  ["토스증권", "https://www.tossinvest.com"], ["다올투자증권", "https://www.daolsecurities.com"],
  ["삼성자산운용", "https://www.samsungfund.com"], ["미래에셋자산운용", "https://investments.miraeasset.com"], ["KB자산운용", "https://www.kbam.co.kr"],
  ["한국투자신탁운용", "https://www.kim.co.kr"], ["신한자산운용", "https://www.shinhanfund.com"], ["한화자산운용", "https://www.hanwhafund.com"], ["키움투자자산운용", "https://www.kiwoomam.com"],
  ["삼성생명", "https://www.samsunglife.com"], ["한화생명", "https://www.hanwhalife.com"], ["교보생명", "https://www.kyobo.com"], ["신한라이프", "https://www.shinhanlife.co.kr"],
  ["NH농협생명", "https://www.nhlife.co.kr"], ["농협생명", "https://www.nhlife.co.kr"], ["흥국생명", "https://www.heungkuklife.co.kr"], ["동양생명", "https://www.myangel.co.kr"],
  ["ABL생명", "https://www.abllife.co.kr"], ["미래에셋생명", "https://life.miraeasset.com"], ["삼성화재", "https://www.samsungfire.com"], ["현대해상", "https://www.hi.co.kr"],
  ["DB손해보험", "https://www.idbins.com"], ["KB손해보험", "https://www.kbinsure.co.kr"], ["메리츠화재", "https://www.meritzfire.com"], ["한화손해보험", "https://www.hwgeneralins.com"],
  ["롯데손해보험", "https://www.lotteins.co.kr"], ["흥국화재", "https://www.heungkukfire.co.kr"], ["농협손해보험", "https://www.nhfire.co.kr"],
  ["신한카드", "https://www.shinhancard.com"], ["삼성카드", "https://www.samsungcard.com"], ["현대카드", "https://www.hyundaicard.com"], ["국민카드", "https://www.kbcard.com"],
  ["롯데카드", "https://www.lottecard.co.kr"], ["우리카드", "https://pc.wooricard.com"], ["하나카드", "https://www.hanacard.co.kr"], ["비씨카드", "https://www.bccard.com"],
  ["SBI저축은행", "https://www.sbisb.co.kr"], ["OK저축은행", "https://www.oksavingsbank.com"], ["웰컴저축은행", "https://www.welcomebank.co.kr"],
];

// 금융지주회사는 통용 이름대로 "…지주"로 줄인다(신한금융지주회사 → 신한금융지주).
export const cleanCompanyName = (name) => String(name || "").replace(/\(\s*주\s*\)|㈜|주식회사/g, " ").replace(/\s+/g, " ").trim().replace(/지주회사$/, "지주");
const monthLabel = (m) => (/^\d{6}$/.test(m) ? `${m.slice(0, 4)}.${m.slice(4)}` : m);
const termLabel = (t) => ({ Q: "분기", H: "반기", Y: "연간" })[t] || "";

function unitMultiplier(unit) {
  const u = String(unit || "").replace(/ /g, "");
  if (u.includes("조")) return 1e12;
  if (u.includes("억")) return 1e8;
  if (u.includes("백만")) return 1e6;
  if (u.includes("천만")) return 1e7;
  if (u.includes("만원") || u === "만") return 1e4;
  if (u.includes("천원") || u === "천") return 1e3;
  if (u.includes("원")) return 1;
  return null;
}
const won = (v, unit) => v * (unitMultiplier(unit) ?? 1);
const fmt = (n, d) => n.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });

/** 금액은 억원(10억원 이상은 소수점 없이), 비율은 소수 둘째 자리 %. */
export function valueText(value, unit) {
  if (String(unit).includes("%")) return fmt(value, 2) + "%";
  const mult = unitMultiplier(unit);
  if (mult == null) return fmt(value, 2).replace(/\.?0+$/, "") + unit;
  const eok = (value * mult) / 1e8;
  if (Math.abs(eok) >= 10 || eok === 0) return Math.round(eok).toLocaleString("en-US") + "억원";
  return fmt(eok, 2).replace(/\.?0+$/, "") + "억원";
}

function homepage(name) {
  const key = name.replace(/\s|\(주\)|주식회사|㈜/g, "");
  const hit = HOMEPAGES.find(([k]) => key.toLowerCase().includes(k.toLowerCase()));
  return hit ? { url: hit[1], known: true } : { url: "https://www.google.com/search?q=" + encodeURIComponent(name + " 공식 홈페이지"), known: false };
}

// ---- 자료 ----
const companies = (sector) => cached("fisis:co:" + sector, 6 * 3600e3, async () =>
  ((await api("/fisis/companies", { sector })).items || []).map((c) => ({ code: c.code, name: cleanCompanyName(c.name) })).filter((c) => c.code && c.name).sort((a, b) => a.name.localeCompare(b.name, "ko")));
const ranking = (sector) => cached("fisis:rank:" + sector, 6 * 3600e3, () => api("/fisis/ranking", { sector, offset: 0 }));
const profileOf = (sector, code) => cached(`fisis:p:${sector}:${code}`, 6 * 3600e3, async () => {
  const p = await api("/fisis/profile", { sector, finance: code });
  return { ...p, name: cleanCompanyName(p.name), metrics: p.metrics || [], basisOptions: p.basisOptions || [] };
});

const at = (metric, month) => {
  if (!metric) return null;
  const pts = metric.points || [];
  return month == null ? pts[pts.length - 1] : pts.find((p) => p.month === month);
};
const group = (p, g, basis) => p.metrics.filter((m) => m.group === g && (basis == null || m.basis === basis || !m.basis));
const metricOf = (p, key, basis) => p.metrics.find((m) => m.key === key && (basis == null || m.basis === basis || !m.basis));
const monthsOf = (metrics) => [...new Set(metrics.flatMap((m) => (m.points || []).map((x) => x.month)))].filter((m) => m >= FIRST_MONTH).sort().reverse();
function amountOnly(metric, month) {
  const v = at(metric, month)?.value;
  return metric && v != null ? valueText(v, metric.unit).replace(/억원$/, "") : null;
}

const picks = () => store.get("fisis.picks", []);
function togglePick(sector, c) {
  let list = picks().filter((p) => p.sector === sector);
  if (list.some((p) => p.code === c.code)) list = list.filter((p) => p.code !== c.code);
  else if (list.length >= MAX_COMPARE) return toast(`비교는 ${MAX_COMPARE}곳까지 담을 수 있습니다.`), false;
  else list.push({ sector, code: c.code, name: c.name });
  store.set("fisis.picks", list);
  return true;
}

export async function render(root, params, ctx) {
  await sectorsReady;
  const sector = SECTORS.some(([c]) => c === params.sector) ? params.sector : store.get("fisis.sector", "F");
  if (params.tool === "risk") return riskPane(root, params, ctx, sector);
  if (params.co) return detail(root, params, ctx, sector);
  if (params.compare) return compare(root, params, ctx, sector);
  return list(root, params, ctx, sector);
}

/** 리스크 대시보드(건전성 추이·지배구조·제재 이력, 업권 사분면·현황)를 이 메뉴 안에서 보여 준다. */
async function riskPane(root, params, ctx, sector) {
  if (params.co) return ctx.go({ sector: params.sector || sector, co: params.co }, { replace: true }); // 옛 #/risk 회사 주소
  const [view, live, core] = await Promise.all([import("./risk-view.js"), import("../lib/risk-live.js"), import("../core.js")]);
  const { tool, ...rest } = params;
  return view.mountRisk(root, rest, {
    provider: live.liveProvider(), setHeader, openUrl: core.openUrl,
    go: (p) => (p.co ? ctx.go({ sector: p.sector || sector, co: p.co }) : ctx.go({ tool: "risk", ...p, view: "overview" })),
    // 앱(내장 화면)에서는 위쪽 탭을 앱이 그리므로 웹 탭 줄과 "회사 목록" 탭을 숨긴다.
    hideTabs: !!window.FssNative,
    extraTab: window.FssNative ? null : { label: "회사 목록", onSelect: () => ctx.go({ sector: SECTORS.some(([c]) => c === rest.sector) ? rest.sector : sector }) },
  });
}
/** 앱 전용: 앱이 회사 상세의 "개황" 탭 자리에 넣는 화면. 회사 코드·이름을 받아 개황만 그린다. */
export async function renderOverview(root, params, ctx) {
  const sector = SECTORS.some(([c]) => c === params.sector) ? params.sector : "A";
  const label = SECTORS.find(([c]) => c === sector)[1];
  return overviewPane(root, { code: params.co, name: params.name || params.co }, sector, label, ctx);
}
/** 회사 목록 화면 맨 위의 탭 줄: 회사 목록 / 업권 현황(규모 순위·분포·Peer 사분면). */
const toolTabs = (ctx, sector, current = 0) => tabs(["회사 목록", "업권 현황"], current, (i) => {
  if (i === 0) return ctx.go({ sector });
  ctx.go({ tool: "risk", sector, view: "overview" });
});
const SECTORS_RISK = ["A", "F", "H", "I", "C", "E"];

async function list(root, params, ctx, sector) {
  setHeader("금융회사 주요 현황", "회사명으로 검색해 경영지표를 비교 · " + SOURCE);
  root.append(toolTabs(ctx, sector));
  const label = SECTORS.find(([c]) => c === sector)[1];
  root.append(h("div", { class: "row-gap", style: { marginBottom: "10px" } },
    SECTORS.map(([c, l]) => h("button", { class: "pill" + (c === sector ? " on" : ""), type: "button", onclick: () => (store.set("fisis.sector", c), ctx.go({ sector: c }, { replace: true })) }, l))));
  const input = h("input", { type: "search", placeholder: `${label} 회사 이름 검색`, value: params.q || "" });
  root.append(h("div", { class: "search" }, icon("search"), input));
  const info = h("div", { class: "section-label", style: { fontWeight: 500 } });
  const body = h("div", { class: "card list" }, loading());
  root.append(info, body);

  let all = [];
  let assets = {};
  // 운용사(G)는 총자산이 아니라 금융투자협회 펀드 순자산(최근일자 공모+사모) 기준으로 순위를 매긴다. 자료가 없는 회사는 맨 뒤.
  let fundMod = null;
  let fundData = null;
  const fundOf = (c) => {
    const f = fundData && fundMod.findCompany(fundData, c.name, "G");
    const v = f ? f.pub[0] + f.pri[0] : 0;
    return v > 0 ? v : null;
  };
  const drawInfo = () => {
    const ps = picks().filter((p) => p.sector === sector);
    info.replaceChildren(
      h("span", null, ps.length ? `비교 선택 ${ps.length}/${MAX_COMPARE}: ${ps.map((p) => p.name).join(", ")}` : `회사를 누르면 상세, 체크하면 비교(최대 ${MAX_COMPARE}곳) · ${NOTICE}`),
      h("div", { class: "acts" },
        ps.length ? h("button", { class: "btn ghost sm", type: "button", onclick: () => (store.set("fisis.picks", picks().filter((p) => p.sector !== sector)), draw()) }, "선택 해제") : null,
        ps.length >= 2 ? h("button", { class: "btn sm", type: "button", onclick: () => ctx.go({ sector, compare: ps.map((p) => p.code).join(",") }) }, "비교하기") : null));
  };
  const draw = () => {
    drawInfo();
    const q = input.value.trim().toLowerCase();
    const sorted = [...all].sort((a, b) => {
      const fa = sector === "F" && FOREIGN_BROKER.test(a.name) ? 1 : 0;
      const fb = sector === "F" && FOREIGN_BROKER.test(b.name) ? 1 : 0;
      if (fa !== fb) return fa - fb;
      if (fundData) {
        const na = fundOf(a), nb = fundOf(b);
        if ((na == null) !== (nb == null)) return na == null ? 1 : -1;
        if (na !== nb) return nb - na;
        return a.name.localeCompare(b.name, "ko");
      }
      const va = assets[a.code] ? won(assets[a.code].value, assets[a.code].unit) : -Infinity;
      const vb = assets[b.code] ? won(assets[b.code].value, assets[b.code].unit) : -Infinity;
      if (va !== vb) return vb - va;
      return a.name.localeCompare(b.name, "ko");
    });
    const shown = sorted.filter((c) => !q || c.name.toLowerCase().includes(q));
    const ps = new Set(picks().filter((p) => p.sector === sector).map((p) => p.code));
    body.replaceChildren(...(shown.length ? shown.map((c, i) => {
      const a = assets[c.code];
      const fv = fundData ? fundOf(c) : null;
      const cb = h("input", { type: "checkbox", title: "비교에 담기", "aria-label": `${c.name} 비교에 담기` });
      cb.checked = ps.has(c.code);
      cb.addEventListener("click", (e) => e.stopPropagation());
      cb.addEventListener("change", () => {
        if (!togglePick(sector, c)) cb.checked = false;
        drawInfo();
      });
      return h("div", { class: "item co-row", onclick: () => ctx.go({ sector, co: c.code }) },
        h("span", { class: "avatar" }, c.name.replace(/^(주식회사|㈜)/, "").trim().slice(0, 1)),
        h("div", { class: "body" }, h("div", { class: "t" }, c.name), fundData
          ? h("div", { class: "m" }, fv != null ? `${q ? "" : `${i + 1}위 · `}펀드 순자산 ${fundMod.eokText(fv)}원 (${fundMod.dateLabel(fundData.asOf.recent)})` : "펀드 순자산 자료 없음")
          : a ? h("div", { class: "m" }, `${q ? "" : `${i + 1}위 · `}총자산 ${valueText(a.value, a.unit)} (${monthLabel(a.month)})`) : null),
        h("label", { class: "acts", onclick: (e) => e.stopPropagation() }, cb));
    }) : [message("검색 결과가 없습니다.")]));
  };
  input.addEventListener("input", draw);
  try {
    [all, assets] = await Promise.all([companies(sector), ranking(sector).then((r) => r.assets || {}).catch(() => ({}))]);
    if (sector === "G") {
      try {
        fundMod = await import("../lib/fundaum.js");
        fundData = await fundMod.loadFundAum();
      } catch {
        fundData = null; // 자료를 못 받으면 총자산 순서로 대신 보여 준다
      }
    }
    if (ctx.alive()) draw();
  } catch (e) {
    body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go({ sector }, { replace: true }) }));
  }
}

function periodPicker(months, selected, term, onSelect) {
  if (!months.length) return null;
  return h("div", { class: "period" }, h("div", { class: "note" }, `시점 선택(${termLabel(term) || "기준"})`),
    h("div", { class: "row-gap" }, months.map((m) => h("button", { class: "pill" + (m === selected ? " on" : ""), type: "button", onclick: () => onSelect(m) }, monthLabel(m)))));
}
const sectionHeader = (title, unit = "단위: 억원") => h("div", { class: "section-label", style: { marginTop: "14px" } }, h("span", { style: { color: "var(--acc-fg)" } }, title), unit ? h("div", { class: "acts note" }, unit) : null);

async function detail(root, params, ctx, sector) {
  const sectorLabel = SECTORS.find(([c]) => c === sector)[1];
  const all = await companies(sector).catch(() => []);
  const co = all.find((c) => c.code === params.co) || { code: params.co, name: params.name || params.co };
  setHeader(co.name, `${sectorLabel} · ${SOURCE}${sector === "F" || sector === "G" ? ", 금융투자협회(FreeSIS)" : ""}`, [
    h("button", { class: "pill", type: "button", title: "PDF·한글로 저장", onclick: () => saveCompany(sector, sectorLabel, co, params) }, icon("download"), h("span", { class: "lbl" }, "저장")),
    h("button", { class: "pill", type: "button", onclick: () => history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록")),
  ]);
  // 증권사·운용사는 금융투자협회의 펀드 순자산 탭을 더한다.
  // 증권사는 협회 자료에 그 회사 자체 수치가 있을 때만(소수) 보이고, 없으면 탭을 숨긴다. 운용사는 항상 보인다.
  let showFund = sector === "G";
  if (sector === "F") {
    const fm = await import("../lib/fundaum.js");
    showFund = await fm.loadFundAum().then((d) => !!fm.findCompany(d, co.name, "F"), () => false);
  }
  const tabNames = [...(SECTORS_RISK.includes(sector) ? ["대시보드"] : []), "개황", "재무손익", "주요경영지표", ...(showFund ? ["펀드 순자산"] : []), "최근 제재내역", "뉴스"];
  const tab = Math.min(+(params.tab || 0), tabNames.length - 1);
  const tabName = tabNames[tab];
  root.append(tabs(tabNames, tab, (i) => ctx.go({ ...params, tab: i }, { replace: true })));
  const body = h("div");
  root.append(body);
  if (tabName === "대시보드") return dashboardPane(body, params, co, sector, ctx, () => saveCompany(sector, sectorLabel, co, params));
  if (tabName === "개황") return overviewPane(body, co, sector, sectorLabel, ctx);
  if (tabName === "뉴스") {
    const m = await import("./news.js");
    return m.companyPane(body, co.name);
  }
  if (tabName === "최근 제재내역") {
    const m = await import("./sanction.js");
    return m.companyPane(body, co.name);
  }
  if (tabName === "펀드 순자산") {
    const m = await import("../lib/fundaum.js");
    return m.fundAumPane(body, co.name, sector);
  }
  if (tabName === "재무손익") return financePane(body, params, co, sector, sectorLabel, ctx);
  body.append(loading("경영정보를 불러오는 중…"));
  let profile;
  try {
    profile = await profileOf(sector, co.code);
  } catch (e) {
    return body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
  }
  if (!ctx.alive()) return;
  if (!profile.metrics.length) return body.replaceChildren(message(`이 회사는 ${sectorLabel} 자료를 찾지 못했습니다.`));
  body.replaceChildren();
  ratioTab(body, sector, profile, params.rm, +(params.span || 4), (p) => ctx.go({ ...params, ...p }, { replace: true }), co.name);
  body.append(h("p", { class: "note", style: { marginTop: "12px" } }, `${SOURCE}. 금액은 억원 단위입니다. ${NOTICE}`));
}

async function dashboardPane(body, params, co, sector, ctx, saveAll) {
  const [view, live, core] = await Promise.all([import("./risk-view.js"), import("../lib/risk-live.js"), import("../core.js")]);
  const go = (p) => (p.co === params.co ? ctx.go({ ...params, ...p }, { replace: true }) : p.co ? ctx.go({ sector: p.sector || sector, co: p.co }) : ctx.go({ tool: "risk", ...p, view: "overview" }));
  return view.dashboard(body, { sector, co: co.code, span: params.span }, { provider: live.liveProvider(), setHeader: () => {}, openUrl: core.openUrl, go, saveAll }, sector);
}

/** 개황: 전자공시(DART) 기업 개황(대표이사·설립일·시장·주소)과 최대주주 지분. 금융통계(FISIS) 회사 이름으로 DART 회사를 맞춘다. */
async function overviewPane(body, co, sector, sectorLabel, ctx) {
  body.append(loading("기업 개황을 불러오는 중…"));
  const [live, view] = await Promise.all([import("../lib/risk-live.js"), import("./risk-view.js")]);
  let dart;
  try {
    dart = await live.dartOf(co.name);
  } catch (e) {
    return body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go({ sector, co: co.code, tab: 0 }, { replace: true }) }));
  }
  if (!ctx.alive()) return;
  // 상장사(종목코드 6자리, 본인 자료)는 개황 위에 주가 추이(기본 1년)를 바로 보여 준다.
  const co2 = dart.company || {};
  const listed = (!dart.rel || dart.rel === "본인") && /^\d{6}$/.test(co2.stock || "") && ["Y", "K"].includes(co2.cls); // 유가증권(Y)·코스닥(K)만. 기타법인(E)·코넥스 등은 주가를 보이지 않는다
  const stockBox = h("div", { style: { marginBottom: "12px" } });
  body.replaceChildren(
    listed ? stockBox : null,
    h("div", { class: "card" }, view.govBody(dart)),
    h("p", { class: "note", style: { marginTop: "12px" } }, `자료출처: 금융감독원 전자공시시스템(DART). ${sectorLabel} 회사 이름으로 맞춘 자료라 다를 수 있습니다.`));
  if (listed) (await import("./dart.js")).stockCard(stockBox, { stock: co2.stock, cls: co2.cls }, {}, ctx, { key: "fisis.stockPeriod", def: 4 });
}

/** 재무손익 비교표 자료(FISIS): 최근 분기 + 직전 3개 사업연도 말. 이 기준(별도·연결)의 자료가 없으면 null. */
function finCompare(profile, sector, basis) {
  const fin = group(profile, "fin", basis).filter((m) => m.basis === basis);
  if (!fin.length) return null;
  const months = [...new Set(fin.flatMap((m) => (m.points || []).map((p) => p.month)))].sort().reverse();
  const latest = months[0];
  const fys = months.filter((m) => m.endsWith("12") && m !== latest).slice(0, 3);
  const metric = (k) => fin.find((m) => m.key === k);
  const amt = (k, mo) => amountOnly(metric(k), mo) ?? "-";
  const card = sector === "C";
  const current = { H: "당반기", Y: "당기" }[profile.term] || "당분기";
  const end = { H: "반기말", Y: "연말" }[profile.term] || "분기말";
  const bsMonths = [latest, ...fys];
  const plMonths = [latest, latest, ...fys];
  const ratio = (mo) => {
    const l = at(metric("liab"), mo)?.value, e = at(metric("equity"), mo)?.value;
    return l != null && e > 0 ? ((l / e) * 100).toFixed(1) + "%" : "-";
  };
  const keys = card ? CARD_PL_KEYS : DEFAULT_PL_KEYS;
  return {
    basis, latest, bsMonths, plMonths,
    bsKeys: ["assets", "liab", "equity"], plKeys: keys,
    bsHead: ["구분", `${monthLabel(latest)} (최근 ${end})`, ...fys.map(monthLabel)],
    bsRows: [...["assets", "liab", "equity"].map((k) => [FINANCE_LABELS[k], ...bsMonths.map((m) => amt(k, m))]), ["부채비율(부채÷자기자본)", ...bsMonths.map(ratio)]],
    plHead: ["구분", `${monthLabel(latest)} ${current}`, `${monthLabel(latest)} 누적`, ...fys.map((m) => `${m.slice(0, 4)}년 연간`)],
    plRows: keys.map((k) => [(card ? CARD_PL_LABELS[k] : null) || FINANCE_LABELS[k], amt(k, latest), amt(k + "_cum", latest), ...fys.map((m) => amt(k + "_cum", m))]),
    plBold: (i) => keys[i] === "netincome",
  };
}

/** DART 재무 자료(/dart/finance)에서 고른 기준(별도·연결)의 자료만 남긴다. 없으면 null. */
const dartBasis = (f, basis) => {
  if (f.basis === basis && (f.balance?.length || f.income?.length)) return { ...f, separate: null };
  const sep = f.separate;
  return sep?.basis === basis && (sep.balance?.length || sep.income?.length) ? { ...f, ...sep, separate: null } : null;
};
/** FISIS에 없는 재무제표를 DART에서 찾는다. 모회사 자료는 쓰지 않는다. 돌려주는 값: { d(DART 회사), f(선택한 기준 자료) } 또는 null. */
async function dartFinanceOf(name, basis) {
  const [live, dart] = await Promise.all([import("../lib/risk-live.js"), import("./dart.js")]);
  const d = await live.dartOf(name);
  if (!d?.corp || (d.rel && d.rel !== "본인")) return null;
  const f = dartBasis(await dart.finance(d.corp), basis);
  return f ? { d, f } : null;
}
const dartSourceText = (basis, d) => `금융통계정보시스템(FISIS)에 ${basis} 재무제표가 없어 전자공시시스템(DART) 정기보고서의 ${basis} 재무제표로 대신 표시합니다. DART 회사: ${d.name || d.company?.name || "-"}(고유번호 ${d.corp})${d.match === "guess" ? " · 회사 이름으로 추정해 맞춘 자료라 다를 수 있습니다" : ""}.`;

// pick(행, 열)을 주면 항목 이름과 금액을 눌러 재무제표를 볼 수 있다.
const cmpTable = (head, rows, bold, pick, name) => withTableTools(h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
  h("thead", null, h("tr", null, head.map((x, i) => h("th", { class: i ? "num" : "" }, x)))),
  h("tbody", null, rows.map((r, ri) => h("tr", { class: bold?.(ri) ? "bold" : "" }, r.map((v, i) => {
    const click = pick && ri < (pick.rows ?? rows.length) ? { class: "stmt-link", title: "눌러서 이 시점의 재무제표 보기", onclick: () => pick(ri, Math.max(0, i - 1)) } : null;
    return i ? h("td", { class: "num" + (String(v).startsWith("-") && v !== "-" ? " neg" : "") + (click ? " stmt-link" : ""), ...(click ? { title: click.title, onclick: click.onclick } : {}) }, v) : h("td", click || null, v);
  })))))), name || "표");

const stmtNum = (v) => (v == null ? "-" : Math.abs(v) >= 10 ? Math.round(v).toLocaleString("en-US") : String(+v.toFixed(2)));
const stmtTitle = (t) => String(t || "").replace(/\(\d{2}\.\d{2}월 (이후|이전)\)/g, "").trim();
const statementOfCo = (sector, code, basis) => cached(`fisis:stmt:${sector}:${code}:${basis}`, 6 * 3600e3, () => api("/fisis/statement", { sector, finance: code, basis }));

/** 재무손익 표의 항목·금액을 눌렀을 때: 그 시점의 재무제표(FISIS가 주는 요약 재무상태표·손익계산서의 모든 계정)를 창으로 보여 준다. */
async function openStatement(sector, co, basis, month, focus) {
  const body = h("div", null, loading("재무제표를 불러오는 중…"));
  let dlg = dialog({ title: `${co.name} 재무제표 (${basis})`, body, ok: "닫기", cancel: null, wide: true });
  let data;
  try {
    data = await statementOfCo(sector, co.code, basis);
  } catch (e) {
    return void body.replaceChildren(message(errorText(e), { error: true }));
  }
  const months = [...new Set(data.sections.flatMap((x) => x.months))].sort().reverse();
  let cur = months.includes(month) ? month : months[0];
  const draw = () => {
    body.replaceChildren(
      h("div", { class: "row-gap", style: { marginBottom: "8px" } }, months.slice(0, 12).map((m) => h("button", { class: "pill" + (m === cur ? " on" : ""), type: "button", onclick: () => ((cur = m), draw()) }, monthLabel(m)))),
      ...data.sections.map((sec) => {
        const rows = sec.rows.filter((r) => r.p[cur]);
        if (!rows.length) return null;
        // 구성비 같은 비율 열은 금액이 아니어서 뺀다.
        const idx = sec.columns.map((c, i) => (/구성비|비중|비율|%/.test(c) ? -1 : i)).filter((i) => i >= 0);
        const cols = idx.length ? idx : sec.columns.map((_, i) => i);
        const multi = cols.length > 1;
        return h("div", null, sectionHeader(stmtTitle(sec.title)),
          h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
            multi ? h("thead", null, h("tr", null, h("th", null, "계정"), cols.map((i) => h("th", { class: "num" }, sec.columns[i])))) : null,
            h("tbody", null, rows.map((r) => h("tr", { class: focus && r.name.split("_").pop().replace(/\s/g, "").replace(/^[\dⅠ-Ⅻ가-하]+[.)]/, "").startsWith(focus.replace(/\s/g, "")) ? "bold" : "" },
              h("td", { style: { paddingLeft: 8 + Math.min(r.indent + r.name.split("_").length - 1, 8) * 10 + "px" } }, r.name.split("_").pop()),
              cols.map((i) => r.p[cur][i]).map((v) => h("td", { class: "num" + (v != null && v < 0 ? " neg" : "") }, stmtNum(v)))))))));
      }),
      h("p", { class: "note", style: { marginTop: "10px" } }, `${SOURCE}. 금융통계정보시스템이 제공하는 요약 재무상태표·손익계산서 범위이며, 금액은 억원 단위입니다(손익은 당기·누계 열). ${NOTICE}`));
  };
  draw();
  return dlg;
}

/** 재무손익 탭: 최근 분기 + 직전 3개 사업연도 비교. FISIS에 없는 재무제표(주로 연결)는 DART에서 찾고 출처를 밝힌다. */
async function financePane(body, params, co, sector, sectorLabel, ctx) {
  body.append(loading("재무 자료를 불러오는 중…"));
  const basis = BASES.includes(params.basis) ? params.basis : "별도";
  let profile = null;
  let perr = null;
  try {
    profile = await profileOf(sector, co.code);
  } catch (e) {
    perr = e;
  }
  if (!ctx.alive()) return;
  const pills = h("div", { class: "row-gap" }, BASES.map((b) => h("button", { class: "pill" + (b === basis ? " on" : ""), type: "button", onclick: () => ctx.go({ ...params, basis: b }, { replace: true }) }, `${b} 재무제표`)));
  const home = homepage(co.name);
  const homeLink = h("a", { class: "btn ghost", href: home.url, target: "_blank", rel: "noopener", style: { marginTop: "14px" } }, icon("external-link"), home.known ? `홈페이지 열기 (${home.url.replace("https://", "")})` : "공식 홈페이지 검색하기");
  const cmp = profile?.metrics?.length ? finCompare(profile, sector, basis) : null;
  if (cmp) {
    body.replaceChildren(pills, sectionHeader(`재무상태 (${basis})`), cmpTable(cmp.bsHead, cmp.bsRows, null, Object.assign((ri, ci) => openStatement(sector, co, basis, cmp.bsMonths[ci], cmp.bsRows[ri][0]), { rows: cmp.bsKeys.length }), `${co.name}_재무상태_${basis}`),
      sectionHeader(`손익 (${basis})`), cmpTable(cmp.plHead, cmp.plRows, cmp.plBold, (ri, ci) => openStatement(sector, co, basis, cmp.plMonths[ci], cmp.plRows[ri][0]), `${co.name}_손익_${basis}`),
      h("p", { class: "note" }, "항목 이름이나 금액을 누르면 그 시점의 재무제표를 볼 수 있습니다. 연간 열은 해당 사업연도 말까지의 누적 값입니다. 최근 분기 열은 그 분기만의 값과 올해 누적 값을 함께 보여 줍니다."));
    const funds = group(profile, "fund");
    if (funds.length) {
      body.append(sectionHeader("펀드 설정원본 · 순자산", null), h("div", { class: "card list" }, funds.map((m) => {
        const v = at(m, cmp.latest)?.value;
        return h("div", { class: "item" }, h("div", { class: "body" }, m.label), h("b", null, v != null ? valueText(v, m.unit) : "-"));
      })));
    }
    body.append(homeLink, h("p", { class: "note", style: { marginTop: "12px" } }, `${SOURCE}. 금액은 억원 단위입니다. ${NOTICE}`));
    return;
  }
  // FISIS에 이 기준의 재무제표가 없다 → DART에서 찾는다.
  body.replaceChildren(pills, loading(`금융통계정보시스템에 ${basis} 재무제표가 없어 전자공시(DART)에서 찾는 중…`));
  let got = null;
  let derr = null;
  try {
    got = await dartFinanceOf(co.name, basis);
  } catch (e) {
    derr = e;
  }
  if (!ctx.alive()) return;
  if (!got) {
    const why = derr && perr ? errorText(derr) : `금융통계정보시스템과 전자공시(DART) 모두에서 ${basis} 재무제표를 찾지 못했습니다. ${basis === "연결" ? "연결 대상 종속회사가 없거나 DART 정기보고서 제출 대상이 아닌 회사일 수 있습니다. " : ""}`;
    return body.replaceChildren(pills, message(why, { error: !!(derr && perr), action: derr && perr ? "다시 시도" : undefined, onAction: () => ctx.go(params, { replace: true }) }), homeLink);
  }
  const box = h("div");
  (await import("./dart.js")).financeTab(box, got.f, {}, basis);
  body.replaceChildren(pills, h("div", { class: "card", style: { margin: "10px 0", borderColor: "var(--acc)" } }, h("b", null, "자료 출처: 전자공시시스템(DART)"), h("p", { class: "note", style: { margin: "4px 0 0" } }, dartSourceText(basis, got.d))), box, homeLink);
}

function ratioTab(body, sector, profile, month, span, set, coName) {
  const rows = RATIO_ROWS[sector];
  if (!rows) return body.append(message("이 권역은 주요경영지표를 제공하지 않습니다."));
  const ratios = group(profile, "ratio");
  const months = monthsOf(ratios);
  const selected = months.includes(month) ? month : months[0];
  body.append(periodPicker(months, selected, ratios[0]?.term || profile.term, (m) => set({ rm: m })) || "");
  body.append(h("div", { class: "row-gap", style: { margin: "8px 0" } }, [4, 8].map((n) => h("button", { class: "pill" + (span === n ? " on" : ""), type: "button", onclick: () => set({ span: n }) }, `최근 ${n}${termLabel(profile.term) || "개 시점"}`))));
  body.append(sectionHeader("주요경영지표", "단위: %"));
  const start = Math.max(0, months.indexOf(selected));
  const shown = months.slice(start, start + span);
  body.append(withTableTools(h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
    h("thead", null, h("tr", null, h("th", null, "지표"), shown.map((m) => h("th", { class: "num" + (m === selected ? " sel" : "") }, monthLabel(m))))),
    h("tbody", null, rows.map(([k, label]) => {
      const metric = ratios.find((m) => m.key === k);
      return h("tr", null, h("td", null, label), shown.map((m) => {
        const v = at(metric, m)?.value;
        return h("td", { class: "num" + (m === selected ? " sel" : "") }, v != null && metric ? valueText(v, metric.unit).replace(/%$/, "") : "-");
      }));
    })))), `${coName || "회사"}_주요경영지표`));
  body.append(h("p", { class: "note" }, "선택한 시점 열은 강조됩니다."));
}

async function compare(root, params, ctx, sector) {
  setHeader("회사 비교", SOURCE, [h("button", { class: "pill", type: "button", onclick: () => history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록"))]);
  const codes = params.compare.split(",").slice(0, MAX_COMPARE);
  const all = await companies(sector).catch(() => []);
  const cos = codes.map((c) => all.find((x) => x.code === c) || { code: c, name: c });
  root.append(loading("경영정보를 불러오는 중…"));
  let profiles;
  try {
    profiles = await Promise.all(cos.map((c) => profileOf(sector, c.code)));
  } catch (e) {
    return root.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
  }
  if (!ctx.alive()) return;
  root.replaceChildren();
  const row = (label, key, pct) => [label, ...profiles.map((p) => {
    const m = metricOf(p, key, "별도");
    const v = at(m, null)?.value;
    return m && v != null ? valueText(v, m.unit).replace(pct ? /%$/ : /억원$/, "") : "-";
  })];
  const sections = [
    { title: "재무상태", unit: "단위: 억원", rows: ["assets", "liab", "equity"].map((k) => row(FINANCE_LABELS[k], k, false)) },
    { title: "손익현황 (누적)", unit: "단위: 억원", rows: ["revenue", "opinc", "netincome"].map((k) => row(FINANCE_LABELS[k] + " (누적)", k + "_cum", false)) },
    ...(RATIO_ROWS[sector] ? [{ title: "주요경영지표", unit: "단위: %", rows: RATIO_ROWS[sector].map(([k, l]) => row(l, k, true)) }] : []),
  ];
  const basisLine = "기준 시점: " + profiles.map((p, i) => `${cos[i].name} ${monthLabel(monthsOf(group(p, "fin", "별도"))[0] || "-")}`).join(" · ");
  const sectorLabel = SECTORS.find(([c]) => c === sector)?.[1] || "";
  const names = cos.map((c) => c.name);
  setHeader("회사 비교", SOURCE, [
    h("button", { class: "pill", type: "button", title: "비교표를 PDF·한글로 저장", onclick: () => saveCompare(sectorLabel, names, basisLine, sections) }, icon("download"), h("span", { class: "lbl" }, "저장")),
    h("button", { class: "pill", type: "button", onclick: () => history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록"))]);
  root.append(h("div", { class: "note" }, basisLine));
  for (const sec of sections) {
    root.append(sectionHeader(sec.title, sec.unit), withTableTools(h("div", { class: "tbl-wrap" }, h("table", { class: "tbl cmp" },
      h("thead", null, h("tr", null, h("th", null, ""), names.map((n) => h("th", { class: "num" }, n)))),
      h("tbody", null, sec.rows.map((r) => h("tr", null, h("td", null, r[0]), r.slice(1).map((v) => h("td", { class: "num" + (v.startsWith("-") && v !== "-" ? " neg" : "") }, v))))))), `${names.join("_")}_${sec.title}`));
  }
  root.append(h("p", { class: "note", style: { marginTop: "10px" } }, "각 회사의 FISIS 최근 시점 기준입니다. 시점이 다르면 위의 기준 시점을 확인하세요."));
}

/** 선택한 2~3개사의 비교표를 PDF·한글로 저장한다. */
async function saveCompare(sectorLabel, names, basisLine, sections) {
  const { saveReport } = await import("../lib/report.js");
  await saveReport({
    title: "회사 비교 저장",
    parts: sections.map((s) => ({ id: s.title, label: `${s.title} (${s.unit.replace("단위: ", "단위 ")})` })),
    build: async (ids) => ({
      title: `${names.join(" · ")} 비교`,
      subtitle: `금융회사 비교 · ${sectorLabel}`,
      fileName: `${names.join("_")}_비교`,
      meta: [basisLine, "자료출처: 금융감독원 금융통계정보시스템(FISIS)", NOTICE],
      sections: sections.filter((s) => ids.has(s.title)).map((s) => ({
        title: s.title,
        note: s.unit,
        blocks: [{ table: { head: ["", ...names], rows: s.rows, weights: [2.2, ...names.map(() => 1.4)] } }],
      })),
    }),
  });
}

// ---- PDF·한글 저장: 개황·주가·재무손익·주요경영지표·대시보드·제재(모든 탭의 저장 단추가 같은 저장 창을 연다) ----
async function saveCompany(sector, sectorLabel, co, params) {
  const { saveReport } = await import("../lib/report.js");
  await saveReport({
    title: `${co.name} 저장`,
    parts: [
      { id: "ovw", label: "개황 (DART 기업 개황·최대주주)" },
      { id: "price", label: "주가 그래프 (최근 1년, 상장사만)" },
      { id: "fin", label: "재무손익 (최근 분기·최근 3개 사업연도 비교, 별도·연결)" },
      { id: "ratio", label: RATIO_ROWS[sector] ? "주요경영지표 (최근 8개 시점)" : "주요경영지표 (이 권역은 제공 안 함)", checked: !!RATIO_ROWS[sector] },
      ...(SECTORS_RISK.includes(sector) ? [{ id: "dash", label: "대시보드 (핵심 지표·체크 포인트·건전성 추이)" }] : []),
      { id: "sanction", label: "제재공시 (2011년 이후 금감원 제재공시 전체)" },
    ],
    build: (ids) => companyDoc(ids, sector, sectorLabel, co, params),
  });
}

async function companyDoc(ids, sector, sectorLabel, co, params) {
  const profile = await profileOf(sector, co.code).catch(() => ({ metrics: [], basisOptions: [] })); // 금융통계가 안 와도 DART 등 나머지는 저장
  const sections = [];
  const ovwSecs = [], dashSecs = [];
  const term = termLabel(profile.term) || "기준";
  if (ids.has("dash") || ids.has("ovw")) {
    // 대시보드·개황: 리스크 대시보드와 같은 자료·같은 표를 그대로 쓴다(실패해도 나머지 저장은 계속).
    try {
      const [live, rd] = await Promise.all([import("../lib/risk-live.js"), import("../lib/risk-doc.js")]);
      if (SECTORS_RISK.includes(sector)) {
        const provider = live.liveProvider();
        const [rows, info] = await Promise.all([provider.peers(sector), provider.company(sector, co.code)]);
        const row = rows.find((r) => r.code === co.code);
        const want = new Set([ids.has("dash") && row ? "sum" : null, ids.has("dash") && row ? "trend" : null, ids.has("ovw") ? "gov" : null].filter(Boolean));
        if (want.size) {
          const d = rd.dashboardDoc(want, { row: row || { code: co.code, name: co.name }, rows, sector, info, charts: true });
          // 대시보드는 화면과 같은 구성(핵심 지표·체크 포인트 → 건전성 추이 그래프)을 한 구역으로 묶는다.
          const pick = (t) => d.sections.find((x) => x.title === t);
          const sum = pick("핵심 지표·체크 포인트"), trend = pick("건전성 추이"), gov = pick("기업 개황·지배구조");
          if (sum || trend) {
            const blocks = [];
            // 자산총계는 재무손익의 재무상태 표에 이미 있으므로 대시보드에서는 뺀다(저장 문서 한정).
            if (sum) blocks.push({ heading: `핵심 지표·체크 포인트 (${sum.note})` }, ...sum.blocks.filter((b) => !(b.text || "").startsWith("자산총계(") || !ids.has("fin")));
            if (trend) blocks.push({ heading: `건전성 추이 (${trend.note})` }, ...trend.blocks);
            dashSecs.push({ title: "대시보드", note: "금융통계정보시스템·전자공시·제재공시 기준 요약", blocks });
          }
          if (gov) ovwSecs.push({ ...gov, title: "개황" });
        }
      } else if (ids.has("ovw")) ovwSecs.push({ ...rd.govSection(await live.dartOf(co.name)), title: "개황" });
    } catch (e) {
      (ids.has("ovw") ? ovwSecs : dashSecs).push({ title: ids.has("ovw") ? "개황" : "대시보드", blocks: [{ text: "이 자료를 불러오지 못해 저장하지 못했습니다: " + errorText(e), gray: true }] });
    }
  }
  sections.push(...ovwSecs);
  if (ids.has("price")) {
    try {
      const ci = (await (await import("../lib/risk-live.js")).dartOf(co.name))?.company;
      sections.push(await (await import("../lib/stockchart.js")).stockSection(ci?.stock, ci?.cls));
    } catch (e) {
      sections.push({ title: "주가 (최근 1년)", blocks: [{ text: "주가 자료를 불러오지 못해 넣지 못했습니다: " + errorText(e), gray: true }] });
    }
  }
  if (ids.has("fin")) {
    const funds = group(profile, "fund");
    const blocks = [];
    let latest = null;
    for (const basis of BASES) {
      const cmp = finCompare(profile, sector, basis);
      if (cmp) {
        latest = latest || cmp.latest;
        blocks.push({ heading: `재무상태 (${basis}) · 단위 억원` }, { table: { head: cmp.bsHead, rows: cmp.bsRows, weights: [2.2, ...cmp.bsHead.slice(1).map(() => 1)] } });
        blocks.push({ heading: `손익 (${basis}) · 단위 억원` }, { table: { head: cmp.plHead, rows: cmp.plRows, bold: cmp.plBold, weights: [2.2, ...cmp.plHead.slice(1).map(() => 1)] } });
        blocks.push({ text: "출처: 금융감독원 금융통계정보시스템(FISIS). 연간 열은 해당 사업연도 말까지의 누적 값입니다.", gray: true });
        continue;
      }
      // FISIS에 없는 재무제표는 전자공시(DART)에서 찾아 출처를 밝힌다.
      let got = null;
      try {
        got = await dartFinanceOf(co.name, basis);
      } catch {}
      if (got) blocks.push({ heading: `${basis} 재무제표 (전자공시 DART)` }, { text: "출처: " + dartSourceText(basis, got.d), gray: true }, ...(await import("./dart.js")).financeBlocks(got.f, false));
      else blocks.push({ text: `${basis} 재무제표: 금융통계정보시스템과 전자공시(DART) 모두에서 찾지 못했습니다.`, gray: true });
    }
    if (funds.length && latest) blocks.push({ heading: "펀드 설정원본 · 순자산" }, { table: { head: ["항목", "금액"], rows: funds.map((m) => [m.label, at(m, latest)?.value != null ? valueText(at(m, latest).value, m.unit) : "-"]), weights: [2, 1] } });
    sections.push({ title: "재무손익", note: "최근 분기와 직전 3개 사업연도 비교", blocks });
  }
  if (ids.has("ratio")) {
    const rows = RATIO_ROWS[sector];
    const ratios = group(profile, "ratio");
    const months = monthsOf(ratios);
    const start = Math.max(0, months.indexOf(params.rm));
    const shown = months.slice(start, start + 8).reverse();
    sections.push({
      title: "주요경영지표",
      note: "단위: %",
      blocks: rows && shown.length
        ? [{ table: { head: ["지표", ...shown.map(monthLabel)], weights: [2.2, ...shown.map(() => 1)], rows: rows.map(([k, label]) => {
            const m = ratios.find((x) => x.key === k);
            return [label, ...shown.map((mo) => {
              const v = at(m, mo)?.value;
              return v != null && m ? valueText(v, m.unit).replace(/%$/, "") : "-";
            })];
          }) } }]
        : [{ text: "이 권역은 주요경영지표를 제공하지 않거나 자료가 없습니다.", gray: true }],
    });
  }
  sections.push(...dashSecs);
  if (ids.has("sanction")) {
    const S = await import("../lib/sanctions.js");
    const data = await S.loadSanctions();
    const cases = S.casesOf(data, co.name);
    const blocks = [];
    if (!cases.length) blocks.push({ text: `${data.fromYear}년 이후 금감원 제재공시에서 ‘${co.name}’ 이름으로 된 공시를 찾지 못했습니다.`, gray: true });
    cases.forEach((c, i) => {
      blocks.push({ heading: `${i + 1}. ${c.company} · ${String(c.date || "").replace(/-/g, ".")}${c.dept ? " · " + c.dept : ""}` });
      const acts = [];
      const inst = S.actionLine(c.inst);
      if (inst) acts.push(["기관", inst]);
      if (!c.inst && c.fine + c.penalty > 0) acts.push(["금전", [c.fine > 0 ? `과태료 ${S.won(c.fine)}` : null, c.penalty > 0 ? `과징금 ${S.won(c.penalty)}` : null].filter(Boolean).join(", ")]);
      const ex = S.actionLine(c.exec);
      if (ex) acts.push(["임원", ex]);
      const sf = S.actionLine(c.staff);
      if (sf) acts.push(["직원", sf]);
      blocks.push({ text: "[조치내용]", indent: 1 });
      if (acts.length) blocks.push({ kv: acts });
      else blocks.push({ text: "표시할 조치내용이 없습니다. 원문을 확인해 주세요.", gray: true, indent: 1 });
      blocks.push({ text: "[지적사항]", indent: 1 });
      if (c.items.length) blocks.push({ text: c.items.map((it, j) => `${j + 1}) ${it.category ? `[${it.category}] ` : ""}${it.title}${it.laws.length ? ` (${it.laws.join(", ")})` : ""}`).join("\n"), indent: 1 });
      else blocks.push({ text: S.whyText(c), gray: true, indent: 1 });
      if (c.viewUrl) blocks.push({ text: "원문: " + c.viewUrl, gray: true, indent: 1 });
    });
    sections.push({ title: "제재공시", note: `${data.fromYear}년 이후 금감원 제재공시 전체 ${cases.length}건 · 회사명으로 맞춘 참고 자료이니 업무에는 원문을 확인하세요.`, blocks });
  }
  return {
    title: co.name,
    subtitle: "금융회사 주요 현황",
    fileName: `${co.name}_금융회사현황`,
    meta: [`권역: ${sectorLabel}   ·   출력: ${new Date().toLocaleDateString("ko-KR")}`],
    sections,
    footer: [`${SOURCE}. ${NOTICE}`, ids.has("sanction") ? "제재내역 출처: 금융감독원 금융회사 제재공시" : ""],
  };
}
