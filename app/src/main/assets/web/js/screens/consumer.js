// 금융소비자 지킴이: Consumer 레포의 화면을 한 메뉴 안의 탭으로 옮긴 것. 첫 탭이 보이스피싱 긴급대응이다.
// 하위 화면은 js/screens/consumer/*.js, 계산·문안은 js/lib/consumer/*.js, 자료는 data/consumer/*.json.
// 입력한 내용은 이 브라우저 밖으로 나가지 않는다(저장은 localStorage 'fss.' 접두어의 긴급대응 진행 표시와 비교 담기뿐).
import { setHeader } from "../app.js";
import { h, icon, tabs } from "../core.js";

const VIEWS = [
  ["emergency", "보이스피싱 긴급대응", () => import("./consumer/emergency.js")],
  ["home", "상황별 시작", () => import("./consumer/home.js")],
  ["diagnose", "권리구제 진단기", () => import("./consumer/diagnose.js")],
  ["cases", "분쟁조정 사례 검색", () => import("./consumer/cases.js")],
  ["company", "제도권 금융회사 조회", () => import("./consumer/company.js")],
  ["scorecard", "소비자보호 성적표", () => import("./consumer/scorecard.js")],
  ["tips", "금융꿀팁·소비자경보", () => import("./consumer/tips.js")],
];
let sub = null;

export async function render(root, params, ctx) {
  setHeader("금융소비자 지킴이", "내 금융 권리를 챙기는 도구 · 가입·로그인 없이 사용", []);
  const idx = Math.max(0, VIEWS.findIndex(([id]) => id === params.v));
  const [id, , load] = VIEWS[idx];
  // 하위 화면의 주소 값은 v(탭) 뒤에 그대로 붙인다.
  const { v, ...rest } = params;
  const subCtx = {
    id: "consumer",
    alive: ctx.alive,
    go: (p = {}, opts) => ctx.go({ v: id, ...p }, opts),
    open: (view, p = {}) => ctx.go({ v: view, ...p }),
  };
  root.append(tabs(VIEWS.map((x) => x[1]), idx, (i) => ctx.go({ v: VIEWS[i][0] }, { replace: true })));
  if (id !== "emergency") root.append(h("a", { class: "sos-inline", href: "#/consumer?v=emergency" }, icon("alert-triangle"), "사기를 당했거나 의심되면 → 보이스피싱 긴급대응"));
  const body = h("div", { class: "screen-consumer" });
  root.append(body);
  let mod;
  try {
    mod = await load();
  } catch {
    body.append(h("div", { class: "state err" }, "화면을 불러오지 못했습니다. 새로고침해 주세요."));
    return;
  }
  if (!ctx.alive()) return;
  sub = mod;
  await mod.render(body, rest, subCtx);
  root.append(h("p", { class: "note legal" }, "금융감독원 등 어떤 기관의 공식 사이트가 아니며, 계산·진단·문서 초안은 공개 법령을 바탕으로 한 참고용으로 법률자문이 아닙니다. 중요한 판단은 금융감독원 1332, 대한법률구조공단 132 등 공적 상담으로 확인하세요."));
}
export function leave() {
  try {
    sub?.leave?.();
  } catch {}
  sub = null;
}
