// 금소법 권리구제 계산: 청약철회 기한(제46조·시행령 제37조), 위법계약해지(제47조·시행령 제38조).
// 모두 순수 함수(화면·저장소와 무관)라 node로 바로 검증할 수 있다. 날짜는 'YYYY-MM-DD' 문자열.
// 기준 법령: 금융소비자 보호에 관한 법률(2026.1.2. 시행본), 같은 법 시행령(2026.4.28. 시행본).
export const LAW_BASIS = "금융소비자 보호에 관한 법률 2026.1.2. 시행본 · 시행령 2026.4.28. 시행본";

const DAY = 86400000;
const parse = (iso) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
  if (!m) return null;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  // 2월 31일 같은 없는 날짜 거르기
  return d.getUTCFullYear() === +m[1] && d.getUTCMonth() === +m[2] - 1 && d.getUTCDate() === +m[3] ? d : null;
};
const fmt = (d) => d.toISOString().slice(0, 10);

export function isDate(iso) {
  return !!parse(iso);
}
/** 민법 제157조(초일 불산입): 받은 날의 다음 날부터 세므로 '받은 날 + n일'이 마지막 날. */
export function addDays(iso, n) {
  const d = parse(iso);
  if (!d) return null;
  return fmt(new Date(d.getTime() + n * DAY));
}
/** 초일 불산입이므로 n년 뒤 같은 월·일이 마지막 날이다(민법 제157조·제160조). 2월 29일은 28일로 맞춘다. */
export function addYears(iso, n) {
  const d = parse(iso);
  if (!d) return null;
  const y = d.getUTCFullYear() + n, mo = d.getUTCMonth(), da = d.getUTCDate();
  const last = new Date(Date.UTC(y, mo + 1, 0)).getUTCDate();
  return fmt(new Date(Date.UTC(y, mo, Math.min(da, last))));
}
export function diffDays(fromIso, toIso) {
  const a = parse(fromIso), b = parse(toIso);
  if (!a || !b) return null;
  return Math.round((b - a) / DAY);
}
export const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];
export function weekday(iso) {
  const d = parse(iso);
  return d ? WEEKDAY[d.getUTCDay()] : "";
}
/** 마지막 날이 토·일이면 다음 월요일(민법 제161조). 공휴일은 반영하지 못한다. */
export function rollWeekend(iso) {
  const d = parse(iso);
  if (!d) return null;
  const w = d.getUTCDay();
  return w === 6 ? addDays(iso, 2) : w === 0 ? addDays(iso, 1) : iso;
}

// ---------------------------------------------------------------- 청약철회

export const PRODUCT_TYPES = {
  loan: { label: "대출", sub: "신용·담보대출, 카드론 등", days: 14 },
  invest: { label: "투자(펀드·신탁·일임)", sub: "고난도 상품 등 일부만 해당", days: 7 },
  insurance: { label: "보험", sub: "보장성 상품", days: 15 },
  advice: { label: "금융상품자문", sub: "투자자문 계약", days: 7 },
};

/** 상품 유형별로 '철회 대상이 아닐 수 있는' 대표 예외(시행령 제37조①). 화면의 확인 문구로 쓴다. */
export const EXCEPTIONS = {
  loan: ["여신전문금융업법상 시설대여·할부금융·연불판매(기간 내 재화를 받은 경우)", "온라인투자연계금융 연계대출", "증권사 신용공여(기간 내 담보증권을 처분한 경우)"],
  invest: ["일반 펀드·주식 등은 대상이 아닙니다. 고난도 금융투자상품(모집 기간 종료 후 운용하는 것), 고난도 투자일임, 신탁계약(금전신탁 제외), 고난도 금전신탁이 대상입니다.", "철회 기간 안에 운용에 동의했다면 대상에서 빠집니다."],
  insurance: ["제3자 동의가 필요한 보증보험", "의무 책임보험(자동차손해배상 보장법. 같은 종류 다른 책임보험이 없는 경우)", "보장기간이 짧은 상품(금융위원회 고시 기간 이내)"],
  advice: [],
};

/**
 * 청약철회 마지막 날 계산.
 * input: { type, contractDate(청약·계약일), docDate(계약서류/보험증권 받은 날, 선택), payoutDate(대출금 받은 날, 선택) }
 * 반환: { ok, error?, deadline, rolled, rule, steps[] }
 */
