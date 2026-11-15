// 저장 문서용 추이 그래프(PNG): 회사 값과 업권 중앙값을 두 선으로 그린다. 값이 없는 시점은 건너뛴다.
const FONT = '"Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",sans-serif';

export function pngOf(cv) {
  const dataUrl = cv.toDataURL("image/png");
  const bin = atob(dataUrl.split(",")[1]);
  return { png: Uint8Array.from(bin, (c) => c.charCodeAt(0)), dataUrl, width: cv.width, height: cv.height };
}

/** series: [{ name, color, dash, pts:[[yyyymm, 값|null]] }], months: 가로축 시점 목록, unit: 값 뒤에 붙일 단위 */
export function trendImage({ title, months, series, unit = "%", W = 800, H = 340 }) {
  const k = W / 1200 * 1.25; // 작게 넣는 그림이라 글자는 비율보다 크게
  const cv = document.createElement("canvas");
  cv.width = W; cv.height = H;
  const g = cv.getContext("2d");
  if (!g) return null;
  g.fillStyle = "#fff"; g.fillRect(0, 0, W, H);
  const L = Math.round(100 * k), R = Math.round(36 * k), T = Math.round(70 * k), B = Math.round(52 * k);
  const vals = series.flatMap((s) => s.pts.map((p) => p[1]).filter((v) => v != null && Number.isFinite(v)));
  if (!vals.length) return null;
  let lo = Math.min(...vals), hi = Math.max(...vals);
  const pad = (hi - lo) * 0.15 || Math.abs(hi) * 0.1 || 1;
  lo -= pad; hi += pad;
  const x = (i) => L + ((W - L - R) * i) / Math.max(1, months.length - 1);
  const y = (v) => T + (H - T - B) * (1 - (v - lo) / (hi - lo));
  g.font = `bold ${Math.round(28 * k)}px ${FONT}`; g.fillStyle = "#1f3a68"; g.textAlign = "left"; g.textBaseline = "middle";
  g.fillText(title, L, T * 0.38);
  // 범례
  let lx = W - R;
  g.font = `${Math.round(22 * k)}px ${FONT}`; g.textAlign = "right";
  [...series].reverse().forEach((s) => {
    const w = g.measureText(s.name).width;
    g.fillStyle = "#4a5568"; g.fillText(s.name, lx, T * 0.38);
    g.strokeStyle = s.color; g.lineWidth = 3; g.setLineDash(s.dash ? [6, 4] : []);
    g.beginPath(); g.moveTo(lx - w - 40 * k, T * 0.38); g.lineTo(lx - w - 8 * k, T * 0.38); g.stroke(); g.setLineDash([]);
    lx -= w + 56 * k;
  });
  g.font = `${Math.round(20 * k)}px ${FONT}`; g.textBaseline = "middle"; g.textAlign = "right";
  for (let k = 0; k <= 3; k++) {
    const v = lo + ((hi - lo) * k) / 3;
    g.strokeStyle = "#e5e9f0"; g.lineWidth = 1;
    g.beginPath(); g.moveTo(L, y(v)); g.lineTo(W - R, y(v)); g.stroke();
    g.fillStyle = "#5a6577"; g.fillText(Math.abs(hi - lo) < 10 ? v.toFixed(2) : Math.round(v).toLocaleString("en-US"), L - 10, y(v));
  }
  g.textAlign = "center"; g.textBaseline = "top"; g.fillStyle = "#5a6577";
  months.forEach((m, i) => g.fillText(`${m.slice(2, 4)}.${m.slice(4)}`, x(i), H - B + 8));
  series.forEach((s) => {
    const idx = new Map(s.pts.map(([m, v]) => [m, v]));
    const pts = months.map((m, i) => [i, idx.get(m)]).filter(([, v]) => v != null && Number.isFinite(v));
    if (!pts.length) return;
    g.strokeStyle = s.color; g.lineWidth = 3; g.lineJoin = "round"; g.setLineDash(s.dash ? [6, 4] : []);
    g.beginPath(); pts.forEach(([i, v], k) => (k ? g.lineTo(x(i), y(v)) : g.moveTo(x(i), y(v)))); g.stroke(); g.setLineDash([]);
    g.fillStyle = s.color;
    pts.forEach(([i, v]) => { g.beginPath(); g.arc(x(i), y(v), 4, 0, 7); g.fill(); });
    const [li, lv] = pts[pts.length - 1];
    g.font = `bold ${Math.round(21 * k)}px ${FONT}`; g.textAlign = "right"; g.textBaseline = s.dash ? "top" : "bottom";
    g.fillText(`${Number(lv).toFixed(2)}${unit}`, x(li), y(lv) + (s.dash ? 10 : -10));
  });
  return pngOf(cv);
}
