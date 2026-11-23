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
const CO = JSON.parse(fs.readFileSync(path.join(HERE, "companies.json"), "utf8"));

// ---------- 출처 정의 ----------
// prior: 게시판 자체가 금융 주제라 키워드가 없어도 후보로 본다(점수가 높을 때만 노출).
const SOURCES = [
  { key: "dc-stock", name: "디시 주식", board: "주식 갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/board/lists/?id=stock_new2", prior: "증시" },
  { key: "dc-stockus", name: "디시 미국주식", board: "미국주식 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=stockus", prior: "증시" },
  { key: "dc-bitcoin", name: "디시 비트코인", board: "비트코인 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=bitcoin", prior: "가상자산" },
  { key: "dc-tenbagger", name: "디시 텐배거", board: "텐배거 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=tenbagger", prior: "증시", w: 0.9 },
  { key: "dc-kospi", name: "디시 코스피", board: "코스피 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=kospi", prior: "증시", w: 0.9 },
  { key: "dc-etf", name: "디시 ETF", board: "ETF 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=etf", prior: "증시", w: 0.9 },
  { key: "dc-dollar", name: "디시 달러", board: "달러 마이너갤러리", kind: "dc", host: "https://gall.dcinside.com", base: "/mgallery/board/lists/?id=dollar", prior: "환율", w: 0.9 },
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
        items.set(it.id, { ...it, src: src.key, source: src.name, board: src.board, prior: src.prior, w: src.w ?? 1 });
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
  cos: Object.entries(CO.groups).flatMap(([g, arr]) => arr.map((e) => ({ label: e.split("|")[0].trim(), group: g, terms: splitTerms(e) }))),
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
  // 금융회사명 대조(FISIS·DART 등록 명칭 사전): 맞으면 분야 후보가 되고 '감독·금융사 언급'으로 본다
  const cos = compiled.cos.filter((c) => has(text, c.terms));
  cos.forEach((c) => labels.push(c.label));
  if (!best && cos.length) best = { cat: "금융사", score: 2 };
  const sup = cos.length > 0 || compiled.sup.some((t) => has(text, t));
  if (!best && item.prior) best = { cat: item.prior, score: 0, priorOnly: true };
  if (!best) return null;
  return { cat: best.cat, priorOnly: !!best.priorOnly, sup, labels: [...new Set(labels)], cos: cos.map((c) => c.label) };
}

// ---------- 제목 토큰(키워드 자동 추출·이슈 묶기) ----------
// 형태소 분석기 없이 한글·영문 단어에서 끝 조사만 떼어 쓴다(완벽하지 않으므로 여러 글에 반복될 때만 의미를 둔다).
const JOSA = ["에서", "에게", "으로", "까지", "부터", "이랑", "하고", "처럼", "보다", "한테", "이다", "은", "는", "이", "가", "을", "를", "의", "에", "도", "로", "와", "과", "만", "고"];
const STOP = new Set(("오늘 어제 내일 지금 진짜 그냥 이거 저거 근데 그리고 하지만 때문 정도 이번 다음 아직 이미 벌써 계속 완전 대박 ㅋㅋ ㅎㅎ 개추 추천 질문 궁금 사람 생각 이유 결과 상황 문제 가능 오히려 처음 마지막 다들 우리 너무 정말 같은 이런 저런 그런 어떤 하는 하면 해서 있는 없는 한다 된다 합니다 입니다 보면 보니 인가 인데 이네 하나 하루 이후 이전 요즘 갑자기 무조건 그래서 혹시 ㄹㅇ ㅇㅇ 갤러리 개념글 후기 정리 공유 오전 오후 주식 코인 종목").split(" "));
function tokens(title) {
  const out = new Set();
  for (const w of norm(title).match(/[가-힣]{2,}|[a-z][a-z0-9&]{1,}|\d+[가-힣]{1,3}/g) || []) {
    let t = w;
    if (/[가-힣]$/.test(t)) for (const j of JOSA) if (t.length - j.length >= 2 && t.endsWith(j)) { t = t.slice(0, -j.length); break; }
    if (t.length < 2 || STOP.has(t) || /^\d+$/.test(t)) continue;
    out.add(t);
  }
  return out;
}
const knownTerm = (t) => compiled.cats.some((c) => c.strong.some((s) => s.terms.includes(t)) || c.weak.some((w) => w.includes(t))) || compiled.cos.some((c) => c.terms.includes(t)) || compiled.sup.some((x) => x.includes(t));

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


