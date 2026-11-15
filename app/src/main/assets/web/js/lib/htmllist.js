// 게시판 목록 HTML에서 (제목, 링크, 날짜)를 뽑는 파서. 앱의 HtmlListParser.kt를 그대로 옮겼다.
// 사이트별 마크업에 묶이지 않도록 "링크 주소 패턴"과 "링크 근처의 날짜"만 쓴다.
// 날짜는 "YYYY-MM-DD" 문자열로 돌려준다.

const noise = /<!--[\s\S]*?-->|<(script|style|noscript)\b[\s\S]*?<\/\1>/gi;
const anchorRe = () => /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
const hrefRe = /href\s*=\s*(?:"([^"]*)"|'([^']*)')/i;
const tagRe = /<[^>]+>/g;
const numericDate = /(20\d{2})\s*[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})/g;
const dmySlashDate = /(?<![\d/])(\d{1,2})\/(\d{1,2})\/(20\d{2})(?![\d/])/g;
const englishDate = /\b(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?![A-Za-z])\.?,?\s+(20\d{2})/gi;
const monthOnlyDate = /(?<![\d-])(20\d{2})-(0[1-9]|1[0-2])(?![\d-])/g;
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const rowOpen = /<(tr|li)\b/gi;
const rowClose = /<\/(tr|li)>/gi;
const boldBlock = /<(b|strong)\b[^>]*>([\s\S]*?)<\/\1>/gi;
const blockBreak = /<\/?(p|div|li|ul|ol|tr|td|th|dl|dt|dd|h[1-6]|br|section|article|table)\b[^>]*>/gi;
const genericLabel = /^(view report|cover note|read more|read|download|pdf|more|details?|view|자세히 ?보기|더 ?보기|바로 ?가기|바로 ?보기|원문 ?보기|다운로드|보기|상세 ?보기|첨부 ?파일?)$/i;
const positionLabel = /^[(（]?(선임|부|책임|초빙|객원|위촉|수석|전문)?\s?(연구위원|연구원|연구조원|위원|원장|부원장|실장|팀장|센터장|본부장|박사|교수)(\s|[)）]|$)[\s\S]*/;
const fullMonthDate = /\b(\d{1,2})\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+(20\d{2})/g;
const textNode = />([^<]+)</g;
const dotDate = /(20\d{2})\s*[.-]\s*(\d{1,2})\s*[.-]\s*(\d{1,2})/g;

const isPosition = (t) => t.length <= 40 && positionLabel.test(t);
const fullMatch = (re, t) => new RegExp(`^(?:${re.source})$`, re.flags.replace("g", "")).test(t);
const isGenericText = (t) => fullMatch(genericLabel, t);

function makeDate(y, m, d) {
  y = +y; m = +m; d = +d;
  if (m < 1 || m > 12 || d < 1 || d > 31) return null;
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCMonth() !== m - 1) return null;
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

function* all(re, text) {
  const r = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
  let m;
  while ((m = r.exec(text))) {
    yield m;
    if (m[0] === "") r.lastIndex++;
  }
}

export function abbreviateMonths(html) {
  return html.replace(fullMonthDate, (_, d, mon, y) => `${d} ${mon.slice(0, 3)} ${y}`);
}

export function decodeEntities(text) {
  let out = text.replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'");
  out = out.replace(/&#(x[0-9a-fA-F]+|\d+);/g, (m, code) => {
    const v = code[0].toLowerCase() === "x" ? parseInt(code.slice(1), 16) : parseInt(code, 10);
    return v >= 1 && v <= 0x10ffff ? String.fromCodePoint(v) : m;
  });
  return out.replace(/&amp;/g, "&");
}

export function compact(text) {
  return text.replace(/ /g, " ").replace(/\s+/g, " ").trim();
}

const stripTags = (s) => s.replace(tagRe, " ");

function resolve(base, link) {
  if (!link || /^javascript:/i.test(link) || link.startsWith("#")) return null;
  try {
    const u = new URL(link.replace(/ /g, "%20"), base || undefined);
    return u.protocol === "http:" || u.protocol === "https:" ? u.href : null;
  } catch {
    return null;
  }
}

function hrefOf(attrs) {
  const m = hrefRe.exec(attrs);
  return m ? m[1] || m[2] || "" : null;
}

