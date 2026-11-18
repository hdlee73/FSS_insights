// 커뮤니티 핫이슈(금융관련) 수집기. GitHub Actions(community-data.yml)가 30분(업무시간)·2시간(야간·주말)마다 실행한다.
// 사용: node server/community-data/build_community.mjs <출력폴더> [이전 데이터 폴더]
//
// 원칙
//  - 사이트마다 robots.txt가 허용하는 공개 목록 페이지(인기·최신 각 1장)만 읽는다. 본문·이미지·닉네임·IP는 읽지도 저장하지도 않는다.
//  - 요청 사이 3초 이상 쉬고, 식별 가능한 User-Agent를 쓴다. 403·429·타임아웃이면 그 출처는 이번 회차를 건너뛰고 상태에 "blocked/error"로 적는다(우회 없음, 재시도 없음).
//  - 저장하는 것: 제목, 출처, 시간, 조회·댓글·추천 수, 원문 주소.
//  - 직전 스냅샷과 비교해 조회·댓글 증가량(급상승)을 구한다. 스냅샷은 72시간만 보관한다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(process.argv[2] || "out");
const PREV = process.argv[3] ? path.resolve(process.argv[3]) : null;
const UA = "FSSInsightsBot/1.0 (+https://www.fssinsights.com; public list titles and metrics only)";
const DELAY_MS = Number(process.env.COMMUNITY_DELAY_MS ?? 3000);
const SNAP_KEEP_MS = 72 * 3600 * 1000;
const KW = JSON.parse(fs.readFileSync(path.join(HERE, "keywords.json"), "utf8"));

// ---------- 출처 정의 ----------
// prior: 게시판 자체가 금융 주제라 키워드가 없어도 후보로 본다(점수가 높을 때만 노출).
const SOURCES = [
  { key: "dc-stock", name: "디시 주식", board: "주식 갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/board/lists/?id=stock_new2", prior: "증시" },
  { key: "dc-stockus", name: "디시 미국주식", board: "미국주식 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=stockus", prior: "증시" },
  { key: "dc-bitcoin", name: "디시 비트코인", board: "비트코인 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=bitcoin", prior: "가상자산" },
  { key: "clien-stock", name: "클리앙", board: "주식한당", kind: "clien", host: "https://www.clien.net", base: "/service/board/cm_stock", prior: "증시", latestOnly: true },
  { key: "clien-park", name: "클리앙", board: "모두의공원", kind: "clien", host: "https://www.clien.net", base: "/service/board/park", prior: null, latestOnly: true },
  { key: "bobae", name: "보배드림", board: "베스트·자유게시판", kind: "bobae", host: "https://www.bobaedream.co.kr", pages: [["/list?code=best", true], ["/list?code=freeb", false]], prior: null },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = new Date();
const KST = (d) => new Date(d.getTime() + 9 * 3600 * 1000);
const kstIso = (d) => KST(d).toISOString().replace("Z", "+09:00").replace(/\.\d+/, "");
const stamp = (d) => KST(d).toISOString().slice(0, 16).replace(/[-:]/g, "").replace("T", "-");

function decode(s) {
  return s
    .replace(/<[^>]+>/g, "")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ").trim();
}
const toNum = (s) => {
  if (s == null) return null;
  const t = String(s).replace(/,/g, "").trim();
  const m = t.match(/^([\d.]+)\s*([kKmM]?)$/);
  if (!m) return null;
  return Math.round(parseFloat(m[1]) * (m[2].toLowerCase() === "k" ? 1000 : m[2].toLowerCase() === "m" ? 1e6 : 1));
};

async function getHtml(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 20000);
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA, Accept: "text/html,application/xhtml+xml,*/*;q=0.8", "Accept-Language": "ko" }, signal: ctl.signal, redirect: "follow" });
    if (res.status === 403 || res.status === 429 || res.status === 430) return { status: "blocked", code: res.status };
    if (!res.ok) return { status: "error", code: res.status };
    return { status: "ok", html: await res.text() };
  } catch (e) {
    return { status: "error", code: 0, msg: e?.name === "AbortError" ? "timeout" : String(e?.message || e).slice(0, 80) };
  } finally {
    clearTimeout(timer);
  }
}

