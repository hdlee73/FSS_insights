// FSS Insights 서버(Cloudflare Worker)
//  - /news    : 네이버 뉴스 검색 프록시. 네이버 키는 이 서버의 비밀값으로만 존재한다.
//  - /library : 자료실. 구글 드라이브의 공유 폴더 목록·파일을 읽기 전용으로 중계한다.
//  - /stats   : 국내 금융 통계(한국은행 ECOS). 키는 이 서버의 비밀값(ECOS_API_KEY)으로만 존재한다.
//  - /library/upload : 자료 올리기. 내 계정으로 실행되는 Apps Script 웹앱이 비공개 "업로드 대기" 폴더에만 올린다(관리자가 확인해 자료실로 옮김).
const NAVER = "https://openapi.naver.com/v1/search/news.json";
const DRIVE = "https://www.googleapis.com/drive/v3/files";
const FOLDER = "application/vnd.google-apps.folder";
// 구글 문서 형식은 내려받을 때 PDF/엑셀로 변환한다.
const EXPORTS = {
  "application/vnd.google-apps.document": ["application/pdf", ".pdf"],
  "application/vnd.google-apps.presentation": ["application/pdf", ".pdf"],
  "application/vnd.google-apps.spreadsheet": ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"],
  "application/vnd.google-apps.drawing": ["application/pdf", ".pdf"],
};

const clamp = (value, min, max, fallback) => {
  const n = parseInt(value, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
};
const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
const fail = (status, message) => json({ error: message }, status);
const ID = /^[A-Za-z0-9_-]{10,100}$/;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!env.APP_TOKEN || request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (url.pathname === "/news" && request.method === "GET") return news(url, env, ctx);
    if (url.pathname === "/stats" && request.method === "GET") {
      try {
        return await stats(url, env, ctx);
      } catch (e) {
        return fail(502, `통계 서버 오류: ${e.message}`);
      }
    }
    if (url.pathname === "/library/upload" && request.method === "POST") {
      try {
        return await upload(request, url, env);
      } catch (e) {
        return fail(502, `업로드 서버 오류: ${e.message}`);
      }
    }
    if (url.pathname.startsWith("/library") && request.method === "GET") {
      try {
        return await library(url, env);
      } catch (e) {
        return fail(502, `자료실 서버 오류: ${e.message}`);
      }
    }
    return new Response("Not found", { status: 404 });
  },
};

