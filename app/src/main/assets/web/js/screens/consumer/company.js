// 제도권 금융회사 조회: 금융감독원 파인(FINE)의 공식 조회 서비스로 연결하고, 믿을 수 있는지 확인하는 순서를 안내한다.
import { setHeader } from "./hdr.js";
import { card, errorText, h, icon, loadJson, loading, message } from "../../core.js";

const CHECK = [
  "전화·문자·SNS로 먼저 연락해 온 업체의 번호나 링크는 믿지 말고, 직접 검색해서 공식 번호로 다시 확인하세요.",
  "업체 이름이 아니라 ‘등록번호·인가 여부’를 확인하세요. 은행·보험사 이름을 흉내 낸 이름이 많습니다.",
  "대출이라면 ‘대출 전 선입금(보증료·수수료)’을 요구하면 사기일 가능성이 매우 높습니다. 정상 금융회사는 대출 전에 돈을 먼저 받지 않습니다.",
  "연 20%를 넘는 이자를 요구하면 법정 최고금리 위반입니다. 불법사금융은 금융감독원에 신고하세요(1332).",
  "‘원금 보장, 고수익 확정’을 내세우는 투자 권유는 유사수신 의심 대상입니다. 유사투자자문업자는 ‘신고’ 업체일 뿐 금융회사가 아닙니다.",
  "확인이 어렵다면 계약하지 말고 금융감독원(1332)에 문의하세요.",
];

export async function render(root) {
  setHeader("제도권 금융회사 조회", "금융감독원 공식 조회 서비스로 연결합니다");
  root.append(loading());
  let data;
  try {
    data = await loadJson("data/consumer/links.json");
  } catch (e) {
    root.replaceChildren(message("조회 안내 자료를 불러오지 못했습니다.\n" + errorText(e), { error: true }));
    return;
  }
  root.replaceChildren(
    h("div", { class: "hint-top" }, icon("info"), h("span", null, "이 사이트는 업체 정보를 직접 보관하지 않습니다. 최신·정확한 확인을 위해 금융감독원 파인(FINE) 등 공식 조회 서비스를 새 창으로 엽니다.")),
    h("div", { class: "grid2" },
      h("div", { class: "stack" },
        card("무엇을 확인하고 싶으세요?", "landmark", h("div", { class: "link-list" }, data.company.map((x) =>
          h("a", { class: "link-item", href: x.u, target: "_blank", rel: "noopener noreferrer" },
            h("div", null, h("b", null, x.t), h("small", null, x.d)),
            h("span", { class: "tag " + (x.tag === "필수" ? "red" : "blue") }, x.tag), icon("external-link"))))),
        card("신고·상담", "alert-triangle", h("div", { class: "link-list" }, data.report.map((x) =>
          h("a", { class: "link-item slim", href: x.u, target: "_blank", rel: "noopener noreferrer" }, h("b", null, x.t), icon("external-link")))))),
      h("div", { class: "stack" },
        card("이렇게 확인하세요", "badge-check", h("ol", { class: "plain num" }, CHECK.map((s) => h("li", null, s)))),
        h("p", { class: "note" }, `연결 주소 확인일 ${data.asOf}. ${data.note} 출처: 금융감독원 금융소비자 정보포털 파인.`)))
  );
}
