// 권리구제 진단기: ① 청약철회 D-Day ② 위법계약해지 자가진단 ③ 요구서·신청서 초안.
import { setHeader } from "./hdr.js";
import { LAW_BASIS, PRODUCT_TYPES, EXCEPTIONS, VIOLATIONS, withdrawal, withdrawalStatus, cancellationWindow, cancellationAssessment, weekday } from "../../lib/consumer/rights.js";
import { CERT_MAIL_GUIDE, cancellationLetter, withdrawalLetter } from "../../lib/consumer/docs.js";
import { card, dialog, download, h, icon, tabs, toast, today } from "../../core.js";

const TABS = [["withdraw", "청약철회 D-Day"], ["cancel", "위법계약해지 점검"], ["doc", "요구서·신청서 초안"]];
// 화면을 오가도 입력이 남도록 메모리에만 보관한다(저장소에 쓰지 않음 = 개인정보 미저장).
const S = { w: { type: "loan" }, c: { answers: {} }, d: { kind: "withdraw", violations: [] } };

const dateField = (label, key, obj, onChange, hint) =>
  h("label", { class: "field" }, h("span", null, label), h("input", { type: "date", value: obj[key] || "", max: "2100-12-31", min: "1990-01-01", onchange: (e) => { obj[key] = e.target.value; onChange(); } }), hint ? h("small", { class: "note" }, hint) : null);
const textField = (label, key, obj, ph = "", onChange) =>
  h("label", { class: "field" }, h("span", null, label), h("input", { type: "text", value: obj[key] || "", placeholder: ph, autocomplete: "off", oninput: (e) => { obj[key] = e.target.value; onChange?.(); } }));
const dk = (iso) => `${iso} (${weekday(iso)})`;

export async function render(root, params, ctx) {
  setHeader("권리구제 진단기", "청약철회 · 위법계약해지 · 요구서 초안");
  const cur = TABS.findIndex(([id]) => id === params.tab);
  const idx = cur < 0 ? 0 : cur;
  root.append(
    h("div", { class: "note-bar" }, icon("lock"), h("span", null, "입력한 내용은 이 화면 안에서만 계산하고 서버로 보내거나 저장하지 않습니다.")),
    tabs(TABS.map((t) => t[1]), idx, (i) => ctx.go({ tab: TABS[i][0] }, { replace: true })),
  );
  const body = h("div", null);
  root.append(body);
  [withdrawTab, cancelTab, docTab][idx](body, ctx);
  root.append(h("p", { class: "note legal" }, `기준: ${LAW_BASIS}. 참고용이며 법률자문이 아닙니다. 마지막 날이 토·일이면 다음 월요일까지로 보는 것이 일반적이나 공휴일은 반영하지 않으므로, 가능하면 기한보다 며칠 일찍 보내세요.`));
}

