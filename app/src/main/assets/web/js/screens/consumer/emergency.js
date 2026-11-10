// 보이스피싱·금융사기 긴급대응 마법사. 상황을 고르면 지금 할 일을 순서대로 보여 주고, 완료 표시는 이 기기에만 저장한다.
// 근거: 전기통신금융사기 피해 방지 및 피해자산 환급에 관한 특별법 제3조(피해구제 신청)·제5조(채권소멸절차)·제7조, 같은 법 시행령 제3조.
import { setHeader } from "./hdr.js";
import { card, dialog, h, icon, store, toast } from "../../core.js";

const F = "https://fine.fss.or.kr";
const SITUATIONS = [
  { id: "sent", t: "돈을 보냈어요", d: "송금·이체했거나 현금을 건넸어요" },
  { id: "app", t: "앱 설치·링크 클릭", d: "모르는 앱을 깔았거나 링크를 눌렀어요" },
  { id: "info", t: "개인정보를 알려줬어요", d: "신분증·계좌·비밀번호·인증번호 등" },
  { id: "doubt", t: "의심만 돼요", d: "아직 피해는 없어요" },
];

// when: 시간대, for: 해당 상황, links: 바로가기
const STEPS = [
  { id: "stop", when: "지금 바로 · 0~10분", for: ["sent"], t: "송금한 금융회사에 지급정지를 신청하세요",
    body: ["송금한 은행·증권사 등 콜센터(앱·카드 뒷면에 번호가 있습니다)에 전화해 ‘보이스피싱 피해로 지급정지를 신청합니다’라고 말하세요. 112에 신고해도 됩니다.", "피해자는 돈을 보낸 금융회사 또는 사기계좌를 관리하는 금융회사에 지급정지 등 피해구제를 신청할 수 있고, 급하면 전화·구술로도 신청할 수 있습니다(특별법 제3조, 시행령 제3조).", "현금을 전달했다면 112에 신고해 수거책 검거를 요청하세요."],
    actions: [{ l: "112 전화", tel: "112", danger: true }, { l: "1332 금융감독원", tel: "1332" }] },
  { id: "net", when: "지금 바로 · 0~10분", for: ["app"], t: "휴대폰 연결을 끊고 증거를 남기세요",
    body: ["의심 앱·원격제어 앱이 깔렸다면 비행기 모드를 켜거나 와이파이·데이터를 끄세요. 사기범이 휴대폰을 조종하거나 정보를 빼가는 것을 막기 위해서입니다.", "문자·통화 기록·앱 화면·송금 내역은 지우지 말고 화면을 캡처해 두세요(수사·피해구제에 필요).", "신고와 지급정지 전화는 가족 휴대폰이나 공중전화 등 다른 기기로 하세요. 앱 삭제·초기화는 수사기관이나 통신사 안내를 받은 뒤에 하는 것이 안전합니다."],
    actions: [{ l: "112 전화", tel: "112", danger: true }] },
  { id: "lock", when: "10~30분", for: ["sent", "app", "info"], t: "내 다른 계좌도 한 번에 막으세요",
    body: ["‘내계좌 지급정지’로 내 모든 금융회사 계좌의 입출금·대출을 한꺼번에 막을 수 있습니다(파인의 내계좌 통합조회·관리 메뉴에서 안내). 본인 확인이 필요하니 신분증과 휴대폰을 준비하세요.", "주거래 은행 앱의 ‘비대면 계좌개설 안심 차단’, ‘해외 IP 차단’ 같은 보안 설정도 켜 두세요."],
    actions: [{ l: "내계좌 통합조회·지급정지 안내", href: F + "/fine/main/contents.do?menuNo=900029" }] },
  { id: "msafer", when: "10~30분", for: ["info", "app", "sent"], t: "내 명의로 휴대폰이 개통되지 않게 막으세요",
    body: ["엠세이퍼(명의도용방지서비스)에서 내 명의로 새 휴대폰이 개통되는 것을 제한하고, 이미 개통된 회선이 있는지 확인하세요. 모르는 회선이 있으면 해당 통신사에 신고하세요.", "신분증을 사진으로 보내거나 실물을 넘겼다면 발급기관(주민센터·경찰서 등)에 분실·도난 신고를 하세요."],
    actions: [{ l: "엠세이퍼 열기", href: "https://www.msafer.or.kr" }] },
  { id: "pd", when: "10~30분", for: ["info", "sent", "app"], t: "개인정보 노출 사실을 등록하세요",
    body: ["‘개인정보노출자 사고예방시스템’에 등록하면 새 계좌·카드 개설 등이 제한되어 명의도용 피해를 줄일 수 있습니다. 금융감독원이 운영합니다."],
    actions: [{ l: "개인정보노출자 사고예방시스템", href: "https://pd.fss.or.kr" }] },
  { id: "docs", when: "당일 ~ 3영업일", for: ["sent"], t: "피해구제신청서를 제출하세요",
    body: ["전화로 신청했다면 신청한 날부터 3영업일 이내에 피해구제신청서와 신분증 사본을 해당 금융회사에 내야 합니다. 내지 않으면 신청이 없었던 것으로 볼 수 있으니 서둘러 주세요(시행령 제3조).", "금융회사가 수사기관의 피해신고확인서 등 자료를 요청할 수 있으니, 경찰서에서 신고를 접수하고 확인서류를 받아 두세요.", "신청서 양식은 금융회사 영업점·앱·홈페이지에 있습니다. 피해 날짜·금액·상대 계좌·사기 경위를 사실대로 적으세요."],
    actions: [{ l: "112 전화", tel: "112" }, { l: "1332 상담", tel: "1332" }] },
  { id: "refund", when: "이후 · 수개월", for: ["sent"], t: "환급 절차를 알아 두세요",
    body: ["① 지급정지 → ② 금융회사가 금융감독원에 채권소멸 공고를 요청, 금융감독원이 공고 → ③ 사기계좌 명의인의 이의제기 기간(공고일 기준 2개월) 경과 → ④ 남아 있는 돈을 피해자에게 환급.", "사기범이 이미 돈을 빼 갔다면 남은 금액만 환급되며, 전액을 돌려받지 못할 수 있습니다(특별법 제5조·제7조).", "진행 상황은 송금한 금융회사 또는 금융감독원(1332)에 문의하세요."],
    actions: [{ l: "보이스피싱 안내(파인)", href: F + "/fine/main/contents.do?menuNo=900206" }] },
  { id: "doubt", when: "지금", for: ["doubt"], t: "일단 끊고, 직접 확인하세요",
    body: ["상대가 알려 준 번호로 다시 걸지 마세요. 기관 대표번호는 직접 검색해서 전화하세요.", "공공기관·금융회사는 전화로 ‘안전계좌로 이체하라’, ‘앱을 설치하라’, ‘현금을 전달하라’고 하지 않습니다.", "이미 개인정보를 일부 알려줬다면 ‘개인정보를 알려줬어요’를 선택해 보호 조치를 하세요."],
    actions: [{ l: "1332 문의", tel: "1332" }] },
];

