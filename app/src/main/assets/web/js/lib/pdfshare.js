// 저장 미리보기의 문서를 PDF 파일로 만든다(공유용). 브라우저 인쇄 기능은 파일을 넘길 수 없어서, 화면에 그려진 문서를 그림으로 찍어 쪽(A4)별로 나눠 PDF에 담는다.
// 그림 방식이라 글자를 선택·검색할 수는 없고, 한글(HWPX)보다 파일이 큽니다. html2canvas는 필요할 때만 불러온다.
let loading;
function loadCanvasLib() {
  if (window.html2canvas) return Promise.resolve(window.html2canvas);
  loading ||= new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = new URL("./vendor/html2canvas.min.js", import.meta.url).href;
    s.onload = () => (window.html2canvas ? resolve(window.html2canvas) : reject(new Error("PDF 만드는 도구를 불러오지 못했습니다.")));
    s.onerror = () => ((loading = null), reject(new Error("PDF 만드는 도구를 불러오지 못했습니다. 인터넷 연결을 확인해 주세요.")));
    document.head.append(s);
  });
  return loading;
}

const A4 = { w: 595.28, h: 841.89, margin: 40 }; // pt

/** 문서 안에서 쪽을 나눠도 되는 세로 위치(블록·표 줄 경계)들. */
function breakPoints(el) {
  const top = el.getBoundingClientRect().top;
  const pts = new Set([0]);
  for (const c of el.children) pts.add(Math.round(c.getBoundingClientRect().top - top));
  for (const tr of el.querySelectorAll("tr")) pts.add(Math.round(tr.getBoundingClientRect().top - top));
  return [...pts].sort((a, b) => a - b);
}

const bytes = (s) => {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 255;
  return out;
};

/** JPEG 쪽 그림들로 PDF 파일을 만든다. pages: [{ jpeg: Uint8Array, w, h(px) }] */
export function pdfFromJpegs(pages, landscape = false) {
  const parts = [];
  const offsets = [];
  let len = 0;
  const push = (d) => {
    const b = typeof d === "string" ? bytes(d) : d;
    parts.push(b);
    len += b.length;
  };
  const obj = (n, body) => {
    offsets[n] = len;
    push(`${n} 0 obj\n`);
    push(body);
    push("\nendobj\n");
  };
  push("%PDF-1.4\n%\xE2\xE3\xCF\xD3\n");
  const n = pages.length;
  // 1: 카탈로그, 2: 쪽 목록, 쪽마다 3개 객체(쪽, 내용, 그림)
  obj(1, "<< /Type /Catalog /Pages 2 0 R >>");
  obj(2, `<< /Type /Pages /Count ${n} /Kids [${pages.map((_, i) => `${3 + i * 3} 0 R`).join(" ")}] >>`);
  pages.forEach((p, i) => {
    const base = 3 + i * 3;
    const w = landscape ? A4.h : A4.w;
    const hh = (p.h / p.w) * w; // 쪽 높이는 그림 비율대로(마지막 쪽은 짧다)
    obj(base, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${w.toFixed(2)} ${hh.toFixed(2)}] /Resources << /XObject << /Im0 ${base + 2} 0 R >> >> /Contents ${base + 1} 0 R >>`);
    const content = `q ${w.toFixed(2)} 0 0 ${hh.toFixed(2)} 0 0 cm /Im0 Do Q`;
    obj(base + 1, `<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
    offsets[base + 2] = len;
    push(`${base + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${p.w} /Height ${p.h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.length} >>\nstream\n`);
    push(p.jpeg);
    push("\nendstream\nendobj\n");
  });
  const total = 3 + n * 3;
  const xref = len;
  push(`xref\n0 ${total}\n0000000000 65535 f \n`);
  for (let i = 1; i < total; i++) push(String(offsets[i]).padStart(10, "0") + " 00000 n \n");
  push(`trailer\n<< /Size ${total} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
  return new Blob(parts, { type: "application/pdf" });
}

/** 화면의 저장용 문서(el)를 A4 쪽으로 나눈 PDF Blob으로 만든다. */
export async function elementToPdf(el, { landscape = false } = {}) {
  const html2canvas = await loadCanvasLib();
  const cssW = el.scrollWidth;
  const cssH = el.scrollHeight;
  const scale = Math.max(1, Math.min(2, 15000 / cssH, 4000 / cssW)); // 휴대폰 캔버스 한도 안에서
  const breaks = breakPoints(el);
  const canvas = await html2canvas(el, { scale, backgroundColor: "#ffffff", useCORS: true, logging: false, windowWidth: cssW });
  const pageH = cssW * (landscape ? A4.w / A4.h : A4.h / A4.w); // 문서 폭 기준 한 쪽 높이(css px)
  const pages = [];
  let start = 0;
  while (start < cssH - 1) {
    const limit = start + pageH;
    let end = limit;
    if (limit < cssH) {
      const fit = breaks.filter((b) => b > start + pageH * 0.45 && b <= limit);
      if (fit.length) end = fit[fit.length - 1]; // 줄·블록 사이에서 끊는다
    } else end = cssH;
    const sy = Math.round(start * scale);
    const sh = Math.max(1, Math.min(canvas.height - sy, Math.round((end - start) * scale)));
    const c = document.createElement("canvas");
    c.width = canvas.width;
    c.height = Math.max(1, Math.round(pageH * scale)); // 모든 쪽 같은 크기(아래는 흰색)
    const ctx = c.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(canvas, 0, sy, canvas.width, sh, 0, 0, canvas.width, sh);
    const blob = await new Promise((r) => c.toBlob(r, "image/jpeg", 0.85));
    if (!blob) throw new Error("PDF 쪽 그림을 만들지 못했습니다.");
    pages.push({ jpeg: new Uint8Array(await blob.arrayBuffer()), w: c.width, h: c.height });
    start = end;
    if (pages.length > 60) throw new Error("문서가 너무 길어 PDF로 만들 수 없습니다.");
  }
  return pdfFromJpegs(pages, landscape);
}
