// 위임 트리 조회 결과를 한글(HWPX) 문서로 만든다(앱 HwpxExporter.kt를 옮김).
// 문서 뼈대(스타일 등)는 assets/hwpx 템플릿을 쓰고 본문(section0.xml)만 만든다. zip은 압축 없이 묶는다.
import * as L from "./law-core.js";
import { zip } from "./xlsx.js";

const C_BODY = 12, C_ITEM_HEAD = 13, C_TITLE = 9, C_SUBTITLE = 10, C_META = 11, C_SECTION = 14, C_GRAY = 15, C_CELL_BOLD = 16, C_ITEM_BOLD = 22, C_FOOT = 23, C_HDR = 8;
const cLevel = (lv) => 17 + lv;
const P_CENTER = 20;
const pIndent = (d) => 21 + Math.max(0, Math.min(6, d));
const MARGIN = 4800;
const PAPERS = { A4: [595, 842], B4: [729, 1032], Letter: [612, 792] };
const CHAR_SIZE = { 0: 1000, 2: 900, 8: 950, 9: 1800, 10: 1400, 11: 900, 12: 950, 13: 1100, 14: 1300, 15: 850, 16: 900, 17: 900, 18: 900, 19: 900, 20: 900, 21: 900, 22: 1000, 23: 800, 24: 850, 25: 850 };
const sizeOf = (c) => CHAR_SIZE[c] || 1000;
const LINE_FACTOR = 1.38, CELL_PAD = 400;

