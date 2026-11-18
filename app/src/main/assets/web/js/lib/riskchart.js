// 리스크 대시보드용 순수 SVG 차트(외부 의존 없음). 문자열(SVG)을 돌려주며, 화면에서 html로 넣는다.
// 색은 app.css의 변수(--acc, --danger …)를 따라 다크 모드·테마를 그대로 쓴다.
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const num = (v) => typeof v === "number" && Number.isFinite(v);

export const monthShort = (m) => (/^\d{6}$/.test(m) ? `${m.slice(2, 4)}.${m.slice(4)}` : String(m));

/** 눈금: min~max 사이 읽기 좋은 값 n개 안팎. */
export function niceTicks(min, max, n = 4) {
  if (!(max > min)) return [min];
  const raw = (max - min) / n;
  const p = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * p).find((s) => s >= raw) || raw;
  const out = [];
  for (let v = Math.ceil(min / step) * step; v <= max + step * 1e-9; v += step) out.push(+v.toFixed(10));
  return out;
}
const fmtTick = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1).replace(/\.0$/, "") : v.toFixed(2).replace(/0$/, "").replace(/\.$/, ""));

/**
 * 꺾은선. series: [{ name, color, dash?, pts: [[라벨, 값|null], …] }] (라벨 순서는 모든 계열이 같다고 본다)
 * 값이 없는 점(null)은 이어 그리지 않고 끊는다.
 */
export function lineChartSvg({ series, unit = "%", width = 560, height = 200 }) {
  const labels = [...new Set(series.flatMap((s) => s.pts.map((p) => p[0])))];
  const vals = series.flatMap((s) => s.pts.map((p) => p[1])).filter(num);
  if (!labels.length || !vals.length) return `<svg viewBox="0 0 ${width} 60" class="rk-chart" role="img" aria-label="자료 없음"><text x="${width / 2}" y="34" text-anchor="middle" class="rk-t">표시할 자료가 없습니다</text></svg>`;
  let lo = Math.min(...vals), hi = Math.max(...vals);
  if (lo === hi) { lo -= 1; hi += 1; }
  const pad = (hi - lo) * 0.12;
  lo -= pad; hi += pad;
  const ml = 44, mr = 14, mt = 12, mb = 26;
  const w = width - ml - mr, h = height - mt - mb;
  const X = (i) => ml + (labels.length === 1 ? w / 2 : (i * w) / (labels.length - 1));
  const Y = (v) => mt + h - ((v - lo) / (hi - lo)) * h;
  const ticks = niceTicks(lo, hi, 4);
  let svg = `<svg viewBox="0 0 ${width} ${height}" class="rk-chart" role="img" aria-label="추이 차트">`;
  for (const t of ticks) svg += `<line x1="${ml}" x2="${width - mr}" y1="${Y(t)}" y2="${Y(t)}" class="rk-grid"/><text x="${ml - 6}" y="${Y(t) + 4}" text-anchor="end" class="rk-t">${fmtTick(t)}</text>`;
  const step = Math.ceil(labels.length / 8);
  labels.forEach((l, i) => { if (i % step === 0 || i === labels.length - 1) svg += `<text x="${X(i)}" y="${height - 8}" text-anchor="middle" class="rk-t">${esc(monthShort(l))}</text>`; });
  for (const s of series) {
    const byLabel = new Map(s.pts);
    let d = "";
    let pen = false;
    labels.forEach((l, i) => {
      const v = byLabel.get(l);
      if (!num(v)) { pen = false; return; }
      d += `${pen ? "L" : "M"}${X(i).toFixed(1)} ${Y(v).toFixed(1)} `;
      pen = true;
    });
    svg += `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="${s.dash ? 1.6 : 2.4}" ${s.dash ? 'stroke-dasharray="5 4"' : ""} stroke-linejoin="round" stroke-linecap="round"/>`;
    if (!s.dash) labels.forEach((l, i) => { const v = byLabel.get(l); if (num(v)) svg += `<circle cx="${X(i).toFixed(1)}" cy="${Y(v).toFixed(1)}" r="${i === labels.length - 1 ? 4 : 2.6}" fill="${s.color}"><title>${esc(s.name)} ${esc(monthShort(l))}: ${v.toFixed(2)}${esc(unit)}</title></circle>`; });
  }
  return svg + "</svg>";
}