export function withdrawal(input) {
  const { type, contractDate, docDate, payoutDate } = input;
  if (!PRODUCT_TYPES[type]) return { ok: false, error: "상품 유형을 선택해 주세요." };
  if (!isDate(contractDate)) return { ok: false, error: "계약(청약)일을 입력해 주세요." };
  for (const [k, v] of [["서류를 받은 날", docDate], ["대출금을 받은 날", payoutDate]]) if (v && !isDate(v)) return { ok: false, error: `${k}이 올바르지 않습니다.` };
  if (docDate && docDate < contractDate) return { ok: false, error: "서류를 받은 날이 계약일보다 앞설 수 없습니다." };
  if (payoutDate && payoutDate < contractDate) return { ok: false, error: "대출금을 받은 날이 계약일보다 앞설 수 없습니다." };

  const steps = [];
  let deadline, rule;
  if (type === "insurance") {
    const byIssue = docDate ? addDays(docDate, 15) : null;
    const byApply = addDays(contractDate, 30);
    steps.push(`청약일 ${contractDate} + 30일 = ${byApply}`);
    if (byIssue) steps.push(`보험증권 받은 날 ${docDate} + 15일 = ${byIssue}`);
    else steps.push("보험증권을 아직 못 받았다면 증권을 받은 날부터 15일이 시작됩니다(받은 날을 넣으면 더 정확합니다).");
    deadline = byIssue && byIssue < byApply ? byIssue : byApply;
    rule = "보험증권을 받은 날부터 15일과 청약일부터 30일 중 먼저 오는 날 (제46조①1호)";
  } else if (type === "loan") {
    let base = docDate || contractDate;
    steps.push(`기준일(${docDate ? "계약서류를 받은 날" : "계약일"}) ${base}`);
    if (payoutDate && payoutDate > base) {
      base = payoutDate;
      steps.push(`대출금 지급일 ${payoutDate}이 더 늦으므로 지급일이 기준일`);
    }
    deadline = addDays(base, 14);
    steps.push(`${base} + 14일 = ${deadline}`);
    rule = "계약서류를 받은 날(또는 계약체결일), 금전 지급이 더 늦으면 지급일부터 14일 (제46조①3호)";
  } else {
    const base = docDate || contractDate;
    steps.push(`기준일(${docDate ? "계약서류를 받은 날" : "계약체결일"}) ${base}`);
    deadline = addDays(base, 7);
    steps.push(`${base} + 7일 = ${deadline}`);
    rule = "계약서류를 받은 날(제공 생략 시 계약체결일)부터 7일 (제46조①2호)";
  }
  const rolled = rollWeekend(deadline);
  return { ok: true, deadline, rolled, rule, steps, type };
}

/** 오늘 기준 상태. daysLeft: 남은 일수(0=오늘이 마지막), 음수=지남. */
export function withdrawalStatus(res, todayIso) {
  const left = diffDays(todayIso, res.deadline);
  const leftRolled = diffDays(todayIso, res.rolled);
  let level, text;
  if (left >= 0) {
    level = left <= 2 ? "urgent" : "ok";
    text = left === 0 ? "오늘이 마지막 날입니다" : `D-${left}`;
  } else if (leftRolled >= 0) {
    level = "urgent";
    text = "마지막 날이 주말이라 다음 월요일까지 가능할 수 있습니다";
  } else {
    level = "over";
    text = `기한이 ${-left}일 지났습니다`;
  }
  return { left, leftRolled, level, text };
}

// ---------------------------------------------------------------- 위법계약해지

