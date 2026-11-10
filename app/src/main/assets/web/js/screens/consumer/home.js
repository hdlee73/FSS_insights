// 홈: 기능 이름 대신 '내 상황'으로 시작한다.
import { setHeader } from "./hdr.js";
import { card, h, icon } from "../../core.js";

const SITUATIONS = [
  { ic: "alert-triangle", t: "사기를 당했어요 / 의심돼요", d: "보이스피싱·메신저피싱. 10분 안에 할 일부터 안내합니다.", to: ["emergency"], danger: true },
  { ic: "calendar", t: "가입한 걸 취소하고 싶어요", d: "대출·보험·투자 청약철회 기한(D-Day)을 계산합니다.", to: ["diagnose", { tab: "withdraw" }] },
  { ic: "scale", t: "설명을 제대로 못 듣고 가입했어요", d: "위법계약해지 요건을 점검하고 요구서 초안을 만듭니다.", to: ["diagnose", { tab: "cancel" }] },
  { ic: "gavel", t: "금감원 분쟁조정, 해볼 만할까요?", d: "내 상황을 문장으로 쓰면 비슷한 사례와 인정 비율을 찾습니다.", to: ["cases"] },
  { ic: "landmark", t: "이 업체, 믿어도 되나요?", d: "제도권 금융회사·등록 대부업체인지 확인하는 방법을 안내합니다.", to: ["company"] },
  { ic: "bar-chart-3", t: "내 금융회사는 소비자보호 잘 하나요?", d: "실태평가 등급, 민원, 제재를 비교합니다.", to: ["scorecard"] },
  { ic: "book-open", t: "금융꿀팁·소비자경보 보기", d: "금융감독원이 알리는 소비자 유의사항을 모았습니다.", to: ["tips"] },
];

export async function render(root, params, ctx) {
  
  root.append(
    h("div", { class: "hero" },
      h("h2", null, "무엇이 필요하세요?"),
      h("p", null, "가입·로그인 없이 바로 쓰는 사이트입니다. 입력한 내용은 이 기기 밖으로 나가지 않습니다.")),
    h("div", { class: "sit-grid" },
      SITUATIONS.map((s) => h("button", { type: "button", class: "sit" + (s.danger ? " danger" : ""), onclick: () => ctx.open(s.to[0], s.to[1] || {}) },
        h("span", { class: "sit-ic" }, icon(s.ic)),
        h("span", { class: "sit-t" }, h("b", null, s.t), h("small", null, s.d)),
        icon("chevron-right")))),
    h("div", { class: "grid2", style: { marginTop: "16px" } },
      card("바로 걸 수 있는 번호", "info", h("div", { class: "stack" },
        h("div", { class: "tel-row" }, h("a", { class: "btn danger", href: "tel:112" }, "112 경찰"), h("span", { class: "note" }, "사기 피해 신고·지급정지 요청")),
        h("div", { class: "tel-row" }, h("a", { class: "btn", href: "tel:1332" }, "1332 금융감독원"), h("span", { class: "note" }, "금융 민원·피해 상담")),
        h("div", { class: "tel-row" }, h("a", { class: "btn ghost", href: "tel:132" }, "132 법률구조공단"), h("span", { class: "note" }, "무료 법률 상담")))),
      card("이 사이트는", "badge-check", h("div", { class: "stack note" },
        h("p", null, "금소법(금융소비자 보호에 관한 법률)과 전기통신금융사기 피해방지·환급 특별법 등 공개 법령을 바탕으로 만든 참고 도구입니다."),
        h("p", null, "금융감독원 금융소비자 정보포털 파인(FINE)의 공개 안내를 모아 연결합니다. 금융감독원이 운영하는 사이트가 아닙니다."),
        h("p", null, "계산·진단·문서 초안은 법률자문이 아니며, 중요한 판단은 공식 안내와 전문가 상담으로 확인하세요."))))
  );
}