async function news(url, env, ctx) {
  const query = (url.searchParams.get("query") || "").trim().slice(0, 200);
  if (!query) return new Response("query required", { status: 400 });
  const display = clamp(url.searchParams.get("display"), 1, 100, 100);
  const start = clamp(url.searchParams.get("start"), 1, 1000, 1);
  const target = `${NAVER}?query=${encodeURIComponent(query)}&display=${display}&start=${start}&sort=date`;
  const cacheKey = new Request(target);
  const cache = caches.default;
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  const upstream = await fetch(target, {
    headers: { "X-Naver-Client-Id": env.NAVER_CLIENT_ID, "X-Naver-Client-Secret": env.NAVER_CLIENT_SECRET },
  });
  const response = new Response(upstream.body, {
    status: upstream.status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=120" },
  });
  if (upstream.ok) ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

async function drive(path, env, params) {
  const query = new URLSearchParams({ ...params, key: env.GOOGLE_API_KEY });
  return fetch(`${DRIVE}${path}?${query}`);
}

// 자료실 색인: 지정한 루트 폴더와 하위 폴더(최대 4단계, 60개)의 모든 파일을 한 번 읽어 1분간 기억한다.
// (API 키로는 파일의 parents 값을 읽을 수 없어서 위에서부터 내려가며 폴더별 목록으로 만든다.)
let indexCache = { at: 0, root: "", data: null };

// 설명 속 #태그를 뽑아 태그 목록과 태그를 뺀 설명으로 나눈다.
function splitDescription(text) {
  const tags = [];
  const body = String(text || "").replace(/(^|\s)#([^\s#]{1,20})/g, (m, sp, tag) => { tags.push(tag); return sp; }).replace(/\s+/g, " ").trim();
  return { tags: [...new Set(tags)], body };
}

function toItem(f, parent) {
  const isFolder = f.mimeType === FOLDER;
  const exp = EXPORTS[f.mimeType];
  const native = f.mimeType.startsWith("application/vnd.google-apps.");
  if (!isFolder && native && !exp) return null; // 바로가기·양식 등 내려받을 수 없는 형식은 숨김
  const d = splitDescription(f.description);
  return {
    id: f.id,
    name: f.name,
    folder: isFolder,
    type: exp ? exp[0] : f.mimeType,
    downloadName: exp && !f.name.toLowerCase().endsWith(exp[1]) ? f.name + exp[1] : f.name,
    size: Number(f.size || 0),
    modified: f.modifiedTime || "",
    description: d.body,
    tags: d.tags,
    parent,
  };
}

async function buildIndex(env) {
  const root = env.GDRIVE_FOLDER_ID;
  if (indexCache.data && indexCache.root === root && Date.now() - indexCache.at < 60000) return indexCache.data;
  const byFolder = new Map();
  const files = new Map();
  const names = new Map([[root, ""]]);
  let level = [root];
  for (let depth = 0; depth < 5 && level.length && byFolder.size < 60; depth++) {
    const next = [];
    for (const folder of level) {
      if (byFolder.size >= 60) break;
      const res = await drive("", env, {
        q: `'${folder}' in parents and trashed = false`,
        fields: "files(id,name,mimeType,size,modifiedTime,description)",
        orderBy: "folder,name_natural",
        pageSize: "300",
      });
      if (!res.ok) throw new Error(`드라이브 응답 ${res.status}`);
      const items = ((await res.json()).files || []).map((f) => toItem(f, folder)).filter(Boolean);
      byFolder.set(folder, items);
      for (const item of items) {
        files.set(item.id, item);
        if (item.folder && !names.has(item.id)) { names.set(item.id, item.name); next.push(item.id); }
      }
    }
    level = next;
  }
  const tagCount = new Map();
  for (const item of files.values()) for (const t of item.tags) tagCount.set(t, (tagCount.get(t) || 0) + 1);
  const tags = [...tagCount.entries()].sort((a, b) => b[1] - a[1]).map(([t]) => t).slice(0, 40);
  const data = { root, byFolder, files, names, tags };
  indexCache = { at: Date.now(), root, data };
  return data;
}

// 자료실 폴더들 안에서 본문에 모든 검색어가 들어 있는 파일 ID 집합(드라이브 fullText). 폴더를 20개씩 묶어 조회한다.
async function bodySearch(words, index, env) {
  const esc = (w) => w.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
  const terms = words.slice(0, 5).map((w) => `fullText contains '${esc(w)}'`).join(" and ");
  const folders = [...index.byFolder.keys()];
  const batches = [];
  for (let i = 0; i < folders.length; i += 20) batches.push(folders.slice(i, i + 20));
  const ids = new Set();
  await Promise.all(batches.map(async (batch) => {
    const parents = batch.map((f) => `'${f}' in parents`).join(" or ");
    const res = await drive("", env, { q: `(${parents}) and ${terms} and trashed = false`, fields: "files(id)", pageSize: "100" });
    if (!res.ok) throw new Error(`드라이브 응답 ${res.status}`);
    for (const f of (await res.json()).files || []) if (index.files.has(f.id)) ids.add(f.id);
  }));
  return ids;
}

async function library(url, env) {
  if (!env.GOOGLE_API_KEY || !env.GDRIVE_FOLDER_ID) return fail(501, "자료실(구글 드라이브)이 아직 연결되지 않았습니다.");
  const path = url.pathname.replace(/^\/library/, "").replace(/\/+$/, "");
  let index;
  try {
    index = await buildIndex(env);
  } catch (e) {
    return fail(502, "드라이브 목록을 읽지 못했습니다. 폴더가 '링크가 있는 모든 사용자'로 공유되어 있는지, API 키가 맞는지 확인해 주세요.");
  }

  if (path === "/list") {
    const folder = url.searchParams.get("folder") || index.root;
    if (!index.byFolder.has(folder)) return fail(403, "자료실 밖의 폴더입니다.");
    return json({ folder, root: folder === index.root, tags: index.tags, items: index.byFolder.get(folder) });
  }

  if (path === "/search") {
    const q = (url.searchParams.get("q") || "").trim().slice(0, 60).toLowerCase();
    if (!q) return json({ items: [], tags: index.tags });
    const tagQuery = q.startsWith("#") ? q.slice(1) : null;
    const words = q.split(/\s+/).filter(Boolean);
    const hits = [];
    // 본문 검색: 드라이브가 파일 내용(PDF·문서·오피스 등)을 직접 색인해 두므로 fullText 검색을 그대로 쓴다. 실패하면 제목·설명 검색만 한다.
    const bodyIds = tagQuery !== null ? new Set() : await bodySearch(words, index, env).catch(() => new Set());
    for (const item of index.files.values()) {
      if (item.folder) continue;
      const haystack = `${item.name} ${item.description} ${item.tags.join(" ")}`.toLowerCase();
      const ok = tagQuery !== null ? item.tags.some((t) => t.toLowerCase() === tagQuery) : words.every((w) => haystack.includes(w));
      if (ok || bodyIds.has(item.id)) hits.push({ ...item, location: index.names.get(item.parent) || "", bodyMatch: !ok });
    }
    hits.sort((a, b) => (b.modified || "").localeCompare(a.modified || ""));
    return json({ items: hits.slice(0, 100), tags: index.tags });
  }

  const m = path.match(/^\/file\/([A-Za-z0-9_-]+)$/);
  if (m) {
    const id = m[1];
    const entry = index.files.get(id);
    if (!entry || entry.folder) return fail(403, "자료실 밖의 파일입니다.");
    const meta = await drive(`/${id}`, env, { fields: "name,mimeType" });
    if (!meta.ok) return fail(404, "파일을 찾을 수 없습니다.");
    const info = await meta.json();
    const conv = EXPORTS[info.mimeType];
    const upstream = conv ? await drive(`/${id}/export`, env, { mimeType: conv[0] }) : await drive(`/${id}`, env, { alt: "media" });
    if (!upstream.ok) return fail(502, "파일을 내려받지 못했습니다(구글 변환·전송 한도를 넘었을 수 있습니다).");
    const headers = {
      "Content-Type": conv ? conv[0] : info.mimeType,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(entry.downloadName)}`,
      "X-Content-Type-Options": "nosniff",
    };
    const length = upstream.headers.get("Content-Length");
    if (length) headers["Content-Length"] = length;
    return new Response(upstream.body, { headers });
  }
  return fail(404, "Not found");
}

// ---- 자료 올리기(관리자 승인형) ----
// 서비스 계정은 저장 용량이 없어 개인 드라이브에 올릴 수 없으므로, 내 계정으로 실행되는 Apps Script 웹앱이 대신 저장한다.
const UPLOAD_MAX = 20 * 1024 * 1024;
const UPLOAD_ALLOWED = new Set(["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "txt", "csv", "png", "jpg", "jpeg"]);

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let out = "";
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(out);
}

async function upload(request, url, env) {
  if (!env.APPS_SCRIPT_URL || !env.APPS_SCRIPT_SECRET) return fail(501, "자료 올리기가 아직 연결되지 않았습니다.");
  const name = (url.searchParams.get("name") || "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").trim().slice(0, 120);
  const ext = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
  if (!name || !UPLOAD_ALLOWED.has(ext)) return fail(400, "올릴 수 없는 파일 형식입니다. (PDF·문서·엑셀·파워포인트·한글·텍스트·이미지)");
  const length = Number(request.headers.get("Content-Length") || 0);
  if (!length) return fail(411, "파일 크기를 알 수 없습니다.");
  if (length > UPLOAD_MAX) return fail(413, "파일은 20MB까지 올릴 수 있습니다.");
  const body = await request.arrayBuffer();
  if (body.byteLength > UPLOAD_MAX) return fail(413, "파일은 20MB까지 올릴 수 있습니다.");
  const who = (url.searchParams.get("uploader") || "").trim().slice(0, 40);
  const note = (url.searchParams.get("description") || "").trim().slice(0, 300);
  const description = [note, who && `올린 사람: ${who}`].filter(Boolean).join(" / ");
  const res = await fetch(env.APPS_SCRIPT_URL, {
    method: "POST",
    headers: { "Content-Type": "text/plain;charset=utf-8" },
    body: JSON.stringify({
      secret: env.APPS_SCRIPT_SECRET, name, description,
      mimeType: request.headers.get("Content-Type") || "application/octet-stream", data: toBase64(body),
    }),
    redirect: "follow",
  });
  let result = null;
  try { result = await res.json(); } catch { /* 본문이 JSON이 아님 */ }
  if (!res.ok || !result?.ok) return fail(502, `드라이브에 올리지 못했습니다(${res.status}${result?.error ? `, ${result.error}` : ""}). Apps Script 웹앱 배포와 접근 권한 설정을 확인해 주세요.`);
  return json({ ok: true });
}

// ---- 국내 금융 통계(한국은행 ECOS) ----
// 통계표·항목 코드는 ECOS 통계표/항목 목록(StatisticTableList·StatisticItemList)에서 확인한 값을 쓴다.
// 코드를 모르는 지표(기준금리·외환보유액)만 표 이름·항목 이름 키워드로 찾는다.
// 못 찾거나 값이 없는 지표는 응답에서 빼고 missing에 id를 남긴다.
// 앱은 /stats?ids=a,b,c 로 고른 통계만 요청한다(ids가 없으면 DEFAULT_STAT_IDS).
const ECOS = "https://ecos.bok.or.kr/api";
// scale: 값에 곱해 단위를 바꾼다(십억원 → 조원). want: 항목이 둘 이상인 표에서 행의 항목 이름으로 고른다.
export const STAT_SPECS = [
  { id: "base-rate", name: "한국은행 기준금리", unit: "%", cycle: "D", table: ["기준금리", "여수신금리"], item: ["기준금리"], lastChange: true },
  { id: "call-1d", name: "콜금리(1일)", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010101000" },
  { id: "cd-91", name: "CD 91일", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010502000" },
  { id: "cp-91", name: "CP 91일", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010503000" },
  { id: "ktb-1y", name: "국고채 1년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010190000" },
  { id: "ktb-2y", name: "국고채 2년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010195000" },
  { id: "ktb-3y", name: "국고채 3년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010200000" },
  { id: "ktb-5y", name: "국고채 5년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010200001" },
  { id: "ktb-10y", name: "국고채 10년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010210000" },
  { id: "ktb-20y", name: "국고채 20년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010220000" },
  { id: "ktb-30y", name: "국고채 30년", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010230000" },
  { id: "corp-aa", name: "회사채 3년 AA-", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010300000" },
  { id: "corp-bbb", name: "회사채 3년 BBB-", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010320000" },
  { id: "household-credit", name: "가계신용 잔액", unit: "조원", scale: 0.001, cycle: "Q", tableCode: "151Y001", itemCode: "1000000" },
  { id: "household-credit-loan", name: "가계대출 잔액(분기)", unit: "조원", scale: 0.001, cycle: "Q", tableCode: "151Y001", itemCode: "1100000" },
  { id: "bank-household-loan", name: "은행 가계대출 잔액", unit: "조원", scale: 0.001, cycle: "M", tableCode: "151Y002", want: ["예금은행"] },
  { id: "deposit-household-loan", name: "예금취급기관 가계대출", unit: "조원", scale: 0.001, cycle: "M", tableCode: "151Y002", want: ["예금취급기관"] },
  { id: "bank-mortgage", name: "은행 주택관련대출", unit: "조원", scale: 0.001, cycle: "M", tableCode: "151Y005", itemCode: "11110A0" },
  { id: "bank-delinquency", name: "은행 가계대출 연체율", unit: "%", cycle: "M", tableCode: "901Y054", want: ["가계대출", "은행전체"] },
  { id: "bank-delinquency-corp", name: "은행 기업대출 연체율", unit: "%", cycle: "M", tableCode: "901Y054", want: ["기업대출", "은행전체"] },
  { id: "loan-rate", name: "예금은행 대출금리", unit: "%", cycle: "M", tableCode: "121Y006", itemCode: "BECBLA01" },
  { id: "mortgage-rate", name: "예금은행 주택담보대출금리", unit: "%", cycle: "M", tableCode: "121Y006", itemCode: "BECBLA0302" },
  { id: "fx-reserves", name: "외환보유액", unit: "", cycle: "M", table: ["외환보유액"], item: [] },
];
export const DEFAULT_STAT_IDS = ["base-rate", "ktb-3y", "ktb-10y", "cd-91", "corp-aa", "household-credit", "bank-household-loan", "bank-delinquency", "loan-rate", "fx-reserves"];

let tableCache = { at: 0, rows: null };

async function ecos(env, path) {
  const res = await fetch(`${ECOS}/${path.replace("{key}", env.ECOS_API_KEY)}`);
  if (!res.ok) throw new Error(`ECOS ${res.status}`);
  const body = await res.json();
  if (body.RESULT) throw new Error(`ECOS ${body.RESULT.CODE}`);
  return body;
}

export const pick = (rows, keywords, nameOf) => {
  const hits = rows.filter((r) => keywords.every((k) => nameOf(r).includes(k)));
  return hits.sort((a, b) => nameOf(a).length - nameOf(b).length)[0] || null;
};

// back: 월·분기 통계에서 되짚어 볼 기간(개월). 항목을 지정하지 않는 조회는 행이 많아 짧게 잡는다.
export const range = (cycle, now = new Date(), back = 36) => {
  const pad = (n) => String(n).padStart(2, "0");
  const ymd = (d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`;
  const ym = (d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}`;
  const monthsAgo = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - back, 1));
  if (cycle === "D") return [ymd(new Date(now.getTime() - 400 * 86400000)), ymd(now)];
  if (cycle === "Q") return [`${monthsAgo.getUTCFullYear()}Q${Math.floor(monthsAgo.getUTCMonth() / 3) + 1}`, `${now.getUTCFullYear()}Q4`];
  return [ym(monthsAgo), ym(now)];
};

// 항목 이름 끝의 각주 표시("은행전체 1)")를 뗀다.
const plainName = (name) => String(name || "").replace(/\s*\d+\)\s*$/, "").trim();

// want의 이름이 모두 행의 항목 이름(ITEM_NAME1~4) 중에 있는 행만 남긴다.
export const filterRows = (rows, want) =>
  rows.filter((r) => {
    const names = [1, 2, 3, 4].map((i) => plainName(r[`ITEM_NAME${i}`]));
    return want.every((w) => names.includes(w));
  });

// ECOS 행(시간순)에서 최근값과 비교값을 뽑는다. lastChange면 마지막으로 값이 바뀌기 전 값을 비교값으로 쓴다.
export const latestPair = (rows, lastChange) => {
  const points = rows
    .map((r) => ({ time: String(r.TIME), value: parseFloat(String(r.DATA_VALUE).replace(/,/g, "")), unit: r.UNIT_NAME || "" }))
    .filter((p) => Number.isFinite(p.value))
    .sort((a, b) => a.time.localeCompare(b.time));
  if (!points.length) return null;
  const last = points[points.length - 1];
  const before = points.slice(0, -1);
  const prev = lastChange ? [...before].reverse().find((p) => p.value !== last.value) : before[before.length - 1];
  return { value: last.value, previous: prev ? prev.value : null, period: last.time, unit: last.unit };
};

async function statOne(spec, env, tables) {
  let tableCode = spec.tableCode;
  let source = spec.tableCode;
  if (!tableCode) {
    const table = pick(tables, spec.table, (r) => r.STAT_NAME);
    if (!table) throw new Error("통계표 없음");
    tableCode = table.STAT_CODE;
    source = table.STAT_NAME;
  }
  let itemCode = spec.itemCode;
  if (!itemCode && !spec.want) {
    const items = (await ecos(env, `StatisticItemList/{key}/json/kr/1/500/${tableCode}`)).StatisticItemList.row;
    const item = spec.item.length ? pick(items, spec.item, (r) => r.ITEM_NAME) : items[0];
    if (!item) throw new Error("항목 없음");
    itemCode = item.ITEM_CODE;
    source += ` / ${item.ITEM_NAME}`;
  }
  const [from, to] = range(spec.cycle, new Date(), spec.want ? 18 : 36);
  const path = `StatisticSearch/{key}/json/kr/1/500/${tableCode}/${spec.cycle}/${from}/${to}` + (itemCode ? `/${itemCode}` : "");
  let rows = (await ecos(env, path)).StatisticSearch.row;
  if (spec.want) rows = filterRows(rows, spec.want);
  const pair = latestPair(rows, spec.lastChange);
  if (!pair) throw new Error("값 없음");
  const scale = spec.scale || 1;
  return {
    id: spec.id,
    name: spec.name,
    cycle: spec.cycle,
    value: pair.value * scale,
    previous: pair.previous == null ? null : pair.previous * scale,
    period: pair.period,
    unit: spec.unit || pair.unit,
    source,
  };
}

// ids를 알려진 통계로 좁힌다. 비었거나 모르는 id뿐이면 기본 묶음.
export const selectSpecs = (idsParam) => {
  const ids = String(idsParam || "").split(",").map((s) => s.trim()).filter(Boolean).slice(0, 24);
  const chosen = STAT_SPECS.filter((s) => ids.includes(s.id));
  return chosen.length ? chosen : STAT_SPECS.filter((s) => DEFAULT_STAT_IDS.includes(s.id));
};

async function stats(url, env, ctx) {
  if (!env.ECOS_API_KEY) return fail(503, "ECOS_API_KEY가 설정되지 않았습니다");
  const specs = selectSpecs(url.searchParams.get("ids"));
  const cacheKey = new Request(`https://stats.cache/ecos-v2/${specs.map((s) => s.id).sort().join(",")}`);
  const cache = caches.default;
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  if (specs.some((s) => !s.tableCode) && (!tableCache.rows || Date.now() - tableCache.at > 86400000)) {
    tableCache = { at: Date.now(), rows: (await ecos(env, "StatisticTableList/{key}/json/kr/1/5000/")).StatisticTableList.row };
  }
  const settled = await Promise.allSettled(specs.map((s) => statOne(s, env, tableCache.rows)));
  const items = settled.filter((r) => r.status === "fulfilled").map((r) => r.value);
  const missing = specs.filter((_, i) => settled[i].status === "rejected").map((s) => s.id);
  const response = new Response(JSON.stringify({ items, missing }), {
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=3600" },
  });
  if (items.length) ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}