// ---------- 키워드 기준선(4주) ----------
// 키워드별 '새 글 언급 수'를 KST 1시간 단위로 28일 보관(kwhist.json)해, 같은 시간대 최근 2주 평균 대비 얼마나 튀었는지(z값)로 급상승을 판정한다.
// 커뮤니티 활동은 시간대 편차가 커서 '지난 며칠의 같은 6시간'과 비교한다. 같은 파일에 글별 첫 관측 시각(first)도 72시간 보관한다.
const HIST_KEEP_MS = 28 * 24 * 3600 * 1000;
const hourKey = (d) => KST(d).toISOString().slice(0, 13).replace(/[-T]/g, "");
function loadHist() {
  try { if (PREV) { const j = JSON.parse(fs.readFileSync(path.join(PREV, "kwhist.json"), "utf8")); if (j?.v === 1) return { buckets: j.buckets || {}, first: j.first || {} }; } } catch { /* 없음 */ }
  return { buckets: {}, first: {} };
}
function windowSum(buckets, endMs, hours, k) {
  let sum = 0, seen = false;
  for (let i = 0; i < hours; i++) {
    const b = buckets[hourKey(new Date(endMs - i * 3600000))];
    if (b) { seen = true; sum += b[k] || 0; }
  }
  return { sum, seen };
}
// 반환: { z, recent, mean, days } 기준선 일수가 3일 미만이면 null
function kwZ(buckets, k) {
  const W = 6;
  const recent = windowSum(buckets, now.getTime(), W, k).sum;
  const vals = [];
  for (let d = 1; d <= 14; d++) {
    const w = windowSum(buckets, now.getTime() - d * 86400000, W, k);
    if (w.seen) vals.push(w.sum);
  }
  if (vals.length < 3) return null;
  const mean = vals.reduce((a, x) => a + x, 0) / vals.length;
  const sd = Math.sqrt(vals.reduce((a, x) => a + (x - mean) ** 2, 0) / vals.length);
  const z = (recent - mean) / Math.max(sd, Math.sqrt(mean + 1), 1);
  return { z: Math.round(z * 10) / 10, recent, mean: Math.round(mean * 10) / 10, days: vals.length };
}

