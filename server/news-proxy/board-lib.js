// Cloudflare Worker 익명게시판 백엔드 (FSS_insights/server/news-proxy 의 env.DB + env.ADMIN_TOKEN 공용)
// 기존 news-proxy/worker.js 에 handleBoard(request, env) 를 연결하거나 단독 Worker로 배포할 수 있습니다.

const ALLOWED_ORIGINS = new Set([
  "https://www.fssinsights.com",
  "https://fssinsights.com",
  "https://hdlee73.github.io",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
]);

function makeCors(request, env) {
  const origin = request.headers.get("Origin") || "";
  const extra = String(env.WEB_ORIGINS || env.ALLOWED_ORIGIN || "").split(",").map((s) => s.trim()).filter(Boolean);
  const allow = ALLOWED_ORIGINS.has(origin) || extra.includes(origin) ? origin : "https://www.fssinsights.com";
  return {
    "Access-Control-Allow-Origin": allow,
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Admin-Token",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(data, status = 200, cors = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...cors },
  });
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

function isAdmin(request, env) {
  const tok = request.headers.get("X-Admin-Token") || "";
  return !!(env.ADMIN_TOKEN && tok && timingSafeEqual(tok, env.ADMIN_TOKEN));
}

let ready = false;
async function ensure(db) {
  if (ready) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS board_posts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      is_notice INTEGER NOT NULL DEFAULT 0,
      title TEXT NOT NULL,
      body TEXT NOT NULL,
      author TEXT NOT NULL DEFAULT '익명',
      pw_hash TEXT,
      created_at TEXT NOT NULL
    )`),
    db.prepare(`CREATE INDEX IF NOT EXISTS idx_board_posts_notice ON board_posts (is_notice DESC, id DESC)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS board_comments (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      post_id INTEGER NOT NULL,
      author TEXT NOT NULL DEFAULT '익명',
      body TEXT NOT NULL,
      pw_hash TEXT,
      created_at TEXT NOT NULL
    )`),
    db.prepare(`CREATE INDEX IF NOT EXISTS idx_board_comments_post ON board_comments (post_id, id ASC)`),
  ]);
  ready = true;
}

async function hashPassword(pw) {
  if (!pw) return null;
  const saltBytes = crypto.getRandomValues(new Uint8Array(8));
  const salt = [...saltBytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${salt}:${pw}`));
  const hash = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${salt}$${hash}`;
}

async function verifyPassword(pw, stored) {
  if (!pw || !stored || !stored.includes("$")) return false;
  const [salt, expected] = stored.split("$");
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${salt}:${pw}`));
  const actual = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return timingSafeEqual(actual, expected);
}