// ---------- 목록 파서(제목·지표만) ----------
function toIso(y, mo, d, h, mi) {
  return new Date(Date.UTC(y, mo - 1, d, h - 9, mi)).toISOString();
}
function parseKstText(s) {
  const m = s && s.match(/(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
  return m ? toIso(+m[1], +m[2], +m[3], +m[4], +m[5]) : null;
}
function parseDc(html, src, popular) {
  const out = [];
  for (const m of html.matchAll(/<tr class="ub-content us-post"[^>]*data-no="(\d+)"[\s\S]*?<\/tr>/g)) {
    const row = m[0];
    const no = m[1];
    const tit = row.match(/<td class="gall_tit[^"]*"[^>]*>([\s\S]*?)<\/td>/);
    if (!tit) continue;
    const a = tit[1].match(/<a[^>]*href="[^"]*"[^>]*>([\s\S]*?)<\/a>/);
    if (!a) continue;
    const title = decode(a[1]);
    const reply = tit[1].match(/class="reply_num">\[(\d+)/);
    const date = row.match(/<td class="gall_date"[^>]*title="([^"]+)"/);
    const count = row.match(/<td class="gall_count">([^<]*)</);
    const rec = row.match(/<td class="gall_recommend">([^<]*)</);
    const id = new URL(src.host + src.base).searchParams.get("id");
    const mg = src.base.startsWith("/mgallery") ? "/mgallery" : "";
    out.push({
      id: `dc:${id}:${no}`, title,
      url: `${src.host}${mg}/board/view/?id=${id}&no=${no}`,
      ts: parseKstText(date?.[1]),
      views: toNum(count?.[1]), comments: reply ? +reply[1] : 0, likes: toNum(rec?.[1]) ?? 0,
      pop: popular,
    });
  }
  return out;
}
function parseClien(html, src) {
  const out = [];
  for (const m of html.matchAll(/<div class="list_item symph_row[^"]*"[^>]*data-board-sn=(\d+)[^>]*data-comment-count=(\d+)[^>]*>([\s\S]*?)(?=<div class="list_item|$)/g)) {
    const sn = m[1];
    const body = m[3];
    const t = body.match(/class="subject_fixed"[^>]*title="([^"]*)"/) || body.match(/<span class="subject_fixed"[^>]*>([\s\S]*?)<\/span>/);
    const title = decode(t?.[1] ?? "");
    if (!title) continue;
    const hit = body.match(/<span class="hit">([^<]*)</);
    const like = body.match(/data-role="list-like-count"><span>([^<]*)</);
    const time = body.match(/class="timestamp">([^<]+)</);
    out.push({
      id: `clien:${src.base.split("/").pop()}:${sn}`, title,
      url: `${src.host}${src.base}/${sn}`,
      ts: parseKstText(time?.[1]),
      views: toNum(hit?.[1]), comments: +m[2] || 0, likes: toNum(like?.[1]) ?? 0,
      pop: false,
    });
  }
  return out;
}
function parseBobae(html, src, popular, code) {
  const out = [];
  for (const m of html.matchAll(/<tr itemscope itemtype="http:\/\/schema.org\/Article">([\s\S]*?)<\/tr>/g)) {
    const row = m[1];
    const a = row.match(/<a[^>]*class="bsubject"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/);
    if (!a) continue;
    const no = a[1].match(/[?&]No=(\d+)/i)?.[1];
    if (!no) continue;
    const cm = row.match(/class="totreply">(\d+)</);
    const date = row.match(/<td class="date"[^>]*>([^<]*)</)?.[1]?.trim() ?? "";
    const rec = row.match(/<td class="recomm">[\s\S]*?<font[^>]*>(\d+)/);
    const cnt = row.match(/<td class="count"[^>]*>([^<]*)</);
    let ts = null;
    const hm = date.match(/^(\d{2}):(\d{2})$/);
    const kd = KST(now);
    if (hm) ts = toIso(kd.getUTCFullYear(), kd.getUTCMonth() + 1, kd.getUTCDate(), +hm[1], +hm[2]);
    const md = date.match(/^(\d{2})\/(\d{2})$/);
    if (md) ts = toIso(kd.getUTCFullYear(), +md[1], +md[2], 12, 0);
    out.push({
      id: `bobae:${code}:${no}`, title: decode(a[2]),
      url: `${src.host}/view?code=${code}&No=${no}`,
      ts, views: toNum(cnt?.[1]), comments: cm ? +cm[1] : 0, likes: rec ? +rec[1] : 0, pop: popular,
    });
  }
  return out;
}

