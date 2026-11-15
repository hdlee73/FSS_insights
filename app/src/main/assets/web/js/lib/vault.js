// 보관함(스크랩·메모)과 읽음 표시. 서버 없이 이 브라우저(localStorage)에만 저장한다.
// 기사 즐겨찾기(news.bookmarks)·보도자료/리포트 저장함(savedReleases)을 그대로 쓰고, 메모와 읽음만 더한다.
import { dialog, store, toast } from "../core.js";
import * as N from "./news.js";
import { agencyById } from "./agencies.js";

// ---- 읽음 ----
const READ_MAX = 3000;
let readSet = null;
const readers = () => (readSet ||= new Set(store.get("read.links", [])));
export const isRead = (link) => !!link && readers().has(link);
export function markRead(link) {
  if (!link || readers().has(link)) return;
  const s = readers();
  s.add(link);
  // 오래된 것부터 버린다(Set은 넣은 순서를 기억)
  while (s.size > READ_MAX) s.delete(s.values().next().value);
  store.set("read.links", [...s]);
}
export function clearRead() {
  readSet = new Set();
  store.remove("read.links");
}

// ---- 메모 ----
const notes = () => store.get("vault.notes", {});
export const getNote = (link) => notes()[link]?.text || "";
export function setNote(link, text) {
  const all = notes();
  if (text) all[link] = { text, at: Date.now() };
  else delete all[link];
  store.set("vault.notes", all);
}

/** 메모 입력 창. 저장하거나 지웠으면 true. */
export async function editNote(link, title) {
  const out = await dialog({ title: "메모", text: title, fields: [{ name: "text", label: "메모 (비우면 삭제)", value: getNote(link), multiline: true }], ok: "저장" });
  if (!out) return false;
  setNote(link, out.text.slice(0, 2000));
  toast(out.text ? "메모를 저장했습니다." : "메모를 지웠습니다.");
  return true;
}

// ---- 보도자료·리포트 저장함 ----
const savedReleases = () => store.get("savedReleases", []);
export const isReleaseSaved = (link) => savedReleases().some((x) => x.link === link);
export function saveRelease(item) {
  if (isReleaseSaved(item.link)) return;
  store.set("savedReleases", [{ ...item, group: agencyById(item.agency)?.group || item.group }, ...savedReleases()]);
}
export function removeRelease(link) {
  store.set("savedReleases", savedReleases().filter((x) => x.link !== link));
}

export function removeItem(it) {
  if (it.kind === "news") {
    if (N.isBookmarked(it.link)) N.toggleBookmark(it);
  } else removeRelease(it.link);
  setNote(it.link, "");
}

/** 기사나 보도자료를 보관함에 담는다(이미 담겼으면 그대로). */
export function ensureSaved(item, kind) {
  if (kind === "news") {
    if (!N.isBookmarked(item.link)) N.toggleBookmark(item);
  } else saveRelease(item);
}
export const isSaved = (link) => N.isBookmarked(link) || isReleaseSaved(link);

/** 보관함 전체: 기사와 보도자료·리포트를 합쳐 최근에 담은 순서대로(기사 → 자료 순으로 섞지 않고 각 목록의 담은 순서 유지). */
export function allItems() {
  const n = notes();
  const news = N.bookmarks().map((b) => ({ kind: "news", link: b.link, title: b.title, source: b.source || "", at: b.at || 0, summary: b.summary || "", note: n[b.link]?.text || "" }));
  const rel = savedReleases().map((r) => ({ kind: "release", link: r.link, title: r.title, source: r.label || "", at: r.date ? Date.parse(r.date) || 0 : 0, summary: "", date: r.date || "", note: n[r.link]?.text || "", agency: r.agency, group: r.group, author: r.author }));
  return [...news, ...rel];
}

// ---- 백업(내보내기·가져오기) ----
export function exportAll() {
  return JSON.stringify({ app: "fss-insights-web", version: 1, savedAt: new Date().toISOString(), bookmarks: N.bookmarks(), releases: savedReleases(), notes: notes() }, null, 1);
}
export function importAll(text) {
  const d = JSON.parse(text);
  if (d?.app !== "fss-insights-web") throw new Error("FSS Insights 웹에서 내보낸 파일이 아닙니다.");
  const mergeBy = (a, b) => {
    const seen = new Set(a.map((x) => x.link));
    return [...a, ...b.filter((x) => x?.link && !seen.has(x.link))];
  };
  store.set("news.bookmarks", mergeBy(N.bookmarks(), d.bookmarks || []).slice(0, 500));
  store.set("savedReleases", mergeBy(savedReleases(), d.releases || []));
  store.set("vault.notes", { ...(d.notes || {}), ...notes() });
  return (d.bookmarks?.length || 0) + (d.releases?.length || 0);
}
