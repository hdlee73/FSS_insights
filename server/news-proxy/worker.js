// FSS Insights 서버(Cloudflare Worker)
//  - /news    : 네이버 뉴스 검색 프록시. 네이버 키는 이 서버의 비밀값으로만 존재한다.
//  - /library : 자료실. 구글 드라이브의 공유 폴더 목록·파일을 읽기 전용으로 중계한다.
//  - /stats   : 국내 금융 통계(한국은행 ECOS). 키는 이 서버의 비밀값(ECOS_API_KEY)으로만 존재한다.
//  - /fisis   : 금융통계정보시스템(FISIS) Open API 중계(금융회사 목록·총자산 순위·주요 경영정보). 인증키는 이 서버의 비밀값(FISIS_API_KEY)으로만 존재한다.
//  - FISIS 회사 상세에는 OpenDART(DART_API_KEY)로 대표이사·최대주주·연결 재무를 덧붙인다.
//  - /library/upload : 자료 올리기. 내 계정으로 실행되는 Apps Script 웹앱이 비공개 "업로드 대기" 폴더에만 올린다(관리자가 확인해 자료실로 옮김).
// 배포할 때 DART 고유번호 목록(금융회사 이름 → corp_code)을 만들어 넣는다. 만들지 않았으면 빈 목록이다.
import dartCorps from "./dart-corps.json";

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
    if (url.pathname.startsWith("/fisis/") && request.method === "GET") {
      try {
        return await fisis(url, env, ctx);
      } catch (e) {
        return fail(502, e.message || "FISIS 서버 오류");
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
  const cacheKey = new Request(`https://stats.cache/ecos-v3/${specs.map((s) => s.id).sort().join(",")}`);
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

// ---- FISIS (금융통계정보시스템) ----
// 금융권역코드(lrgDiv/partDiv): A 국내은행, F 증권사, G 자산운용사, H 생명보험, I 손해보험, E 상호저축은행, C 신용카드사.
const FISIS_API = "https://fisis.fss.or.kr/openapi";
const FISIS_SECTORS = new Set(["F", "G", "A", "H", "I", "E", "C"]);
const FISIS_TERMS = ["Q", "H", "Y"];
const CLOSED = /\[폐\]|\(폐\)|［폐］/; // 폐업한 회사는 이름 끝에 [폐]가 붙는다.

const rowsOf = (v) => (Array.isArray(v) ? v : v && Array.isArray(v.row) ? v.row : v && typeof v === "object" ? [v] : []);
const num = (x) => {
  if (x === null || x === undefined || String(x).trim() === "" || String(x).trim() === "-") return null;
  const n = Number(String(x).replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
};

async function fisisCall(env, name, params) {
  if (!env.FISIS_API_KEY) throw new Error("FISIS 인증키(FISIS_API_KEY)가 아직 설정되지 않았습니다.");
  const q = new URLSearchParams({ lang: "kr", auth: env.FISIS_API_KEY, ...params });
  const res = await fetch(`${FISIS_API}/${name}.json?${q}`);
  if (res.status === 429) throw new Error("FISIS 일일 호출 한도를 넘었습니다. 내일 다시 시도해 주세요.");
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`FISIS 응답을 읽지 못했습니다 (HTTP ${res.status})`);
  }
  const result = body.result || body;
  const code = String(result.err_cd ?? "000");
  if (code === "020") throw new Error("FISIS 일일 호출 한도를 넘었습니다. 내일 다시 시도해 주세요.");
  if (code !== "000" && code !== "100") throw new Error(`FISIS 오류 ${code}: ${result.err_msg || ""}`.trim()); // 100: 조회 결과 없음
  return result;
}

// 같은 요청은 서버 메모리와 Cache API에 보관해 FISIS 일일 호출 한도를 아낀다.
const fisisMemo = new Map();
async function fisisCached(ctx, key, ttl, build) {
  const hot = fisisMemo.get(key);
  if (hot && Date.now() - hot.at < ttl * 1000) return hot.value;
  const cacheKey = new Request(`https://fisis.cache/v2/${key}`);
  const hit = await caches.default.match(cacheKey);
  if (hit) {
    const value = await hit.json();
    fisisMemo.set(key, { at: Date.now(), value });
    return value;
  }
  const value = await build();
  fisisMemo.set(key, { at: Date.now(), value });
  ctx.waitUntil(
    caches.default.put(
      cacheKey,
      new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": `public, max-age=${ttl}` } })
    )
  );
  return value;
}

const jsonCached = async (ctx, key, ttl, build) => json(await fisisCached(ctx, key, ttl, build));

async function inChunks(items, size, fn) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(...(await Promise.all(items.slice(i, i + size).map(fn))));
  return out;
}

