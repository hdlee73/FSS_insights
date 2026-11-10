// 금액 입력·표시. "81억", "1조 2,500억", "3억5천만", "12,000,000" 같은 한국식 표기를 원 단위 숫자로 바꾼다.
// 화면(DOM)과 무관한 순수 함수만 둔다(단위 테스트 대상).

const UNITS = [
  ["조", 1e12],
  ["억", 1e8],
  ["만", 1e4],
];

/** 한국식 금액 문자열 → 원(정수). 읽을 수 없으면 null. 음수는 받지 않는다. */
export function parseAmount(input) {
  if (input == null) return null;
  if (typeof input === "number") return Number.isFinite(input) && input >= 0 ? Math.round(input) : null;
  let s = String(input).replace(/[\s,]/g, "").replace(/원$/, "");
  if (!s) return null;
  if (/^\d+(\.\d+)?$/.test(s)) return Math.round(Number(s));
  let total = 0;
  for (const [unit, mul] of UNITS) {
    const i = s.indexOf(unit);
    if (i < 0) continue;
    const head = s.slice(0, i);
    const n = head === "" ? 1 : smallNumber(head);
    if (n == null) return null;
    total += n * mul;
    s = s.slice(i + 1);
  }
  if (s) {
    const n = smallNumber(s);
    if (n == null) return null;
    total += n;
  }
  return Number.isFinite(total) ? Math.round(total) : null;
}

/** "3천", "5백", "2천5백", "1.5" 같은 만 미만 자리. */
function smallNumber(s) {
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s);
  const m = /^(?:(\d+(?:\.\d+)?)?천)?(?:(\d+(?:\.\d+)?)?백)?(?:(\d+(?:\.\d+)?)?십)?(\d+(?:\.\d+)?)?$/.exec(s);
  if (!m || !/[천백십]/.test(s)) return null;
  const part = (v, has, mul) => (has ? (v === undefined || v === "" ? 1 : Number(v)) * mul : 0);
  return part(m[1], s.includes("천"), 1000) + part(m[2], s.includes("백"), 100) + part(m[3], s.includes("십"), 10) + (m[4] ? Number(m[4]) : 0);
}

/** 1,234,500,000 → "12억 3,450만원" */
export function korUnit(won) {
  if (won == null || !Number.isFinite(won)) return "-";
  const neg = won < 0;
  let n = Math.round(Math.abs(won));
  if (n === 0) return "0원";
  const parts = [];
  for (const [unit, mul] of UNITS) {
    const q = Math.floor(n / mul);
    if (q) parts.push(q.toLocaleString("ko-KR") + unit);
    n -= q * mul;
  }
  if (n) parts.push(n.toLocaleString("ko-KR"));
  return (neg ? "-" : "") + parts.join(" ") + "원";
}

/** "8,100,000,000원 (81억원)" */
export function won(n) {
  if (n == null || !Number.isFinite(n)) return "-";
  const base = Math.round(n).toLocaleString("ko-KR") + "원";
  return Math.abs(n) >= 1e4 ? `${base} (${korUnit(n)})` : base;
}

/** 단위 미만 버림(과징금 100만원, 과태료 10만원). 음수는 0. */
export function truncate(n, unit) {
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.floor(n / unit) * unit;
}

export const pct = (r) => (r == null || !Number.isFinite(r) ? "-" : `${Math.round(r * 1000) / 10}%`);
