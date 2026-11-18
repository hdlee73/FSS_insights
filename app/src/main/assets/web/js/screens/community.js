// 커뮤니티 핫이슈(금융관련): 디시·클리앙·보배드림 게시판, 네이버 블로그·카페 언급 신호, 유튜브 영상을 '출처별 칸'으로 나눠 보여 준다.
// 자료는 FSS_insights 레포 community-data 브랜치(GitHub Actions community-data.yml이 평일 업무시간 30분·그 밖 2시간마다 갱신하는 hot.json). 별도 서버 없음.
// 제목·출처·시간·조회/댓글 수와 원문 링크만 담으며 본문·닉네임은 수집하지 않는다. 출처가 서로 다른 지표를 가지므로 하나의 순위표에 섞지 않고, 각 칸 안에서만 순위를 매긴다.
import { setHeader } from "../app.js";
import { errorText, h, icon, iconButton, loading, message, openUrl, tabs } from "../core.js";

// 앱(WebView)에 내장된 core.js가 웹보다 오래된 경우에도 같은 파일이 동작하도록 작은 도우미는 여기서 정의한다.
const canPoll = () => document.visibilityState === "visible" && navigator.onLine !== false;
function onResume(cb) {
  const f = () => canPoll() && cb();
  document.addEventListener("visibilitychange", f);
  addEventListener("online", f);
  return () => {
    document.removeEventListener("visibilitychange", f);
    removeEventListener("online", f);
  };
}
function safeHttpUrl(url) {
  try {
    const u = new URL(String(url));
    return u.protocol === "https:" || u.protocol === "http:" ? u.href : "";
  } catch {
    return "";
  }
}

const URL_HOT = "https://raw.githubusercontent.com/hdlee73/FSS_insights/community-data/hot.json";
const GROUPS = [
  ["dc", "디시인사이드", "주식·미국주식·비트코인 갤러리의 개념글과 최신 글"],
  ["clien", "클리앙", "주식한당·모두의공원 최신 글(사이트가 인기 목록 수집을 허용하지 않음)"],
  ["bobae", "보배드림", "베스트·자유게시판 중 금융 키워드 글(보조)"],
  ["naver", "네이버 블로그·카페", "조회·댓글 수가 없어 순위가 아닌 ‘언급량 신호’"],
  ["yt", "유튜브", "금융 관련 인기 영상(조회·댓글·좋아요)"],
];
const TAB_IDS = ["all", ...GROUPS.map((g) => g[0])];
const TAB_LABELS = ["출처별 보기", "디시", "클리앙", "보배드림", "네이버", "유튜브"];
const groupOf = (sk) => (sk.startsWith("dc-") ? "dc" : sk.startsWith("clien") ? "clien" : sk === "bobae" ? "bobae" : sk === "youtube" ? "yt" : sk);

export const LIMITS = [
  "일부 커뮤니티만 수집합니다. 사이트가 수집을 허용하지 않거나 막는 곳(네이버 종토방·뽐뿌·에펨코리아 등)은 제외했습니다.",
  "약 30분(업무시간) 또는 2시간(야간·주말) 주기로 갱신하므로 실제 게시 시점보다 늦게 보일 수 있습니다.",
  "인기글 기준은 사이트별 방식과 자체 열기점수에 따라 달라, 모든 이슈가 포함되지는 않습니다. 열기점수는 같은 출처 안에서만 비교하세요.",
  "본문은 수집하지 않습니다. 제목·출처·시간·조회·댓글 수와 원문 링크만 제공하며, 클리앙은 인기 목록이 막혀 최신 글만 봅니다.",
  "네이버는 조회·댓글 수가 없어 ‘오늘 올라온 블로그 글 수(최근 100건 기준)’ 같은 언급량 신호만 제공합니다.",
  "커뮤니티 글은 검증되지 않은 의견·루머일 수 있으며 투자·감독 판단의 근거가 아닙니다.",
  "사이트 차단·점검·구조 변경 시 일시적으로 비거나 지연될 수 있습니다(아래 ‘출처 상태’ 참고). 키 등록 전 출처는 ‘준비 중’으로 표시됩니다.",
  "키워드·금융 관련 판정에는 오탐(관련 없는 글)과 누락이 있을 수 있으며, 비속어가 든 제목은 표시하지 않습니다.",
];

