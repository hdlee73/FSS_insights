// 공용 상류 응답 공유 캐시. 같은 자료를 여러 사용자가 요청해도 외부(상류) 호출은 한 번만 하도록 한다.
//  1) 같은 서버 인스턴스(isolate) 안의 같은 요청이 동시에 오면 하나로 합친다(in-flight 합치기).
//  2) 성공 응답은 인스턴스 메모리에 ttl 동안 보관한다(Cache API 호출·상류 호출을 아낌).
// 사용자별·관리자 응답에는 쓰지 않는다(호출하는 쪽에서 공용 자료만 넘긴다).
// 응답 본문은 한 번 읽어 스냅샷(상태·머리글·바이트)으로 만들고, 호출마다 새 Response 로 돌려준다(본문 스트림 재사용 오류 방지).

const MAX_ENTRIES = 100;
const MAX_BYTES = 256 * 1024; // 이보다 큰 응답은 메모리에 두지 않는다(합치기만 한다). 최대 약 25MB

const hot = new Map(); // key -> { at, snap }
const inflight = new Map(); // key -> Promise<snap>

/** Response → 스냅샷. 필요한 머리글만 보관한다. */
export async function snapOf(res, keep = ["content-type", "cache-control", "x-final-url"]) {
  const headers = {};
  for (const k of keep) {
    const v = res.headers.get(k);
    if (v != null) headers[k] = v;
  }
  return { status: res.status, headers, body: await res.arrayBuffer() };
}

/** 스냅샷 → 새 Response(호출마다 따로). */
export function toResponse(snap, extra = {}) {
  return new Response(snap.body, { status: snap.status, headers: { ...snap.headers, ...extra } });
}

/**
 * key 의 스냅샷을 돌려준다. 메모리에 ttlMs 이내 값이 있으면 그것을, 같은 key 를 만드는 중이면 그 결과를 기다리고, 아니면 build() 로 만든다.
 * build 는 스냅샷({status, headers, body})을 돌려준다. status 가 2xx 가 아니거나 volatile 표시가 있거나 body 가 크면 메모리에는 두지 않는다(합치기는 적용).
 * build 가 던진 오류는 기다린 모든 호출에 전달되고 기억하지 않는다.
 */
export async function shared(key, ttlMs, build, now = Date.now()) {
  const h = hot.get(key);
  if (h && now - h.at < ttlMs) return h.snap;
  const pending = inflight.get(key);
  if (pending) return pending;
  const p = (async () => {
    const snap = await build();
    if (!snap.volatile && snap.status >= 200 && snap.status < 300 && snap.body.byteLength <= MAX_BYTES && ttlMs > 0) {
      hot.set(key, { at: Date.now(), snap });
      if (hot.size > MAX_ENTRIES) hot.delete(hot.keys().next().value);
    }
    return snap;
  })();
  inflight.set(key, p);
  try {
    return await p;
  } finally {
    inflight.delete(key);
  }
}

/** 시험용: 기억한 값을 모두 비운다. */
export function resetShared() {
  hot.clear();
  inflight.clear();
}

/** Cache-Control 머리글: 신선 기간 뒤에도 swr 초 동안은 낡은 값을 먼저 보여 주고 뒤에서 새로 받게 한다(브라우저 HTTP 캐시). */
export const cacheControl = (maxAge, swr = 0) => `public, max-age=${maxAge}` + (swr > 0 ? `, stale-while-revalidate=${swr}` : "");