async function collect() {
  const items = new Map();
  const status = [];
  for (const src of SOURCES) {
    const st = { key: src.key, name: src.name, board: src.board, ok: false, count: 0, checkedAt: now.toISOString() };
    let first = true;
    const pages = src.kind === "bobae" ? src.pages.map(([p, pop]) => ({ url: src.host + p, pop, code: new URL(src.host + p).searchParams.get("code") }))
      : src.latestOnly ? [{ url: src.host + src.base, pop: false }]
      : [{ url: src.host + src.base + "&exception_mode=recommend", pop: true }, { url: src.host + src.base, pop: false }];
    for (const pg of pages) {
      if (!first) await sleep(DELAY_MS);
      first = false;
      const r = await getHtml(pg.url);
      if (r.status !== "ok") {
        st.error = r.status === "blocked" ? `blocked(${r.code})` : `error(${r.code}${r.msg ? " " + r.msg : ""})`;
        if (r.status === "blocked") { st.blocked = true; break; } // 막히면 이 출처의 나머지 요청도 하지 않는다
        continue;
      }
      const rows = src.kind === "dc" ? parseDc(r.html, src, pg.pop) : src.kind === "clien" ? parseClien(r.html, src) : parseBobae(r.html, src, pg.pop, pg.code);
      if (!rows.length) { st.error = st.error || "parse-empty"; continue; }
      for (const it of rows) {
        const old = items.get(it.id);
        if (old) { old.pop = old.pop || it.pop; continue; }
        items.set(it.id, { ...it, src: src.key, source: src.name, board: src.board, prior: src.prior });
      }
      st.count += rows.length;
      st.ok = true;
    }
    if (st.ok && st.error && !st.blocked) delete st.error; // 일부 페이지만 실패한 경우는 정상으로 본다
    status.push(st);
    await sleep(DELAY_MS);
  }
  return { items: [...items.values()], status };
}

// ---------- 키워드 판정 ----------
const norm = (s) => s.toLowerCase().replace(/\s+/g, " ");
const splitTerms = (e) => e.split("|").map((x) => norm(x.trim())).filter(Boolean);
const compiled = {
  cats: Object.entries(KW.categories).map(([cat, v]) => ({ cat, strong: v.strong.map((e) => ({ label: e.split("|")[0].trim(), terms: splitTerms(e) })), weak: v.weak.map((e) => splitTerms(e)) })),
  sup: KW.supervision.terms.map(splitTerms),
  exclude: KW.exclude.map((x) => norm(x)),
  profanity: (KW.profanity || []).map((x) => norm(x)),
};
const has = (text, terms) => terms.some((t) => text.includes(t));
function classify(item) {
  const text = norm(item.title);
  if (text.length < 5) return null;
  if (compiled.exclude.some((x) => text.includes(x)) || compiled.profanity.some((x) => text.includes(x))) return null;
  if (/\d{6}[- ]?[1-4]\d{6}|01[016789][- ]?\d{3,4}[- ]?\d{4}/.test(item.title)) return null; // 개인정보 형태가 보이는 제목은 버린다
  let best = null;
  const labels = [];
  for (const c of compiled.cats) {
    const sm = c.strong.filter((s) => has(text, s.terms));
    const wm = c.weak.filter((w) => has(text, w));
    const score = sm.length * 2 + wm.length;
    if ((sm.length >= 1 || wm.length >= 2) && (!best || score > best.score)) best = { cat: c.cat, score };
    sm.forEach((s) => labels.push(s.label));
  }
  const sup = compiled.sup.some((t) => has(text, t));
  if (!best && item.prior) best = { cat: item.prior, score: 0, priorOnly: true };
  if (!best) return null;
  return { cat: best.cat, priorOnly: !!best.priorOnly, sup, labels: [...new Set(labels)] };
}

