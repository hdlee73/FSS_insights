// 뉴스검색(앱 NewsScreen·MonitorScreen·SettingsDialog와 같은 기능):
// 맞춤 키워드 뉴스, 직접 검색(AND·OR·"문구"·-제외어), 언론 범위·기간 필터, 중복 정리, 즐겨찾기, 이전 기사 더 불러오기,
// 키워드 모니터링(최근 7일 기사 수·급증), 맞춤 키워드 설정.
import { setHeader } from "../app.js";
import { cached, dialog, errorText, forget, h, icon, iconButton, loading, message, openUrl, share, skeleton, toast } from "../core.js";
import * as N from "../lib/news.js";
import { cachedOffline } from "../lib/offline.js";
import { editNote, getNote, isRead, markRead } from "../lib/vault.js";

const SEOUL = "Asia/Seoul";
const dayFmt = new Intl.DateTimeFormat("ko-KR", { timeZone: SEOUL, month: "long", day: "numeric", weekday: "long" });
const fullFmt = new Intl.DateTimeFormat("ko-KR", { timeZone: SEOUL, month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
const seoulDay = (t) => new Date(t + 9 * 3600e3).toISOString().slice(0, 10);

function daySection(t) {
  if (!t) return "날짜 미상";
  const d = seoulDay(t);
  if (d === seoulDay(Date.now())) return "오늘";
  if (d === seoulDay(Date.now() - 864e5)) return "어제";
  return dayFmt.format(t);
}
export function relativeTime(t) {
  if (!t) return "시간 미상";
  const m = (Date.now() - t) / 60000;
  if (m < 1) return "방금";
  if (m < 60) return `${Math.floor(m)}분 전`;
  if (m < 1440) return `${Math.floor(m / 60)}시간 전`;
  return fullFmt.format(t);
}

/** 기사 한 줄(브리핑에서도 쓴다). */
export function articleRow(a, { compact = false } = {}) {
  const bm = () => {
    const on = N.isBookmarked(a.link);
    const b = iconButton(on ? "bookmark-check" : "bookmark", on ? "즐겨찾기 빼기" : "즐겨찾기", () => {
      const now = N.toggleBookmark(a);
      b.replaceWith(bm());
      toast(now ? "즐겨찾기에 담았습니다." : "즐겨찾기에서 뺐습니다.");
    }, on ? "on" : "");
    return b;
  };
  const open = () => {
    markRead(a.link);
    row.classList.add("read");
    openUrl(a.link);
  };
  const memo = () => iconButton("pencil", getNote(a.link) ? "메모 고치기" : "메모 쓰기(보관함에 담김)", async () => {
    if (!N.isBookmarked(a.link)) N.toggleBookmark(a);
    if (await editNote(a.link, a.title)) acts.replaceChildren(bm(), memo(), shareBtn);
  }, getNote(a.link) ? "on" : "");
  const shareBtn = iconButton("share-2", "제목과 링크 공유", () => share(a.title, a.link));
  const acts = h("div", { class: "acts" }, bm(), memo(), shareBtn);
  const row = h("div", { class: "item news-item" + (a.priority ? " prio" : "") + (isRead(a.link) ? " read" : "") },
    h("div", { class: "body", onclick: open, role: "link", tabindex: 0, onkeydown: (e) => e.key === "Enter" && open() },
      h("div", { class: "m src" }, h("span", { class: "tag" + (a.priority ? " orange" : " blue") }, String(a.source || "").slice(0, 12)), relativeTime(a.at)),
      h("div", { class: "t" }, a.title),
      compact ? null : h("div", { class: "sum" }, a.summary),
      a.matched?.length ? h("div", { class: "kw" }, a.matched.map((k) => "#" + k).join(" · ")) : null),
    acts);
  return row;
}

// 직접 검색 결과는 화면을 오가도(뒤로가기) 다시 읽지 않도록 잠시 기억한다.
const results = new Map();
addEventListener("fss-refresh", () => results.clear()); // 헤더의 새로고침 버튼
let filterOpen = false; // 필터를 펼친 채로 조건을 바꿔도 접히지 않게
const TTL = 3 * 60 * 1000;

export async function render(root, params, ctx) {
  if (params.view === "monitor") return renderMonitor(root, ctx);
  const s = N.settings();
  const q = (params.q || "").trim();
  const bmOnly = params.bm === "1";
  setHeader("뉴스검색", "금융 뉴스를 키워드로 찾아보기", [
    h("button", { class: "pill", type: "button", onclick: () => ctx.go({ view: "monitor" }) }, icon("activity"), h("span", { class: "lbl" }, "키워드 모니터링")),
    h("button", { class: "pill" + (bmOnly ? " on" : ""), type: "button", onclick: () => ctx.go(bmOnly ? { q } : { q, bm: "1" }) }, icon("bookmark"), h("span", { class: "lbl" }, "즐겨찾기")),
    h("button", { class: "pill", type: "button", onclick: () => openSettings(ctx) }, icon("sliders"), h("span", { class: "lbl" }, "설정")),
  ]);

  // 검색 조건 패널
  const input = h("input", { type: "search", placeholder: "키워드·회사명 검색", value: q, enterkeyhint: "search", "aria-label": "뉴스 검색어" });
  const runSearch = (text) => {
    text = text.trim();
    if (!text) return;
    try {
      N.parseQuery(text);
    } catch (e) {
      return toast(errorText(e));
    }
    input.blur();
    filterOpen = false;
    results.delete(key(text, s));
    ctx.go({ q: text });
  };
  const form = h("form", { class: "search", onsubmit: (e) => (e.preventDefault(), runSearch(input.value)) }, icon("search"), input, h("button", { class: "btn sm", type: "submit" }, "검색"));
  const chips = h("div", { class: "chip-row" }, s.keywords.map((k) => h("button", { class: "pill" + (k === q ? " on" : ""), type: "button", onclick: () => ((input.value = k), ctx.go({ q: k })) }, k)));
  const filterBody = h("div", { class: "news-filter", hidden: !filterOpen },
    h("div", { class: "section-label" }, "언론 범위·기간", h("div", { class: "acts" }, iconButton("help-circle", "30대 언론 목록과 선정 기준", outletsHelp))),
    h("div", { class: "chip-row" },
      N.SCOPES.map(([id, label]) => h("button", { class: "pill" + (s.scope === id ? " on" : ""), type: "button", onclick: () => (N.saveSettings({ scope: id }), ctx.go(params, { replace: true })) }, label)),
      h("span", { class: "sep" }),
      N.RANGES.map(([id, label]) => h("button", { class: "pill" + (s.range === id ? " on" : ""), type: "button", onclick: () => (N.saveSettings({ range: id }), ctx.go(params, { replace: true })) }, label))),
    h("p", { class: "note" }, "공백·AND: 모두 포함 / OR: 하나 이상 / \"문구\": 정확히 일치 / -단어: 그 단어가 들어간 기사 제외"),
    h("p", { class: "note" }, "제외 방법: 빼고 싶은 단어 앞에 ‘-’를 붙이고 단어와 띄우지 않습니다. 제목·요약에 그 단어가 있는 기사를 결과에서 뺍니다. 예) 금감원 -연예   /   금감원 AND (증권사 OR 운용사) -\"인사 발령\"   (문구는 따옴표로 묶기). 제외어만으로는 검색할 수 없습니다."));
  const filterToggle = h("button", { class: "link-more", type: "button", onclick: () => {
    filterOpen = filterBody.hidden;
    filterBody.hidden = !filterOpen;
    filterToggle.lastChild.replaceWith(icon(filterOpen ? "chevron-left" : "chevron-down"));
  } }, "필터", icon(filterOpen ? "chevron-left" : "chevron-down"));
  root.append(h("section", { class: "card news-panel" }, form, chips,
    h("p", { class: "note news-hint" }, "위 키워드는 자주 보는 검색어입니다. ",
      h("button", { class: "link-more", type: "button", onclick: () => openSettings(ctx) }, "설정"), "에서 원하는 키워드를 10개까지 등록할 수 있어요."),
    h("div", { class: "row-gap news-cond" }, h("span", { class: "note" }, `${N.scopeLabel(s.scope)} · ${N.rangeLabel(s.range)}`), filterToggle), filterBody));

  // 결과
  const head = h("div", { class: "news-head" });
  const list = h("div", { class: "card list news-list" });
  const more = h("div", { class: "news-more" });
  root.append(head, list, more);

  const drawHead = (title, page) => {
    const detail = h("p", { class: "note", hidden: true });
    if (page) {
      const parts = [`${page.note} · 가져온 ${page.fetched}건 중 ${page.articles.length}건 표시`];
      if (page.duplicates > 0) parts.push(`중복 ${page.duplicates}건 제외`);
      if (page.outletExcluded > 0) parts.push(`언론 범위 ${page.outletExcluded}건 제외`);
      if (page.failed > 0) parts.push(`일부 검색 ${page.failed}건 실패`);
      detail.textContent = parts.join(" · ");
    }
    const count = page ? `${page.articles.length}건 · ${page.note.split(" (")[0]}` : "";
    head.replaceChildren(
      h("div", { class: "nh-t", onclick: () => page && (detail.hidden = !detail.hidden), role: page ? "button" : null },
        h("h3", null, title), count ? h("small", null, count, " · 상세 ▾") : null),
      h("div", { class: "ch-acts" },
        iconButton("home", "맞춤 뉴스", () => ((input.value = ""), ctx.go({}))),
        iconButton("refresh-cw", "새로고침", () => {
          forget("news:");
          results.delete(key(q, s));
          ctx.go(params, { replace: true });
        })),
      detail);
  };

  const drawList = (articles) => {
    if (!articles.length) {
      list.replaceChildren(message(bmOnly ? "즐겨찾기한 기사가 없습니다.\n기사의 북마크 버튼을 눌러 저장해 보세요." : "조건에 맞는 기사를 찾지 못했습니다.\n검색 기간을 넓히거나 전체 언론으로 바꿔 보세요."));
      return;
    }
    const out = [];
    let prev = null;
    for (const a of articles) {
      const sec = daySection(a.at);
      if (sec !== prev) out.push(h("div", { class: "news-day" }, sec));
      prev = sec;
      out.push(articleRow(a));
    }
    list.replaceChildren(...out);
  };

  if (bmOnly) {
    const saved = N.bookmarks().map((b) => ({ ...b, matched: [] }));
    drawHead("즐겨찾기", null);
    head.querySelector("h3").after(h("small", null, `즐겨찾기 ${saved.length}건`));
    drawList(saved);
    return;
  }

  const title = q ? `‘${q}’ 검색 결과` : "맞춤 뉴스";
  drawHead(title, null);
  list.replaceChildren(skeleton(6));
  let state;
  try {
    const k = key(q, s);
    state = results.get(k);
    if (!state || Date.now() - state.at > TTL) {
      const page = await (q ? N.search(q, s) : loadHome(s));
      state = { at: Date.now(), page: { ...page, articles: page.articles.slice() } };
      results.set(k, state);
    }
  } catch (e) {
    if (!ctx.alive()) return;
    list.replaceChildren(message(friendly(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
    return;
  }
  if (!ctx.alive()) return;
  drawHead(title, state.page);
  drawList(state.page.articles);

  const drawMore = () => {
    more.replaceChildren();
    if (!q || !state.page.hasMore) return;
    const btn = h("button", { class: "btn ghost", type: "button", onclick: async () => {
      btn.disabled = true;
      btn.textContent = "불러오는 중…";
      try {
        const next = await N.search(q, s, state.page.nextStart);
        if (!ctx.alive()) return;
        const p = state.page;
        const seen = new Set();
        const merged = [...p.articles, ...next.articles]
          .filter((a) => {
            const k = N.normalizeTitle(a.title) || a.link;
            return !seen.has(k) && seen.add(k);
          })
          .sort((a, b) => b.at - a.at);
        const cross = p.articles.length + next.articles.length - merged.length;
        state.page = { ...p, articles: merged, hasMore: next.hasMore, nextStart: next.nextStart, fetched: p.fetched + next.fetched,
          duplicates: p.duplicates + next.duplicates + cross, outletExcluded: p.outletExcluded + next.outletExcluded, failed: p.failed + next.failed };
        drawHead(title, state.page);
        drawList(state.page.articles);
        drawMore();
      } catch (e) {
        toast(friendly(e));
        btn.disabled = false;
        btn.textContent = "이전 기사 더 불러오기";
      }
    } }, "이전 기사 더 불러오기");
    more.append(btn);
  };
  drawMore();
}

/** 맞춤 뉴스(브리핑의 최근 기사와 함께 쓴다). */
export function loadHome(s = N.settings()) {
  return cachedOffline("news:home:" + key("", s), TTL, () => N.home(s));
}

const key = (q, s) => [q, s.scope, s.range, s.keywords.join("|")].join("\u001f");

function friendly(e) {
  const m = errorText(e);
  if (/401|403/.test(m)) return "네이버 뉴스 서버 인증에 문제가 있습니다. 잠시 뒤 다시 시도해 주세요.";
  return m || "기사를 불러오지 못했습니다. 네트워크 연결을 확인해 주세요.";
}

function outletsHelp() {
  const sec = (t, list) => [h("h4", null, t), h("p", null, list.join(" · "))];
  dialog({ title: "30대 언론 선정 기준", wide: true, cancel: null, ok: "닫기", body: h("div", { class: "prose" },
    h("p", null, "공식 순위나 발행부수 상위 30곳을 뜻하지 않습니다. 국내 금융 뉴스를 확인하기 위해 통신·방송 11곳, 종합지 10곳, 경제·산업지 9곳을 고르게 포함한 고정 목록입니다."),
    sec("통신·방송 (11)", N.MAJOR30.slice(0, 11)), sec("종합지 (10)", N.MAJOR30.slice(11, 21)), sec("경제·산업지 (9)", N.MAJOR30.slice(21)),
    h("p", { class: "note" }, "기사의 출처명과 링크 도메인을 이 목록에 대조합니다. 표기 차이나 도메인 식별 때문에 제외될 수 있습니다. 지역지·전문지 등까지 보고 싶으면 ‘전체 언론’을 선택하세요. 검색 서비스에 수집된 결과 안에서 적용되는 필터이며, 모든 기사를 직접 수집하는 기능은 아닙니다.")) });
}

async function openSettings(ctx) {
  const s = N.settings();
  const fields = Array.from({ length: 10 }, (_, i) => ({ name: "k" + i, label: `키워드 ${i + 1}`, value: s.keywords[i] || "" }));
  const out = await dialog({ title: "맞춤 뉴스 키워드", text: "첫 화면에서 아래 키워드의 최신 기사를 한꺼번에 모읍니다. (최대 10개, 제외어 -단어 사용 가능)", fields, ok: "저장" });
  if (!out) return;
  const kws = Object.values(out).filter(Boolean);
  N.saveSettings({ keywords: kws.length ? kws : N.DEFAULT_KEYWORDS });
  forget("news:");
  results.clear();
  toast("키워드를 저장했습니다.");
  ctx.go({}, { replace: true });
}

// ---- 키워드 모니터링 ----
async function renderMonitor(root, ctx) {
  const s = N.settings();
  setHeader("키워드 모니터링", "맞춤 키워드의 최근 7일 기사 수", [
    iconButton("refresh-cw", "새로고침", () => (forget("news:trend"), ctx.go({ view: "monitor" }, { replace: true }))),
  ]);
  root.append(h("p", { class: "note", style: { margin: "0 2px 12px" } },
    `내 키워드 ${s.keywords.length}개의 최근 7일 기사 수입니다. 오늘 기사가 5건 이상이면서 지난 6일 평균의 2배 이상이면 ‘급증’으로 표시합니다. 키워드를 누르면 기사를 검색합니다. 새 기사 알림은 ‘알림’ 메뉴에서 키워드별로 설정합니다.`));
  const box = h("div", { class: "card list" }, loading());
  root.append(box);
  let list;
  try {
    list = await cached("news:trend:" + s.keywords.join("|"), 10 * 60 * 1000, () => N.trends(s));
  } catch (e) {
    if (ctx.alive()) box.replaceChildren(message("추이를 불러오지 못했습니다.", { error: true, action: "다시 시도", onAction: () => ctx.go({ view: "monitor" }, { replace: true }) }));
    return;
  }
  if (!ctx.alive()) return;
  const sorted = list.slice().sort((a, b) => b.surge - a.surge || b.today - a.today);
  box.replaceChildren(...sorted.map((t) => h("div", { class: "item trend-row", onclick: () => ctx.go({ q: t.keyword }, {}), role: "link", tabindex: 0 },
    h("div", { class: "body" },
      h("div", { class: "t" }, t.keyword, t.surge ? h("span", { class: "tag orange", style: { marginLeft: "6px" } }, "급증") : null),
      h("div", { class: "m" + (t.error ? " err" : "") }, t.error ? `불러오지 못함 (${t.error.slice(0, 40)})` : `오늘 ${t.today}건 · 6일 평균 ${t.avg.toFixed(1)}건 · 7일 ${t.total}건`)),
    spark(t.daily, t.surge))));
  if (list.some((t) => t.capped)) root.append(h("p", { class: "note", style: { margin: "10px 2px" } }, "※ 검색은 키워드당 최근 100건까지만 가져오므로, 기사가 아주 많은 키워드는 오래된 날의 수가 실제보다 적게 보일 수 있습니다."));
}

function spark(daily, surge) {
  const max = Math.max(1, ...daily);
  const n = daily.length;
  const W = 92, H = 36, bw = W / (2 * n - 1);
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("class", "spark");
  svg.setAttribute("aria-hidden", "true");
  daily.forEach((v, i) => {
    const hgt = Math.max(2, (H * v) / max);
    const r = document.createElementNS(ns, "rect");
    Object.entries({ x: i * 2 * bw, y: H - hgt, width: bw, height: hgt, rx: 2 }).forEach(([k, val]) => r.setAttribute(k, val));
    r.setAttribute("class", i === n - 1 ? (surge ? "last surge" : "last") : "");
    svg.append(r);
  });
  return svg;
}

/** 회사 상세의 「뉴스」 탭: 최근 3일 그 회사 기사(링크 포함). */
export async function companyPane(body, name) {
  body.replaceChildren(loading("최근 3일 뉴스를 찾는 중…"));
  try {
    const page = await N.companyNews(name);
    const list = h("div", { class: "list" });
    for (const a of page.articles) list.append(articleRow(a));
    body.replaceChildren(
      h("p", { class: "note" }, `「${N.companySearchName(name)}」${page.names?.length > 1 ? `(${page.names.slice(1, 4).join(", ")} 포함)` : ""} 최근 3일 기사 ${page.articles.length}건 · ${String(page.note).split(" (")[0]}`),
      page.articles.length ? list : message("최근 3일 안에 이 회사 이름이 나온 기사를 찾지 못했습니다."));
  } catch (e) {
    body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => companyPane(body, name) }));
  }
}
