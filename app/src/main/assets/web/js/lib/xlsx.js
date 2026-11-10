// 외부 라이브러리 없이 엑셀(.xlsx)을 만든다(앱의 XlsxWriter.kt를 옮김). 압축 없는 zip으로 묶는다.
// sheet = { name, header:[...], rows:[[...]], widths:[...], merges:[[col, firstRow, lastRow]] } (행 번호는 rows 기준 0부터)

const enc = new TextEncoder();
const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** [이름, 글] 목록을 압축 없는 zip Blob으로 */
export function zip(files, type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const [name, text] of files) {
    const data = enc.encode(text);
    const nameBytes = enc.encode(name);
    const crc = crc32(data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true); // UTF-8 이름
    local.setUint16(8, 0, true); // 압축 안 함
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);
    parts.push(new Uint8Array(local.buffer), nameBytes, data);
    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true);
    cd.setUint16(4, 20, true);
    cd.setUint16(6, 20, true);
    cd.setUint16(8, 0x0800, true);
    cd.setUint32(16, crc, true);
    cd.setUint32(20, data.length, true);
    cd.setUint32(24, data.length, true);
    cd.setUint16(28, nameBytes.length, true);
    cd.setUint32(42, offset, true);
    central.push(new Uint8Array(cd.buffer), nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const cdSize = central.reduce((s, p) => s + p.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, cdSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, new Uint8Array(end.buffer)], { type });
}

const esc = (s) => String(s).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
export function col(i) {
  let n = i + 1;
  let s = "";
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

function rowXml(n, cells, header, merged) {
  let s = `<row r="${n}">`;
  cells.forEach((v, i) => {
    const ref = col(i) + n;
    if (merged.has(i) && v == null) s += `<c r="${ref}" s="4"/>`;
    else if (v == null) return;
    else if (!header && typeof v === "number") s += `<c r="${ref}" s="2"><v>${v}</v></c>`;
    else {
      const text = String(v);
      const style = header ? 1 : merged.has(i) ? 4 : text.includes("\n") ? 3 : 0;
      s += `<c r="${ref}" t="inlineStr" s="${style}"><is><t xml:space="preserve">${esc(text)}</t></is></c>`;
    }
  });
  return s + "</row>";
}

function sheetXml(sh) {
  const out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
    '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'];
  if (sh.widths?.length) out.push("<cols>" + sh.widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join("") + "</cols>");
  out.push("<sheetData>", rowXml(1, sh.header, true, new Set()));
  const merges = (sh.merges || []).filter(([, a, b]) => b > a);
  const merged = new Map();
  for (const [c, a, b] of merges) for (let r = a; r <= b; r++) (merged.get(r) || merged.set(r, new Set()).get(r)).add(c);
  sh.rows.forEach((r, i) => out.push(rowXml(i + 2, r, false, merged.get(i) || new Set())));
  out.push("</sheetData>");
  if (sh.rows.length) out.push(`<autoFilter ref="A1:${col(sh.header.length - 1)}${sh.rows.length + 1}"/>`);
  if (merges.length) out.push(`<mergeCells count="${merges.length}">` + merges.map(([c, a, b]) => `<mergeCell ref="${col(c)}${a + 2}:${col(c)}${b + 2}"/>`).join("") + "</mergeCells>");
  out.push("</worksheet>");
  return out.join("");
}

/** 시트 목록 → .xlsx Blob */
export function xlsx(sheets) {
  const x = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  const files = [
    ["[Content_Types].xml", x + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
      sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join("") + "</Types>"],
    ["_rels/.rels", x + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
    ["xl/workbook.xml", x + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>' +
      sheets.map((s, i) => `<sheet name="${esc(s.name.replace(/[[\]:*?/\\]/g, " ").slice(0, 31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join("") + "</sheets></workbook>"],
    ["xl/_rels/workbook.xml.rels", x + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join("") +
      `<Relationship Id="rId${sheets.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
    ["xl/styles.xml", x + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="10"/><name val="맑은 고딕"/></font><font><b/><sz val="10"/><name val="맑은 고딕"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8ECFB"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/><xf numFmtId="3" applyNumberFormat="1"/><xf applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf><xf applyAlignment="1"><alignment vertical="center"/></xf></cellXfs></styleSheet>'],
    ...sheets.map((s, i) => [`xl/worksheets/sheet${i + 1}.xml`, sheetXml(s)]),
  ];
  return zip(files);
}