// ---------- 스냅샷 ----------
function loadSnaps() {
  const snaps = [];
  if (!PREV) return snaps;
  const dir = path.join(PREV, "snap");
  if (!fs.existsSync(dir)) return snaps;
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith(".json")) continue;
    try {
      const j = JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
      if (j?.at && now - new Date(j.at) <= SNAP_KEEP_MS) snaps.push({ file: f, ...j });
    } catch { /* 깨진 파일은 무시 */ }
  }
  return snaps.sort((a, b) => new Date(a.at) - new Date(b.at));
}
const median = (arr) => {
  const a = arr.filter((x) => x > 0).sort((x, y) => x - y);
  return a.length ? a[Math.floor(a.length / 2)] : 1;
};


// ---------- 네이버(검색 API: 블로그·카페글) — 순위가 아니라 '언급 신호' ----------
// 응답에 조회·댓글 수가 없다. 관찰어별로 최신 블로그 글 100건 중 오늘 올라온 글 수(포화되면 100+)와 최신 카페글 제목을 보여 준다.
// 블로거 이름·본문 요약(description)은 저장하지 않는다. 호출: 관찰어당 2건(블로그 1, 카페 1).
const NV_ID = process.env.NAVER_CLIENT_ID, NV_SECRET = process.env.NAVER_CLIENT_SECRET;
async function naverGet(kind, query, display) {
  const url = `https://openapi.naver.com/v1/search/${kind}.json?query=${encodeURIComponent(query)}&display=${display}&sort=date`;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15000);
  try {
    const res = await fetch(url, { headers: { "X-Naver-Client-Id": NV_ID, "X-Naver-Client-Secret": NV_SECRET, "User-Agent": UA }, signal: ctl.signal });
    if (res.status === 429) return { err: "rate-limit(429)" };
    if (res.status === 401 || res.status === 403) return { err: `auth(${res.status})` };
    if (!res.ok) return { err: `error(${res.status})` };
    return { json: await res.json() };
  } catch (e) {
    return { err: e?.name === "AbortError" ? "timeout" : "error" };
  } finally { clearTimeout(timer); }
}
async function collectNaver(snaps) {
  const st = { key: "naver", name: "네이버", board: "블로그·카페 언급량(검색 API)", ok: false, count: 0, checkedAt: now.toISOString(), signal: true };
  if (!NV_ID || !NV_SECRET) { st.pending = true; st.error = "키 없음"; return { st, terms: [] }; }
  const kd = KST(now);
  const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, "");
  const today = ymd(kd), yday = ymd(new Date(kd - 86400000));
  const terms = [];
  const baseSnaps = snaps.filter((x) => now - new Date(x.at) <= 24 * 3600 * 1000 && (x.nv || x.nvTotal));
  for (const word of KW.naver?.watch || []) {
    const b = await naverGet("blog", word, 100);
    await sleep(200);
    const c = await naverGet("cafearticle", word, 10);
    await sleep(200);
    if (b.err && c.err) { st.error = b.err; if (/auth|rate/.test(b.err)) break; continue; }
    const t = { k: word };
    if (b.json) {
      const arr = b.json.items || [];
      t.today = arr.filter((x) => x.postdate === today).length;
      t.yday = arr.filter((x) => x.postdate === yday).length;
      t.sat = t.today >= 100;
      t.total = b.json.total ?? null;
      t.blog = arr.filter((x) => x.postdate === today).slice(0, 3).map((x) => ({ t: decode(x.title), u: x.link }));
    }
    if (c.json) t.cafe = (c.json.items || []).slice(0, 4).map((x) => ({ t: decode(x.title), u: x.link, c: decode(x.cafename || "") }));
    // 인기 검색어는 최근 100건이 모두 오늘 글이라 '오늘 글 수'만으로는 구분이 안 된다. 그래서 검색 결과 총건수(total)의 회차 간 증가량을 시간당 건수로 환산해 함께 본다.
    const prevS = snaps.length ? snaps[snaps.length - 1] : null;
    const pt = prevS?.nvTotal?.[word];
    if (t.total != null && Number.isFinite(pt)) {
      const dtH = Math.max(0.25, (now - new Date(prevS.at)) / 3600000);
      t.rate = Math.round(Math.max(0, t.total - pt) / dtH);
    }
    const rates = [];
    for (let i = 1; i < baseSnaps.length; i++) {
      const a = baseSnaps[i - 1].nvTotal?.[word], b = baseSnaps[i].nvTotal?.[word];
      if (Number.isFinite(a) && Number.isFinite(b)) rates.push(Math.max(0, b - a) / Math.max(0.25, (new Date(baseSnaps[i].at) - new Date(baseSnaps[i - 1].at)) / 3600000));
    }
    const avgRate = rates.length ? rates.reduce((x, y) => x + y, 0) / rates.length : null;
    const hist = baseSnaps.map((x) => x.nv?.[word]).filter((n) => Number.isFinite(n));
    const avg = hist.length ? hist.reduce((a, n) => a + n, 0) / hist.length : null;
    t.trend = 0;
    if (t.rate != null && avgRate != null) t.trend = t.rate >= avgRate * 2 + 10 ? 2 : t.rate > avgRate * 1.3 + 3 ? 1 : 0;
    else if (!t.sat && avg != null && t.today != null) t.trend = t.today >= avg * 2 + 5 ? 2 : t.today > avg * 1.3 + 2 ? 1 : 0;
    terms.push(t);
    st.count += (b.json?.items?.length || 0);
    st.ok = true;
  }
  if (st.ok && st.error && !/auth|rate/.test(st.error)) delete st.error;
  terms.sort((a, b) => b.trend - a.trend || (b.rate ?? b.today ?? 0) - (a.rate ?? a.today ?? 0));
  return { st, terms };
}

