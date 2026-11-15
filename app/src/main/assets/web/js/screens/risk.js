// (앱 전용·자동 생성) 금융사 리스크 대시보드 진입 화면. 화면 본체는 risk-view.js.
import { setHeader } from "../app.js";
import { openUrl } from "../core.js";
import { liveProvider } from "../lib/risk-live.js";
import { mountRisk } from "./risk-view.js";

export async function render(root, params, ctx) {
  return mountRisk(root, params, { provider: liveProvider(), setHeader, go: (p) => ctx.go(p), openUrl });
}
