// 법령 한 건의 전체 조문과 이번 개정 내용(제·개정이유, 신·구조문 대비, 개정된 조문 표시).
// 법령(?mst=)과 행정규칙(?adm=: 감독규정·시행세칙) 모두 같은 화면으로 연다. pd=공포(발령)일, ef=시행일, type=제개정 구분.
import { setHeader } from "../app.js";
import { errorText, h, icon, loading, message } from "../core.js";
import * as L from "../lib/law-core.js";

const dot = (v) => (v && /^\d{8}$/.test(v) ? `${v.slice(0, 4)}.${v.slice(4, 6)}.${v.slice(6)}` : v || "-");

/** 조문 글 속 개정 표시(<개정 2015.7.31, 2026.9.8> 등)에 공포·발령일이 들어 있으면 이번 개정으로 바뀐 조문. */
export function amendedOn(text, ymd) {
  if (!/^\d{8}$/.test(ymd || "") || !text) return false;
  for (const m of text.matchAll(/<[^<>]*>/g)) {
    for (const d of m[0].matchAll(/(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})/g)) {
      if (d[1] + d[2].padStart(2, "0") + d[3].padStart(2, "0") === ymd) return true;
    }
  }
  return false;
}

/** <P>바뀐 글자</P> 표시를 강조 조각으로 나눈다. */
function marked(text) {
  const out = [];
  let last = 0;
  for (const m of text.matchAll(/<P>([\s\S]*?)<\/P>/g)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    out.push(h("mark", null, m[1]));
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export async function render(root, params, ctx) {
  const name = params.name || "법령";
  const adm = params.adm || null;
  const pd = params.pd || "";
  const sub = [params.kind, params.type, pd ? `공포·발령 ${dot(pd)}` : null, params.ef ? `시행 ${dot(params.ef)}` : null].filter(Boolean).join(" · ");
  setHeader(name, sub || "전체 조문과 이번 개정 내용");
  root.append(h("a", { class: "link-more", href: "#/lawnews", style: { display: "inline-block", margin: "0 2px 8px" } }, "‹ 최근 개정 소식으로"));
  const box = h("div", null, loading("조문과 개정 내용을 읽는 중…"));
  root.append(box);
  try {
    const att = (p) => p.then((value) => ({ ok: true, value }), (error) => ({ ok: false, error }));
    const doc = adm ? L.repo.adminRule(adm) : L.repo.lawArticles({ mst: params.mst });
    const [artsR, metaR, onR] = await Promise.all([
      att(doc),
      adm ? Promise.resolve({ ok: true, value: null }) : att(L.repo.lawMeta({ mst: params.mst })),
      adm ? Promise.resolve({ ok: true, value: null }) : att(L.repo.oldNew({ mst: params.mst })),
    ]);
    if (!ctx.alive()) return;
    if (!artsR.ok) throw artsR.error;
    const arts = (adm ? artsR.value.articles : artsR.value.filter((a) => a.number));
    const reason = adm ? artsR.value.reason : metaR.ok ? metaR.value?.reason : null;
    const groups = onR.ok ? onR.value : null; // [{head: "제2조(정의)", rows: [{old, nw}]}]
    const nGroups = groups ? groups.length : 0;
    const nItems = groups ? groups.reduce((n, g) => n + g.rows.length, 0) : 0;
    // 조 번호 열쇠(제84조의2 → "84-2"): 신·구조문 대비에 나온 조도 ‘이번 개정’ 조로 센다.
    const noKey = (t) => {
      const m = /제\s*(\d+)\s*조(?:\s*의\s*(\d+))?/.exec(t || "");
      return m ? m[1] + (m[2] ? "-" + m[2] : "") : "";
    };
    const diffKeys = new Set((groups || []).map((g) => noKey(g.head)).filter(Boolean));
    const rows = arts.map((a) => {
      const raw = adm ? a.content || a.body || "" : a.content;
      return { a, amended: amendedOn(raw, pd) || diffKeys.has(noKey(a.fullTitle)) };
    });
    const nAmended = rows.filter((r) => r.amended).length;

    const intro = h("section", { class: "card lw-amend" },
      h("div", { class: "ch" }, icon("file-text"), h("h3", null, "이번 개정 내용"), h("div", { class: "ch-acts" }, params.type || "")),
      reason
        ? h("details", { open: true }, h("summary", null, "제·개정이유"), h("div", { class: "lw-text" }, reason))
        : h("p", { class: "note" }, "국가법령정보 API가 이 개정의 제·개정이유를 제공하지 않았습니다(타법개정·소규모 개정은 비어 있는 경우가 많습니다)."),
      groups && groups.length
        ? h("details", null, h("summary", null, `신·구조문 대비 — 바뀐 조 ${nGroups}개 (세부 항목 ${nItems}건)`), h("div", { class: "lw-on" }, groups.slice(0, 200).map((g) =>
          h("div", { class: "lw-on-g" },
            h("div", { class: "lw-on-h" }, h("b", null, g.head || "조 표시 없음"), h("span", { class: "tag" }, `${g.rows.length}건`)),
            h("table", { class: "lw-on-t" },
              h("thead", null, h("tr", null, h("th", null, "개정 전"), h("th", null, "개정 후"))),
              h("tbody", null, g.rows.map((p) => h("tr", null, h("td", { class: "lw-text" }, marked(p.old || "(없음)")), h("td", { class: "lw-text" }, marked(p.nw || "(삭제)"))))))))))
        : h("p", { class: "note" }, adm ? "행정규칙은 신·구조문 대비표를 API로 제공하지 않습니다. 아래에서 ‘이번 개정’ 표시가 붙은 조문을 확인하세요." : onR.ok ? "신·구조문 대비 자료가 비어 있습니다(제정·전부개정이거나 API가 제공하지 않는 경우)." : "신·구조문 대비 자료를 가져오지 못했습니다."),
      h("p", { class: "note" }, nAmended ? `이번 개정(${dot(pd)})으로 바뀐 조는 ${nAmended}개 조입니다.${nItems ? ` (신·구조문 대비의 세부 항목 ${nItems}건은 이 조들에 속한 항·호·목입니다.)` : ""}` : "조문 글에서 이번 개정 표시를 찾지 못했습니다(제정이거나 개정 표시 형식이 다른 경우)."));

    const filter = h("input", { type: "search", placeholder: "조문 제목·내용에서 찾기", "aria-label": "조문 찾기" });
    let only = false;
    const chip = h("button", { type: "button", class: "pill", disabled: !nAmended }, `이번 개정 조문만 (${nAmended}개 조)`);
    const list = h("div", { class: "stack" });
    const draw = () => {
      const q = filter.value.trim();
      const shown = rows.filter((r) => (!only || r.amended) && (!q || (r.a.title + r.a.content + (r.a.body || "")).includes(q)));
      list.replaceChildren(...shown.slice(0, 500).map(({ a, amended }) => h("section", { class: "card lw-doc-a" + (amended ? " lw-amended" : "") },
        h("div", { class: "lw-head-row" }, h("h3", { class: "lw-title" }, a.fullTitle), amended ? h("span", { class: "tag orange" }, "이번 개정") : null),
        h("div", { class: "lw-text" }, a.body))));
      if (!shown.length) list.append(message("맞는 조문이 없습니다."));
    };
    filter.addEventListener("input", draw);
    chip.onclick = () => (only = !only, chip.classList.toggle("on", only), draw());
    box.replaceChildren(intro, h("div", { class: "search" }, icon("search"), filter), h("div", { class: "lw-search-opts" }, chip, h("span", { class: "note" }, `전체 조문 ${rows.length}개`)), list);
    draw();
  } catch (e) {
    box.replaceChildren(message(errorText(e), { error: true }));
  }
}