// ---------------------------------------------------------------- ① 청약철회
function withdrawTab(root, ctx) {
  const f = S.w;
  const out = h("div", null);
  const types = h("div", { class: "seg-cards" });
  const drawTypes = () =>
    types.replaceChildren(...Object.entries(PRODUCT_TYPES).map(([id, t]) => h("button", { type: "button", class: "seg-card" + (f.type === id ? " on" : ""), onclick: () => { f.type = id; drawTypes(); drawForm(); update(); } }, h("b", null, t.label), h("small", null, t.sub))));
  const form = h("div", { class: "form-grid" });
  const drawForm = () => {
    const t = f.type;
    form.replaceChildren(
      dateField(t === "insurance" ? "청약일" : "계약일", "contractDate", f, update),
      dateField(t === "insurance" ? "보험증권을 받은 날 (선택)" : "계약서류를 받은 날 (선택)", "docDate", f, update, "없으면 비워 두세요. 모르면 계약일과 같다고 봅니다."),
      t === "loan" ? dateField("대출금을 받은 날 (선택)", "payoutDate", f, update, "계약일보다 늦게 받았다면 이 날이 기준이 됩니다.") : null);
  };
  const update = () => {
    const res = withdrawal(f);
    if (!res.ok) return out.replaceChildren(f.contractDate ? h("div", { class: "result bad" }, res.error) : h("div", { class: "hint" }, "계약일을 입력하면 마감일을 계산합니다."));
    const st = withdrawalStatus(res, today());
    const ex = EXCEPTIONS[f.type] || [];
    out.replaceChildren(
      h("div", { class: "result " + st.level },
        h("div", { class: "big" }, st.left >= 0 ? st.text : st.leftRolled >= 0 ? "마지막 날 확인" : "기한 경과"),
        h("div", { class: "big-sub" }, `철회 마지막 날 ${dk(res.deadline)}`),
        res.rolled !== res.deadline ? h("div", { class: "big-sub" }, `주말이라 ${dk(res.rolled)}까지로 볼 수 있음`) : null,
        h("div", { class: "note" }, st.left < 0 && st.leftRolled < 0 ? "기한이 지난 것으로 계산됩니다. 약정으로 더 긴 기간을 정한 경우에는 그 기간이 적용되니 계약서를 확인하세요. 위법계약해지 점검도 해 보세요." : "철회는 서면·이메일·문자 등을 ‘발송한 때’ 효력이 생깁니다(대출은 받은 돈과 이자 등을 돌려준 때).")),
      card("계산 근거", "calendar", h("div", { class: "stack" }, h("p", { class: "note" }, res.rule), h("ul", { class: "plain" }, res.steps.map((s) => h("li", null, s))))),
      ex.length ? card("대상이 아닐 수 있는 경우", "alert-triangle", h("div", { class: "stack" }, h("ul", { class: "plain" }, ex.map((s) => h("li", null, s))), h("p", { class: "note" }, "상품이 철회 대상인지는 시행령 제37조와 금융위원회 고시로 정해집니다. 헷갈리면 금융회사나 금융감독원(1332)에 확인하세요."))) : null,
      h("div", { class: "row-gap" },
        h("button", { class: "btn", type: "button", onclick: () => { S.d.kind = "withdraw"; S.d.contractDate = f.contractDate; ctx.go({ tab: "doc" }); } }, icon("file-text"), "철회 의사표시서 초안 만들기"),
        h("button", { class: "btn ghost", type: "button", onclick: () => downloadIcs(res) }, icon("calendar"), "마감 알림(캘린더 파일)")));
  };
  root.append(h("div", { class: "stack" }, card("상품 종류", "list", types), card("날짜 입력", "calendar", form), out));
  drawTypes(); drawForm(); update();
}

function downloadIcs(res) {
  const d = res.rolled.replace(/-/g, "");
  const prev = new Date(Date.UTC(+res.rolled.slice(0, 4), +res.rolled.slice(5, 7) - 1, +res.rolled.slice(8, 10) - 1)).toISOString().slice(0, 10).replace(/-/g, "");
  const next = new Date(Date.UTC(+res.rolled.slice(0, 4), +res.rolled.slice(5, 7) - 1, +res.rolled.slice(8, 10) + 1)).toISOString().slice(0, 10).replace(/-/g, "");
  const ics = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//금융소비자 지킴이//KO", "BEGIN:VEVENT", `UID:${d}-${Date.now()}@fcp`, `DTSTAMP:${new Date().toISOString().replace(/[-:]/g, "").slice(0, 15)}Z`,
    `DTSTART;VALUE=DATE:${prev}`, `DTEND;VALUE=DATE:${next}`, "SUMMARY:청약철회 마감 (금융소비자 지킴이)", `DESCRIPTION:철회 마지막 날 ${res.deadline}. 서면·이메일·문자 발송 기준.`,
    "BEGIN:VALARM", "TRIGGER:-PT0M", "ACTION:DISPLAY", "DESCRIPTION:청약철회 마감 임박", "END:VALARM", "END:VEVENT", "END:VCALENDAR"].join("\r\n");
  download(new Blob([ics], { type: "text/calendar;charset=utf-8" }), "청약철회-마감.ics");
  toast("하루 전 날짜부터 표시되는 캘린더 일정 파일을 내려받았습니다.");
}