function findDates(text) {
  const found = [];
  for (const m of all(numericDate, text)) {
    const d = makeDate(m[1], m[2], m[3]);
    if (d) found.push({ date: d, start: m.index, end: m.index + m[0].length });
  }
  for (const m of all(englishDate, text)) {
    const d = makeDate(m[3], MONTHS.indexOf(m[2].toLowerCase()) + 1, m[1]);
    if (d) found.push({ date: d, start: m.index, end: m.index + m[0].length });
  }
  for (const m of all(dmySlashDate, text)) {
    const d = makeDate(m[3], m[2], m[1]);
    if (d) found.push({ date: d, start: m.index, end: m.index + m[0].length });
  }
  if (!found.length) {
    for (const m of all(monthOnlyDate, text)) {
      const d = makeDate(m[1], m[2], 1);
      if (d) found.push({ date: d, start: m.index, end: m.index + m[0].length });
    }
  }
  return found.sort((a, b) => a.start - b.start);
}

function isTitleLike(text) {
  if (text.length < 6 || isGenericText(text)) return false;
  const d = findDates(text)[0];
  return !(d && d.start === 0 && d.end >= text.length - 1);
}

function contextTitle(clean, from, hit) {
  const windowHtml = clean.slice(Math.max(from, hit.start - 1200), hit.start);
  const bolds = [...all(boldBlock, windowHtml)].map((m) => compact(decodeEntities(stripTags(m[2])))).filter(isTitleLike);
  if (bolds.length) return bolds[bolds.length - 1];
  const opens = [...all(rowOpen, windowHtml)];
  const rowStart = opens.length ? opens[opens.length - 1].index : 0;
  const lines = stripTags(windowHtml.slice(rowStart).replace(blockBreak, "\n")).split("\n").map((l) => compact(decodeEntities(l))).filter(isTitleLike);
  return lines.reduce((best, l) => (l.length > best.length ? l : best), "");
}

function rowDate(clean, hit) {
  const windowStart = Math.max(0, hit.start - 1500);
  const opens = [...all(rowOpen, clean.slice(windowStart, hit.start))];
  if (!opens.length) return null;
  const open = opens[opens.length - 1];
  const closeRe = new RegExp(rowClose.source, "gi");
  closeRe.lastIndex = hit.end;
  const close = closeRe.exec(clean);
  if (!close || close.index - hit.end > 1500) return null;
  const row = clean.slice(windowStart + open.index, hit.start) + " " + clean.slice(hit.end, close.index);
  return findDates(row)[0]?.date || null;
}

function dateFor(clean, hit, prevEnd, nextStart) {
  const r = rowDate(clean, hit);
  if (r) return r;
  const afterText = clean.slice(hit.end, Math.min(nextStart, hit.end + 600));
  const beforeText = clean.slice(Math.max(prevEnd, hit.start - 300), hit.start);
  const after = findDates(afterText)[0];
  const bl = findDates(beforeText);
  const before = bl[bl.length - 1];
  if (!after) return before?.date || null;
  if (!before) return after.date;
  return beforeText.length - before.end < after.start ? before.date : after.date;
}

export function anchorCount(html) {
  return [...all(anchorRe(), html)].length;
}

/** 링크 주소가 [pattern]에 맞는 항목. 같은 링크는 가장 긴 제목만, 제목이 "보기" 같은 글이면 앞쪽 글에서 찾는다. */
export function extract(html, baseUrl, pattern, minTitleLength = 4) {
  const clean = html.replace(noise, " ");
  const hits = [];
  for (const m of all(anchorRe(), clean)) {
    const raw = hrefOf(m[1]);
    if (raw == null) continue;
    const decoded = decodeEntities(raw).trim();
    if (!pattern.test(decoded)) continue;
    const url = resolve(baseUrl, decoded);
    if (!url) continue;
    hits.push({ start: m.index, end: m.index + m[0].length, url, title: compact(decodeEntities(stripTags(m[2]))) });
  }
  const byUrl = new Map();
  for (const hit of hits) {
    const ex = byUrl.get(hit.url);
    if (!ex || hit.title.length > ex.title.length) byUrl.set(hit.url, { ...hit, start: ex ? ex.start : hit.start });
  }
  const ordered = [...byUrl.values()].sort((a, b) => a.start - b.start);
  const out = [];
  ordered.forEach((hit, i) => {
    const prevEnd = i > 0 ? ordered[i - 1].end : 0;
    const title = !hit.title || isGenericText(hit.title) ? contextTitle(clean, prevEnd, hit) : hit.title;
    if (title.length < minTitleLength) return;
    const nextStart = i + 1 < ordered.length ? ordered[i + 1].start : clean.length;
    out.push({ title, url: hit.url, date: dateFor(clean, hit, prevEnd, nextStart) });
  });
  return out;
}

