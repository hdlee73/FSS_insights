// 금융사 리스크 대시보드(메뉴 "금융회사 현황 및 검사" 아래). 실제 서버 자료를 쓴다. 화면 본체는 risk-view.js.
import { setHeader } from "../app.js";
import { openUrl } from "../core.js";
import { liveProvider } from "../lib/risk-live.js";
import { mountRisk } from "./risk-view.js";

export async function render(root, params, ctx) {
  return mountRisk(root, params, { provider: liveProvider(), setHeader, go: (p) => ctx.go(p), openUrl });
}