const SIGNS = [
  "검찰·경찰·금융감독원이라며 ‘당신 계좌가 범죄에 이용됐다’고 하는 전화",
  "‘안전계좌’, ‘국가안전계좌’로 돈을 옮기라는 요구",
  "더 낮은 금리로 바꿔 준다며 기존 대출을 먼저 갚으라는 ‘대환대출’ 권유",
  "‘엄마, 폰 고장 났어’ 같은 가족·지인 사칭 메신저, 상품권·계좌 이체 요청",
  "택배·과태료·건강검진 등의 링크 문자(스미싱), 모르는 앱 설치 요구",
  "통화 중 은행 앱이나 원격제어 앱을 켜라는 요구, 인증번호·OTP 요구",
];

export async function render(root) {
  setHeader("보이스피싱 긴급대응", "당황하지 말고 위에서부터 차례로 하세요");
  let sit = store.get("emg.sit", null);
  let done = new Set(store.get("emg.done", []));
  const wrap = h("div", { class: "stack" });
  const save = () => {
    store.set("emg.sit", sit);
    store.set("emg.done", [...done]);
  };
  const clear = async () => {
    if (!(await dialog({ title: "진행 기록 지우기", text: "이 기기에 저장된 선택과 완료 표시를 지웁니다.", ok: "지우기", danger: true }))) return;
    store.remove("emg.sit"); store.remove("emg.done");
    sit = null; done = new Set(); draw(); toast("지웠습니다.");
  };
  const stepCard = (s, i) => h("section", { class: "step" + (done.has(s.id) ? " done" : "") },
    h("div", { class: "step-h" },
      h("span", { class: "step-n" }, String(i + 1)),
      h("div", null, h("div", { class: "when" }, s.when), h("b", null, s.t)),
      h("label", { class: "chk", title: "완료 표시" }, h("input", { type: "checkbox", checked: done.has(s.id), onchange: (e) => { e.target.checked ? done.add(s.id) : done.delete(s.id); save(); draw(); } }), h("span", null, "완료"))),
    h("ul", { class: "plain" }, s.body.map((b) => h("li", null, b))),
    h("div", { class: "row-gap" }, s.actions.map((a) => h("a", { class: "btn" + (a.danger ? " danger" : a.tel ? "" : " ghost"), href: a.tel ? "tel:" + a.tel : a.href, ...(a.tel ? {} : { target: "_blank", rel: "noopener noreferrer" }) }, icon(a.tel ? "bell" : "external-link"), a.l))));
  function draw() {
    const steps = sit ? STEPS.filter((s) => s.for.includes(sit)) : [];
    wrap.replaceChildren(
      h("div", { class: "alert-banner" }, icon("alert-triangle"), h("div", null, h("b", null, "송금한 직후라면 1분이 중요합니다."), h("span", null, " 먼저 112 또는 송금한 금융회사 콜센터에 전화해 지급정지를 요청하세요."))),
      h("div", { class: "row-gap" }, h("a", { class: "btn danger big-btn", href: "tel:112" }, "112 경찰 신고"), h("a", { class: "btn big-btn", href: "tel:1332" }, "1332 금융감독원")),
      card("지금 어떤 상황인가요?", "help-circle", h("div", { class: "sit-grid two" }, SITUATIONS.map((s) =>
        h("button", { type: "button", class: "sit" + (sit === s.id ? " on" : ""), onclick: () => { sit = s.id; save(); draw(); window.scrollTo?.(0, 0); } }, h("span", { class: "sit-t" }, h("b", null, s.t), h("small", null, s.d)))))),
      ...(sit ? [h("div", { class: "progress" }, `${steps.filter((s) => done.has(s.id)).length} / ${steps.length} 단계 완료`), ...steps.map(stepCard)] : [h("p", { class: "note" }, "상황을 고르면 그에 맞는 순서대로 할 일을 보여 드립니다.")]),
      card("이런 연락은 사기입니다", "eye-off", h("ul", { class: "plain" }, SIGNS.map((s) => h("li", null, s)))),
      card("더 알아보기", "book-open", h("div", { class: "link-list" },
        [["금융감독원 보이스피싱 안내", F + "/fine/main/contents.do?menuNo=900206"], ["불법금융 신고·상담", F + "/fine/main/contents.do?menuNo=900124"], ["금융소비자경보 발령내역", F + "/fine/bbs/B0000175/list.do?menuNo=900512"]]
          .map(([t, u]) => h("a", { class: "link-item slim", href: u, target: "_blank", rel: "noopener noreferrer" }, h("b", null, t), icon("external-link"))))),
      h("p", { class: "note legal" }, "근거: 전기통신금융사기 피해 방지 및 피해자산 환급에 관한 특별법(2026.10.2. 시행본)·시행령. 기한과 절차는 개정될 수 있으니 금융회사·금융감독원 안내를 함께 확인하세요. 참고용이며 법률자문이 아닙니다."),
      sit || done.size ? h("button", { class: "link-more", type: "button", onclick: clear }, "내 진행 기록 지우기 (이 기기에만 저장됩니다)") : null
    );
  }
  root.append(wrap);
  draw();
}
