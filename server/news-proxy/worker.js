// FSS Insights 서버(Cloudflare Worker)
//  - /news    : 네이버 뉴스 검색 프록시. 네이버 키는 이 서버의 비밀값으로만 존재한다.
//  - /library : 자료실. 구글 드라이브의 공유 폴더 목록·파일을 읽기 전용으로 중계한다.
//  - /library/upload : 자료 올리기. 서비스 계정이 비공개 "업로드 대기" 폴더에만 올린다(관리자가 확인해 자료실로 옮김).
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
    for (const item of index.files.values()) {
      if (item.folder) continue;
      const haystack = `${item.name} ${item.description} ${item.tags.join(" ")}`.toLowerCase();
      const ok = tagQuery !== null ? item.tags.some((t) => t.toLowerCase() === tagQuery) : words.every((w) => haystack.includes(w));
      if (ok) hits.push({ ...item, location: index.names.get(item.parent) || "" });
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
const UPLOAD_MAX = 20 * 1024 * 1024;
const UPLOAD_ALLOWED = new Set(["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "txt", "csv", "png", "jpg", "jpeg"]);

const b64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

// 서비스 계정 키로 드라이브 쓰기용 액세스 토큰을 만든다(잠시 기억).
let tokenCache = { at: 0, token: "" };
async function accessToken(env) {
  if (tokenCache.token && Date.now() - tokenCache.at < 50 * 60 * 1000) return tokenCache.token;
  const key = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON);
  const now = Math.floor(Date.now() / 1000);
  const enc = new TextEncoder();
  const head = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64url(enc.encode(JSON.stringify({
    iss: key.client_email, scope: "https://www.googleapis.com/auth/drive",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  })));
  const der = Uint8Array.from(atob(key.private_key.replace(/-----[^-]+-----|\s/g, "")), (c) => c.charCodeAt(0));
  const pk = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pk, enc.encode(`${head}.${claim}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claim}.${b64url(sig)}` }),
  });
  if (!res.ok) throw new Error(`구글 인증 ${res.status}`);
  tokenCache = { at: Date.now(), token: (await res.json()).access_token };
  return tokenCache.token;
}

async function upload(request, url, env) {
  if (!env.GOOGLE_SERVICE_ACCOUNT_JSON || !env.GDRIVE_UPLOAD_FOLDER_ID) return fail(501, "자료 올리기가 아직 연결되지 않았습니다.");
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
  const boundary = "fss" + crypto.randomUUID().replace(/-/g, "");
  const meta = JSON.stringify({ name, description, parents: [env.GDRIVE_UPLOAD_FOLDER_ID] });
  const enc = new TextEncoder();
  const type = request.headers.get("Content-Type") || "application/octet-stream";
  const parts = [
    enc.encode(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\nContent-Type: ${type}\r\n\r\n`),
    new Uint8Array(body),
    enc.encode(`\r\n--${boundary}--`),
  ];
  const payload = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { payload.set(p, at); at += p.length; }
  const res = await fetch("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id", {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken(env)}`, "Content-Type": `multipart/related; boundary=${boundary}` },
    body: payload,
  });
  if (!res.ok) {
    let reason = "";
    try {
      const e = (await res.json()).error || {};
      reason = e.errors?.[0]?.reason || e.status || "";
    } catch { /* 본문 없음 */ }
    const hint = {
      accessNotConfigured: "구글 클라우드에서 Google Drive API를 사용 설정해 주세요.",
      storageQuotaExceeded: "서비스 계정은 저장 용량이 없어 개인 드라이브 폴더에는 올릴 수 없습니다. 공유 드라이브 폴더가 필요합니다.",
      notFound: "업로드 대기 폴더를 찾을 수 없습니다. 폴더 ID와 서비스 계정 공유를 확인해 주세요.",
    }[reason] || "업로드 대기 폴더가 서비스 계정에 편집자로 공유되어 있는지 확인해 주세요.";
    return fail(502, `드라이브에 올리지 못했습니다(${res.status}${reason ? `, ${reason}` : ""}). ${hint}`);
  }
  return json({ ok: true });
}
