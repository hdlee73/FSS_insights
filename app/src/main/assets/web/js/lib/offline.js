// 오프라인 저장: 마지막으로 불러온 기사·보도자료 목록을 이 브라우저(IndexedDB)에 저장해 두었다가,
// 네트워크가 끊겼을 때 이전 목록을 그대로 보여 준다. 또 Ctrl+K 검색이 훑는 자료의 출처이기도 하다.
import { cached, markStale } from "../core.js";

const DB = "fss-offline";
const STORE = "lists";
let dbp = null;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    if (!("indexedDB" in window)) return reject(new Error("no idb"));
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new Error("blocked"));
  });
  dbp.catch(() => (dbp = null)); // 막힌 브라우저(시크릿 창 등)에서는 저장 없이 계속
  return dbp;
}

const wrap = (mode, fn) =>
  open().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode);
    const out = fn(tx.objectStore(STORE));
    tx.oncomplete = () => resolve(out.result);
    tx.onerror = tx.onabort = () => reject(tx.error);
  }));

export const idbGet = (key) => wrap("readonly", (s) => s.get(key)).catch(() => undefined);
export const idbSet = (key, value) => wrap("readwrite", (s) => s.put(value, key)).catch(() => {});

/** prefix로 시작하는 저장본을 모두 [{key, at, value}]로. */
export async function idbAll(prefix) {
  try {
    const db = await open();
    return await new Promise((resolve) => {
      const out = [];
      const req = db.transaction(STORE, "readonly").objectStore(STORE).openCursor();
      req.onsuccess = () => {
        const c = req.result;
        if (!c) return resolve(out);
        if (String(c.key).startsWith(prefix)) out.push({ key: c.key, ...c.value });
        c.continue();
      };
      req.onerror = () => resolve(out);
    });
  } catch {
    return [];
  }
}

/** core.cached와 같지만, 성공하면 저장해 두고 실패(연결 끊김 등)하면 저장본으로 대신한다. 저장본을 쓰면 헤더에 '저장본'이 표시된다. */
export async function cachedOffline(key, ttlMs, load) {
  try {
    return await cached(key, ttlMs, async () => {
      const value = await load();
      if (value != null) idbSet(key, { at: Date.now(), value });
      return value;
    });
  } catch (e) {
    const hit = await idbGet(key);
    if (hit && hit.value != null) {
      markStale(hit.at);
      return hit.value;
    }
    throw e;
  }
}