const tpl = new Map();
async function res(name) {
  if (!tpl.has(name)) {
    const r = await fetch(new URL(`../../assets/hwpx/${name}`, import.meta.url));
    if (!r.ok) throw new Error(`HWPX 템플릿 ${name}을(를) 읽지 못했어요`);
    tpl.set(name, await r.text());
  }
  return tpl.get(name);
}
const esc = (s) => String(s).replace(/[^\t\n\r -￿]/g, "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const lines = (s) => String(s).split("\n");

function wrap(text, width, size) {
  if (!text) return [""];
  const out = [];
  let cur = "", w = 0;
  for (const ch of text) {
    const cw = size * (ch.charCodeAt(0) >= 0x1100 ? 1 : 0.55);
    if (cur && w + cw > width) {
      out.push(cur);
      cur = "";
      w = 0;
      if (ch === " ") continue;
    }
    cur += ch;
    w += cw;
  }
  if (cur) out.push(cur);
  return out;
}
const pXml = (id, text, char, para) => `<hp:p id="${id}" paraPrIDRef="${para}" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="${char}">` + (text ? `<hp:t>${esc(text)}</hp:t>` : "<hp:t/>") + "</hp:run></hp:p>";

class Body {
  constructor(width) {
    this.sb = [];
    this.id = 1000000;
    this.used = 0;
    this.width = width;
  }
  next() {
    return this.id++;
  }
  p(text = "", char = C_BODY, depth = 0, para = null) {
    this.sb.push(pXml(this.next(), text, char, para ?? pIndent(depth)));
    this.used += sizeOf(char) * 1.3 * wrap(text, this.width - depth * 1400, sizeOf(char)).length;
  }
  raw(x) {
    this.sb.push(x);
  }
}

function keep(children, levels) {
  return children.flatMap((c) => {
    const kept = keep(c.children, levels);
    return levels.has(c.level) ? [{ ...c, children: kept }] : kept;
  });
}
function nodeBlock(b, n, depth) {
  b.p(`[${L.LEVELS[n.level].label}] ${n.sourceName}`, cLevel(n.level), depth);
  b.p(L.displayHeading(n), C_ITEM_HEAD, depth);
  lines(n.article.body).forEach((l) => b.p(l, C_BODY, depth));
  b.p("");
  n.children.forEach((c) => nodeBlock(b, c, depth + 1));
}
function sequential(b, root, levels) {
  const attached = L.attach(root.article, keep(root.children, levels));
  const parsed = root.article.parsed;
  b.p(`[${L.LEVELS[root.level].label}] ${root.sourceName}`, cLevel(root.level));
  b.p(root.article.heading, C_ITEM_HEAD);
  if (parsed.lead.trim()) lines(parsed.lead).forEach((l) => b.p(l, C_BODY));
  const group = (path, depth, label) => {
    const nodes = attached.get(L.pathKey(path));
    if (!nodes) return;
    b.p(`▼ ${label} 하위 규정`, C_META, depth + 1);
    nodes.forEach((n) => nodeBlock(b, n, depth + 1));
  };
  const item = (i, d) => (lines(i.text).forEach((l) => b.p(l, C_BODY, d)), group(i.path, d, L.pathDisplay(i.path)), i.children.forEach((c) => item(c, d + 1)));
  if (!parsed.items.length && !parsed.lead.trim()) lines(root.article.content).forEach((l) => b.p(l, C_BODY));
  group(L.LEAD, 0, "각 호 외의 부분");
  parsed.items.forEach((i) => item(i, 0));
  group(L.EMPTY, 0, "조 전체에 연결된");
}

function compareTable(b, root, levels, pageW, pageH) {
  const chosen = new Set([...levels].filter((l) => l !== L.LAW));
  const cols = L.compareColumns(chosen);
  const rows = L.compareRows(root, chosen);
  const colW = Math.floor((pageW - MARGIN * 2 - 100) / cols.length);
  const cellChar = cols.length <= 3 ? 2 : 24;
  const cellBold = cols.length <= 3 ? C_CELL_BOLD : 25;
  const headH = 1800;
  const wrapW = (colW - CELL_PAD * 2) * 0.93;
  const lineH = sizeOf(cellChar) * LINE_FACTOR;
  const avail = (pageH - MARGIN * 2) * 0.95;
  const visual = (ls) => ls.flatMap((l) => wrap(l.text, wrapW, sizeOf(l.char)).map((t) => ({ ...l, text: t })));
  const line = (text, char, para = pIndent(0)) => ({ text, char, para });
  const chunks = [];
  let y = (b.used % avail) + headH;
  for (const row of rows) {
    const lawLines = visual(lines(row.lawText).map((t) => line(t, cellChar)));
    const leadLogical = (row.lawText.slice(0, Math.max(0, Math.min(row.anchorStart, row.lawText.length))).match(/\n/g) || []).length;
    const lead = lines(row.lawText).slice(0, leadLogical).reduce((s, t) => s + wrap(t, wrapW, sizeOf(cellChar)).length, 0);
    const colLines = [lawLines, ...cols.slice(1).map((lv) => {
      const ls = [];
      (row.cells.get(lv) || []).forEach((n, i) => {
        if (i) ls.push(line("", cellChar));
        ls.push(line(L.displayHeading(n) || n.sourceName, cellBold));
        if (lv >= L.SUP) ls.push(line(n.sourceName, C_GRAY));
        lines(n.article.body).forEach((t) => ls.push(line(t, cellChar)));
      });
      const vis = visual(ls);
      return vis.length ? [...Array.from({ length: lead }, () => line("", cellChar)), ...vis] : vis;
    })];
    const n = Math.max(...colLines.map((c) => c.length));
    let idx = 0;
    while (idx < n) {
      let k = Math.floor((avail - y - CELL_PAD * 2) / lineH);
      if (k < 3 && y > headH) {
        y = headH;
        k = Math.floor((avail - y - CELL_PAD * 2) / lineH);
      }
      const take = Math.min(Math.max(k, 1), n - idx);
      chunks.push(colLines.map((c) => c.slice(idx, idx + take)));
      y += take * lineH + CELL_PAD * 2;
      idx += take;
      if (idx < n) y = headH;
    }
  }
  if (!chunks.length) chunks.push(cols.map(() => []));
  const hOf = (ch) => Math.floor(Math.max(...ch.map((c) => c.length)) * lineH + CELL_PAD * 2);
  const sb = [];
  sb.push(`<hp:tbl id="${b.next()}" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" pageBreak="TABLE" repeatHeader="1" rowCnt="${chunks.length + 1}" colCnt="${cols.length}" cellSpacing="0" borderFillIDRef="3" noAdjust="0">`);
  sb.push(`<hp:sz width="${colW * cols.length}" widthRelTo="ABSOLUTE" height="${headH + chunks.reduce((s, c) => s + hOf(c), 0)}" heightRelTo="ABSOLUTE" protect="0"/>`);
  sb.push(`<hp:pos treatAsChar="0" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>`);
  sb.push(`<hp:outMargin left="0" right="0" top="0" bottom="0"/><hp:inMargin left="${CELL_PAD}" right="${CELL_PAD}" top="${CELL_PAD}" bottom="${CELL_PAD}"/>`);
  const cell = (r, c, ls, header, height) => {
    if (c === 0) sb.push("<hp:tr>");
    const fill = header != null ? 4 + header : 3;
    sb.push(`<hp:tc name="" header="${header != null ? 1 : 0}" hasMargin="0" protect="0" editable="0" dirty="0" borderFillIDRef="${fill}">`);
    sb.push(`<hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" vertAlign="${header != null ? "CENTER" : "TOP"}" linkListIDRef="0" linkListNextIDRef="0" textWidth="0" textHeight="0" hasTextRef="0" hasNumRef="0">`);
    (ls.length ? ls : [line("", cellChar)]).forEach((l) => sb.push(pXml(b.next(), l.text, l.char, l.para)));
    sb.push("</hp:subList>");
    sb.push(`<hp:cellAddr colAddr="${c}" rowAddr="${r}"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="${colW}" height="${height}"/><hp:cellMargin left="${CELL_PAD}" right="${CELL_PAD}" top="${CELL_PAD}" bottom="${CELL_PAD}"/></hp:tc>`);
    if (c === cols.length - 1) sb.push("</hp:tr>");
  };
  cols.forEach((lv, c) => cell(0, c, [line(L.LEVELS[lv].label, C_HDR, P_CENTER)], lv, headH));
  chunks.forEach((ch, r) => {
    const height = hOf(ch);
    cols.forEach((_, c) => cell(r + 1, c, ch[c], null, height));
  });
  sb.push("</hp:tbl>");
  b.raw(`<hp:p id="${b.next()}" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="0">${sb.join("")}<hp:t/></hp:run></hp:p>`);
}

/** opt = { compare, levels:Set, paper, precedentList, precedentBodies, relatedArticles, extras, footer } → Blob */
export async function hwpx(tree, opt) {
  const levels = opt.levels;
  const extras = opt.extras || { precedents: [], relatedArticles: [] };
  const [pw, ph] = PAPERS[opt.paper] || PAPERS.A4;
  const landscape = opt.compare && L.compareColumns(levels).length >= 2;
  const pageW = (landscape ? ph : pw) * 100, pageH = (landscape ? pw : ph) * 100;
  const b = new Body(pageW - MARGIN * 2);
  const today = new Date().toISOString().slice(0, 10);
  const target = `${tree.law.name} ${L.queryTarget(tree.query)}`;
  b.p("FSS Insights 위임 법령 조회 결과", C_TITLE);
  b.p(target, C_SUBTITLE);
  b.p("기준: " + (tree.version ? `${L.formatYmd(tree.version.effectiveDate)} 시행 (${tree.version.status || "연혁"})` : "현행") + `   ·   출력: ${today}`, C_META);
  if (landscape) {
    const cols = L.compareColumns(levels);
    b.p(`비교 방식: ${cols.length}단 비교 (${cols.map((l) => L.LEVELS[l].label).join("·")}) · ${opt.paper || "A4"} 가로`, C_META);
  } else {
    b.p("포함 범위: " + ["법률", ...[L.DECREE, L.RULE, L.SUP, L.DETAIL].filter((l) => levels.has(l)).map((l) => L.LEVELS[l].label), opt.precedentList ? "판례 목록" : null, opt.precedentBodies ? "판례 본문" : null, opt.relatedArticles ? "인용 조문" : null].filter(Boolean).join(", "), C_META);
  }
  tree.warnings.forEach((w) => b.p("※ " + w, C_GRAY));
  b.p("");
  if (landscape) compareTable(b, tree.root, levels, pageW, pageH);
  else sequential(b, tree.root, levels);

  const cited = keep(tree.cited, levels);
  if (cited.length) {
    b.p("");
    b.p(`인용된 법규 (${cited.reduce((s, n) => s + 1 + L.descendants(n), 0)}건)`, C_SECTION);
    b.p("이 조문을 인용했지만 위임받은 규정은 아닙니다.", C_META);
    cited.forEach((n) => {
      nodeBlock(b, n, 0);
      if (n.delegatedBy.length) b.p("위임 근거: " + n.delegatedBy.map((a) => `${tree.root.sourceName} ${a.heading}`).join(", "), C_META);
    });
  }
  if (opt.relatedArticles) {
    b.p("");
    b.p(`인용 조문 (${extras.relatedArticles.length}건)`, C_SECTION);
    if (!extras.relatedArticles.length) b.p("본문이 인용한 같은 법의 다른 조문이 없거나 불러오지 못했습니다.", C_GRAY);
    extras.relatedArticles.forEach((a) => (b.p(`${tree.law.name} ${a.heading}`, C_ITEM_BOLD), lines(a.body).forEach((l) => b.p(l, C_BODY, 1)), b.p("")));
  }
  if (opt.precedentList) {
    b.p("");
    b.p(`관련 판례 (${tree.precedents.length}건)`, C_SECTION);
    if (!tree.precedents.length) b.p("찾은 판례가 없습니다.", C_GRAY);
    tree.precedents.forEach((p) => (b.p("• " + p.caseName, C_ITEM_BOLD), b.p([p.court, p.caseNumber, p.decisionDate ? L.formatYmd(p.decisionDate) : null].filter(Boolean).join(" · "), C_META, 1)));
  }
  if (opt.precedentBodies) {
    b.p("");
    b.p(`판례 본문 (${extras.precedents.length}건)`, C_SECTION);
    if (!extras.precedents.length) b.p("불러온 판례 본문이 없습니다.", C_GRAY);
    extras.precedents.forEach((d) => {
      b.p(d.summary.caseName, C_ITEM_BOLD);
      b.p([d.summary.court, d.summary.caseNumber, d.summary.decisionDate ? L.formatYmd(d.summary.decisionDate) : null].filter(Boolean).join(" · "), C_META, 1);
      [["판시사항", d.holdings], ["판결요지", d.gist], ["참조조문", d.referencedArticles], ["참조판례", d.referencedCases]].forEach(([t, text]) => {
        if (!text.trim()) return;
        b.p(`[${t}]`, C_CELL_BOLD, 1);
        lines(text).forEach((l) => b.p(l, C_BODY, 1));
      });
      b.p("");
    });
  }
  b.p("");
  b.p("출처: 국가법령정보센터 공동활용 API (law.go.kr). 법적 판단의 근거로는 반드시 원문을 확인하세요.", C_FOOT);
  lines(opt.footer || "").filter((l) => l.trim()).forEach((l) => b.p(l, C_FOOT));

  const first = (await res("first_paragraph.xml")).replace("{{LANDSCAPE}}", landscape ? "NARROWLY" : "WIDELY").replace("{{WIDTH}}", pageW).replace("{{HEIGHT}}", pageH).replaceAll("{{MARGIN}}", MARGIN);
  const section = (await res("section_head.xml")) + first + b.sb.join("") + "</hs:sec>";
  const hpf = (await res("content.hpf")).replaceAll("{{TITLE}}", esc(target)).replaceAll("{{DATE}}", today);
  const files = [
    ["mimetype", "application/hwp+zip"],
    ["version.xml", await res("version.xml")],
    ["settings.xml", await res("settings.xml")],
    ["META-INF/container.xml", await res("container.xml")],
    ["META-INF/manifest.xml", await res("manifest.xml")],
    ["META-INF/container.rdf", await res("container.rdf")],
    ["Contents/content.hpf", hpf],
    ["Contents/header.xml", await res("header.xml")],
    ["Contents/section0.xml", section],
    ["Preview/PrvText.txt", `FSS Insights 위임 법령 조회 결과\r\n${target}`],
  ];
  return zip(files, "application/hwp+zip");
}

// ---- 일반 보고서(회사 정보 등) ----
// doc = { title, subtitle, meta:[문자열], sections:[{ title, note, blocks:[{kv:[[항목,값]]} | {table:{head, rows, weights, num}} | {text, gray}] }], footer:[문자열] }
const P_RIGHT = 28, P_KEEP = 29; // P_KEEP: 다음 문단과 같은 쪽에 두는 문단 모양
function reportTable(b, t, pageW) {
  const n = t.head.length;
  const weights = t.weights || t.head.map((_, i) => (i === 0 ? 1.6 : 1));
  const total = weights.reduce((s, w) => s + w, 0);
  const full = pageW - MARGIN * 2 - 100;
  const widths = weights.map((w) => Math.floor((full * w) / total));
  const cellChar = 2, pad = 300, lineH = sizeOf(cellChar) * LINE_FACTOR;
  const isNum = (c) => (t.num ? t.num(c) : c > 0);
  const linesOf = (v, c) => String(v ?? "").split("\n").flatMap((l) => wrap(l, (widths[c] - pad * 2) * 0.93, sizeOf(cellChar)));
  const rowH = (row, head) => Math.max(head ? 1200 : 0, Math.floor(Math.max(...row.map((v, c) => linesOf(v, c).length)) * lineH + pad * 2));
  const heights = [rowH(t.head, true), ...t.rows.map((r) => rowH(r))];
  const sb = [];
  sb.push(`<hp:tbl id="${b.next()}" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" pageBreak="${t.rows.length > 38 ? "CELL" : "NONE"}" repeatHeader="1" rowCnt="${t.rows.length + 1}" colCnt="${n}" cellSpacing="0" borderFillIDRef="3" noAdjust="0">`);
  sb.push(`<hp:sz width="${widths.reduce((s, w) => s + w, 0)}" widthRelTo="ABSOLUTE" height="${heights.reduce((s, x) => s + x, 0)}" heightRelTo="ABSOLUTE" protect="0"/>`);
  sb.push(`<hp:pos treatAsChar="0" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>`);
  sb.push(`<hp:outMargin left="0" right="0" top="0" bottom="0"/><hp:inMargin left="${pad}" right="${pad}" top="${pad}" bottom="${pad}"/>`);
  [t.head, ...t.rows].forEach((row, r) => {
    sb.push("<hp:tr>");
    row.forEach((v, c) => {
      const head = r === 0;
      const char = head ? C_HDR : t.bold?.(r - 1) ? C_CELL_BOLD : cellChar;
      const para = head ? P_CENTER : isNum(c) ? P_RIGHT : pIndent(0);
      sb.push(`<hp:tc name="" header="${head ? 1 : 0}" hasMargin="0" protect="0" editable="0" dirty="0" borderFillIDRef="${head ? 4 : r % 2 === 0 && t.zebra !== false ? 9 : 3}">`);
      sb.push(`<hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" vertAlign="CENTER" linkListIDRef="0" linkListNextIDRef="0" textWidth="0" textHeight="0" hasTextRef="0" hasNumRef="0">`);
      String(v ?? "").split("\n").forEach((l) => sb.push(pXml(b.next(), l, char, para)));
      sb.push("</hp:subList>");
      sb.push(`<hp:cellAddr colAddr="${c}" rowAddr="${r}"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="${widths[c]}" height="${heights[r]}"/><hp:cellMargin left="${pad}" right="${pad}" top="${pad}" bottom="${pad}"/></hp:tc>`);
    });
    sb.push("</hp:tr>");
  });
  sb.push("</hp:tbl>");
  b.raw(`<hp:p id="${b.next()}" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="0">${sb.join("")}<hp:t/></hp:run></hp:p>`);
}

/**
 * 그림(PNG)을 글자처럼 놓인 문단으로 넣는다. ims가 둘이면 한 줄에 나란히 놓는다. images에 쌓아 두면 호출한 쪽이 BinData로 묶는다.
 * orgSz·imgDim은 원본 그림 크기(96dpi 픽셀 × 75), curSz·sz는 표시 크기다. 둘이 같으면 한글이 그림을 실제보다 크게 그리므로 구분해야 한다.
 */
function imageParagraph(b, ims, images, pageW) {
  const full = pageW - MARGIN * 2;
  const each = ims.length > 1 ? Math.floor((full - 600) / ims.length) : Math.min(full - 400, ims[0].wide ? 44000 : 40000);
  const pics = ims.map((im) => {
    images.push(im);
    const id = `image${images.length}`;
    const w = each, hgt = Math.round((w * im.height) / im.width);
    const ow = im.width * 75, oh = im.height * 75;
    const sx = (w / ow).toFixed(6), sy = (hgt / oh).toFixed(6);
    return { hgt, xml: `<hp:pic id="${b.next()}" zOrder="0" numberingType="PICTURE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" href="" groupLevel="0" instid="${b.next()}" reverse="0">`
    + `<hp:offset x="0" y="0"/><hp:orgSz width="${ow}" height="${oh}"/><hp:curSz width="${w}" height="${hgt}"/><hp:flip horizontal="0" vertical="0"/>`
    + `<hp:rotationInfo angle="0" centerX="${Math.round(w / 2)}" centerY="${Math.round(hgt / 2)}" rotateimage="1"/>`
    + `<hp:renderingInfo><hc:transMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:scaMatrix e1="${sx}" e2="0" e3="0" e4="0" e5="${sy}" e6="0"/><hc:rotMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/></hp:renderingInfo>`
    + `<hp:imgRect><hc:pt0 x="0" y="0"/><hc:pt1 x="${ow}" y="0"/><hc:pt2 x="${ow}" y="${oh}"/><hc:pt3 x="0" y="${oh}"/></hp:imgRect>`
    + `<hp:imgClip left="0" right="${ow}" top="0" bottom="${oh}"/><hp:inMargin left="0" right="0" top="0" bottom="0"/><hp:imgDim dimwidth="${ow}" dimheight="${oh}"/>`
    + `<hc:img binaryItemIDRef="${id}" bright="0" contrast="0" effect="REAL_PIC" alpha="0"/><hp:effects/>`
    + `<hp:sz width="${w}" widthRelTo="ABSOLUTE" height="${hgt}" heightRelTo="ABSOLUTE" protect="0"/>`
    + `<hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>`
    + `<hp:outMargin left="0" right="${ims.length > 1 ? 150 : 0}" top="0" bottom="0"/></hp:pic>` };
  });
  const lineH = Math.max(...pics.map((p) => p.hgt)) + 200;
  // 줄 높이를 그림 높이로 알려 주어(linesegarray) 앞뒤 내용과 겹치거나 잘리지 않게 한다.
  b.raw(`<hp:p id="${b.next()}" paraPrIDRef="${P_CENTER}" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="0">${pics.map((p) => p.xml).join("")}<hp:t/></hp:run>`
    + `<hp:linesegarray><hp:lineseg textpos="0" vertpos="0" vertsize="${lineH}" textheight="${lineH}" baseline="${Math.round(lineH * 0.85)}" spacing="0" horzpos="0" horzsize="${full}" flags="393216"/></hp:linesegarray></hp:p>`);
}

/** 일반 보고서 → HWPX Blob */
export async function hwpxReport(doc) {
  const [pw, ph] = PAPERS.A4;
  const pageW = pw * 100, pageH = ph * 100;
  const b = new Body(pageW - MARGIN * 2);
  const today = new Date().toISOString().slice(0, 10);
  const images = [];
  b.p(doc.title, C_TITLE);
  if (doc.subtitle) b.p(doc.subtitle, C_SUBTITLE);
  (doc.meta || []).forEach((m) => b.p(m, C_META));
  for (const s of doc.sections) {
    b.p("");
    b.p(s.title, C_SECTION, 0, P_KEEP);
    if (s.note) b.p(s.note, C_META, 0, P_KEEP);
    for (let bi = 0; bi < s.blocks.length; bi++) {
      const blk = s.blocks[bi];
      if (blk.kv) reportTable(b, { head: ["항목", "내용"], rows: blk.kv.filter(([, v]) => v), weights: [1, 3], num: () => false }, pageW);
      else if (blk.table) reportTable(b, blk.table, pageW);
      else if (blk.image) {
        // half(작은 그래프)는 두 개씩 한 줄에 나란히 놓는다.
        const nx = s.blocks[bi + 1];
        if (blk.half && nx?.image?.half) { imageParagraph(b, [blk.image, nx.image], images, pageW); bi++; }
        else imageParagraph(b, [blk.image], images, pageW);
      } else if (blk.heading) b.p(blk.heading, C_ITEM_BOLD, 0, P_KEEP);
      else if (blk.text != null) lines(blk.text).forEach((l) => b.p(l, blk.gray ? C_GRAY : C_BODY, blk.indent || 0));
    }
  }
  b.p("");
  (doc.footer || []).filter((l) => l && l.trim()).forEach((l) => b.p(l, C_FOOT));

  const first = (await res("first_paragraph.xml")).replace("{{LANDSCAPE}}", "WIDELY").replace("{{WIDTH}}", pageW).replace("{{HEIGHT}}", pageH).replaceAll("{{MARGIN}}", MARGIN);
  const section = (await res("section_head.xml")) + first + b.sb.join("") + "</hs:sec>";
  const title = doc.subtitle ? `${doc.title} - ${doc.subtitle}` : doc.title;
  const hpf = (await res("content.hpf")).replaceAll("{{TITLE}}", esc(title)).replaceAll("{{DATE}}", today)
    .replace("</opf:manifest>", images.map((_, i) => `<opf:item id="image${i + 1}" href="BinData/image${i + 1}.png" media-type="image/png" isEmbeded="1"/>`).join("") + "</opf:manifest>");
  return zip([
    ["mimetype", "application/hwp+zip"],
    ["version.xml", await res("version.xml")],
    ["settings.xml", await res("settings.xml")],
    ["META-INF/container.xml", await res("container.xml")],
    ["META-INF/manifest.xml", await res("manifest.xml")],
    ["META-INF/container.rdf", await res("container.rdf")],
    ["Contents/content.hpf", hpf],
    ["Contents/header.xml", await res("header.xml")],
    ["Contents/section0.xml", section],
    ...images.map((im, i) => [`BinData/image${i + 1}.png`, im.png]),
    ["Preview/PrvText.txt", title],
  ], "application/hwp+zip");
}
