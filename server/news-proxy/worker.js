// 감독 인사이트 서버(Cloudflare Worker)
//  - /news    : 네이버 뉴스 검색 프록시. 네이버 키는 이 서버의 비밀값으로만 존재한다.
//  - /library : 자료실. 구글 드라이브의 공유 폴더 목록·파일을 읽기 전용으로 중계한다.
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

async function listFolder(folder, env, fields, extra = "") {
  const res = await drive("", env, {
    q: `'${folder}' in parents and trashed = false${extra}`,
    fields: `files(${fields})`,
    orderBy: "folder,name_natural",
    pageSize: "300",
  });
  if (!res.ok) throw new Error(`드라이브 응답 ${res.status}`);
  return (await res.json()).files || [];
}

// 지정한 루트 폴더와 그 아래 하위 폴더(최대 4단계, 60개)의 ID 목록. 1분간 기억한다.
// (API 키로는 파일의 parents 값을 읽을 수 없어서 위에서부터 내려가며 확인한다.)
let allowedCache = { at: 0, root: "", ids: new Set() };
async function allowedFolders(env) {
  if (allowedCache.root === env.GDRIVE_FOLDER_ID && Date.now() - allowedCache.at < 60000) return allowedCache.ids;
  const ids = new Set([env.GDRIVE_FOLDER_ID]);
  let level = [env.GDRIVE_FOLDER_ID];
  for (let depth = 0; depth < 4 && level.length && ids.size < 60; depth++) {
    const next = [];
    for (const folder of level) {
      const subs = await listFolder(folder, env, "id", ` and mimeType = '${FOLDER}'`);
      for (const f of subs) if (!ids.has(f.id) && ids.size < 60) { ids.add(f.id); next.push(f.id); }
    }
    level = next;
  }
  allowedCache = { at: Date.now(), root: env.GDRIVE_FOLDER_ID, ids };
  return ids;
}

async function library(url, env) {
  if (!env.GOOGLE_API_KEY || !env.GDRIVE_FOLDER_ID) return fail(501, "자료실(구글 드라이브)이 아직 연결되지 않았습니다.");
  const path = url.pathname.replace(/^\/library/, "").replace(/\/+$/, "");

  if (path === "/list") {
    const folder = url.searchParams.get("folder") || env.GDRIVE_FOLDER_ID;
    if (!ID.test(folder)) return fail(400, "잘못된 폴더입니다.");
    if (!(await allowedFolders(env)).has(folder)) return fail(403, "자료실 밖의 폴더입니다.");
    const res = await drive("", env, {
      q: `'${folder}' in parents and trashed = false`,
      fields: "files(id,name,mimeType,size,modifiedTime)",
      orderBy: "folder,name_natural",
      pageSize: "300",
    });
    if (!res.ok) return fail(res.status === 403 || res.status === 404 ? 502 : res.status, "드라이브 목록을 읽지 못했습니다. 폴더가 '링크가 있는 모든 사용자'로 공유되어 있는지, API 키가 맞는지 확인해 주세요.");
    const data = await res.json();
    const items = (data.files || []).map((f) => {
      const isFolder = f.mimeType === FOLDER;
      const exp = EXPORTS[f.mimeType];
      const native = f.mimeType.startsWith("application/vnd.google-apps.");
      if (!isFolder && native && !exp) return null; // 바로가기·양식 등 내려받을 수 없는 형식은 숨김
      return {
        id: f.id,
        name: f.name,
        folder: isFolder,
        type: exp ? exp[0] : f.mimeType,
        downloadName: exp && !f.name.toLowerCase().endsWith(exp[1]) ? f.name + exp[1] : f.name,
        size: Number(f.size || 0),
        modified: f.modifiedTime || "",
      };
    }).filter(Boolean);
    return json({ folder, root: folder === env.GDRIVE_FOLDER_ID, items });
  }

  const m = path.match(/^\/file\/([A-Za-z0-9_-]+)$/);
  if (m) {
    const id = m[1];
    if (!ID.test(id)) return fail(400, "잘못된 파일입니다.");
    // 앱이 목록에서 본 폴더(?folder=)를 알려 주면, 그 폴더가 자료실 안이고 파일이 실제로 그 안에 있을 때만 내준다.
    const parent = url.searchParams.get("folder") || "";
    if (!ID.test(parent) || !(await allowedFolders(env)).has(parent)) return fail(403, "자료실 밖의 파일입니다.");
    if (!(await listFolder(parent, env, "id")).some((f) => f.id === id)) return fail(403, "자료실 밖의 파일입니다.");
    const metaRes = await drive(`/${id}`, env, { fields: "name,mimeType,size" });
    if (!metaRes.ok) return fail(404, "파일을 찾을 수 없습니다.");
    const meta = await metaRes.json();
    if (meta.mimeType === FOLDER) return fail(400, "폴더는 내려받을 수 없습니다.");
    const exp = EXPORTS[meta.mimeType];
    const upstream = exp
      ? await drive(`/${id}/export`, env, { mimeType: exp[0] })
      : await drive(`/${id}`, env, { alt: "media" });
    if (!upstream.ok) return fail(502, "파일을 내려받지 못했습니다(구글 변환·전송 한도를 넘었을 수 있습니다).");
    const name = exp && !meta.name.toLowerCase().endsWith(exp[1]) ? meta.name + exp[1] : meta.name;
    const headers = {
      "Content-Type": exp ? exp[0] : meta.mimeType,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
      "X-Content-Type-Options": "nosniff",
    };
    const length = upstream.headers.get("Content-Length");
    if (length) headers["Content-Length"] = length;
    return new Response(upstream.body, { headers });
  }
  return fail(404, "Not found");
}
