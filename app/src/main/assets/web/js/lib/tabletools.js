// 화면의 표(<table>)를 엑셀로 가져갈 수 있게 한다: 표 복사(탭 구분, 엑셀에 바로 붙여넣기) · CSV · 엑셀(.xlsx).
// 표는 눌렀을 때의 화면 그대로 읽으므로 어느 표에나 붙일 수 있다.
import { download, h, icon, toast } from "../core.js";
import { xlsx } from "./xlsx.js";

const clean = (s) => String(s ?? "").replace(/\s+/g, " ").trim();

/** 표 → { head:[...], rows:[[...]] }. 숨겨진 열(.wide-only가 가려진 경우 등)은 화면에 보이는 것만 담는다. */
export function tableData(table) {
  const shown = (el) => el.getClientRects().length > 0;
  const grid = (tr) => [...tr.children].filter((c) => shown(c)).map((c) => clean(c.innerText ?? c.textContent));
  const headRow = table.querySelector("thead tr");
  const head = headRow ? grid(headRow) : [];
  const rows = [...table.querySelectorAll("tbody tr")].map(grid).filter((r) => r.some(Boolean));
  return { head, rows };
}

const csvCell = (v) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
export const toCsv = ({ head, rows }) => [head, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n");
export const toTsv = ({ head, rows }) => [head, ...rows].map((r) => r.map((v) => v.replace(/[\t\r\n]+/g, " ")).join("\t")).join("\n");

/** "1,234" · "-5.2" · "12.5%"처럼 숫자로 읽을 수 있으면 숫자, 아니면 글. */
export function asCell(v) {
  const s = String(v);
  return /^-?\d{1,3}(,\d{3})*(\.\d+)?$|^-?\d+(\.\d+)?$/.test(s) ? Number(s.replace(/,/g, "")) : s;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const ta = h("textarea", { style: { position: "fixed", opacity: "0", top: "0" } });
    ta.value = text;
    document.body.append(ta);
    ta.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {
      /* 복사 권한 없음 */
    }
    ta.remove();
    return ok;
  }
}

const stamp = () => new Date().toISOString().slice(0, 10).replace(/-/g, "");
const safe = (s) => String(s).replace(/[\\/:*?"<>|\s]+/g, "_");

/** 단추 줄(복사·CSV·엑셀). getTable은 눌렀을 때 표를 돌려주는 함수. */
export function tableBar(getTable, name) {
  const data = () => {
    const t = getTable();
    const d = t && tableData(t);
    if (!d || !d.rows.length) {
      toast("저장할 표 내용이 없습니다.");
      return null;
    }
    return d;
  };
  const file = (ext) => `${safe(name)}_${stamp()}.${ext}`;
  return h("div", { class: "tt-bar" },
    h("button", { type: "button", class: "btn ghost sm", title: "표를 복사해 엑셀에 바로 붙여넣기", onclick: async () => {
      const d = data();
      if (d) toast((await copyText(toTsv(d))) ? "표를 복사했어요. 엑셀에 붙여넣으세요." : "복사하지 못했어요. CSV로 저장해 보세요.");
    } }, icon("copy"), "표 복사"),
    h("button", { type: "button", class: "btn ghost sm", onclick: () => {
      const d = data();
      if (d) download(new Blob(["﻿" + toCsv(d)], { type: "text/csv;charset=utf-8" }), file("csv"));
    } }, icon("file-down"), "CSV"),
    h("button", { type: "button", class: "btn ghost sm", onclick: () => {
      const d = data();
      if (!d) return;
      const header = d.head.map((x, i) => x || (i ? " " : "항목"));
      download(xlsx([{ name: name.slice(0, 31), header, rows: d.rows.map((r) => r.map(asCell)), widths: header.map((_, i) => (i ? 16 : 28)) }]), file("xlsx"));
    } }, icon("download"), "엑셀"));
}

/** 표 둘레(.tbl-wrap)에 단추 줄을 위에 붙여 돌려준다. */
export function withTableTools(wrap, name) {
  return h("div", { class: "tt" }, tableBar(() => wrap.querySelector("table"), name), wrap);
}