/** 세로 막대(연도별 건수 등). bars: [{ label, parts: [{ value, color, title }] }] 쌓아 그린다. */
export function stackedBarsSvg({ bars, width = 560, height = 120 }) {
  const total = (b) => b.parts.reduce((a, p) => a + p.value, 0);
  const max = Math.max(1, ...bars.map(total));
  const ml = 24, mr = 6, mt = 14, mb = 22;
  const w = width - ml - mr, h = height - mt - mb;
  const slot = w / Math.max(1, bars.length);
  const bw = Math.min(34, slot * 0.66);
  let svg = `<svg viewBox="0 0 ${width} ${height}" class="rk-chart" role="img" aria-label="연도별 제재 건수">`;
  svg += `<line x1="${ml}" x2="${width - mr}" y1="${mt + h}" y2="${mt + h}" class="rk-grid"/>`;
  bars.forEach((b, i) => {
    const x = ml + slot * i + (slot - bw) / 2;
    let y = mt + h;
    for (const p of b.parts) {
      if (!p.value) continue;
      const bh = (p.value / max) * h;
      y -= bh;
      svg += `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${bh.toFixed(1)}" rx="2" fill="${p.color}"><title>${esc(b.label)} ${esc(p.title)} ${p.value}건</title></rect>`;
    }
    const t = total(b);
    if (t) svg += `<text x="${(x + bw / 2).toFixed(1)}" y="${(y - 3).toFixed(1)}" text-anchor="middle" class="rk-t rk-b">${t}</text>`;
    svg += `<text x="${(x + bw / 2).toFixed(1)}" y="${height - 6}" text-anchor="middle" class="rk-t">${esc(b.label)}</text>`;
  });
  return svg + "</svg>";
}

/**
 * 사분면 산점도.
 * points: [{ id, name, x, y, self?, big? }]; 기준선 xMean·yMean(중앙값).
 * xHigherBetter=true(자본비율 등), yHigherBetter(부실비율은 false, 수익성은 true).
 * "취약 사분면"(자본 낮고 위험 높은 쪽)을 옅은 붉은색으로 깐다. 범위를 벗어난 극단값은 가장자리에 속이 빈 점으로 붙인다.
 */
export function quadrantSvg({ points, xMean, yMean, xLabel, yLabel, xUnit = "%", yUnit = "%", yHigherBetter = false, width = 600, height = 440, labelAll = false }) {
  const pts = points.filter((p) => num(p.x) && num(p.y));
  if (!pts.length) return `<svg viewBox="0 0 ${width} 80" class="rk-chart"><text x="${width / 2}" y="44" text-anchor="middle" class="rk-t">표시할 자료가 없습니다</text></svg>`;
  const q = (arr, f) => { const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.max(0, Math.round((s.length - 1) * f)))]; };
  const dom = (vals, mean) => {
    let lo = q(vals, 0.04), hi = q(vals, 0.96);
    lo = Math.min(lo, mean); hi = Math.max(hi, mean);
    if (lo === hi) { lo -= 1; hi += 1; }
    const pad = (hi - lo) * 0.14;
    return [lo - pad, hi + pad];
  };
  const [x0, x1] = dom(pts.map((p) => p.x), xMean);
  const [y0, y1] = dom(pts.map((p) => p.y), yMean);
  const ml = 52, mr = 16, mt = 16, mb = 44;
  const w = width - ml - mr, h = height - mt - mb;
  const X = (v) => ml + ((Math.min(x1, Math.max(x0, v)) - x0) / (x1 - x0)) * w;
  const Y = (v) => mt + h - ((Math.min(y1, Math.max(y0, v)) - y0) / (y1 - y0)) * h;
  const xm = X(xMean), ym = Y(yMean);
  // 취약: x는 평균보다 낮고, y는 나쁜 쪽(부실비율↑ 이면 위, 수익성↓ 이면 아래)
  const dy0 = yHigherBetter ? ym : mt, dy1 = yHigherBetter ? mt + h : ym;
  let svg = `<svg viewBox="0 0 ${width} ${height}" class="rk-chart rk-quad" role="img" aria-label="업권 내 위치(사분면)">`;
  svg += `<rect x="${ml}" y="${mt}" width="${w}" height="${h}" class="rk-plot"/>`;
  svg += `<rect x="${ml}" y="${dy0}" width="${xm - ml}" height="${dy1 - dy0}" class="rk-danger"/>`;
  for (const t of niceTicks(x0, x1, 5)) svg += `<line x1="${X(t)}" x2="${X(t)}" y1="${mt}" y2="${mt + h}" class="rk-grid"/><text x="${X(t)}" y="${mt + h + 15}" text-anchor="middle" class="rk-t">${fmtTick(t)}</text>`;
  for (const t of niceTicks(y0, y1, 4)) svg += `<line x1="${ml}" x2="${ml + w}" y1="${Y(t)}" y2="${Y(t)}" class="rk-grid"/><text x="${ml - 6}" y="${Y(t) + 4}" text-anchor="end" class="rk-t">${fmtTick(t)}</text>`;
  svg += `<line x1="${xm}" x2="${xm}" y1="${mt}" y2="${mt + h}" class="rk-mean"/><line x1="${ml}" x2="${ml + w}" y1="${ym}" y2="${ym}" class="rk-mean"/>`;
  svg += `<text x="${ml + 6}" y="${dy0 + 14}" class="rk-t rk-corner rk-dn">취약 구간</text>`;
  svg += `<text x="${xm + 4}" y="${mt + 11}" class="rk-t">업권 중앙값 ${fmtTick(xMean)}${esc(xUnit)}</text>`;
  svg += `<text x="${ml + w - 4}" y="${ym - 4}" text-anchor="end" class="rk-t">중앙값 ${fmtTick(yMean)}${esc(yUnit)}</text>`;
  svg += `<text x="${ml + w / 2}" y="${height - 6}" text-anchor="middle" class="rk-t rk-ax">${esc(xLabel)} →</text>`;
  svg += `<text transform="translate(12 ${mt + h / 2}) rotate(-90)" text-anchor="middle" class="rk-t rk-ax">${esc(yLabel)} →</text>`;
  const placed = [];
  const hit = (b) => placed.some((p) => !(b.x1 < p.x0 || b.x0 > p.x1 || b.y1 < p.y0 || b.y0 > p.y1));
  // 큰 회사·내 회사를 먼저 그리고 글씨가 겹치면 생략
  const order = [...pts].sort((a, b) => (b.self ? 1 : 0) - (a.self ? 1 : 0) || (b.big ?? 0) - (a.big ?? 0));
  const dots = [], labels = [];
  for (const p of order) {
    const cx = X(p.x), cy = Y(p.y);
    const out = p.x < x0 || p.x > x1 || p.y < y0 || p.y > y1;
    const r = p.self ? 7 : 4.6;
    dots.push(`<g class="rk-pt${p.self ? " self" : ""}" data-id="${esc(p.id)}" tabindex="0" role="button" aria-label="${esc(p.name)}"><circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="${r}" class="${out ? "rk-out" : ""}"/><circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="14" fill="transparent"/><title>${esc(p.name)}\n${esc(xLabel)} ${p.x.toFixed(2)}${esc(xUnit)} · ${esc(yLabel)} ${p.y.toFixed(2)}${esc(yUnit)}${out ? "\n(축 범위 밖: 가장자리에 표시)" : ""}</title></g>`);
    const text = p.name.length > 9 ? p.name.slice(0, 8) + "…" : p.name;
    const lw = text.length * 10.5 + 4;
    const right = cx + r + 3 + lw < width - 4;
    const b = right ? { x0: cx + r + 2, x1: cx + r + 3 + lw, y0: cy - 8, y1: cy + 8 } : { x0: cx - r - 3 - lw, x1: cx - r - 2, y0: cy - 8, y1: cy + 8 };
    if ((p.self || labelAll || order.indexOf(p) < 8) && !hit(b)) {
      placed.push(b);
      labels.push(`<text x="${right ? cx + r + 3 : cx - r - 3}" y="${cy + 4}" text-anchor="${right ? "start" : "end"}" class="rk-t rk-nm${p.self ? " self" : ""}">${esc(text)}</text>`);
    }
  }
  return svg + dots.join("") + labels.join("") + "</svg>";
}