/** 제47조① 위반 유형 5가지. q는 쉬운 질문, law는 근거 조문. */
export const VIOLATIONS = [
  {
    id: "suitability",
    title: "적합성 원칙 위반",
    law: "제17조 제3항",
    qs: ["내 나이·재산·소득·투자경험·가입 목적을 물어보지 않고 상품을 권했나요?", "내게 맞지 않는 위험한 상품(또는 갚기 어려운 대출)인데도 권했나요?"],
    text: "금융회사는 제 재산상황, 투자(또는 변제) 능력, 가입 목적 등을 충분히 파악하지 않았고, 제게 적합하지 않은 계약 체결을 권유하였습니다.",
  },
  {
    id: "appropriateness",
    title: "적정성 원칙 위반",
    law: "제18조 제2항",
    qs: ["제가 먼저 가입을 원했더라도, 제게 맞지 않는 상품이라는 안내를 받지 못했나요?", "그 안내를 받았다는 확인서에 제가 서명한 기억이 없나요?"],
    text: "저에게 적합하지 않을 수 있는 상품임에도, 금융회사는 그 사실을 알리고 확인을 받는 절차를 이행하지 않았습니다.",
  },
  {
    id: "explanation",
    title: "설명의무 위반",
    law: "제19조 제1항·제3항",
    qs: ["원금손실 가능성, 수수료, 중도해지 불이익 같은 중요한 내용을 설명받지 못했나요?", "설명이 거짓이거나 사실과 달랐나요(또는 중요한 사항을 빼먹었나요)?", "설명서를 받지 못했거나, 설명을 듣지 않았는데 들었다고 서명하게 했나요?"],
    text: "금융회사는 상품의 중요한 사항(손실 가능성, 수수료, 중도해지 불이익 등)을 제가 이해할 수 있게 설명하지 않았거나, 사실과 다르게 설명하였습니다.",
  },
  {
    id: "unfair",
    title: "불공정영업행위",
    law: "제20조 제1항",
    qs: ["대출을 해 주는 조건으로 다른 보험·펀드 등의 가입을 요구(꺾기)했나요?", "우월한 지위를 이용해 부당한 이익이나 불이익을 요구했나요?"],
    text: "금융회사는 우월한 지위를 이용하여 대출 조건으로 다른 금융상품의 가입을 요구하는 등 불공정한 영업행위를 하였습니다.",
  },
  {
    id: "undue",
    title: "부당권유행위",
    law: "제21조",
    qs: ["'원금 보장', '확실히 오른다'처럼 불확실한 것을 단정해서 말했나요?", "가입을 거절했는데도 계속 권유했나요?", "다른 상품과 비교해서 사실과 다르게 알려 주었나요?"],
    text: "금융회사는 불확실한 사항에 대해 단정적 판단을 제공하거나, 사실과 다른 내용을 알리는 등 부당하게 계약 체결을 권유하였습니다.",
  },
];

/** 해지 요구 가능 기간: 위반을 안 날부터 1년 이내, 단 계약체결일부터 5년 이내 (시행령 제38조②). */
export function cancellationWindow({ contractDate, knownDate }, todayIso) {
  if (!isDate(contractDate)) return { ok: false, error: "계약체결일을 입력해 주세요." };
  if (!isDate(knownDate)) return { ok: false, error: "위반 사실을 알게 된 날을 입력해 주세요(정확히 모르면 오늘 날짜)." };
  if (knownDate < contractDate) return { ok: false, error: "알게 된 날이 계약체결일보다 앞설 수 없습니다." };
  const byKnown = addYears(knownDate, 1);
  const byContract = addYears(contractDate, 5);
  const end = byKnown < byContract ? byKnown : byContract;
  const left = diffDays(todayIso, end);
  return { ok: true, end, byKnown, byContract, left, expired: left < 0, limiter: byKnown < byContract ? "known" : "contract" };
}

/** 체크 결과. answers: { 위반id: 'yes'|'unsure'|'no' } → 가능성 단계. */
export function cancellationAssessment(answers) {
  const yes = VIOLATIONS.filter((v) => answers[v.id] === "yes");
  const unsure = VIOLATIONS.filter((v) => answers[v.id] === "unsure");
  let level, text;
  if (yes.length) {
    level = "high";
    text = "해당할 가능성이 있습니다. 해지 요구서를 보내 볼 만합니다.";
  } else if (unsure.length) {
    level = "check";
    text = "추가 확인이 필요합니다. 가입 서류·녹취·문자 등 증거를 먼저 모아 보세요.";
  } else {
    level = "low";
    text = "체크한 항목 중 해당하는 것이 없어 요건 충족 가능성이 낮아 보입니다.";
  }
  return { level, text, yes, unsure };
}