async function fisis(url, env, ctx) {
  const action = url.pathname.slice("/fisis/".length);
  const sector = url.searchParams.get("sector") || "";
  if (!FISIS_SECTORS.has(sector)) return fail(400, "지원하지 않는 권역입니다.");
  if (action === "companies") return jsonCached(ctx, `companies/${sector}`, 86400, () => fisisCompanies(env, sector));
  if (action === "spec") return json(await resolveFisisSpec(env, ctx, sector)); // 어떤 통계표·계정을 쓰는지 확인용
  if (action === "ranking") {
    const offset = clamp(url.searchParams.get("offset"), 0, 100000, 0);
    return json(await fisisRanking(env, ctx, sector, offset));
  }
  if (action === "profile") {
    const finance = url.searchParams.get("finance") || "";
    if (!/^[0-9A-Za-z]{4,12}$/.test(finance)) return fail(400, "회사 코드가 올바르지 않습니다.");
    return jsonCached(ctx, `profile/${sector}/${finance}`, 43200, () => fisisProfile(env, ctx, sector, finance));
  }
  return new Response("Not found", { status: 404 });
}

/** 권역의 영업 중인 금융회사(이름에 [폐]가 붙은 폐업 회사는 뺀다). */
async function fisisCompanies(env, sector) {
  const r = await fisisCall(env, "companySearch", { partDiv: sector });
  const items = rowsOf(r.list)
    .map((c) => ({ code: String(c.finance_cd || ""), name: String(c.finance_nm || "").trim() }))
    .filter((c) => c.code && c.name && !CLOSED.test(c.name));
  return { items };
}