// ---------- 네이버(검색 API: 블로그·카페글) — 순위가 아니라 '언급 신호' ----------
// 응답에 조회·댓글 수가 없다. 관찰어별로 최신 블로그 글 100건 중 오늘 올라온 글 수(포화되면 100+)와 최신 카페글 제목을 보여 준다.
// 블로거 이름·본문 요약(description)은 저장하지 않는다. 호출: 관찰어당 2건(블로그 1, 카페 1).
// 키는 최대 2개(NAVER_CLIENT_ID/SECRET, NAVER_CLIENT_ID_2/SECRET_2). 호출마다 번갈아 쓰고, 한 키가 한도 초과(429)·인증 오류(401/403)이면 이번 실행에서는 그 키를 빼고 남은 키로 넘어간다. 값은 로그에 남기지 않는다(번호·상태코드만).
const NV_KEYS = [
  [process.env.NAVER_CLIENT_ID, process.env.NAVER_CLIENT_SECRET],
  [process.env.NAVER_CLIENT_ID_2, process.env.NAVER_CLIENT_SECRET_2],
].map(([id, secret], i) => ({ n: i + 1, id, secret, dead: false, stat: {} })).filter((k) => k.id && k.secret);
let nvTurn = 0;
async function naverGet(kind, query, display) {
  const url = `https://openapi.naver.com/v1/search/${kind}.json?query=${encodeURIComponent(query)}&display=${display}&sort=date`;
  let lastErr = "키 없음";
  for (let tries = 0; tries < NV_KEYS.length; tries++) {
    const alive = NV_KEYS.filter((k) => !k.dead);
    if (!alive.length) break;
    const key = alive[nvTurn++ % alive.length];
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 15000);
    try {
      const res = await fetch(url, { headers: { "X-Naver-Client-Id": key.id, "X-Naver-Client-Secret": key.secret, "User-Agent": UA }, signal: ctl.signal });
      key.stat[res.status] = (key.stat[res.status] || 0) + 1;
      if (res.status === 429) { key.dead = true; lastErr = "rate-limit(429)"; continue; }
      if (res.status === 401 || res.status === 403) { key.dead = true; lastErr = `auth(${res.status})`; continue; }
      if (!res.ok) return { err: `error(${res.status})` };
      return { json: await res.json() };
    } catch (e) {
      return { err: e?.name === "AbortError" ? "timeout" : "error" };
    } finally { clearTimeout(timer); }
  }
  return { err: lastErr };
}
function logNaverKeys() {
  for (const k of NV_KEYS) console.log(`naver key#${k.n}: ${JSON.stringify(k.stat)}${k.dead ? " (제외됨)" : ""}`);
}
async function collectNaver(snaps) {
  const st = { key: "naver", name: "네이버", board: "블로그·카페 언급량(검색 API)", ok: false, count: 0, checkedAt: now.toISOString(), signal: true };
  if (!NV_KEYS.length) { st.pending = true; st.error = "키 없음"; return { st, terms: [] }; }
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
// 키(YOUTUBE_API_KEY)가 없으면 '준비 중'으로 조용히 건너뛴다.
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
// 수집 방식(v3): 금융·경제 채널 목록(keywords.json youtube.channels)의 '업로드 재생목록'을 읽는다.
//  - playlistItems.list는 1쿼터, videos.list(통계)도 50건에 1쿼터라 채널 10개를 읽어도 회차당 약 11쿼터(하루 1만 한도의 5% 안팎)다.
//    예전 방식(search.list 검색어 3개=300쿼터, 조회수순)은 쿼터가 비싸 50분에 한 번만 돌릴 수 있었고, 금융과 무관한 대형 방송(예: 시사 라이브)이 상위를 차지했다.
//  - 채널 단위로 읽으면 출처가 확실하고, 제목이 금융 키워드에 맞는 영상만 남긴다. 금융 전문 채널(prior 지정)은 키워드가 약해도 후보로 본다.
// 점수: 반응량 = 조회 + 좋아요×3 + 댓글×5 를 시간당으로 환산(올라온 뒤 시간^0.8)하고, 시간당 5,000회 = 100점(로그 눈금). 감독·금융사 키워드 +10점.
// 금융 전문 채널이라 키워드가 약한 영상(priorOnly)은 ×0.7. 올라온 지 6시간 안 영상은 조회가 적어도(최소 100회) 보여 주고, 그 이후는 최소 1,000회.
const ytScore = (v, priorOnly) => {
  const ageH = v.ts ? Math.max(1, (now - new Date(v.ts)) / 3600000) : 24;
  const weighted = v.v + 3 * v.l + 5 * v.c;
  const rate = weighted / Math.pow(ageH, 0.8);
  let sc = Math.min(100, Math.round((100 * Math.log1p(rate)) / Math.log1p(5000)));
  if (priorOnly) sc = Math.round(sc * 0.7);
  return Math.min(100, sc + (v.sup ? 10 : 0));
};
function ytItem(f, st, prior) {
  // 출연자 명단이 대괄호 안에 줄줄이 붙은 방송 전체 영상(예: 시사 라이브 회차)은 제목에 '주식' 같은 말이 우연히 섞여도 금융 영상이 아니므로 뺀다.
  if ((f.title.match(/\[[^\]]*,[^\]]*,[^\]]*\]/) || (KW.youtube?.exclude || []).some((x) => f.title.includes(x)))) return null;
  const cl = classify({ title: f.title, prior: prior || null });
  if (!cl) return null; // 금융 키워드가 맞는 영상(또는 금융 전문 채널 영상)만
  const views = Number(st?.viewCount ?? 0);
  const ageH = f.ts ? (now - new Date(f.ts)) / 3600000 : 99;
  if (views < (ageH <= 6 ? 100 : 1000)) return null;
  const x = { id: `yt:${f.id}`, t: f.title, u: `https://www.youtube.com/watch?v=${f.id}`, s: "유튜브", sk: "youtube", b: f.ch, ts: f.ts, v: views, c: Number(st?.commentCount ?? 0), l: Number(st?.likeCount ?? 0), cat: cl.cat, sup: cl.sup || undefined };
  x.score = ytScore(x, cl.priorOnly);
  return x;
}
async function collectYoutube() {
  const cfg = KW.youtube || {};
  const st = { key: "youtube", name: "유튜브", board: "금융·경제 채널 영상(조회·댓글 수 포함)", ok: false, count: 0, checkedAt: now.toISOString() };
  if (!YT_KEY) { st.pending = true; st.error = "키 없음"; return { st, items: [] }; }
  // 직전 호출이 가까우면 이전 결과를 그대로 쓴다(채널 방식은 값이 싸므로 기본 25분).
  let prevYt = null;
  try { prevYt = PREV ? JSON.parse(fs.readFileSync(path.join(PREV, "hot.json"), "utf8")).youtube : null; } catch { /* 없음 */ }
  if (prevYt?.ver !== 3) prevYt = null; // 수집 방식이 바뀐 이전 버전 결과는 쓰지 않는다
  if (prevYt?.checkedAt && now - new Date(prevYt.checkedAt) < (cfg.minIntervalMin ?? 25) * 60000 && prevYt.items?.length) {
    st.ok = true; st.count = prevYt.items.length; st.checkedAt = prevYt.checkedAt; st.cached = true;
    return { st, items: prevYt.items, chOk: prevYt.chOk };
  }
  const after = now - (cfg.hours ?? 48) * 3600000;
  const found = new Map();
  let chOk = 0, chFail = 0;
  for (const ch of cfg.channels || []) {
    if (!/^UC[\w-]{20,}$/.test(ch.id || "")) continue;
    const r = await ytGet("playlistItems", { part: "snippet", playlistId: "UU" + ch.id.slice(2), maxResults: String(cfg.perChannel ?? 15) });
    if (r.err) { st.error = r.err; chFail++; if (r.err === "quota") break; continue; }
    chOk++;
    for (const it of r.json.items || []) {
      const id = it.snippet?.resourceId?.videoId;
      const ts = it.snippet?.publishedAt || null;
      if (!id || (ts && new Date(ts) < after) || found.has(id)) continue;
      found.set(id, { id, title: decode(it.snippet?.title || ""), ch: ch.name || decode(it.snippet?.videoOwnerChannelTitle || ""), ts, prior: ch.prior || null });
    }
    await sleep(150);
  }
  const out = new Map();
  const ids = [...found.keys()];
  for (let i = 0; i < ids.length; i += 50) {
    const v = await ytGet("videos", { part: "statistics", id: ids.slice(i, i + 50).join(",") });
    if (v.err) { st.error = v.err; continue; }
    for (const it of v.json?.items || []) {
      const f = found.get(it.id);
      const x = f && ytItem(f, it.statistics, f.prior);
      if (x) out.set(x.id, x);
    }
  }
  // 한 채널이 목록을 독차지하지 않게 채널당 최대 6건만 남긴다.
  const perCh = {};
  const items = [...out.values()].sort((a, b) => b.score - a.score).filter((x) => (perCh[x.b] = (perCh[x.b] || 0) + 1) <= 6).slice(0, 30);
  st.count = items.length;
  st.ok = chOk > 0;
  if (st.error && st.error !== "quota" && st.ok) delete st.error; // 일부 채널만 실패한 경우는 정상으로 본다
  if (!items.length && prevYt?.items?.length && !st.ok) { st.stale = true; return { st, items: prevYt.items, chOk: prevYt.chOk }; }
  return { st, items, chOk };
}

