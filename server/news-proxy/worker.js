// 감독 인사이트 서버(Cloudflare Worker)
//  - /news  : 네이버 뉴스 검색 프록시. 네이버 키는 이 서버의 비밀값으로만 존재한다.
//  - /board : "감독 및 검사 팁" 게시판(D1 = 글·댓글, R2 = 첨부 파일).
const NAVER = "https://openapi.naver.com/v1/search/news.json";
const MAX_FILE = 10 * 1024 * 1024;
const CATEGORIES = ["검사", "감독", "질문", "자료"];
const EXT_TYPES = {
  jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp",
  pdf: "application/pdf", txt: "text/plain; charset=utf-8", csv: "text/csv; charset=utf-8",
  doc: "application/msword", docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xls: "application/vnd.ms-excel", xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ppt: "application/vnd.ms-powerpoint", pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  hwp: "application/x-hwp", hwpx: "application/hwp+zip",
};
const REPORT_HIDE_AT = 3;

const clamp = (value, min, max, fallback) => {
  const n = parseInt(value, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
};
const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
const fail = (status, message) => json({ error: message }, status);
const clean = (text, max) => String(text ?? "").replace(/\u0000/g, "").trim().slice(0, max);

let schemaReady = false;
async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS posts (
      id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
      nick TEXT NOT NULL, device TEXT NOT NULL, created INTEGER NOT NULL,
      views INTEGER NOT NULL DEFAULT 0, reports INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS comments (
      id INTEGER PRIMARY KEY AUTOINCREMENT, post_id INTEGER NOT NULL, body TEXT NOT NULL,
      nick TEXT NOT NULL, device TEXT NOT NULL, created INTEGER NOT NULL)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS files (
      key TEXT PRIMARY KEY, post_id INTEGER, name TEXT NOT NULL, type TEXT NOT NULL,
      size INTEGER NOT NULL, device TEXT NOT NULL, created INTEGER NOT NULL)`),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_posts_created ON posts(created DESC)"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_comments_post ON comments(post_id)"),
  ]);
  schemaReady = true;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!env.APP_TOKEN || request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (url.pathname === "/news" && request.method === "GET") return news(url, env, ctx);
    if (url.pathname.startsWith("/board")) {
      if (!env.DB) return fail(501, "게시판 저장소(D1)가 연결되지 않았습니다.");
      try {
        await ensureSchema(env.DB);
        return await board(request, url, env);
      } catch (e) {
        return fail(500, `서버 오류: ${e.message}`);
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

async function board(request, url, env) {
  const db = env.DB;
  const device = clean(request.headers.get("X-Device-Id"), 64);
  if (!/^[A-Za-z0-9-]{16,64}$/.test(device)) return fail(400, "기기 식별값이 없습니다.");
  const path = url.pathname.replace(/^\/board/, "").replace(/\/+$/, "");
  const method = request.method;
  const now = Date.now();
  let m;

  if (path === "/posts" && method === "GET") {
    const category = url.searchParams.get("category") || "";
    const sort = url.searchParams.get("sort") || "new";
    const q = clean(url.searchParams.get("q"), 60);
    const before = clamp(url.searchParams.get("before"), 0, Number.MAX_SAFE_INTEGER, 0);
    const limit = clamp(url.searchParams.get("limit"), 1, 50, 30);
    const where = ["hidden = 0"];
    const args = [];
    if (CATEGORIES.includes(category)) { where.push("category = ?"); args.push(category); }
    if (sort === "mine") { where.push("device = ?"); args.push(device); }
    if (sort === "popular") { where.push("created > ?"); args.push(now - 7 * 86400000); }
    if (q) { where.push("(title LIKE ? OR body LIKE ?)"); args.push(`%${q}%`, `%${q}%`); }
    let order = "created DESC";
    if (sort === "popular") order = "(views + 5 * (SELECT COUNT(*) FROM comments c WHERE c.post_id = posts.id)) DESC, created DESC";
    else if (before) { where.push("created < ?"); args.push(before); }
    const sql = `SELECT id, category, title, nick, created, views,
        substr(body, 1, 90) AS preview,
        (SELECT COUNT(*) FROM comments c WHERE c.post_id = posts.id) AS comments,
        (SELECT COUNT(*) FROM files f WHERE f.post_id = posts.id) AS attachments,
        (SELECT key FROM files f WHERE f.post_id = posts.id AND f.type LIKE 'image/%' ORDER BY created LIMIT 1) AS thumb,
        (device = ?) AS mine
      FROM posts WHERE ${where.join(" AND ")} ORDER BY ${order} LIMIT ?`;
    const { results } = await db.prepare(sql).bind(device, ...args, limit).all();
    return json({ posts: results.map((r) => ({ ...r, mine: !!r.mine })) });
  }

  if (path === "/posts" && method === "POST") {
    const body = await request.json().catch(() => null);
    if (!body) return fail(400, "잘못된 요청입니다.");
    const title = clean(body.title, 100);
    const text = clean(body.body, 5000);
    const nick = clean(body.nick, 16) || "익명";
    const category = CATEGORIES.includes(body.category) ? body.category : "감독";
    if (title.length < 2 || text.length < 2) return fail(400, "제목과 내용을 입력해 주세요.");
    const recent = await db.prepare("SELECT COUNT(*) AS n FROM posts WHERE device = ? AND created > ?").bind(device, now - 3600000).first();
    if (recent.n >= 10) return fail(429, "글을 너무 자주 올리고 있습니다. 잠시 후 다시 시도해 주세요.");
    const res = await db.prepare("INSERT INTO posts (category, title, body, nick, device, created) VALUES (?,?,?,?,?,?)")
      .bind(category, title, text, nick, device, now).run();
    const id = res.meta.last_row_id;
    const keys = Array.isArray(body.files) ? body.files.slice(0, 5).map((k) => clean(k, 80)) : [];
    for (const key of keys) {
      await db.prepare("UPDATE files SET post_id = ? WHERE key = ? AND device = ? AND post_id IS NULL").bind(id, key, device).run();
    }
    return json({ id });
  }

  if ((m = path.match(/^\/posts\/(\d+)$/))) {
    const id = Number(m[1]);
    if (method === "GET") {
      const post = await db.prepare("SELECT id, category, title, body, nick, created, views, (device = ?) AS mine FROM posts WHERE id = ? AND hidden = 0").bind(device, id).first();
      if (!post) return fail(404, "삭제되었거나 없는 글입니다.");
      await db.prepare("UPDATE posts SET views = views + 1 WHERE id = ?").bind(id).run();
      const files = await db.prepare("SELECT key, name, type, size FROM files WHERE post_id = ? ORDER BY created").bind(id).all();
      const comments = await db.prepare("SELECT id, body, nick, created, (device = ?) AS mine FROM comments WHERE post_id = ? ORDER BY created").bind(device, id).all();
      return json({
        post: { ...post, mine: !!post.mine, views: post.views + 1 },
        files: files.results,
        comments: comments.results.map((c) => ({ ...c, mine: !!c.mine })),
      });
    }
    if (method === "DELETE") {
      const post = await db.prepare("SELECT device FROM posts WHERE id = ?").bind(id).first();
      if (!post) return fail(404, "없는 글입니다.");
      if (post.device !== device) return fail(403, "내가 쓴 글만 삭제할 수 있습니다.");
      const files = await db.prepare("SELECT key FROM files WHERE post_id = ?").bind(id).all();
      if (env.FILES) for (const f of files.results) await env.FILES.delete(f.key);
      await db.batch([
        db.prepare("DELETE FROM files WHERE post_id = ?").bind(id),
        db.prepare("DELETE FROM comments WHERE post_id = ?").bind(id),
        db.prepare("DELETE FROM posts WHERE id = ?").bind(id),
      ]);
      return json({ ok: true });
    }
  }

  if ((m = path.match(/^\/posts\/(\d+)\/comments$/)) && method === "POST") {
    const id = Number(m[1]);
    const body = await request.json().catch(() => null);
    const text = clean(body && body.body, 1000);
    const nick = clean(body && body.nick, 16) || "익명";
    if (!text) return fail(400, "댓글 내용을 입력해 주세요.");
    const exists = await db.prepare("SELECT id FROM posts WHERE id = ? AND hidden = 0").bind(id).first();
    if (!exists) return fail(404, "없는 글입니다.");
    const recent = await db.prepare("SELECT COUNT(*) AS n FROM comments WHERE device = ? AND created > ?").bind(device, now - 3600000).first();
    if (recent.n >= 40) return fail(429, "댓글을 너무 자주 달고 있습니다. 잠시 후 다시 시도해 주세요.");
    await db.prepare("INSERT INTO comments (post_id, body, nick, device, created) VALUES (?,?,?,?,?)").bind(id, text, nick, device, now).run();
    return json({ ok: true });
  }

  if ((m = path.match(/^\/comments\/(\d+)$/)) && method === "DELETE") {
    const c = await db.prepare("SELECT device FROM comments WHERE id = ?").bind(Number(m[1])).first();
    if (!c) return fail(404, "없는 댓글입니다.");
    if (c.device !== device) return fail(403, "내가 쓴 댓글만 삭제할 수 있습니다.");
    await db.prepare("DELETE FROM comments WHERE id = ?").bind(Number(m[1])).run();
    return json({ ok: true });
  }

  if ((m = path.match(/^\/posts\/(\d+)\/report$/)) && method === "POST") {
    const id = Number(m[1]);
    await db.prepare("UPDATE posts SET reports = reports + 1, hidden = CASE WHEN reports + 1 >= ? THEN 1 ELSE hidden END WHERE id = ?").bind(REPORT_HIDE_AT, id).run();
    return json({ ok: true });
  }

  if (path === "/upload" && method === "POST") {
    if (!env.FILES) return fail(501, "첨부 저장소(R2)가 연결되지 않았습니다.");
    const name = clean(url.searchParams.get("name"), 120).replace(/[\\/:*?"<>|]/g, "_") || "file";
    const ext = (name.split(".").pop() || "").toLowerCase();
    const type = EXT_TYPES[ext];
    if (!type) return fail(415, "지원하지 않는 파일 형식입니다. (이미지, PDF, 오피스·한글 문서, 텍스트)");
    const length = Number(request.headers.get("Content-Length") || 0);
    if (length > MAX_FILE) return fail(413, "파일은 10MB까지 올릴 수 있습니다.");
    const recent = await db.prepare("SELECT COUNT(*) AS n FROM files WHERE device = ? AND created > ?").bind(device, now - 3600000).first();
    if (recent.n >= 30) return fail(429, "파일을 너무 많이 올렸습니다. 잠시 후 다시 시도해 주세요.");
    const data = await request.arrayBuffer();
    if (data.byteLength === 0 || data.byteLength > MAX_FILE) return fail(413, "파일은 10MB까지 올릴 수 있습니다.");
    const key = `${crypto.randomUUID()}.${ext}`;
    await env.FILES.put(key, data, { httpMetadata: { contentType: type } });
    await db.prepare("INSERT INTO files (key, post_id, name, type, size, device, created) VALUES (?,?,?,?,?,?,?)")
      .bind(key, null, name, type, data.byteLength, device, now).run();
    return json({ key, name, type, size: data.byteLength });
  }

  if ((m = path.match(/^\/files\/([A-Za-z0-9.-]+)$/)) && method === "GET") {
    if (!env.FILES) return fail(501, "첨부 저장소(R2)가 연결되지 않았습니다.");
    const meta = await db.prepare("SELECT f.name, f.type FROM files f LEFT JOIN posts p ON p.id = f.post_id WHERE f.key = ? AND (p.hidden = 0 OR f.post_id IS NULL)").bind(m[1]).first();
    if (!meta) return fail(404, "없는 파일입니다.");
    const object = await env.FILES.get(m[1]);
    if (!object) return fail(404, "없는 파일입니다.");
    return new Response(object.body, {
      headers: {
        "Content-Type": meta.type,
        "Content-Disposition": `inline; filename*=UTF-8''${encodeURIComponent(meta.name)}`,
        "Cache-Control": "private, max-age=86400",
        "X-Content-Type-Options": "nosniff",
      },
    });
  }

  return fail(404, "Not found");
}
