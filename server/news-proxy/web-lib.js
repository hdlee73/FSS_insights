// 웹 버전(fss-insights-web, PWA) 지원.
// 웹은 소스가 공개되어 앱 토큰을 숨길 수 없다. 그래서 허용 출처(WEB_ORIGINS, 쉼표로 구분)에서 온 브라우저 요청만
// 토큰 없이 받고, 남용을 막으려고 IP별 요청 수를 제한한다(WEB_LIMIT 바인딩, wrangler.toml).
// 네이버 뉴스(/news)는 웹 범위가 아니어서 열지 않는다.

const WEB_PATHS = /^\/(law|dart|fisis|stats|library|fetch|usage\/ping)(\/|$)/;

// /fetch 가 대신 읽어 줄 수 있는 사이트(앱이 기기에서 직접 읽던 곳). 이 밖의 주소는 거절한다.
const FETCH_HOSTS = [
  "fss.or.kr", "fsc.go.kr", "kcmi.re.kr", "kif.re.kr", "iosco.org", "bis.org", "fsb.org", "esma.europa.eu",
  "sec.gov", "mas.gov.sg", "bok.or.kr", "mofe.go.kr", "moef.go.kr", "stock.naver.com", "finance.naver.com",
  "finance.yahoo.com", "kofia.or.kr", "kofiabond.or.kr", "krx.co.kr",
];
const MAX_BYTES = 4 * 1024 * 1024;
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

export function allowedHost(host) {
  return FETCH_HOSTS.some((h) => host === h || host.endsWith("." + h));
}

/** 허용 출처에서 온 웹 요청이면 { origin, check } 를, 아니면 null. check()는 한도를 넘었을 때 429 응답을 돌려준다. */
export function webAccess(request, url, env) {
  const origin = request.headers.get("Origin");
  if (!origin || !WEB_PATHS.test(url.pathname)) return null;
  const allowed = String(env.WEB_ORIGINS || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!allowed.includes(origin)) return null;
  return {
    origin,
    async check() {
      if (!env.WEB_LIMIT) return null;
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const { success } = await env.WEB_LIMIT.limit({ key: ip });
      return success ? null : new Response(JSON.stringify({ error: "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요." }), {
        status: 429,
        headers: { "Content-Type": "application/json; charset=utf-8", "Retry-After": "60" },
      });
    },
  };
}

export function corsPreflight(origin) {
  return new Response(null, {
    status: 204,
    headers: {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Max-Age": "86400",
      Vary: "Origin",
    },
  });
}

export function withCors(response, origin) {
  const out = new Response(response.body, response);
  out.headers.set("Access-Control-Allow-Origin", origin);
  out.headers.set("Access-Control-Expose-Headers", "Content-Disposition, X-Final-Url");
  out.headers.append("Vary", "Origin");
  return out;
}

/**
 * GET /fetch?url=… (POST는 본문을 그대로 넘김): 허용된 사이트의 응답을 바이트 그대로 돌려준다(문자 집합은 브라우저가 판단).
 * GET 결과는 10분간 캐시해 같은 목록을 여러 사람이 열어도 기관 사이트에 한 번만 간다.
 */
export async function proxyFetch(request, url, ctx) {
  let target;
  try {
    target = new URL(url.searchParams.get("url") || "");
  } catch {
    return new Response("url required", { status: 400 });
  }
  if (target.protocol !== "https:" && target.protocol !== "http:") return new Response("bad url", { status: 400 });
  if (!allowedHost(target.hostname)) return new Response("허용되지 않은 사이트입니다.", { status: 403 });
  const isPost = request.method === "POST";
  const cache = caches.default;
  const cacheKey = new Request(`https://fetch-cache.invalid/${encodeURIComponent(target.href)}`);
  if (!isPost) {
    const hit = await cache.match(cacheKey);
    if (hit) return hit;
  }
  const init = {
    method: isPost ? "POST" : "GET",
    redirect: "follow",
    headers: {
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.5",
      "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.5",
    },
  };
  if (isPost) {
    const body = await request.arrayBuffer();
    if (body.byteLength > 32 * 1024) return new Response("body too large", { status: 413 });
    init.body = body;
    init.headers["Content-Type"] = request.headers.get("Content-Type") || "application/x-www-form-urlencoded";
  }
  const upstream = await fetch(target.href, init);
  const finalUrl = upstream.url || target.href;
  if (!allowedHost(new URL(finalUrl).hostname)) return new Response("허용되지 않은 사이트로 이동했습니다.", { status: 403 });
  const bytes = await upstream.arrayBuffer();
  if (bytes.byteLength > MAX_BYTES) return new Response("응답이 너무 큽니다.", { status: 413 });
  const out = new Response(bytes, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") || "application/octet-stream",
      "Cache-Control": "public, max-age=600",
      "X-Final-Url": finalUrl,
    },
  });
  if (!isPost && upstream.ok) ctx.waitUntil(cache.put(cacheKey, out.clone()));
  return out;
}