// ---------- 유튜브(Data API v3) ----------
// 검색 1건=100쿼터(하루 1만). 그래서 검색어 3개를 최소 50분 간격으로만 호출한다. 키(YOUTUBE_API_KEY)가 없으면 '준비 중'으로 조용히 건너뛴다.
const YT_KEY = process.env.YOUTUBE_API_KEY;
async function ytGet(endpoint, params) {
  const url = `https://www.googleapis.com/youtube/v3/${endpoint}?${new URLSearchParams({ ...params, key: YT_KEY })}`;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15000);
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA }, signal: ctl.signal });
    if (!res.ok) {
      let reason = "";
      try { reason = (await res.json())?.error?.errors?.[0]?.reason || ""; } catch { /* 본문 없음 */ }
      return { err: reason === "quotaExceeded" ? "quota" : `error(${res.status}${reason ? " " + reason : ""})` };
    }
    return { json: await res.json() };
  } catch (e) {
    return { err: e?.name === "AbortError" ? "timeout" : "error" };
  } finally { clearTimeout(timer); }
}
async function collectYoutube() {
  const cfg = KW.youtube || {};
  const st = { key: "youtube", name: "유튜브", board: "영상(조회·댓글 수 포함)", ok: false, count: 0, checkedAt: now.toISOString() };
  if (!YT_KEY) { st.pending = true; st.error = "키 없음"; return { st, items: [] }; }
  // 직전에 호출했다면 쿼터 절약을 위해 이전 결과를 그대로 쓴다.
  let prevYt = null;
  try { prevYt = PREV ? JSON.parse(fs.readFileSync(path.join(PREV, "hot.json"), "utf8")).youtube : null; } catch { /* 없음 */ }
  if (prevYt?.checkedAt && now - new Date(prevYt.checkedAt) < (cfg.minIntervalMin ?? 50) * 60000 && prevYt.items?.length) {
    st.ok = true; st.count = prevYt.items.length; st.checkedAt = prevYt.checkedAt; st.cached = true;
    return { st, items: prevYt.items };
  }
  const after = new Date(now - (cfg.hours ?? 48) * 3600000).toISOString();
  const found = new Map();
  for (const q of cfg.queries || []) {
    const r = await ytGet("search", { part: "snippet", q, type: "video", order: "viewCount", publishedAfter: after, regionCode: "KR", relevanceLanguage: "ko", maxResults: String(cfg.maxPerQuery ?? 15), safeSearch: "moderate" });
    if (r.err) { st.error = r.err; if (r.err === "quota") break; continue; }
    for (const it of r.json.items || []) {
      const id = it.id?.videoId;
      if (id && !found.has(id)) found.set(id, { id, title: decode(it.snippet?.title || ""), ch: decode(it.snippet?.channelTitle || ""), ts: it.snippet?.publishedAt || null });
    }
    await sleep(300);
  }
  if (!found.size) { if (prevYt?.items?.length) { st.ok = true; st.stale = true; st.count = prevYt.items.length; return { st, items: prevYt.items }; } return { st, items: [] }; }
  const ids = [...found.keys()].slice(0, 50);
  const v = await ytGet("videos", { part: "statistics", id: ids.join(",") });
  if (v.err) { st.error = v.err; }
  const out = [];
  for (const it of v.json?.items || []) {
    const f = found.get(it.id);
    if (!f) continue;
    const s = it.statistics || {};
    const text = { title: f.title, prior: null };
    const cl = classify(text);
    if (!cl) continue; // 금융 키워드가 맞는 영상만
    const ageH = f.ts ? Math.max(1, (now - new Date(f.ts)) / 3600000) : 24;
    const views = Number(s.viewCount ?? 0);
    out.push({ id: `yt:${f.id}`, t: f.title, u: `https://www.youtube.com/watch?v=${f.id}`, s: "유튜브", sk: "youtube", b: f.ch, ts: f.ts, v: views, c: Number(s.commentCount ?? 0), l: Number(s.likeCount ?? 0), cat: cl.cat, sup: cl.sup || undefined, vph: Math.round(views / Math.pow(ageH, 0.6)) });
  }
  const mx = Math.max(1, ...out.map((x) => Math.log1p(x.vph)));
  for (const x of out) x.score = Math.round((100 * Math.log1p(x.vph)) / mx);
  out.sort((a, b) => b.score - a.score);
  st.ok = out.length > 0 || !st.error;
  st.count = out.length;
  if (st.ok && st.error && st.error !== "quota") delete st.error;
  return { st, items: out.slice(0, 30) };
}