// ---------------------------------------------------------------- ② 위법계약해지
function cancelTab(root, ctx) {
  const f = S.c;
  const out = h("div", null);
  const list = h("div", { class: "stack" });
  const ANS = [["yes", "그랬어요"], ["unsure", "잘 모르겠어요"], ["no", "아니에요"]];
  const update = () => {
    const win = cancellationWindow(f, today());
    const as = cancellationAssessment(f.answers);
    const answered = Object.keys(f.answers).length;
    out.replaceChildren(
      !win.ok ? h("div", { class: "hint" }, f.contractDate || f.knownDate ? win.error : "계약체결일과 위반을 알게 된 날을 넣으면 해지를 요구할 수 있는 기간을 계산합니다.")
        : h("div", { class: "result " + (win.expired ? "over" : win.left <= 30 ? "urgent" : "ok") },
          h("div", { class: "big" }, win.expired ? "요구 기간 경과" : `${win.left}일 남음`),
          h("div", { class: "big-sub" }, `해지 요구 마지막 날 ${dk(win.end)}`),
          h("div", { class: "note" }, `위반을 안 날부터 1년(${win.byKnown})과 계약체결일부터 5년(${win.byContract}) 중 ${win.limiter === "known" ? "앞서는 ‘알게 된 날부터 1년’" : "앞서는 ‘계약체결일부터 5년’"}이 적용됩니다.`)),
      answered ? h("div", { class: "result " + (as.level === "high" ? "ok" : as.level === "check" ? "urgent" : "over") },
        h("div", { class: "big small" }, as.level === "high" ? "요건 충족 가능성 있음" : as.level === "check" ? "추가 확인 필요" : "가능성 낮음"),
        h("div", { class: "note" }, as.text),
        h("div", { class: "row-gap", style: { marginTop: "8px" } },
          h("button", { class: "btn", type: "button", onclick: () => { S.d.kind = "cancel"; S.d.violations = as.yes.concat(as.unsure).map((v) => v.id); S.d.contractDate = f.contractDate; S.d.knownDate = f.knownDate; ctx.go({ tab: "doc" }); } }, icon("file-text"), "해지 요구서 초안 만들기"))) : null,
      card("해지를 요구한 뒤에는", "info", h("ul", { class: "plain" },
        h("li", null, "금융회사는 요구를 받은 날부터 10일 안에 수락 여부를 알려야 하고, 거절하면 이유를 함께 알려야 합니다."),
        h("li", null, "정당한 사유 없이 따르지 않으면 소비자가 직접 계약을 해지할 수 있고, 해지 수수료·위약금은 요구할 수 없습니다(법 제47조)."),
        h("li", null, "해지 요구서에는 위반 사항을 증명하는 서류를 붙여야 합니다(시행령 제38조③). 계약서·설명서·녹취·문자·통화기록을 모아 두세요."),
        h("li", null, "계속적 거래가 이루어지는 금융상품 중 금융위원회가 고시하는 상품만 대상입니다(시행령 제38조①). 대상 여부는 금융회사나 금융감독원(1332)에 확인하세요."),
        h("li", null, "해지는 장래를 향한 것이라, 이미 생긴 손실이 자동으로 회복되지는 않습니다. 손해 배상이 필요하면 금융감독원 분쟁조정 등을 별도로 알아보세요."))));
  };
  const draw = () =>
    list.replaceChildren(...VIOLATIONS.map((v) => card(`${v.title} (${v.law})`, null, h("div", { class: "stack" },
      v.qs.map((q) => h("p", { class: "q" }, q)),
      h("div", { class: "ans" }, ANS.map(([val, label]) => h("button", { type: "button", class: "pill" + (f.answers[v.id] === val ? " on" : ""), onclick: () => { if (f.answers[v.id] === val) delete f.answers[v.id]; else f.answers[v.id] = val; draw(); update(); } }, label)))))));
  root.append(h("div", { class: "stack" },
    card("날짜 입력", "calendar", h("div", { class: "form-grid" }, dateField("계약체결일", "contractDate", f, update), dateField("위반 사실을 알게 된 날", "knownDate", f, update, "정확히 모르면 오늘 날짜를 넣으세요."))),
    h("p", { class: "note" }, "아래 5가지는 금소법 제47조가 정한 해지 요구 사유입니다. 각 항목에서 가장 가까운 답을 골라 주세요."),
    list, out));
  draw(); update();
}

