// 저장 문서(PDF·한글)에 넣을 최근 1년 주가 그래프(PNG). 상장사(종목코드 6자리)만 만든다.
import { history } from "./market.js";
import { pngOf } from "./dashchart.js";

const dot = (d) => `${d.slice(0, 4)}.${d.slice(4, 6)}.${d.slice(6, 8)}`;
const n0 = (v) => Math.round(v).toLocaleString("en-US");

/** canvas로 선그래프를 그려 PNG 바이트와 data URL을 돌려준다. 실패하면 null. */
function drawPng(dates, closes, W = 1200, H = 480) {
  const cv = document.createElement("canvas");
  cv.width = W;
  cv.height = H;
  const g = cv.getContext("2d");
  if (!g) return null;
  g.fillStyle = "#fff";
  g.fillRect(0, 0, W, H);
  const L = 110, R = 30, T = 30, B = 56;
  const lo0 = Math.min(...closes), hi0 = Math.max(...closes);
  const pad = (hi0 - lo0) * 0.08 || hi0 * 0.05 || 1;
  const lo = lo0 - pad, hi = hi0 + pad;
  const x = (i) => L + ((W - L - R) * i) / (closes.length - 1);
  const y = (v) => T + (H - T - B) * (1 - (v - lo) / (hi - lo));
  const font = '"Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",sans-serif';
  g.font = `22px ${font}`;
  g.textBaseline = "middle";
  g.strokeStyle = "#e2e6ee";
  g.fillStyle = "#5a6577";
  g.lineWidth = 1;
  g.textAlign = "right";
  for (let k = 0; k <= 4; k++) {
    const v = lo + ((hi - lo) * k) / 4;
    g.beginPath(); g.moveTo(L, y(v)); g.lineTo(W - R, y(v)); g.stroke();
    g.fillText(n0(v), L - 10, y(v));
  }
  // 월 눈금(분기마다 표시)
  g.textAlign = "center";
  g.textBaseline = "top";
  let last = "";
  dates.forEach((d, i) => {
    const ym = d.slice(0, 6);
    if (ym !== last) {
      last = ym;
      if (+ym.slice(4) % 3 === (+dates[0].slice(4, 6) % 3) || i === 0) {
        g.strokeStyle = "#eef1f6";
        g.beginPath(); g.moveTo(x(i), T); g.lineTo(x(i), H - B); g.stroke();
        g.fillStyle = "#5a6577";
        g.fillText(`${ym.slice(2, 4)}.${ym.slice(4)}`, Math.min(x(i), W - R - 20), H - B + 12);
      }
    }
  });
  const up = closes[closes.length - 1] >= closes[0];
  const color = up ? "#d6342c" : "#1f64d8"; // 국내 관례: 상승 빨강·하락 파랑
  const grad = g.createLinearGradient(0, T, 0, H - B);
  grad.addColorStop(0, up ? "rgba(214,52,44,.18)" : "rgba(31,100,216,.18)");
  grad.addColorStop(1, "rgba(255,255,255,0)");
  g.beginPath();
  closes.forEach((v, i) => (i ? g.lineTo(x(i), y(v)) : g.moveTo(x(i), y(v))));
  g.lineTo(x(closes.length - 1), H - B); g.lineTo(x(0), H - B); g.closePath();
  g.fillStyle = grad; g.fill();
  g.beginPath();
  closes.forEach((v, i) => (i ? g.lineTo(x(i), y(v)) : g.moveTo(x(i), y(v))));
  g.strokeStyle = color; g.lineWidth = 3; g.lineJoin = "round"; g.stroke();
  // 최고·최저·마지막 표시
  const iMax = closes.indexOf(hi0), iMin = closes.indexOf(lo0);
  g.font = `bold 21px ${font}`;
  const tag = (i, text, above) => {
    g.fillStyle = color; g.beginPath(); g.arc(x(i), y(closes[i]), 5, 0, 7); g.fill();
    g.fillStyle = "#1a2233";
    g.textAlign = x(i) > W - 150 ? "right" : x(i) < L + 90 ? "left" : "center";
    g.textBaseline = above ? "bottom" : "top";
    g.fillText(text, x(i), y(closes[i]) + (above ? -8 : 8));
  };
  tag(iMax, `최고 ${n0(hi0)}`, true);
  if (iMin !== iMax) tag(iMin, `최저 ${n0(lo0)}`, false);
  tag(closes.length - 1, `종가 ${n0(closes[closes.length - 1])}`, closes[closes.length - 1] >= (hi0 + lo0) / 2);
  return pngOf(cv);
}

/**
 * 저장 문서용 주가 구역({title, note, blocks}). 종목코드가 없거나 시세를 못 받으면 사유만 담은 구역을 돌려준다.
 * stock: 6자리 종목코드, cls: DART 시장 구분(K=코스닥, 그 외 유가증권).
 */
export async function stockSection(stock, cls) {
  const title = "주가 (최근 1년)";
  if (!/^\d{6}$/.test(stock || "")) return { title, blocks: [{ text: "비상장 회사이거나 종목코드를 찾지 못해 주가 그래프를 넣지 못했습니다.", gray: true }] };
  try {
    const hist = await history(`${stock}.${cls === "K" ? "KQ" : "KS"}`);
    const { dates, closes } = hist;
    if (!(closes?.length >= 2)) throw new Error("시세 자료 부족");
    const img = drawPng(dates, closes);
    const first = closes[0], last = closes[closes.length - 1];
    const r = ((last / first - 1) * 100).toFixed(1);
    const blocks = [];
    if (img) blocks.push({ image: { ...img, wide: true } });
    blocks.push({ table: { head: ["시작일 종가", "마지막 종가", "기간 등락", "52주 최고", "52주 최저"], weights: [1, 1, 1, 1, 1], num: () => true,
      rows: [[`${dot(dates[0])}\n${n0(first)}원`, `${dot(dates[dates.length - 1])}\n${n0(last)}원`, `${r > 0 ? "+" : ""}${r}%`, `${n0(hist.high)}원`, `${n0(hist.low)}원`]] } });
    return { title, note: `종목코드 ${stock} · 일별 종가 기준 · 시세 제공처 자료로 지연·오차가 있을 수 있습니다`, blocks };
  } catch {
    return { title, blocks: [{ text: "주가 자료를 불러오지 못해 그래프를 넣지 못했습니다.", gray: true }] };
  }
}