async function run() {
  fs.mkdirSync(path.join(OUT, "snap"), { recursive: true });
  const snaps = loadSnaps();
  const { items: raw, status } = await collect();
  const nv = await collectNaver(snaps);
  const yt = await collectYoutube();
  status.push(nv.st, yt.st);
  const okCount = status.filter((s) => s.ok).length;

  // 보드별 중앙값(정규화 기준)
  const bySrc = {};
  for (const it of raw) (bySrc[it.src] ||= []).push(it);
  const med = Object.fromEntries(Object.entries(bySrc).map(([k, v]) => [k, { v: median(v.map((x) => x.views ?? 0)), c: median(v.map((x) => x.comments)), l: median(v.map((x) => x.likes)) }]));

  const prev = snaps.length ? snaps[snaps.length - 1] : null; // 직전 회차
  const prevMap = prev?.items || {};
  const prevIds = new Set(Object.keys(prevMap));
  const kwNew = {}; // 이번 회차에 처음 본 글의 키워드(급상승 키워드 집계용)

  const cand = [];
  for (const it of raw) {
    const cl = classify(it);
    if (!cl) continue;
    const m = med[it.src];
    const ageH = it.ts ? Math.max(0, (now - new Date(it.ts)) / 3600000) : 12;
    const v = it.views ?? 0;
    let dv = null, dc = null;
    const p = prevMap[it.id];
    if (p && prev) {
      const dtH = Math.max(0.25, (now - new Date(prev.at)) / 3600000);
      dv = Math.max(0, v - p[0]);
      dc = Math.max(0, it.comments - p[1]);
      it.dvh = dv / dtH;
    }
    let raw_ = 0.35 * Math.log(1 + v / m.v) + 0.3 * Math.log(1 + it.comments / m.c) + 0.15 * Math.log(1 + it.likes / m.l);
    if (it.dvh != null) raw_ += 0.5 * Math.log(1 + it.dvh / m.v) + 0.2 * Math.log(1 + (dc ?? 0) / Math.max(1, m.c) * 2);
    if (it.pop) raw_ += 0.3;
    raw_ *= Math.pow(0.5, ageH / 8);
    if (cl.sup) raw_ += KW.supervision.bonus * Math.pow(0.5, ageH / 24);
    const score = Math.round(100 * raw_ / (raw_ + 1.2));
    cand.push({ ...it, ...cl, score, dv, dc, ageH });
    if (!prevIds.has(it.id)) for (const l of cl.labels) kwNew[l] = (kwNew[l] || 0) + 1;
  }

  // 후보 선별: 키워드로 금융 관련이 확인됐거나, 금융 게시판 글이면서 점수가 높은 것만
  let list = cand.filter((c) => !c.priorOnly || c.pop || c.score >= 30);
  if (process.env.COMMUNITY_DEBUG) for (const c of cand.sort((a, b) => b.score - a.score).slice(0, 60)) console.log(c.score, c.src, c.priorOnly ? "P" : "K", c.pop ? "pop" : "", c.views, c.comments, c.title.slice(0, 40));
  list = list.filter((c) => c.ageH <= 48 && (c.score >= 12 || !c.priorOnly)); // 키워드가 맞은 글은 점수가 낮아도 남긴다(보배드림처럼 일반 게시판이 비지 않게)

  // 중복 묶기(제목 정규화 + 바이그램 유사도)
  const key = (t) => t.toLowerCase().replace(/[^0-9a-z가-힣]/g, "");
  const grams = (t) => { const k = key(t), s = new Set(); for (let i = 0; i < k.length - 1; i++) s.add(k.slice(i, i + 2)); return s; };
  list.sort((a, b) => b.score - a.score);
  const kept = [];
  for (const c of list) {
    const g = grams(c.title);
    const dup = kept.find((k) => {
      if (k.src === c.src && k.id === c.id) return true;
      const g2 = k._g; let inter = 0; for (const x of g) if (g2.has(x)) inter++;
      const uni = g.size + g2.size - inter;
      return uni > 0 && inter / uni >= 0.7;
    });
    if (dup) { dup.also = [...new Set([...(dup.also || []), c.source])].filter((n) => n !== dup.source); continue; }
    c._g = g; kept.push(c);
  }

  const top = kept.slice(0, 100).map((c) => ({
    id: c.id, t: c.title, u: c.url, s: c.source, sk: c.src, b: c.board, ts: c.ts,
    v: c.views, c: c.comments, l: c.likes, score: c.score, cat: c.cat, sup: c.sup || undefined,
    dv: c.dv ?? undefined, dc: c.dc ?? undefined, pop: c.pop || undefined, also: c.also?.length ? c.also : undefined,
  }));

  // 급상승 키워드: 최근 3시간 새 글 언급 수 vs 직전 3시간
  const histNew = snaps.map((s) => ({ at: new Date(s.at), kw: s.kwNew || {} }));
  histNew.push({ at: now, kw: kwNew });
  const sum = (from, to) => {
    const r = {};
    for (const h of histNew) if (h.at > new Date(now - to * 3600000) && h.at <= new Date(now - from * 3600000 + 1000)) for (const [k, n] of Object.entries(h.kw)) r[k] = (r[k] || 0) + n;
    return r;
  };
  const recent = sum(0, 3), before = sum(3, 6);
  const haveHist = snaps.length >= 2;
  const cur = {};
  for (const c of kept) for (const l of c.labels) cur[l] = (cur[l] || 0) + 1;
  const keywords = Object.entries(cur).map(([k, n]) => {
    const r = recent[k] || 0, b = before[k] || 0;
    const trend = !haveHist ? 0 : r >= 3 && r >= b * 2 + 2 ? 2 : r > b ? 1 : r < b ? -1 : 0;
    return { k, n, d: r, trend };
  }).sort((a, b) => b.trend - a.trend || b.d - a.d || b.n - a.n).slice(0, 12);

  // 검색용 보관본: 최근 72시간 안에 본 금융 관련 글의 제목·지표(본문 없음). 이전 보관본과 합쳐 점수가 낮아 목록에서 빠진 글도 검색되게 한다.
  const arch = new Map();
  try {
    const old = PREV ? JSON.parse(fs.readFileSync(path.join(PREV, "archive.json"), "utf8")).items : [];
    for (const x of old) if (now - new Date(x.seen) <= SNAP_KEEP_MS) arch.set(x.id, x);
  } catch { /* 없음 */ }
  const addArch = (x) => arch.set(x.id, { id: x.id, t: x.t, u: x.u, s: x.s, sk: x.sk, b: x.b, ts: x.ts, v: x.v, c: x.c, l: x.l, score: x.score, cat: x.cat, sup: x.sup || undefined, seen: now.toISOString() });
  for (const c of list) addArch({ id: c.id, t: c.title, u: c.url, s: c.source, sk: c.src, b: c.board, ts: c.ts, v: c.views, c: c.comments, l: c.likes, score: c.score, cat: c.cat, sup: c.sup });
  for (const y of yt.items) addArch(y);
  const archive = [...arch.values()].sort((a, b) => b.score - a.score).slice(0, 800);

  const hot = {
    v: 1, updated: now.toISOString(), updatedKst: kstIso(now), warm: haveHist,
    note: "공개 게시글의 제목·출처·시간·조회·댓글 수와 원문 링크만 담습니다. 본문·닉네임은 수집하지 않습니다.",
    sources: status,
    stats: { collected: raw.length, finance: kept.length, supervision: kept.filter((c) => c.sup).length, sourcesOk: okCount, sourcesTotal: status.filter((x) => !x.pending).length, sourcesPending: status.filter((x) => x.pending).length },
    items: top, keywords,
    naver: { checkedAt: nv.st.checkedAt, terms: nv.terms },
    youtube: { checkedAt: yt.st.checkedAt, items: yt.items },
  };

  // 모든 출처가 실패하면 이전 hot.json을 그대로 두고(화면이 비지 않게) 상태만 갱신한다
  if (okCount === 0 && PREV && fs.existsSync(path.join(PREV, "hot.json"))) {
    const old = JSON.parse(fs.readFileSync(path.join(PREV, "hot.json"), "utf8"));
    old.sources = status; old.stale = true; old.checkedAt = now.toISOString();
    fs.writeFileSync(path.join(OUT, "hot.json"), JSON.stringify(old));
    if (fs.existsSync(path.join(PREV, "archive.json"))) fs.copyFileSync(path.join(PREV, "archive.json"), path.join(OUT, "archive.json"));
    copySnaps(snaps);
    console.log("모든 출처 실패 — 이전 데이터를 유지합니다.", JSON.stringify(status));
    return;
  }
  fs.writeFileSync(path.join(OUT, "hot.json"), JSON.stringify(hot));
  fs.writeFileSync(path.join(OUT, "archive.json"), JSON.stringify({ v: 1, updated: now.toISOString(), items: archive }));

  // 스냅샷 저장: 후보로 본 글의 [조회, 댓글, 추천]만(제목 없음). 72시간 지난 스냅샷은 버린다.
  const items = {};
  for (const it of raw) items[it.id] = [it.views ?? 0, it.comments, it.likes];
  const nvSnap = Object.fromEntries(nv.terms.filter((t) => t.today != null).map((t) => [t.k, t.today]));
  fs.writeFileSync(path.join(OUT, "snap", `${stamp(now)}.json`), JSON.stringify({ at: now.toISOString(), items, kwNew, nv: nvSnap, nvTotal: Object.fromEntries(nv.terms.filter((t) => t.total != null).map((t) => [t.k, t.total])) }));
  copySnaps(snaps);
  console.log(`수집 ${raw.length}건 → 금융 관련 ${kept.length}건(감독 ${hot.stats.supervision}), 출처 ${okCount}/${status.length} 정상, 스냅샷 ${snaps.length + 1}개`);
  for (const s of status) console.log(" -", s.key, s.ok ? "ok" : "FAIL", s.count, s.error || "");
}
function copySnaps(snaps) {
  for (const s of snaps) fs.writeFileSync(path.join(OUT, "snap", s.file), JSON.stringify({ at: s.at, items: s.items, kwNew: s.kwNew, nv: s.nv, nvTotal: s.nvTotal }));
}

run().catch((e) => { console.error(e); process.exit(1); });