/** 분포 히스토그램(distribution() 결과). 사분위 범위를 띠로, 중앙값을 선으로 표시한다. */
export function histogramSvg({ dist, unit = "%", width = 520, height = 150, highlight = null }) {
  if (!dist) return `<svg viewBox="0 0 ${width} 60" class="rk-chart"><text x="${width / 2}" y="34" text-anchor="middle" class="rk-t">자료가 없습니다</text></svg>`;
  const ml = 30, mr = 10, mt = 14, mb = 24;
  const w = width - ml - mr, h = height - mt - mb;
  const max = Math.max(1, ...dist.bins.map((b) => b.n));
  const X = (v) => ml + ((Math.min(dist.hi, Math.max(dist.lo, v)) - dist.lo) / (dist.hi - dist.lo)) * w;
  const bw = w / dist.bins.length;
  let svg = `<svg viewBox="0 0 ${width} ${height}" class="rk-chart" role="img" aria-label="분포">`;
  svg += `<line x1="${ml}" x2="${width - mr}" y1="${mt + h}" y2="${mt + h}" class="rk-grid"/>`;
  svg += `<rect x="${X(dist.q1)}" y="${mt}" width="${Math.max(1, X(dist.q3) - X(dist.q1))}" height="${h}" class="rk-iqr"/>`;
  dist.bins.forEach((b, i) => {
    const bh = (b.n / max) * h;
    svg += `<rect x="${(ml + bw * i + 1).toFixed(1)}" y="${(mt + h - bh).toFixed(1)}" width="${(bw - 2).toFixed(1)}" height="${bh.toFixed(1)}" rx="2" class="rk-bar"><title>${fmtTick(b.x0)}~${fmtTick(b.x1)}${esc(unit)}: ${b.n}곳</title></rect>`;
    if (b.n) svg += `<text x="${(ml + bw * i + bw / 2).toFixed(1)}" y="${(mt + h - bh - 3).toFixed(1)}" text-anchor="middle" class="rk-t">${b.n}</text>`;
  });
  svg += `<line x1="${X(dist.med)}" x2="${X(dist.med)}" y1="${mt - 4}" y2="${mt + h}" class="rk-mean"/>`;
  if (highlight != null && Number.isFinite(highlight)) svg += `<path d="M${X(highlight)} ${mt + h + 2} l-5 8 h10 z" class="rk-hl"/>`;
  for (const t of niceTicks(dist.lo, dist.hi, 5)) svg += `<text x="${X(t)}" y="${height - 6}" text-anchor="middle" class="rk-t">${fmtTick(t)}</text>`;
  return svg + "</svg>";
}