const viewLink = /href\s*=\s*["']([^"']*flexer\/view\?[^"']*)["']/gi;
const downloadLink = /href\s*=\s*["']([^"']*common\/downloadw\?[^"']*)["']/gi;

/** 자본시장연구원 보고서 목록: 글 조각 순서로 "날짜로 끝나는 짧은 조각"을 기준점 삼아 제목·저자·링크를 읽는다. */
export function extractReports(html, baseUrl) {
  const clean = html.replace(noise, " ");
  const nodes = [...all(textNode, clean)]
    .map((m) => ({ text: compact(decodeEntities(m[1])), start: m.index + 1, end: m.index + 1 + m[1].length }))
    .filter((n) => n.text);
  const lastDot = (t) => {
    const l = [...all(dotDate, t)];
    return l[l.length - 1];
  };
  const anchors = [];
  nodes.forEach((n, i) => {
    const m = lastDot(n.text);
    if (m && m.index + m[0].length - 1 >= n.text.length - 1 && n.text.length <= 40) anchors.push(i);
  });
  const out = [];
  anchors.forEach((i, n) => {
    const node = nodes[i];
    const dm = lastDot(node.text);
    const date = makeDate(dm[1], dm[2], dm[3]);
    if (!date) return;
    const parts = [];
    const lead = node.text.slice(0, dm.index).trim();
    if (lead) parts.unshift(lead);
    const prevAnchor = n > 0 ? anchors[n - 1] : -1;
    let k = i - 1;
    while (k > prevAnchor && (nodes[k].text.length < 12 || isPosition(nodes[k].text)) && parts.length < 4 && !new RegExp(dotDate.source).test(nodes[k].text)) {
      parts.unshift(nodes[k].text);
      k--;
    }
    let title = "";
    for (let t = k; t > prevAnchor && t >= 0; t--) {
      const c = nodes[t].text;
      if (c.length >= 8 && !isGenericText(c) && !isPosition(c)) {
        title = c;
        break;
      }
    }
    if (!title) return;
    const nextStart = n + 1 < anchors.length ? nodes[anchors[n + 1]].start : clean.length;
    const region = clean.slice(node.end, nextStart);
    const views = [...all(viewLink, region)].map((m) => decodeEntities(m[1]));
    const downs = [...all(downloadLink, region)].map((m) => decodeEntities(m[1]));
    const pick = views.find((v) => v.includes("fty=004003")) || downs.find((v) => v.includes("fty=004003")) || views[0] || downs[0];
    out.push({ title, url: (pick && resolve(baseUrl, pick)) || baseUrl, date, author: parts.join(" ").trim() || null });
  });
  return dedupe(out);
}

/** extractReports의 보완: 보고서 링크(fid)마다 앞쪽 글에서 제목·날짜를 읽는다. */
export function extractReportsByLink(html, baseUrl) {
  const clean = html.replace(noise, " ");
  const linkRegex = /href\s*=\s*["']([^"']*(?:flexer\/view|common\/downloadw)\?[^"']*)["']/gi;
  const links = [];
  for (const m of all(linkRegex, clean)) {
    const raw = decodeEntities(m[1]);
    const fid = /fid=(\d+)/.exec(raw)?.[1];
    const url = resolve(baseUrl, raw);
    if (fid && url) links.push({ fid, url, start: m.index, end: m.index + m[0].length, report: raw.includes("fty=004003") });
  }
  const groups = new Map();
  for (const l of links) (groups.get(l.fid) || groups.set(l.fid, []).get(l.fid)).push(l);
  const sorted = [...groups.values()].sort((a, b) => Math.min(...a.map((x) => x.start)) - Math.min(...b.map((x) => x.start)));
  let prevEnd = 0;
  const out = [];
  const okTitle = (t) => t.length >= 8 && !isGenericText(t) && !isPosition(t);
  for (const g of sorted) {
    const first = Math.min(...g.map((x) => x.start));
    const region = clean.slice(prevEnd, first);
    prevEnd = Math.max(...g.map((x) => x.end));
    const nodes = [...all(textNode, region)].map((m) => compact(decodeEntities(m[1]))).filter(Boolean);
    const dateIdx = nodes.findIndex((t) => new RegExp(dotDate.source).test(t));
    if (dateIdx < 0) continue;
    const dl = [...all(dotDate, nodes[dateIdx])];
    const dm = dl[dl.length - 1];
    const date = makeDate(dm[1], dm[2], dm[3]);
    if (!date) continue;
    const title = nodes.slice(0, dateIdx).reverse().find(okTitle) || nodes.slice(dateIdx + 1).find(okTitle);
    if (!title) continue;
    const author = nodes[dateIdx].slice(0, dm.index).trim() || nodes.slice(0, dateIdx).reverse().find(isPosition) || null;
    out.push({ title, url: (g.find((x) => x.report) || g[0]).url, date, author });
  }
  return dedupe(out);
}

function dedupe(list) {
  const seen = new Set();
  return list.filter((x) => {
    const k = x.url + x.title;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

const field = (body, name) => new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)</${name}>`, "i").exec(body)?.[1] || "";
const unwrapCdata = (t) => /<!\[CDATA\[([\s\S]*?)]]>/.exec(t)?.[1] ?? t;

function parseRssDate(value) {
  const text = compact(unwrapCdata(value));
  if (!text) return null;
  const t = Date.parse(text);
  if (!Number.isNaN(t)) {
    const d = new Date(t);
    return makeDate(d.getFullYear(), d.getMonth() + 1, d.getDate());
  }
  return findDates(text)[0]?.date || null;
}

/** RSS 2.0 <item>의 제목·링크·발행일. */
export function parseRss(xml, baseUrl) {
  const out = [];
  for (const m of all(/<item\b[\s\S]*?<\/item>/gi, xml)) {
    const body = m[0];
    const title = compact(decodeEntities(stripTags(unwrapCdata(field(body, "title")))));
    const link = compact(unwrapCdata(field(body, "link")));
    if (!title || !link) continue;
    const url = resolve(baseUrl, decodeEntities(link));
    if (!url) continue;
    out.push({ title, url, date: parseRssDate(field(body, "pubDate") || field(body, "dc:date")) });
  }
  return out;
}

/** 링크 패턴을 모를 때: 같은 행에 날짜가 있고 글자가 제목답게 긴 링크. */
export function extractLoose(html, baseUrl) {
  const clean = html.replace(noise, " ");
  const found = new Map();
  for (const m of all(anchorRe(), clean)) {
    const text = compact(decodeEntities(stripTags(m[2])));
    if (text.length < 8 || isGenericText(text) || findDates(text).length) continue;
    const hit = { start: m.index, end: m.index + m[0].length };
    const date = rowDate(clean, hit);
    if (!date) continue;
    const url = resolve(baseUrl, decodeEntities(hrefOf(m[1]) || "").trim()) || baseUrl;
    if (!found.has(text)) found.set(text, { title: text, url, date });
  }
  return [...found.values()];
}

/** 주소 패턴을 모르는 임의의 목록 페이지(사용자가 추가한 기관). */
export function extractGeneric(html, baseUrl) {
  const dated = extractLoose(html, baseUrl);
  if (dated.length) return dated;
  const clean = html.replace(noise, " ").replace(/<(nav|header|footer|aside)\b[\s\S]*?<\/\1>/gi, " ");
  let baseHost;
  try {
    baseHost = new URL(baseUrl).hostname.replace(/^www\./, "");
  } catch {
    return [];
  }
  const found = new Map();
  for (const m of all(anchorRe(), clean)) {
    const text = compact(decodeEntities(stripTags(m[2])));
    if (text.length < 10 || isGenericText(text)) continue;
    const raw = hrefOf(m[1]);
    if (raw == null) continue;
    const url = resolve(baseUrl, decodeEntities(raw).trim());
    if (!url) continue;
    if (new URL(url).hostname.replace(/^www\./, "") !== baseHost) continue;
    if (url.split("#")[0] === baseUrl.split("#")[0]) continue;
    if (!found.has(url)) found.set(url, { start: m.index, end: m.index + m[0].length, url, title: text });
    if (found.size >= 30) break;
  }
  const hits = [...found.values()];
  return hits.map((hit, i) => ({ title: hit.title, url: hit.url, date: dateFor(clean, hit, i > 0 ? hits[i - 1].end : 0, i + 1 < hits.length ? hits[i + 1].start : clean.length) }));
}

const labeledDate = /(first published|published date|date published|published)\s*:?/gi;
const dateOnlyNode = /^(\d{1,2}\s+[A-Za-z]{3,9}\s+20\d{2}|\d{1,2}\/\d{1,2}\/20\d{2}|20\d{2}[-./]\d{1,2}[-./]\d{1,2})$/;
const isNoiseNode = (t) => isGenericText(t) || dateOnlyNode.test(t);

function labeledDateIn(text) {
  for (const label of all(labeledDate, text)) {
    const from = label.index + label[0].length;
    const d = findDates(text.slice(from, from + 40))[0];
    if (d && d.start <= 8) return d.date;
  }
  return null;
}

/** 카드형 목록(BIS·MAS 등): 링크 안의 [분류·날짜·제목·설명] 조각에서 제목과 날짜를 고른다. */
export function extractCards(html, baseUrl, pattern, maxTitle = 220) {
  const clean = abbreviateMonths(html.replace(noise, " "));
  const cards = new Map();
  const strip = (u) => u.split("#")[0].split("?")[0];
  for (const m of all(anchorRe(), clean)) {
    const raw = hrefOf(m[1]);
    if (raw == null) continue;
    const decoded = decodeEntities(raw).trim();
    if (!pattern.test(decoded)) continue;
    const url = resolve(baseUrl, decoded);
    if (!url || strip(url) === strip(baseUrl)) continue;
    const nodes = [...all(textNode, ">" + m[2] + "<")].map((x) => compact(decodeEntities(x[1]))).filter(Boolean);
    if (!nodes.length) continue;
    const ex = cards.get(url);
    if (!ex || nodes.length > ex.nodes.length) cards.set(url, { url, start: ex ? ex.start : m.index, end: m.index + m[0].length, nodes });
  }
  const ordered = [...cards.values()].sort((a, b) => a.start - b.start);
  const out = [];
  ordered.forEach((c, i) => {
    const nodes = c.nodes;
    const dateIdx = nodes.findIndex((t) => {
      const d = findDates(t)[0];
      return d && d.start === 0 && d.end >= t.length - 1;
    });
    let title;
    let date = null;
    if (dateIdx >= 0 && nodes.length > 1) {
      date = findDates(nodes[dateIdx])[0].date;
      title = nodes.slice(dateIdx + 1).find((t) => isTitleLike(t) && !isNoiseNode(t)) || nodes.slice(0, dateIdx).reverse().find((t) => isTitleLike(t) && !isNoiseNode(t));
    } else {
      const cands = nodes.filter((t) => !isNoiseNode(t) && t.length >= 4 && !findDates(t).length);
      title = cands.reduce((best, t) => (!best || Math.min(t.length, maxTitle) > Math.min(best.length, maxTitle) ? t : best), null);
    }
    if (!title || title.length > maxTitle * 2) return;
    if (!date) date = labeledDateIn(nodes.join(" "));
    if (!date) {
      const next = i + 1 < ordered.length ? ordered[i + 1].start : clean.length;
      const after = compact(decodeEntities(stripTags(clean.slice(c.end, Math.min(next, c.end + 900)))));
      date = labeledDateIn(after) || findDates(after.slice(0, 160))[0]?.date || null;
    }
    if (!date) {
      const before = compact(decodeEntities(stripTags(clean.slice(Math.max(i > 0 ? ordered[i - 1].end : 0, c.start - 400), c.start))));
      const bl = findDates(before);
      date = labeledDateIn(before) || bl[bl.length - 1]?.date || null;
    }
    out.push({ title: title.slice(0, maxTitle), url: c.url, date });
  });
  return out;
}

/**
 * 금감원 게시판 가운데 카드형 목록(structured-item, 금융감독연구 자료실 등)에서 제목·저자·등록일을 읽는다.
 * 카드마다 저자(소속)·연월·권호·등록일 항목이 있으며, 등록일을 날짜로 쓴다. 앱의 HtmlListParser.extractFssCards와 같다.
 */
export function extractFssCards(html, baseUrl) {
  const clean = html.replace(noise, " ");
  const item = /<li\b[^>]*class="[^"]*structured-item[^"]*"[^>]*>([\s\S]*?)<\/li>\s*(?=<li\b[^>]*structured-item|<\/ul>)/gi;
  const link = /<a\b[^>]*href\s*=\s*"([^"]*nttId=\d+[^"]*)"[^>]*>([\s\S]*?)<\/a>/i;
  const field = /<dt[^>]*>([\s\S]*?)<\/dt>\s*<dd[^>]*>([\s\S]*?)<\/dd>/gi;
  const strip = (t) => compact(decodeEntities(String(t).replace(/<[^>]+>/g, " ")));
  const out = new Map();
  for (const m of clean.matchAll(item)) {
    const block = m[1];
    const l = block.match(link);
    if (!l) continue;
    const url = resolve(baseUrl, decodeEntities(l[1]).trim());
    const title = strip(l[2]);
    if (!url || !title) continue;
    const f = {};
    for (const x of block.matchAll(field)) f[strip(x[1])] = strip(x[2]);
    const date = findDates(f["등록일"] || "")[0]?.date || Object.values(f).map((v) => findDates(v)[0]?.date).find(Boolean) || null;
    if (!out.has(url)) out.set(url, { title, url, date, author: f["저자(소속)"] || null });
  }
  return [...out.values()];
}