// ---------------------------------------------------------------- ③ 초안
function docTab(root, ctx) {
  const f = S.d;
  const kinds = [["withdraw", "청약철회 의사표시서"], ["cancel", "위법계약해지 요구서"]];
  const text = h("textarea", { class: "doc", rows: 26, spellcheck: false });
  const build = () => {
    const base = { ...f, date: f.date || today() };
    if (f.kind === "withdraw") base.type = S.w.type;
    text.value = f.kind === "withdraw" ? withdrawalLetter(base) : cancellationLetter(base);
  };
  const form = h("div", { class: "form-grid" });
  const drawForm = () => form.replaceChildren(
    textField("금융회사명", "company", f, "예: OO은행", build), textField("성명", "name", f, "", build), textField("연락처", "contact", f, "010-0000-0000", build),
    textField("상품명", "product", f, "", build), textField("계약번호", "contractNo", f, "", build),
    dateField("계약(청약)일", "contractDate", f, build),
    f.kind === "cancel" ? dateField("위반을 알게 된 날", "knownDate", f, build) : textField("환불받을 계좌 (선택)", "account", f, "은행 / 계좌번호 / 예금주", build),
    dateField("작성일", "date", f, build));
  const kindTabs = () => tabs(kinds.map((k) => k[1]), kinds.findIndex((k) => k[0] === f.kind), (i) => { f.kind = kinds[i][0]; draw(); }, "seg");
  const wrap = h("div", { class: "stack" });
  const violations = () => f.kind !== "cancel" ? null : card("해당하는 위반 유형", "scale", h("div", { class: "chip-row wrap" }, VIOLATIONS.map((v) => h("button", { type: "button", class: "pill" + (f.violations.includes(v.id) ? " on" : ""), onclick: () => { f.violations = f.violations.includes(v.id) ? f.violations.filter((x) => x !== v.id) : [...f.violations, v.id]; draw(); } }, v.title))));
  const facts = () => f.kind !== "cancel" ? null : card("구체적인 사실관계 (직접 적기)", "pencil", h("textarea", { rows: 4, class: "facts", placeholder: "가입한 날짜, 장소, 담당자, 들은 말 등을 적어 주세요. 비워 두면 안내 문구가 들어갑니다.", oninput: (e) => { f.facts = e.target.value; build(); } }, f.facts || ""));
  function draw() {
    drawForm(); build();
    wrap.replaceChildren(
      kindTabs(), violations(), card("기본 정보", "list", form), facts(),
      card("초안 (직접 고쳐 쓸 수 있습니다. 위 정보를 바꾸면 초안이 다시 만들어집니다)", "file-text", h("div", { class: "stack" }, text,
        h("div", { class: "row-gap" },
          h("button", { class: "btn", type: "button", onclick: async () => { try { await navigator.clipboard.writeText(text.value); toast("복사했습니다."); } catch { text.select(); toast("선택된 글을 직접 복사해 주세요."); } } }, icon("copy"), "복사"),
          h("button", { class: "btn ghost", type: "button", onclick: () => download(new Blob([text.value], { type: "text/plain;charset=utf-8" }), f.kind === "withdraw" ? "청약철회-의사표시서.txt" : "위법계약해지-요구서.txt") }, icon("download"), "텍스트 파일"),
          h("button", { class: "btn ghost", type: "button", onclick: () => window.print() }, icon("printer"), "인쇄")),
        h("button", { class: "link-more", type: "button", onclick: clearAll }, "입력한 내용 모두 지우기"))),
      card("내용증명으로 보내려면", "info", h("ul", { class: "plain" }, CERT_MAIL_GUIDE.map((s) => h("li", null, s)))));
  }
  async function clearAll() {
    if (!(await dialog({ title: "입력 내용 지우기", text: "이 화면에 입력한 모든 내용(진단 답변 포함)을 지웁니다.", ok: "지우기", danger: true }))) return;
    S.w = { type: "loan" }; S.c = { answers: {} }; S.d = { kind: "withdraw", violations: [] };
    toast("지웠습니다.");
    ctx.go({ tab: "doc" });
  }
  root.append(wrap);
  draw();
}