export async function handleBoard(request, env, corsHeaders) {
  const url = new URL(request.url);
  const path = url.pathname;
  const cors = corsHeaders || makeCors(request, env);

  if (!path.startsWith("/board/")) return null;
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

  const db = env.DB;
  if (!db) return json({ error: "D1(DB) 바인딩이 연결되지 않았습니다." }, 503, cors);
  await ensure(db);

  // POST /board/admin/verify
  if (path === "/board/admin/verify" && request.method === "POST") {
    if (!isAdmin(request, env)) return json({ error: "관리자 토큰이 올바르지 않습니다." }, 403, cors);
    return json({ ok: true }, 200, cors);
  }

  // GET /board/posts
  if (path === "/board/posts" && request.method === "GET") {
    const page = Math.max(1, Number(url.searchParams.get("page") || 1));
    const pageSize = 20;
    const offset = (page - 1) * pageSize;
    const q = (url.searchParams.get("q") || "").trim();

    const noticesRes = await db.prepare(
      `SELECT p.id, p.is_notice, p.title, p.author, p.created_at,
              (SELECT COUNT(*) FROM board_comments c WHERE c.post_id = p.id) AS comment_count
       FROM board_posts p
       WHERE p.is_notice = 1
       ORDER BY p.id DESC
       LIMIT 10`
    ).all();

    let countRow, postsRes;
    if (q) {
      const like = `%${q}%`;
      countRow = await db.prepare(
        `SELECT COUNT(*) AS cnt FROM board_posts WHERE is_notice = 0 AND (title LIKE ? OR body LIKE ?)`
      ).bind(like, like).first();
      postsRes = await db.prepare(
        `SELECT p.id, p.is_notice, p.title, p.author, p.created_at,
                (SELECT COUNT(*) FROM board_comments c WHERE c.post_id = p.id) AS comment_count
         FROM board_posts p
         WHERE p.is_notice = 0 AND (p.title LIKE ? OR p.body LIKE ?)
         ORDER BY p.id DESC
         LIMIT ? OFFSET ?`
      ).bind(like, like, pageSize, offset).all();
    } else {
      countRow = await db.prepare(`SELECT COUNT(*) AS cnt FROM board_posts WHERE is_notice = 0`).first();
      postsRes = await db.prepare(
        `SELECT p.id, p.is_notice, p.title, p.author, p.created_at,
                (SELECT COUNT(*) FROM board_comments c WHERE c.post_id = p.id) AS comment_count
         FROM board_posts p
         WHERE p.is_notice = 0
         ORDER BY p.id DESC
         LIMIT ? OFFSET ?`
      ).bind(pageSize, offset).all();
    }

    return json({
      notices: noticesRes.results || [],
      posts: postsRes.results || [],
      total: countRow?.cnt || 0,
      page,
      pageSize,
    }, 200, cors);
  }

  // POST /board/posts
  if (path === "/board/posts" && request.method === "POST") {
    const bodyJson = await request.json().catch(() => null);
    if (!bodyJson) return json({ error: "잘못된 요청 형식입니다." }, 400, cors);

    const wantNotice = !!bodyJson.is_notice;
    if (wantNotice && !isAdmin(request, env)) {
      return json({ error: "공지사항은 관리자만 작성할 수 있습니다." }, 403, cors);
    }

    const title = String(bodyJson.title || "").trim().slice(0, 120);
    const body = String(bodyJson.body || "").trim().slice(0, 5000);
    const author = (String(bodyJson.author || "").trim() || (wantNotice ? "관리자" : "익명")).slice(0, 20);
    const password = String(bodyJson.password || "");

    if (!title || !body) return json({ error: "제목과 내용을 모두 입력해 주세요." }, 400, cors);
    if (password && password.length < 4) return json({ error: "비밀번호는 4자 이상이어야 합니다." }, 400, cors);

    const pwHash = wantNotice ? null : await hashPassword(password);
    const now = new Date().toISOString();
    const res = await db.prepare(
      `INSERT INTO board_posts (is_notice, title, body, author, pw_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(wantNotice ? 1 : 0, title, body, author, pwHash, now).run();

    return json({ id: res.meta.last_row_id, ok: true }, 201, cors);
  }

  // GET/DELETE /board/posts/:id
  const postMatch = /^\/board\/posts\/(\d+)$/.exec(path);
  if (postMatch) {
    const id = Number(postMatch[1]);
    if (request.method === "GET") {
      const post = await db.prepare(
        `SELECT id, is_notice, title, body, author, created_at FROM board_posts WHERE id = ?`
      ).bind(id).first();
      if (!post) return json({ error: "글을 찾을 수 없습니다." }, 404, cors);
      const comments = await db.prepare(
        `SELECT id, post_id, author, body, created_at FROM board_comments WHERE post_id = ? ORDER BY id ASC`
      ).bind(id).all();
      return json({ post, comments: comments.results || [] }, 200, cors);
    }
    if (request.method === "DELETE") {
      const post = await db.prepare(`SELECT id, is_notice, pw_hash FROM board_posts WHERE id = ?`).bind(id).first();
      if (!post) return json({ error: "글을 찾을 수 없습니다." }, 404, cors);
      const admin = isAdmin(request, env);
      if (post.is_notice && !admin) {
        return json({ error: "공지사항은 관리자만 삭제할 수 있습니다." }, 403, cors);
      }
      if (!admin) {
        const bodyJson = await request.json().catch(() => ({}));
        const ok = await verifyPassword(String(bodyJson?.password || ""), post.pw_hash);
        if (!ok) return json({ error: "비밀번호가 일치하지 않습니다." }, 403, cors);
      }
      await db.prepare(`DELETE FROM board_comments WHERE post_id = ?`).bind(id).run();
      await db.prepare(`DELETE FROM board_posts WHERE id = ?`).bind(id).run();
      return json({ ok: true }, 200, cors);
    }
  }

  // POST /board/posts/:id/comments
  const cmtPostMatch = /^\/board\/posts\/(\d+)\/comments$/.exec(path);
  if (cmtPostMatch && request.method === "POST") {
    const postId = Number(cmtPostMatch[1]);
    const exists = await db.prepare(`SELECT id FROM board_posts WHERE id = ?`).bind(postId).first();
    if (!exists) return json({ error: "원글을 찾을 수 없습니다." }, 404, cors);

    const bodyJson = await request.json().catch(() => null);
    if (!bodyJson) return json({ error: "잘못된 요청 형식입니다." }, 400, cors);
    const body = String(bodyJson.body || "").trim().slice(0, 1000);
    const author = (String(bodyJson.author || "").trim() || "익명").slice(0, 20);
    const password = String(bodyJson.password || "");
    if (!body) return json({ error: "댓글 내용을 입력해 주세요." }, 400, cors);
    if (password && password.length < 4) return json({ error: "비밀번호는 4자 이상이어야 합니다." }, 400, cors);

    const pwHash = await hashPassword(password);
    const now = new Date().toISOString();
    const res = await db.prepare(
      `INSERT INTO board_comments (post_id, author, body, pw_hash, created_at) VALUES (?, ?, ?, ?, ?)`
    ).bind(postId, author, body, pwHash, now).run();
    return json({ id: res.meta.last_row_id, ok: true }, 201, cors);
  }

  // DELETE /board/comments/:id
  const cmtDelMatch = /^\/board\/comments\/(\d+)$/.exec(path);
  if (cmtDelMatch && request.method === "DELETE") {
    const cid = Number(cmtDelMatch[1]);
    const cmt = await db.prepare(`SELECT id, pw_hash FROM board_comments WHERE id = ?`).bind(cid).first();
    if (!cmt) return json({ error: "댓글을 찾을 수 없습니다." }, 404, cors);
    if (!isAdmin(request, env)) {
      const bodyJson = await request.json().catch(() => ({}));
      const ok = await verifyPassword(String(bodyJson?.password || ""), cmt.pw_hash);
      if (!ok) return json({ error: "비밀번호가 일치하지 않습니다." }, 403, cors);
    }
    await db.prepare(`DELETE FROM board_comments WHERE id = ?`).bind(cid).run();
    return json({ ok: true }, 200, cors);
  }

  return json({ error: "지원하지 않는 게시판 경로입니다." }, 404, cors);
}

export default {
  async fetch(request, env) {
    const cors = makeCors(request, env);
    const res = await handleBoard(request, env, cors);
    return res || json({ error: "Not Found" }, 404, cors);
  },
};
