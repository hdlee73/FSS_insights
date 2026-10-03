// 네이버 뉴스 검색 프록시(Cloudflare Worker). 네이버 키는 이 서버의 비밀값으로만 존재하고 앱에는 들어가지 않는다.
const NAVER = "https://openapi.naver.com/v1/search/news.json";

const clamp = (value, min, max, fallback) => {
  const n = parseInt(value, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname !== "/news" || request.method !== "GET") return new Response("Not found", { status: 404 });
    if (!env.APP_TOKEN || request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return new Response("Unauthorized", { status: 401 });
    }
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
  },
};