// ---- 통계표·계정 찾기: 통계표 이름·계정 이름으로 필요한 항목을 찾는다(통계표 번호를 코드에 박아 두지 않는다) ----
const ACCOUNT_NOISE = /비율|률$|증감|구성비/;
const FIN_METRICS = [
  { key: "assets", label: "총자산", tables: /재무상태|대차대조|자산/, pats: [/^자산\s*총계$/, /^총\s*자산$/, /자산\s*총계|총\s*자산|자산\s*합계/, /^자산$/] },
  { key: "liab", label: "총부채", tables: /재무상태|대차대조|부채/, pats: [/^부채\s*총계$/, /^총\s*부채$/, /부채\s*총계|총\s*부채|부채\s*합계/, /^부채$/] },
  { key: "equity", label: "자기자본", tables: /재무상태|대차대조|자본/, pats: [/^자본\s*총계$/, /^자기자본$/, /자본\s*총계|총\s*자본|자본\s*합계/, /^자본$/] },
  { key: "capital", label: "자본금", tables: /재무상태|대차대조|자본/, pats: [/^자본금$/, /^납입\s*자본금$/, /자본금/] },
  { key: "revenue", label: "영업수익", tables: /손익|포괄|영업/, pats: [/^영업\s*수익$/, /영업\s*수익/, /^총\s*수익$/, /수익\s*(합계|총계)/, /^수익$/, /이자\s*수익/] },
  { key: "expense", label: "영업비용", tables: /손익|포괄|영업/, pats: [/^영업\s*비용$/, /영업\s*비용/, /^총\s*비용$/, /비용\s*(합계|총계)/, /이자\s*비용/] },
  { key: "netincome", label: "당기순이익", tables: /손익|포괄|영업/, pats: [/^당기\s*순\s*(이익|손익)$/, /당기\s*순\s*(이익|손익)/, /^순\s*(이익|손익)$/] },
];
const RATIO_SPECS = {
  F: [
    { key: "ncr", label: "순자본비율", pats: [/순자본비율|영업용\s*순자본비율|NCR/] },
    { key: "lev", label: "레버리지비율", pats: [/레버리지/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율|유동성/] },
  ],
  G: [
    { key: "ncr", label: "영업용순자본비율", pats: [/영업용\s*순자본비율|순자본비율|NCR/] },
    { key: "eqr", label: "자기자본비율", pats: [/자기자본\s*비율/] },
  ],
  A: [
    { key: "bis", label: "BIS 자기자본비율", pats: [/BIS/, /총자본\s*비율/, /자기자본\s*비율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/고정이하\s*여신\s*비율/, /부실\s*채권\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "lcr", label: "유동성커버리지비율", pats: [/유동성\s*커버리지|LCR/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율/] },
  ],
  E: [
    { key: "bis", label: "BIS 자기자본비율", pats: [/BIS/, /자기자본\s*비율/] },
    { key: "npl", label: "고정이하여신비율", pats: [/고정이하\s*여신\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "liq", label: "유동성비율", pats: [/유동성\s*비율/] },
  ],
  C: [
    { key: "adj", label: "조정자기자본비율", pats: [/조정\s*자기자본\s*비율/, /자기자본\s*비율/] },
    { key: "delinq", label: "연체율", pats: [/연체율/] },
    { key: "npl", label: "고정이하채권비율", pats: [/고정이하\s*(채권|여신)\s*비율/, /부실\s*채권\s*비율/] },
    { key: "lev", label: "레버리지배율", pats: [/레버리지/] },
  ],
  H: [
    { key: "solv", label: "지급여력비율(K-ICS)", pats: [/K-?ICS|킥스/, /지급여력\s*비율/] },
    { key: "per13", label: "13회차 유지율", pats: [/13\s*회차/] },
  ],
  I: [
    { key: "solv", label: "지급여력비율(K-ICS)", pats: [/K-?ICS|킥스/, /지급여력\s*비율/] },
    { key: "loss", label: "손해율", pats: [/손해율/] },
    { key: "opex", label: "사업비율", pats: [/사업비율/] },
  ],
};
const ANY_ACCT = /^(합\s*계|계|총\s*계|전체|펀드\s*합계|전체\s*펀드)$/;
const FUND_SPECS = [
  { key: "fund_all", label: "펀드 설정원본", col: /설정\s*원본/, pats: [ANY_ACCT, /합계|총계/, /설정\s*원본/] },
  { key: "nav_all", label: "펀드 순자산", col: /순자산/, pats: [ANY_ACCT, /합계|총계/, /순자산/] },
  { key: "fund_pub", label: "공모펀드 설정원본", col: /설정\s*원본/, pats: [/공모/] },
  { key: "nav_pub", label: "공모펀드 순자산", col: /순자산/, pats: [/공모/] },
  { key: "fund_priv", label: "사모펀드 설정원본", col: /설정\s*원본/, pats: [/사모/] },
  { key: "nav_priv", label: "사모펀드 순자산", col: /순자산/, pats: [/사모/] },
];
const INFO_SPECS = [
  { key: "ceo", label: "대표이사", pats: [/대표\s*이사|대표자|^대표/] },
  { key: "holder", label: "최대주주", pats: [/^최대\s*주주$|최대\s*주주\s*(명|성명)?$/, /최대\s*주주/] },
  { key: "holderPct", label: "최대주주 지분율", pats: [/최대\s*주주.*(지분|비율)/, /지분율/] },
];

async function fisisLists(env, ctx, sector) {
  return fisisCached(ctx, `lists/${sector}`, 604800, async () => {
    const r = await fisisCall(env, "statisticsListSearch", { lrgDiv: sector });
    return rowsOf(r.list).map((x) => ({ sml: String(x.sml_div_nm || ""), listNo: String(x.list_no || ""), listNm: String(x.list_nm || "") })).filter((x) => x.listNo);
  });
}

async function fisisAccounts(env, ctx, listNo) {
  return fisisCached(ctx, `accounts/${listNo}`, 2592000, async () => {
    const r = await fisisCall(env, "accountListSearch", { listNo });
    return rowsOf(r.list).map((a) => ({ code: String(a.account_cd || ""), name: String(a.account_nm || "").trim() })).filter((a) => a.code);
  });
}

/** 패턴 순서대로, 선호하는 통계표 순으로 훑어 처음 맞는 (통계표, 계정)을 돌려준다. */
function pickAccount(tables, accountsOf, spec, { skipNoise = false } = {}) {
  const ordered = spec.tables ? [...tables.filter((t) => spec.tables.test(t.listNm)), ...tables.filter((t) => !spec.tables.test(t.listNm))] : tables;
  for (const pat of spec.pats) {
    for (const t of ordered) {
      const a = (accountsOf.get(t.listNo) || []).find((x) => pat.test(x.name) && !(skipNoise && ACCOUNT_NOISE.test(x.name)));
      if (a) return { key: spec.key, label: spec.label, listNo: t.listNo, listNm: t.listNm, accountCd: a.code, accountNm: a.name, col: spec.col ? spec.col.source : "" };
    }
  }
  return null;
}

/** 권역의 통계표·계정 목록에서 재무·비율·펀드·일반현황 항목이 어느 통계표의 어느 계정인지 찾는다(30일 보관). */
async function resolveFisisSpec(env, ctx, sector) {
  return fisisCached(ctx, `spec/${sector}`, 2592000, async () => {
    const lists = await fisisLists(env, ctx, sector);
    const of = (re) => lists.filter((l) => re.test(l.sml));
    const fin = of(/재무현황/);
    const ratio = of(/경영지표/);
    const info = of(/일반현황/);
    const fund = sector === "G" ? of(/영업활동|재무현황/).filter((l) => /펀드|설정|순자산|수탁|운용/.test(l.listNm)) : [];
    const wanted = [...new Map([...fin, ...ratio, ...info, ...fund].map((l) => [l.listNo, l])).values()].slice(0, 44);
    const accountsOf = new Map();
    await inChunks(wanted, 8, async (l) => accountsOf.set(l.listNo, await fisisAccounts(env, ctx, l.listNo).catch(() => [])));
    const pickAll = (tables, specs, opt) => specs.map((sp) => pickAccount(tables, accountsOf, sp, opt)).filter(Boolean);
    const basis = {};
    const separate = fin.filter((l) => !/연결/.test(l.listNm));
    const connected = fin.filter((l) => /연결/.test(l.listNm));
    basis["별도"] = pickAll(separate.length ? separate : fin, FIN_METRICS, { skipNoise: true });
    if (connected.length) basis["연결"] = pickAll(connected, FIN_METRICS, { skipNoise: true });
    return {
      basis,
      ratios: pickAll(ratio, RATIO_SPECS[sector] || []),
      funds: pickAll(fund, FUND_SPECS),
      info: pickAll(info, INFO_SPECS),
      tables: { fin: fin.map((l) => l.listNm), ratio: ratio.map((l) => l.listNm), info: info.map((l) => l.listNm), fund: fund.map((l) => l.listNm) },
    };
  });
}

const monthsBack = (term, now = new Date()) => {
  const y = now.getUTCFullYear();
  const end = `${y}${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
  const span = { Q: 3, H: 5, Y: 8 }[term];
  return { start: `${y - span}${end.slice(4)}`, end };
};

/** 한 회사·한 통계표를 분기→반기→연 순으로 받아 계정별 시계열로 만든다. */
async function fisisTable(env, ctx, finance, listNo, accountCd = "") {
  return fisisCached(ctx, `table/${finance}/${listNo}/${accountCd}`, accountCd ? 604800 : 43200, async () => {
    for (const term of FISIS_TERMS) {
      const { start, end } = monthsBack(term);
      const params = { financeCd: finance, listNo, term, startBaseMm: start, endBaseMm: end };
      if (accountCd) params.accountCd = accountCd;
      const r = await fisisCall(env, "statisticsInfoSearch", params);
      const rows = rowsOf(r.list);
      if (!rows.length) continue;
      const desc = r.description || {};
      const cols = rowsOf(desc.column ?? desc).map((c) => ({ id: String(c.column_id || ""), name: String(c.column_nm || "") })).filter((c) => c.id);
      const columns = cols.length ? cols : [{ id: "a", name: "" }];
      const accounts = {};
      for (const row of rows) {
        const key = String(row.account_cd ?? "");
        const a = (accounts[key] ||= { name: String(row.account_nm || ""), points: [] });
        const v = {};
        const raw = {};
        for (const c of columns) {
          v[c.id] = num(row[c.id]);
          raw[c.id] = row[c.id] == null ? "" : String(row[c.id]).trim();
        }
        a.points.push({ month: String(row.base_month || ""), v, raw });
      }
      for (const a of Object.values(accounts)) a.points.sort((x, y) => x.month.localeCompare(y.month));
      return { term, unit: String(desc.unit || ""), name: String((rows[0] && rows[0].finance_nm) || ""), columns, accounts };
    }
    return { term: "", unit: "", name: "", columns: [], accounts: {} };
  });
}

/** 설정에 맞는 열을 고른다(열 이름 패턴이 있으면 그 열, 없으면 첫 열). 없으면 null. */
function pickColumn(table, colSource) {
  if (!colSource) return table.columns[0] || null;
  const re = new RegExp(colSource);
  return table.columns.find((c) => re.test(c.name)) || null;
}

async function fisisProfile(env, ctx, sector, finance) {
  const spec = await resolveFisisSpec(env, ctx, sector);
  const requests = [
    ...Object.entries(spec.basis).flatMap(([basis, list]) => list.map((m) => ({ ...m, group: "fin", basis }))),
    ...spec.ratios.map((m) => ({ ...m, group: "ratio", basis: "" })),
    ...spec.funds.map((m) => ({ ...m, group: "fund", basis: "" })),
    ...spec.info.map((m) => ({ ...m, group: "info", basis: "" })),
  ];
  const listNos = [...new Set(requests.map((m) => m.listNo))];
  const tables = new Map();
  await inChunks(listNos, 6, async (l) => tables.set(l, await fisisTable(env, ctx, finance, l).catch(() => null)));
  let name = "";
  let term = "";
  const metrics = [];
  for (const m of requests) {
    const t = tables.get(m.listNo);
    if (!t || !t.term) continue;
    name = name || t.name;
    const account = t.accounts[m.accountCd];
    const column = pickColumn(t, m.col);
    if (!account || !column) continue;
    const text = m.group === "info";
    const points = account.points
      .map((p) => ({ month: p.month, value: p.v[column.id], text: text ? p.raw[column.id] : undefined }))
      .filter((p) => (text ? p.text : p.value !== null));
    if (!points.length) continue;
    if (m.group === "fin" && !term) term = t.term;
    metrics.push({
      key: m.key, label: m.label, group: m.group, basis: m.basis, unit: t.unit, term: t.term,
      column: t.columns.length > 1 ? column.name : "", source: `${m.listNm} · ${m.accountNm}`, points,
    });
  }
  const basisOptions = Object.keys(spec.basis);
  const dart = await dartProfile(env, name || (await fisisCompanyName(env, ctx, sector, finance))).catch(() => ({ note: "DART 조회 실패", metrics: [] }));
  for (const m of dart.metrics) {
    if (m.group === "info" && metrics.some((x) => x.key === m.key)) continue; // FISIS 값이 있으면 그대로 둔다
    metrics.push(m);
    if (m.basis && !basisOptions.includes(m.basis)) basisOptions.push(m.basis);
  }
  return { name, term, basisOptions, metrics, dart: dart.note };
}

async function fisisCompanyName(env, ctx, sector, finance) {
  const { items } = await fisisCached(ctx, `companies/${sector}`, 86400, () => fisisCompanies(env, sector));
  return (items.find((c) => c.code === finance) || {}).name || "";
}

/** 총자산 순위용: 회사 30곳씩 최근 총자산을 가져온다. 앱이 next가 없을 때까지 이어서 부른다. */
async function fisisRanking(env, ctx, sector, offset) {
  const { items } = await fisisCached(ctx, `companies/${sector}`, 86400, () => fisisCompanies(env, sector));
  const spec = await resolveFisisSpec(env, ctx, sector);
  const ref = (spec.basis["별도"] || Object.values(spec.basis)[0] || []).find((m) => m.key === "assets");
  const batch = items.slice(offset, offset + 30);
  const assets = {};
  if (ref) {
    await inChunks(batch, 6, async (c) => {
      const t = await fisisTable(env, ctx, c.code, ref.listNo, ref.accountCd).catch(() => null);
      const a = t && t.accounts[ref.accountCd];
      const column = t && pickColumn(t, "");
      const last = a && column && [...a.points].reverse().find((p) => p.v[column.id] !== null);
      if (last) assets[c.code] = { value: last.v[column.id], month: last.month, unit: t.unit };
    });
  }
  return { assets, next: offset + 30 < items.length ? offset + 30 : null, total: items.length, ranked: Boolean(ref) };
}

// ---- OpenDART: 대표이사·최대주주(지분율)·연결 재무 ----
// FISIS 회사 이름으로 DART 회사를 찾는다(corp_code 목록은 배포할 때 만든다).
const normName = (n) => String(n || "").replace(/\(주\)|㈜|주식회사|\(유\)|유한회사/g, "").replace(/[^0-9A-Za-z가-힣]/g, "").toLowerCase();
let dartIndex = null;
function matchDartCorp(name) {
  const target = normName(name);
  if (target.length < 2 || !Array.isArray(dartCorps) || !dartCorps.length) return null;
  dartIndex ||= dartCorps.map(([n, code, stock]) => ({ n, norm: normName(n), code, listed: Boolean(stock) }));
  const exact = dartIndex.filter((e) => e.norm === target);
  if (exact.length) return exact.find((e) => e.listed) || exact[0];
  let best = null;
  let bestScore = 99;
  for (const e of dartIndex) {
    if (!(e.norm.includes(target) || (e.norm.length >= 4 && target.includes(e.norm)))) continue;
    const score = Math.abs(e.norm.length - target.length) - (e.listed ? 0.5 : 0);
    if (score < bestScore) { best = e; bestScore = score; }
  }
  return bestScore <= 6 ? best : null;
}

async function dartCall(env, name, params) {
  const q = new URLSearchParams({ crtfc_key: env.DART_API_KEY, ...params });
  const res = await fetch(`https://opendart.fss.or.kr/api/${name}.json?${q}`);
  const body = await res.json().catch(() => null);
  if (!body) return null;
  if (body.status === "020") throw new Error("DART 일일 호출 한도를 넘었습니다.");
  return body.status === "000" ? body : null; // 013: 조회 결과 없음
}

const DART_MONTH = { 11013: "03", 11012: "06", 11014: "09", 11011: "12" };
const DART_FIN = [
  { key: "assets", label: "총자산", re: /^자산총계$/ },
  { key: "liab", label: "총부채", re: /^부채총계$/ },
  { key: "equity", label: "자기자본", re: /^자본총계$/ },
  { key: "capital", label: "자본금", re: /^자본금$/ },
  { key: "revenue", label: "영업수익", re: /^(영업수익|매출액|수익\(매출액\))$/ },
  { key: "netincome", label: "당기순이익", re: /^당기순이익(\(손실\))?$/ },
];

/** 대표이사·최대주주·지분율(공시 기준)과 연결 재무(주요계정)를 FISIS 지표와 같은 모양으로 돌려준다. */
async function dartProfile(env, name) {
  if (!env.DART_API_KEY) return { note: "", metrics: [] };
  const corp = matchDartCorp(name);
  if (!corp) return { note: "DART에서 같은 이름의 회사를 찾지 못했습니다", metrics: [] };
  const y = new Date().getUTCFullYear();
  const periods = [[y, 11014], [y, 11012], [y, 11013], [y - 1, 11011], [y - 1, 11014], [y - 2, 11011]].map(([year, r]) => ({ year, r, month: `${year}${DART_MONTH[r]}` }));
  const base = { corp_code: corp.code };
  const [company, holder, fins] = await Promise.all([
    dartCall(env, "company", base).catch(() => null),
    (async () => {
      for (const p of periods) {
        const b = await dartCall(env, "hyslrSttus", { ...base, bsns_year: String(p.year), reprt_code: String(p.r) }).catch(() => null);
        if (b && rowsOf(b.list).length) return { p, rows: rowsOf(b.list) };
      }
      return null;
    })(),
    Promise.all(periods.slice(0, 4).map((p) => dartCall(env, "fnlttSinglAcnt", { ...base, bsns_year: String(p.year), reprt_code: String(p.r) }).then((b) => ({ p, b })).catch(() => ({ p, b: null })))),
  ]);
  const metrics = [];
  const info = (key, label, text, month, source) => metrics.push({ key, label, group: "info", basis: "", unit: "", term: "", column: "", source, points: [{ month, value: null, text }] });
  const thisMonth = `${y}${String(new Date().getUTCMonth() + 1).padStart(2, "0")}`;
  if (company && company.ceo_nm) info("ceo", "대표이사", String(company.ceo_nm).trim(), thisMonth, "OpenDART 기업개요");
  if (holder) {
    const top = holder.rows.find((r) => /본인/.test(r.relate || "")) || holder.rows[0];
    const pct = top && (num(top.trmend_posesn_stock_qota_rt) ?? num(top.bsis_posesn_stock_qota_rt));
    if (top && top.nm) info("holder", "최대주주", String(top.nm).trim(), holder.p.month, "OpenDART 최대주주 현황");
    if (pct !== null && pct !== undefined) info("holderPct", "최대주주 지분율", `${pct.toFixed(2)}%`, holder.p.month, "OpenDART 최대주주 현황");
  }
  // 연결 재무: 각 보고서의 당기·전기 금액을 시점별로 모은다(단위: 원).
  const series = new Map();
  for (const { p, b } of fins) {
    for (const row of b ? rowsOf(b.list) : []) {
      if (row.fs_div !== "CFS") continue;
      const spec = DART_FIN.find((d) => d.re.test(String(row.account_nm || "").trim()));
      if (!spec) continue;
      const points = series.get(spec.key) || new Map();
      const cur = num(row.thstrm_amount);
      const prev = num(row.frmtrm_amount);
      if (cur !== null) points.set(p.month, cur);
      if (prev !== null && !points.has(`${p.year - 1}${p.month.slice(4)}`)) points.set(`${p.year - 1}${p.month.slice(4)}`, prev);
      series.set(spec.key, points);
    }
  }
  for (const spec of DART_FIN) {
    const pts = series.get(spec.key);
    if (!pts || !pts.size) continue;
    metrics.push({
      key: spec.key, label: spec.label, group: "fin", basis: "연결", unit: "원", term: "Q", column: "", source: "OpenDART 주요계정(연결)",
      points: [...pts.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, value]) => ({ month, value })),
    });
  }
  return { note: `OpenDART: ${corp.n}`, metrics };
}
