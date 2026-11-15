// 회사 정보 같은 화면 내용을 PDF(인쇄 창) 또는 한글(HWPX)로 저장한다.
// doc 모양은 hwpx.js의 hwpxReport 설명을 따른다. 저장 범위는 sections의 id로 고른다.
import { APP_NAME, APP_VERSION, dialog, download, errorText, h, shareFile, toast } from "../core.js";

export const footerLine = () => `${APP_NAME} v${APP_VERSION} · 만든이: 이현덕 with Claude (hdlee73@gmail.com)`;
const safeName = (s) => String(s).replace(/[\\/:*?"<>|\s]+/g, "_");

/**
 * 저장 창을 띄우고 고른 형식·범위로 저장한다.
 * parts: [{ id, label, checked }] · build(ids:Set) → Promise<doc>
 */
export async function saveReport({ title, parts, build }) {
  const radio = (value, label, checked) => h("label", { class: "lw-opt" }, h("input", { type: "radio", name: "_fmt", value, checked }), label);
  const body = h("div", { class: "lw-save" },
    h("div", { class: "section-label" }, "저장 형식"),
    radio("pdf", "PDF (인쇄 창에서 ‘PDF로 저장’)", true), radio("hwpx", "한글(HWPX)", false),
    h("div", { class: "section-label" }, "포함할 내용"),
    parts.map((p) => h("label", { class: "lw-opt" }, h("input", { type: "checkbox", "data-k": p.id, checked: p.checked !== false }), p.label)));
  const ok = await dialog({ title: title || "저장할 범위", body, ok: "저장", cancel: "취소" });
  if (!ok) return;
  const fmt = body.querySelector('input[name="_fmt"]:checked').value;
  const ids = new Set(parts.filter((p) => body.querySelector(`input[data-k="${p.id}"]`).checked).map((p) => p.id));
  if (!ids.size) return toast("저장할 내용을 하나 이상 골라 주세요.");
  toast("자료를 모으는 중…");
  let doc;
  try {
    doc = await build(ids);
  } catch (e) {
    return toast("자료를 불러오지 못했어요: " + errorText(e));
  }
  doc.footer = [...(doc.footer || []), footerLine()];
  const base = safeName(doc.fileName || [doc.subtitle, doc.title].filter(Boolean).join("_"));
  if (fmt === "hwpx") {
    try {
      const { hwpxReport } = await import("./hwpx.js");
      download(await hwpxReport(doc), base + ".hwpx");
      toast("한글(HWPX) 문서를 저장했어요");
    } catch (e) {
      toast("한글 문서를 만들지 못했어요: " + errorText(e));
    }
  } else printReport(doc, base);
}

/** 파일을 만드는 동안 눌린 시간이 오래 지나 브라우저가 공유를 막을 수 있어(특히 아이폰), 그럴 때는 한 번 더 눌러 공유하게 한다. */
async function shareReady(blob, name, title) {
  if (navigator.userActivation && !navigator.userActivation.isActive) {
    const ok = await dialog({ title: "공유할 파일이 준비됐어요", text: name, ok: "공유", cancel: "취소" });
    if (!ok) return;
  }
  await shareFile(blob, name, title);
}

/** 인쇄 창(→ PDF로 저장) */
export function printReport(doc, base) {
  const el = h("div", { class: "print-doc rp" });
  el.append(h("div", { class: "rp-kicker" }, doc.subtitle || "FSS Insights"));
  el.append(h("h1", null, doc.title));
  (doc.meta || []).forEach((m) => el.append(h("p", { class: "meta" }, m)));
  for (const s of doc.sections) {
    el.append(h("h3", null, s.title));
    if (s.note) el.append(h("p", { class: "meta" }, s.note));
    for (const b of s.blocks) {
      if (b.kv) el.append(h("table", { class: "rp-kv" }, h("tbody", null, b.kv.filter(([, v]) => v).map(([k, v]) => h("tr", null, h("th", null, k), h("td", null, v))))));
      else if (b.table) {
        const t = b.table;
        const num = (c) => (t.num ? t.num(c) : c > 0);
        el.append(h("table", { class: "rp-tbl" },
          h("thead", null, h("tr", null, t.head.map((x, c) => h("th", { class: num(c) ? "num" : "" }, x)))),
          h("tbody", null, t.rows.map((r, i) => h("tr", { class: t.bold?.(i) ? "b" : "" }, r.map((x, c) => h("td", { class: num(c) ? "num" : "" }, x ?? "")))))));
      } else if (b.image) el.append(h("img", { class: "rp-img" + (b.half ? " half" : ""), src: b.image.dataUrl, alt: "그래프" }));
      else if (b.heading) el.append(h("div", { class: "ph" }, b.heading));
      else if (b.text != null) el.append(h("div", { class: "pb" + (b.gray ? " gray" : ""), style: b.indent ? { marginLeft: b.indent * 14 + "pt" } : null }, b.text));
    }
  }
  (doc.footer || []).filter(Boolean).forEach((l) => el.append(h("p", { class: "foot" }, l)));

  previewPrint(el, base, { title: doc.title, doc });
}

/**
 * 저장용 문서(el)를 미리보기 바(PDF로 저장·PDF 공유)와 함께 띄우고 인쇄한다. 법령 비교표처럼 문서 모양이 다른 화면도 같은 바를 쓴다.
 * opt: { title, pageCss: "@page {…}", landscape, doc(있으면 한글 파일 공유 단추도 보인다) }
 */
export function previewPrint(el, base, { title = base, pageCss, landscape = false, doc = null } = {}) {
  document.querySelectorAll(".print-doc, .rp-bar").forEach((e) => e.remove()); // 앞서 남은 인쇄용 문서
  const style = h("style", null, pageCss || "@page { size: 210mm 297mm; margin: 17mm; }");
  document.head.append(style);
  const prevTitle = document.title;
  // 휴대폰은 화면 폭(약 360px)대로 배치되면 표가 찌그러지므로, 미리보기·인쇄 동안만 폭 820px 기준으로 배치한다.
  const vp = document.querySelector('meta[name="viewport"]');
  const prevVp = vp?.getAttribute("content");
  if (vp) vp.setAttribute("content", "width=820");
  // 화면에 저장용 문서만 보이는 미리보기를 띄운 뒤 인쇄한다(휴대폰 브라우저는 print 전용 CSS를 따르지 않고 화면을 그대로 찍는 일이 있다).
  const bar = h("div", { class: "rp-bar" }, h("span", null, "저장 미리보기"),
    h("button", { type: "button", class: "primary", onclick: () => window.print() }, "PDF로 저장(인쇄)"),
    // PDF 파일 자체를 만들어 카카오톡 등 공유 창으로 보낸다(브라우저 인쇄 기능은 파일을 넘길 수 없어, 화면의 문서를 쪽별 그림으로 담은 PDF를 만든다).
    navigator.share ? h("button", { type: "button", onclick: async () => {
      try {
        toast("공유할 PDF 파일을 만드는 중…");
        const { elementToPdf } = await import("./pdfshare.js");
        await shareReady(await elementToPdf(el, { landscape }), base + ".pdf", title);
      } catch (e) { toast("공유할 PDF를 만들지 못했어요: " + errorText(e)); }
    } }, "PDF 공유") : null,
    navigator.share && doc ? h("button", { type: "button", onclick: async () => {
      try {
        toast("공유할 한글(HWPX) 파일을 만드는 중…");
        const { hwpxReport } = await import("./hwpx.js");
        await shareReady(await hwpxReport(doc), base + ".hwpx", title);
      } catch (e) { toast("공유할 파일을 만들지 못했어요: " + errorText(e)); }
    } }, "공유(한글 파일)") : null,
    h("button", { type: "button", onclick: () => done() }, "닫기"));
  const done = () => (el.remove(), bar.remove(), style.remove(), document.body.classList.remove("printing", "rp-view"), (document.title = prevTitle), vp && prevVp != null && vp.setAttribute("content", prevVp), removeEventListener("afterprint", onAfter));
  // 컴퓨터는 인쇄 창을 닫으면 미리보기도 닫고, 휴대폰(터치)은 다시 저장할 수 있게 미리보기를 남겨 둔다.
  const onAfter = () => { if (matchMedia("(pointer: fine)").matches) done(); };
  addEventListener("afterprint", onAfter);
  document.body.append(el, bar);
  document.body.classList.add("printing", "rp-view");
  scrollTo(0, 0);
  document.title = base;
  setTimeout(() => window.print(), 400);
}