async function run() {
  fs.mkdirSync(path.join(OUT, "snap"), { recursive: true });
  const snaps = loadSnaps();
  const hist = loadHist();
  const { items: raw, status } = await collect();
  const nv = await collectNaver(snaps);
  logNaverKeys();
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

  // 1~4시간 전 스냅샷 중 2시간 전에 가장 가까운 것에서 해당 글의 기록을 찾는다
  const refPool = snaps.filter((x) => { const h = (now - new Date(x.at)) / 3600000; return h >= 1 && h <= 4; }).sort((a, b) => Math.abs((now - new Date(a.at)) / 3600000 - 2) - Math.abs((now - new Date(b.at)) / 3600000 - 2));
  const refSnap = (id) => { for (const sn of refPool) if (sn.items?.[id]) return { at: sn.at, e: sn.items[id] }; return null; };

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
    // 증가 속도: 1~4시간 전(약 2시간 전에 가장 가까운) 스냅샷과 비교한 시간당 증가량. 30분 간격 값은 잡음이 커서 긴 창을 우선 쓴다.
    const ref = refSnap(it.id);
    let rv = null, rc = null, implied = false;
    if (ref) {
      const dtH = Math.max(0.5, (now - new Date(ref.at)) / 3600000);
      rv = Math.max(0, v - ref.e[0]) / dtH;
      rc = Math.max(0, it.comments - ref.e[1]) / dtH;
    } else if (it.dvh != null) { rv = it.dvh; rc = (dc ?? 0) / Math.max(0.25, (now - new Date(prev.at)) / 3600000); }
    else if (ageH <= 6 && snaps.length) { rv = (v / Math.max(ageH, 0.5)) * 0.6; rc = (it.comments / Math.max(ageH, 0.5)) * 0.6; implied = true; } // 새로 뜬 글은 올라온 뒤 평균 속도로 가늠한다
    // 순위의 중심은 '지금 얼마나 빨리 늘고 있나'. 누적 조회·댓글은 보조(가중치 절반 이하)
    let raw_ = 0.18 * Math.log(1 + v / m.v) + 0.15 * Math.log(1 + it.comments / m.c) + 0.07 * Math.log(1 + it.likes / m.l);
    if (rv != null) raw_ += 0.9 * Math.log(1 + rv / m.v) + 0.5 * Math.log(1 + (rc / Math.max(1, m.c)) * 2);
    if (it.pop) raw_ += 0.2;
    if (cl.priorOnly) raw_ *= 0.45; // 금융 키워드 없이 금융 게시판이라는 이유만으로 들어온 글은 키워드가 맞은 글보다 낮게
    raw_ *= Math.pow(0.5, ageH / 6) * (it.w ?? 1); // 출처별 가중치(보조 갤러리는 약간 낮춤)
    // 이미 오래 보여 준 글은 점수를 깎는다(24시간 넘게 목록에 머문 글: 하루마다 ×0.75)
    const firstAt = hist.first[it.id] || (p ? prev.at : now.toISOString());
    if (!hist.first[it.id]) hist.first[it.id] = firstAt;
    const heldH = (now - new Date(firstAt)) / 3600000;
    if (heldH > 24) raw_ *= Math.pow(0.75, (heldH - 24) / 24);
    const isNew = heldH <= 3;
    if (cl.sup) raw_ += KW.supervision.bonus * Math.pow(0.5, ageH / 24);
    const score = Math.round(100 * raw_ / (raw_ + 1.2));
    cand.push({ ...it, ...cl, score, dv, dc, ageH, rvh: rv != null && !implied ? Math.round(rv) : undefined, isNew, isNewPost: !prevIds.has(it.id) });
    if (!prevIds.has(it.id)) for (const l of cl.labels) kwNew[l] = (kwNew[l] || 0) + 1;
  }

  // 후보 선별: 키워드로 금융 관련이 확인됐거나, 금융 게시판 글이면서 점수가 높은 것만
  let list = cand.filter((c) => !c.priorOnly || c.pop || c.score >= 30);
  if (process.env.COMMUNITY_DEBUG) for (const c of cand.sort((a, b) => b.score - a.score).slice(0, 60)) console.log(c.score, c.src, c.priorOnly ? "P" : "K", c.pop ? "pop" : "", c.views, c.comments, c.title.slice(0, 40));
  list = list.filter((c) => c.ageH <= 48 && (c.score >= 12 || !c.priorOnly)); // 키워드가 맞은 글은 점수가 낮아도 남긴다(보배드림처럼 일반 게시판이 비지 않게)

  // 같은 이슈 묶기: 제목이 거의 같거나(바이그램 70%↑), 의미 있는 단어를 2개 이상 공유하고 단어 집합이 40% 이상 겹치면 한 이슈로 본다.
  const key = (t) => t.toLowerCase().replace(/[^0-9a-z가-힣]/g, "");
  const grams = (t) => { const k = key(t), s2 = new Set(); for (let i = 0; i < k.length - 1; i++) s2.add(k.slice(i, i + 2)); return s2; };
  list.sort((a, b) => b.score - a.score);
  for (const c of list) { c._g = grams(c.title); c._tk = tokens(c.title); }
  const parent = list.map((_, i) => i);
  const find = (i) => { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; };
  const similar = (a, b) => {
    let inter = 0; for (const x of a._g) if (b._g.has(x)) inter++;
    const uni = a._g.size + b._g.size - inter;
    if (uni > 0 && inter / uni >= 0.7) return true;
    let sh = 0; for (const x of a._tk) if (b._tk.has(x)) sh++;
    const tu = a._tk.size + b._tk.size - sh;
    return sh >= 2 && tu > 0 && sh / tu >= 0.4;
  };
  for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length; j++) if (find(i) !== find(j) && similar(list[i], list[j])) parent[find(j)] = find(i);
  const groupsMap = new Map();
  list.forEach((c, i) => { const r = find(i); if (!groupsMap.has(r)) groupsMap.set(r, []); groupsMap.get(r).push(c); });
  const kept = [];
  for (const members of groupsMap.values()) {
    const rep = members[0]; // 점수순 정렬이라 첫 항목이 대표
    const others = members.slice(1);
    rep.also = [...new Set(others.map((o) => o.source))].filter((n) => n !== rep.source);
    rep.cn = members.length;
    rep.rel = others.slice(0, 4).map((o) => ({ t: o.title, u: o.url, s: o.source }));
    // 여러 글·여러 출처에서 같이 오르는 이슈는 조금 가산(최대 +10)
    if (members.length > 1) rep.score = Math.min(100, rep.score + Math.min(10, Math.round(3 * Math.log2(members.length) + 2 * rep.also.length)));
    kept.push(rep);
  }
  kept.sort((a, b) => b.score - a.score);

  const top = kept.slice(0, 100).map((c) => ({
    id: c.id, t: c.title, u: c.url, s: c.source, sk: c.src, b: c.board, ts: c.ts,
    v: c.views, c: c.comments, l: c.likes, score: c.score, cat: c.cat, sup: c.sup || undefined,
    dv: c.dv ?? undefined, dc: c.dc ?? undefined, rv: c.rvh || undefined, nw: c.isNew || undefined, cn: c.cn > 1 ? c.cn : undefined, rel: c.cn > 1 ? c.rel : undefined, co: c.cos?.length ? c.cos : undefined, pop: c.pop || undefined, also: c.also?.length ? c.also : undefined,
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
  // 이번 회차 새 글 언급을 시간 버킷에 더하고, 28일이 지난 버킷은 버린다
  const hk = hourKey(now);
  hist.buckets[hk] = hist.buckets[hk] || {};
  for (const [k, n] of Object.entries(kwNew)) hist.buckets[hk][k] = (hist.buckets[hk][k] || 0) + n;
  for (const b of Object.keys(hist.buckets)) { const y = +b.slice(0, 4), mo = +b.slice(4, 6), d = +b.slice(6, 8), h = +b.slice(8, 10); if (now - new Date(Date.UTC(y, mo - 1, d, h - 9)) > HIST_KEEP_MS) delete hist.buckets[b]; }
  for (const [id, at] of Object.entries(hist.first)) if (now - new Date(at) > SNAP_KEEP_MS) delete hist.first[id];
  // 자동 추출 키워드: 사전에 없는 단어 중 이번 후보 글 2건 이상에 나온 것을 '#단어'로 시간 버킷에 쌓고(새 글 기준), 최근 6시간 언급이 평소(같은 시간대 기준선)보다 튀는 것만 뽑는다.
  const df = {}, dfNew = {};
  for (const c of cand) { if (c.ageH > 24 || c.priorOnly) continue; // 키워드가 맞은 금융 글에서만 새 단어를 찾는다(게시판 잡담 제외)
      for (const t of tokens(c.title)) { if (knownTerm(t)) continue; df[t] = (df[t] || 0) + 1; if (c.isNewPost) dfNew[t] = (dfNew[t] || 0) + 1; } }
  const autoCand = Object.keys(dfNew).filter((t) => df[t] >= 2);
  for (const t of autoCand) hist.buckets[hk]["#" + t] = (hist.buckets[hk]["#" + t] || 0) + dfNew[t];
  const autoOut = [];
  for (const k of Object.keys(hist.buckets[hk])) {
    if (k[0] !== "#") continue;
    const t = k.slice(1);
    const rec = windowSum(hist.buckets, now.getTime(), 6, k).sum;
    if (rec < 4 || !(df[t] >= 2)) continue;
    const base = kwZ(hist.buckets, k);
    const prior6 = windowSum(hist.buckets, now.getTime() - 6 * 3600000, 6, k).sum;
    const z = base ? base.z : rec >= 5 && df[t] >= 3 && rec >= 2 * prior6 + 2 ? rec : 0; // 기준선이 쌓이기 전에는 더 엄격하게
    if (z >= 3) autoOut.push({ k: t, n: df[t], r: rec, z: Math.round(z * 10) / 10 });
  }
  autoOut.sort((a, b) => b.z - a.z || b.r - a.r);
  const cur = {};
  for (const c of kept) for (const l of c.labels) cur[l] = (cur[l] || 0) + 1;
  const keywords = Object.entries(cur).map(([k, n]) => {
    const r = recent[k] || 0, b = before[k] || 0;
    const base = kwZ(hist.buckets, k); // 같은 시간대 최근 2주 대비(기준선 3일 이상일 때)
    let trend;
    if (base) trend = base.z >= 3 && base.recent >= 3 ? 2 : base.z >= 1.5 && base.recent >= 2 ? 1 : base.z <= -1.5 ? -1 : 0;
    else trend = !haveHist ? 0 : r >= 3 && r >= b * 2 + 2 ? 2 : r > b ? 1 : r < b ? -1 : 0;
    return { k, n, d: r, trend, z: base?.z, base: base ? true : undefined };
  }).sort((a, b) => b.trend - a.trend || (b.z ?? 0) - (a.z ?? 0) || b.d - a.d || b.n - a.n).slice(0, 12);

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
    items: top, keywords, auto: autoOut.slice(0, 10),
    naver: { checkedAt: nv.st.checkedAt, terms: nv.terms },
    youtube: { ver: 3, checkedAt: yt.st.checkedAt, chOk: yt.chOk, items: yt.items },
  };

  // 모든 출처가 실패하면 이전 hot.json을 그대로 두고(화면이 비지 않게) 상태만 갱신한다
  if (okCount === 0 && PREV && fs.existsSync(path.join(PREV, "hot.json"))) {
    const old = JSON.parse(fs.readFileSync(path.join(PREV, "hot.json"), "utf8"));
    old.sources = status; old.stale = true; old.checkedAt = now.toISOString();
    fs.writeFileSync(path.join(OUT, "hot.json"), JSON.stringify(old));
    if (fs.existsSync(path.join(PREV, "archive.json"))) fs.copyFileSync(path.join(PREV, "archive.json"), path.join(OUT, "archive.json"));
    if (fs.existsSync(path.join(PREV, "kwhist.json"))) fs.copyFileSync(path.join(PREV, "kwhist.json"), path.join(OUT, "kwhist.json"));
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
  fs.writeFileSync(path.join(OUT, "kwhist.json"), JSON.stringify({ v: 1, updated: now.toISOString(), buckets: hist.buckets, first: hist.first }));
  copySnaps(snaps);
  console.log(`수집 ${raw.length}건 → 금융 관련 ${kept.length}건(감독 ${hot.stats.supervision}), 출처 ${okCount}/${status.length} 정상, 스냅샷 ${snaps.length + 1}개`);
  for (const s of status) console.log(" -", s.key, s.ok ? "ok" : "FAIL", s.count, s.error || "");
}
function copySnaps(snaps) {
  for (const s of snaps) fs.writeFileSync(path.join(OUT, "snap", s.file), JSON.stringify({ at: s.at, items: s.items, kwNew: s.kwNew, nv: s.nv, nvTotal: s.nvTotal }));
}

run().catch((e) => { console.error(e); process.exit(1); });