const num = (n) => (n == null ? "-" : Number(n).toLocaleString("ko-KR"));
function rel(ts) {
  if (!ts) return "";
  const m = Math.max(0, Math.round((Date.now() - new Date(ts)) / 60000));
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  if (m < 1440) return `${Math.floor(m / 60)}시간 전`;
  return `${Math.floor(m / 1440)}일 전`;
}
const hhmm = (iso) => {
  if (!iso) return "-";
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

async function load() {
  let res;
  try {
    res = await fetch(URL_HOT, { cache: "no-cache" });
  } catch {
    throw new Error("네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
  }
  if (res.status === 404) throw new Error("아직 수집된 자료가 없습니다(404). 첫 수집이 끝난 뒤 다시 확인해 주세요.");
  if (!res.ok) throw new Error(`서버 응답 오류(${res.status})`);
  try {
    return await res.json();
  } catch {
    throw new Error("데이터 형식을 읽지 못했습니다.");
  }
}

function link(url, label, cls = "") {
  const safe = safeHttpUrl(url);
  if (!safe) return h("span", { class: cls }, label);
  return h("a", { class: cls, href: safe, target: "_blank", rel: "noopener noreferrer", onclick: (e) => (e.preventDefault(), openUrl(safe)) }, label);
}

function itemRow(it, rank) {
  const meta = [h("span", { class: "tag blue" }, it.s), it.b && it.sk !== "youtube" ? h("span", { class: "cm-b" }, it.b) : it.b ? h("span", { class: "cm-b" }, it.b) : null, h("span", null, rel(it.ts))];
  meta.push(h("span", null, `조회 ${num(it.v)}`), h("span", null, `댓글 ${num(it.c)}`));
  if (it.sk === "youtube" && it.l) meta.push(h("span", null, `좋아요 ${num(it.l)}`));
  if (it.dv > 0) meta.push(h("span", { class: "cm-up", title: "직전 수집 대비 조회 증가량" }, `▲ 조회 +${num(it.dv)}`));
  if (it.sup) meta.push(h("span", { class: "tag orange" }, "감독·금융사"));
  if (it.also?.length) meta.push(h("span", { class: "tag" }, `다른 출처 ${it.also.length}`));
  return h("div", { class: "cm-row" },
    h("b", { class: "cm-rank" }, String(rank)),
    h("div", { class: "cm-body" },
      link(it.u, it.t, "cm-t"),
      h("div", { class: "cm-m" }, meta, h("span", { class: "cm-heat", title: `열기점수 ${it.score} (같은 출처 안에서만 비교)` }, h("i", { style: `--p:${Math.max(6, Math.min(100, it.score))}` }), h("em", null, "🔥" + it.score)))));
}

function naverRows(terms, limit) {
  const list = limit ? terms.slice(0, limit) : terms;
  if (!list.length) return [h("div", { class: "cm-empty" }, "표시할 신호가 없습니다.")];
  return list.map((t) => {
    const arrow = t.trend === 2 ? "▲▲" : t.trend === 1 ? "▲" : "";
    const cnt = t.today == null ? "-" : t.sat ? "100건+" : `${t.today}건`;
    const detail = h("div", { class: "cm-nvd" },
      ...(t.blog || []).map((b) => h("div", null, h("span", { class: "tag" }, "블로그"), link(b.u, b.t))),
      ...(t.cafe || []).map((c) => h("div", null, h("span", { class: "tag green" }, c.c || "카페"), link(c.u, c.t))));
    const row = h("details", { class: "cm-nv" },
      h("summary", null, h("b", null, t.k), h("span", { class: "cm-nvc" }, `오늘 블로그 ${cnt}`, t.yday != null ? h("small", null, ` · 어제 ${t.yday}건`) : null, arrow ? h("em", { class: "cm-up" }, " " + arrow) : null)),
      detail);
    return row;
  });
}

function pendingBox(st) {
  const pending = st?.pending;
  const text = pending ? "준비 중입니다. 수집에 필요한 키를 등록하면 자동으로 채워집니다." : st && !st.ok ? `일시적으로 수집하지 못했습니다(${st.error || "오류"}). 다음 갱신 때 다시 시도합니다.` : "표시할 항목이 없습니다.";
  return h("div", { class: "cm-empty" }, text);
}

export async function render(root, params, ctx) {
  const tab = TAB_IDS.includes(params.t) ? params.t : "all";
  setHeader("커뮤니티 핫이슈(금융관련)", "공개 게시글 제목·지표·원문 링크만 표시 · 약 30분 주기", [iconButton("refresh-cw", "새로고침", () => ctx.go(params, { replace: true }))]);
  const body = h("div", { class: "cm" }, loading());
  root.append(body);
  let data;
  try {
    data = await load();
  } catch (e) {
    if (!ctx.alive()) return;
    body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
    return;
  }
  if (!ctx.alive()) return;

  // 자동 새로고침: 화면이 보일 때만 5분마다 같은 파일을 다시 읽는다(서버 요청 아님: 정적 파일).
  const again = () => ctx.go(params, { replace: true });
  const off = onResume(() => (ctx.alive() ? again() : off()));
  const timer = setInterval(() => {
    if (!ctx.alive()) { clearInterval(timer); off(); return; }
    if (canPoll()) again();
  }, 5 * 60 * 1000);

  const items = data.items || [];
  const kw = (params.k || "").trim();
  const onlySup = params.sup === "1";
  const apply = (arr) => arr.filter((it) => (!onlySup || it.sup) && (!kw || it.t.includes(kw)));
  const groups = Object.fromEntries(GROUPS.map(([id]) => [id, []]));
  for (const it of items) groups[groupOf(it.sk)]?.push(it);
  for (const it of data.youtube?.items || []) groups.yt.push(it);
  for (const g of Object.values(groups)) g.sort((a, b) => b.score - a.score);
  const status = Object.fromEntries((data.sources || []).map((s) => [groupOf(s.key), []]));
  for (const s of data.sources || []) status[groupOf(s.key)].push(s);
  const stOf = (id) => {
    const arr = status[id] || [];
    if (!arr.length) return null;
    if (arr.every((s) => s.pending)) return { pending: true };
    const ok = arr.some((s) => s.ok);
    return { ok, error: arr.find((s) => s.error)?.error };
  };

  const stats = data.stats || {};
  const stale = data.stale || (data.updated && Date.now() - new Date(data.updated) > 6 * 3600 * 1000);

  // 한계점·이용 안내(접이식)
  const info = h("div", { class: "cm-info", hidden: true }, h("b", null, "ⓘ 한계점·이용 안내"), h("ul", null, LIMITS.map((t) => h("li", null, t))));
  const infoBtn = h("button", { type: "button", class: "cm-infobtn", "aria-expanded": "false", onclick: () => { info.hidden = !info.hidden; infoBtn.setAttribute("aria-expanded", String(!info.hidden)); } }, icon("info"), " 한계점·이용 안내 ▾");

  const kpi = (label, v, sub) => h("div", { class: "kpi" }, h("small", null, label), h("b", null, v), sub ? h("small", null, sub) : null);
  const kpis = h("div", { class: "kpis cm-kpis" },
    kpi("이번 수집 글", num(stats.collected), "게시판 목록 기준"),
    kpi("금융 관련 판정", num(stats.finance), "키워드·열기점수 기준"),
    kpi("감독·금융사 언급", num(stats.supervision), "금융당국·금융사 키워드"),
    kpi("수집 출처", `${stats.sourcesOk ?? 0}/${stats.sourcesTotal ?? 0}`, stats.sourcesPending ? `준비 중 ${stats.sourcesPending}` : "정상/전체"));

  // 급상승 키워드(칩): 누르면 제목 필터
  const kws = (data.keywords || []).slice(0, 10);
  const mk = (extra) => ({ ...params, ...extra, t: tab });
  const chips = h("div", { class: "cm-chips" },
    h("b", null, "급상승 키워드"),
    h("button", { type: "button", class: "pill" + (!kw && !onlySup ? " on" : ""), onclick: () => ctx.go({ t: tab }) }, "전체"),
    h("button", { type: "button", class: "pill" + (onlySup ? " on" : ""), onclick: () => ctx.go(mk({ sup: onlySup ? "" : "1", k: "" })) }, "감독·금융사만"),
    kws.map((k) => h("button", { type: "button", class: "pill" + (kw === k.k ? " on" : ""), title: `이번 목록 ${k.n}건`, onclick: () => ctx.go(mk({ k: kw === k.k ? "" : k.k, sup: "" })) }, k.k, k.trend ? h("em", { class: "cm-up" }, k.trend === 2 ? " ▲▲" : " ▲") : null)),
    !data.warm ? h("small", { class: "cm-note" }, "수집 기록이 쌓이는 중이라 급상승 판정은 잠시 후 정확해집니다.") : null);

  const tabBar = tabs(TAB_LABELS, TAB_IDS.indexOf(tab), (i) => ctx.go({ t: TAB_IDS[i] === "all" ? "" : TAB_IDS[i] }), "seg");

  // 본문
  const panel = h("div", { class: "cm-panel" });
  const groupCard = (id, limit) => {
    const [, name, sub] = GROUPS.find((g) => g[0] === id);
    const st = stOf(id);
    let rows;
    if (id === "naver") {
      const terms = data.naver?.terms || [];
      rows = st?.pending || (!terms.length && st) ? [pendingBox(st)] : naverRows(kw ? terms.filter((t) => t.k.includes(kw)) : terms, limit);
    } else {
      const arr = apply(groups[id]);
      rows = arr.length ? (limit ? arr.slice(0, limit) : arr.slice(0, 50)).map((it, i) => itemRow(it, i + 1)) : [pendingBox(id === "yt" || id === "dc" || id === "clien" || id === "bobae" ? st : null)];
    }
    const more = limit && tab === "all" ? h("a", { class: "cm-more", href: `#/community?t=${id}` }, "이 출처 전체 보기 ›") : null;
    return h("section", { class: "card cm-card" },
      h("div", { class: "ch" }, h("h3", null, name), h("div", { class: "ch-acts" }, st?.pending ? h("span", { class: "tag" }, "준비 중") : st && !st.ok ? h("span", { class: "tag orange" }, "일시 중단") : null)),
      h("div", { class: "cm-sub" }, sub), rows, more);
  };

  if (tab === "all") {
    // 전체 급상승: 출처별 최대 2건, 같은 출처 안 순위로만 뽑는다(출처 간 점수 직접 비교 없음)
    const top = [];
    for (let r = 0; r < 2; r++) for (const id of ["dc", "clien", "bobae", "yt"]) { const it = apply(groups[id])[r]; if (it) top.push(it); }
    top.sort((a, b) => (b.dv || 0) - (a.dv || 0) || b.score - a.score);
    const topCard = h("section", { class: "card cm-card cm-top" },
      h("div", { class: "ch" }, icon("trending-up"), h("h3", null, "지금 눈에 띄는 글")),
      h("div", { class: "cm-sub" }, "각 출처에서 상위 2건씩만 모았습니다(점수는 출처 안에서만 비교됩니다)"),
      top.length ? top.slice(0, 8).map((it, i) => itemRow(it, i + 1)) : h("div", { class: "cm-empty" }, "조건에 맞는 글이 없습니다."));
    panel.append(topCard, h("div", { class: "cm-grid" }, GROUPS.map(([id]) => groupCard(id, 5))));
  } else {
    panel.append(groupCard(tab, 0));
  }

  // 출처 상태
  const stRows = (data.sources || []).map((s) => h("span", { class: "cm-st " + (s.pending ? "pend" : s.ok ? "ok" : "bad"), title: s.error || "" }, `${s.name}${s.board && s.key.startsWith("clien") ? "·" + s.board : ""}`, " ", s.pending ? "준비 중" : s.ok ? "정상" : "수집 실패"));
  const foot = h("div", { class: "cm-foot" },
    h("div", { class: "cm-sts" }, h("b", null, "출처 상태"), stRows, h("small", null, ` · 마지막 갱신 ${hhmm(data.updated)}`, stale ? " (오래됨: 수집이 지연되고 있습니다)" : "")),
    h("div", { class: "cm-disc" }, icon("info"), " 일부 커뮤니티만 약 30분 주기로 수집하며 지연·누락이 있을 수 있습니다. 제목·지표·원문 링크만 제공하고, 커뮤니티 글은 검증되지 않은 의견으로 투자·감독 판단의 근거가 아닙니다."));

  body.replaceChildren(h("div", { class: "cm-head" }, infoBtn), info, kpis, chips, tabBar, panel, foot);
}
