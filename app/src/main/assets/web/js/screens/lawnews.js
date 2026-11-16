// 최근 금융관련 법령개정 소식: 주요 금융 법령의 최근 제·개정을 공포일 최신순으로 보여 준다(홈 '법령 개정 소식' 카드의 전체 목록).
import { setHeader } from "../app.js";
import { cached, h, icon, skeleton } from "../core.js";
import { loadLawNews } from "../lib/lawnews.js";

const dot = (v) => (v && /^\d{8}$/.test(v) ? `${v.slice(0, 4)}.${v.slice(4, 6)}.${v.slice(6)}` : v || "-");
const today = () => new Date().toISOString().slice(0, 10).replace(/-/g, "");

export async function render(root, params, ctx) {
  setHeader("최근 금융관련 법령개정 소식", "주요 금융 법령 20개의 최근 제·개정(공포일 최신순) · 법령명을 누르면 스마트 법령검색으로");
  const box = h("div", { class: "card list" }, skeleton(8));
  root.append(box, h("p", { class: "note", style: { margin: "10px 2px" } }, "국가법령정보 연혁에서 읽은 자료입니다. 시행일이 아직 오지 않은 개정은 ‘시행 예정’으로 표시합니다. 정확한 내용은 법령 원문을 확인하세요."));
  let rows;
  try {
    rows = await cached("lawnews.all", 6 * 3600 * 1000, () => loadLawNews({ per: 3, limit: 40 }));
  } catch {
    rows = [];
  }
  if (!ctx.alive()) return;
  if (!rows.length) return box.replaceChildren(h("div", { class: "note" }, "법령 개정 정보를 불러오지 못했습니다. 새로고침을 눌러 주세요."));
  const t = today();
  box.replaceChildren(...rows.map((x) => h("a", { class: "item ln-row", href: "#/law?q=" + encodeURIComponent(x.name) }, h("div", { class: "body" },
    h("div", { class: "ln-t" }, h("b", null, x.name), x.type ? h("span", { class: "tag blue" }, x.type) : null, x.effectiveDate > t ? h("span", { class: "tag orange" }, "시행 예정") : null),
    h("div", { class: "m" }, `공포 ${dot(x.promulgationDate)} · 시행 ${dot(x.effectiveDate)}`)), icon("chevron-right"))));
}
