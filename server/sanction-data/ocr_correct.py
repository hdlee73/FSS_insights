"""스캔 문서를 글자 인식(OCR)으로 읽은 제재공시의 오인식을 바로잡는다.

OCR(tesseract)는 영문 약어를 숫자로('ELS'→'615', 'BIS'→'815'), 비슷한 한글을('보험'→'보혐', '업무'→'엽무')
잘못 읽고, 법령명 앞에 「·『 같은 기호가 붙거나 조문 번호에 숫자가 하나 더 붙기도 한다.
글자 층이 있는 문서(정확한 자료)로 만든 어휘·법령명 사전을 기준으로 보정하므로, 새 오류가 생겨도 자료가 쌓이면 저절로 잡힌다.

보정은 모두 '고칠 근거가 분명할 때'만 한다(근거 없는 추정 금지). 고친 건수는 stats에 유형별로 센다.
"""
import re
from collections import Counter, defaultdict

# ---------------------------------------------------------------- 영문 약어(숫자로 잘못 읽힌 것)
# 약어 → 그 약어를 OCR이 흔히 바꿔 읽는 글자들. 한 글자씩 위치마다 후보를 두고 정규식을 만든다.
ACRONYMS = ["ELS", "DLS", "ELB", "DLB", "ELF", "DLF", "ELT", "DLT", "ELW", "ETF", "ETN", "ISA", "IRP", "BIS", "MMF", "CMA",
            "ABS", "ABCP", "NCR", "PEF", "CFD", "TRS", "OTC", "IPO", "DSR", "LTV", "DTI", "KYC", "AML", "STO", "HTS", "MTS"]
CONFUSE = {
    "E": "E6F", "L": "L1l|!", "S": "S58$", "B": "B8", "I": "I1l|!", "D": "D0O", "T": "T7", "F": "F", "W": "W", "N": "N",
    "R": "R", "P": "P", "A": "A4", "C": "C", "M": "M", "O": "O0", "K": "K", "Y": "Y", "Q": "Q", "G": "G", "H": "H", "U": "U", "V": "V",
}
# 숫자·기호만으로 된 오인식('615')은 실제 숫자와 구별이 안 되므로, 뒤에 이 낱말이 올 때만 약어로 본다.
NUM_UNIT = r"(?:원|건|명|개|주|억|만|천|백|조|회|일|월|년|%|호|쪽|점|배|좌|세|곳|차|항|개월|퍼센트)"
CTX = (r"\s?(?:기준|판매|상품|발행|투자|녹취|운용|편입|헤지|파생|조기상환|펀드|계약|관련|비율|거래|가입|계좌|운영|설명|광고|권유|"
       r"손실|사모|공모|설정|환매|등\b|및)")


def _acr_regex(a: str):
    cls = "".join("[" + re.escape(CONFUSE.get(ch, ch)) + "]" for ch in a)
    return re.compile(r"(?<![A-Za-z0-9])" + cls + r"(?![A-Za-z0-9])")


ACR_RX = [(a, _acr_regex(a)) for a in ACRONYMS]


def fix_acronyms(text: str, stats: Counter = None) -> str:
    """'615 판매'→'ELS 판매', '815기준'→'BIS기준' 처럼 약어가 숫자·비슷한 글자로 읽힌 것을 되돌린다."""
    def sub_all(t):
        for acr, rx in ACR_RX:
            def rep(m, acr=acr):
                s = m.group(0)
                if s == acr:
                    return s
                tail = t[m.end(): m.end() + 4]
                head = t[max(0, m.start() - 1): m.start()]
                has_alpha = bool(re.search(r"[A-Za-z]", s))
                if not has_alpha:  # 숫자·기호뿐: 실제 숫자일 수 있으니 뒤 문맥을 본다
                    if re.match(r"\s?" + NUM_UNIT, tail) or (head != "" and head in ".,"):
                        return s
                    if not re.match(CTX, tail):
                        return s
                    # '615'는 ELS, '815'는 BIS일 때만(비슷하게 생긴 다른 약어와 헷갈리지 않게 숫자형은 이 둘만 인정)
                    if s.replace(" ", "") not in ("615", "815", "6L5", "6l5", "B15"):
                        return s
                if stats is not None:
                    stats["약어"] += 1
                return acr
            t = rx.sub(rep, t)
        return t
    return sub_all(text)


# ---------------------------------------------------------------- 어휘 사전
H = re.compile(r"[가-힣]")
# OCR가 서로 바꿔 읽는 한글 쌍(틀리게 읽힌 글자, 맞는 글자). 한 글자 차이라도 이 쌍일 때만 고친다(멀쩡한 낱말을 고치지 않으려는 안전장치).
CONF_PAIRS = {tuple(p) for p in (
    "혐험 헌험 협험 엽업 엄업 업엽 칠철 절철 졸철 두투 블불 제재 선신 경정 남납 접집 메매 되퇴 러리 범법 지자 급금 판관 간관 기관 겸검 으은 중증 "
    "잠장 인신 믿민 뷰부 읍음 칭청 결걸 팀탐 령렁 훼휘 윤융 폭목 빙방 빈번 순술 암암 복북 샤사 틈름 쥬주 숴서 룹릅".split())}
CONF_PAIRS |= {(b, a) for a, b in list(CONF_PAIRS)}


def _conf_diff(w: str, v: str) -> bool:
    """같은 길이의 두 낱말이 OCR가 바꿔 읽는 글자 쌍으로만 다른가(최소 1글자)."""
    diffs = [(x, y) for x, y in zip(w, v) if x != y]
    return 1 <= len(diffs) <= 2 and all((x, y) in CONF_PAIRS for x, y in diffs)


def _toks(text: str):
    return re.findall(r"[가-힣]{2,}", text or "")


class Lexicon:
    """글자 층이 있는 문서로 읽은(=정확한) 제재 자료에서 어휘·법령명·조문 번호를 모은다."""

    def __init__(self, cases):
        self.words = Counter()
        self.laws = Counter()       # 공백 없는 법령명 → 횟수
        self.law_disp = {}          # 공백 없는 법령명 → 가장 많이 쓴 표기
        self.law_articles = defaultdict(set)
        self.law_max = defaultdict(int)
        disp = defaultdict(Counter)
        for c in cases:
            if c.get("ocr"):
                continue
            for it in c.get("items", []):
                for w in _toks(it.get("t", "")):
                    self.words[w] += 1
                for l in it.get("laws", []):
                    n, a = split_law(l)
                    if not n:
                        continue
                    k = n.replace(" ", "")
                    self.laws[k] += 1
                    disp[k][n] += 1
                    if a:
                        self.law_articles[k].add(a)
                        m = re.match(r"(\d+)", a)
                        if m:
                            self.law_max[k] = max(self.law_max[k], int(m.group(1)))
        self.law_disp = {k: v.most_common(1)[0][0] for k, v in disp.items()}
        # 길이별로 묶어 두면 후보 찾기가 빠르다.
        self._words_by_len = defaultdict(list)
        for w, n in self.words.items():
            if n >= 15:
                self._words_by_len[len(w)].append((w, n))
        self._laws_by_len = defaultdict(list)
        for k, n in self.laws.items():
            if n >= 3:
                self._laws_by_len[len(k)].append((k, n))

    # --- 낱말
    def fix_word(self, w: str):
        """어휘에 거의 없는 낱말이 흔한 낱말과 한 글자만 다르면 그 낱말로 고친다(후보가 하나뿐일 때만)."""
        if not H.search(w) or len(w) < 3 or self.words.get(w, 0) >= 3:
            return None
        cands = []
        for v, n in self._words_by_len.get(len(w), ()):
            if _conf_diff(w, v) and sum(1 for x, y in zip(w, v) if x != y) == 1 and n >= max(15, 10 * self.words.get(w, 0)):
                cands.append((n, v))
        if not cands:
            return None
        cands.sort(reverse=True)
        if len(cands) > 1 and cands[0][0] < cands[1][0] * 3:  # 후보가 팽팽하면 손대지 않는다
            return None
        return cands[0][1]

    # --- 법령명
    def fix_law_name(self, name: str):
        k = name.replace(" ", "")
        if self.laws.get(k, 0) >= 3 or len(k) < 3:
            return None
        cands = []
        for cand, n in self._laws_by_len.get(len(k), ()):
            d = sum(1 for x, y in zip(k, cand) if x != y)
            if _conf_diff(k, cand):
                cands.append((d, -n, cand))
        if not cands:
            return None
        cands.sort()
        if len(cands) > 1 and cands[0][0] == cands[1][0] and -cands[0][1] < -cands[1][1] * 3:
            return None
        return self.law_disp[cands[0][2]]


# ---------------------------------------------------------------- 법령 참조('법령명 제N조') 정리
LAW_SPLIT = re.compile(r"^(.*?)\s*(제\s*\d+조(?:의\s*\d+)?(?:제\s*\d+항)?.*)$")


def split_law(ref: str):
    """'자본시장법 시행령 제68조' → ('자본시장법 시행령', '68조'). 조문이 없으면 (이름, '')."""
    m = re.match(r"^(.*)\s+제(\d+조(?:의\d+)?)$", ref.strip())
    if m:
        return m.group(1).strip(), m.group(2)
    return ref.strip(), ""


def clean_law_name(n: str) -> str:
    """이름 앞뒤에 붙은 따옴표·괄호표시와, 옆 조문이 섞여 들어온 찌꺼기를 걷어 낸다."""
    n = re.split(r"[|「『]", n)[-1]
    n = n.strip(" \t\"'“”‘’<>,.-·ㆍ」』")
    n = re.sub(r"^좀\s+(?=[가-힣])", "", n)
    return n


def fix_article(lex: Lexicon, name: str, art: str):
    """사전에 없는 조문 번호가 그 법령의 가장 큰 조문 번호보다 눈에 띄게 크면(숫자가 하나 더 붙은 오인식), 한 자리를 빼서 사전에 있는 조문이 되면 고친다."""
    k = name.replace(" ", "")
    m = re.match(r"(\d+)(조(?:의\d+)?)$", art)
    if not m or k not in lex.law_articles or lex.laws[k] < 20 or art in lex.law_articles[k]:
        return art
    num, rest = m.group(1), m.group(2)
    if int(num) <= lex.law_max[k] or len(num) < 3:
        return art
    cands = {num[:i] + num[i + 1:] + rest for i in range(len(num))} & lex.law_articles[k]
    return cands.pop() if len(cands) == 1 else art


def fix_law_ref(lex: Lexicon, ref: str, stats: Counter = None) -> str:
    name, art = split_law(ref)
    new = clean_law_name(name)
    if new != name and stats is not None:
        stats["법령명 기호·찌꺼기"] += 1
    fixed = lex.fix_law_name(new)
    if fixed and fixed != new:
        if stats is not None:
            stats["법령명 오인식"] += 1
        new = fixed
    if art:
        a2 = fix_article(lex, new, art)
        if a2 != art:
            if stats is not None:
                stats["조문 번호"] += 1
            art = a2
    return f"{new} 제{art}" if art else new


# 글자 층 자료에 한 번도 안 나와 사전으로 못 잡는 흔한 오인식(관찰된 것). 낱말 안에 들어 있어도 고친다.
STATIC_FIXES = [
    ("엽무", "업무"), ("엄무", "업무"), ("업부", "업무"), ("보혐", "보험"), ("보헌", "보험"), ("보엄", "보험"), ("뽀험", "보험"),
    ("보험엽", "보험업"), ("보험엄", "보험업"), ("엽법", "업법"), ("엄법", "업법"), ("은행엽", "은행업"), ("급융", "금융"),
    ("금융기판", "금융기관"), ("금융기간", "금융기관"), ("원러금", "원리금"), ("지본시장", "자본시장"), ("차본시장", "자본시장"),
    ("자본시잠", "자본시장"), ("범률", "법률"), ("범인세", "법인세"), ("으행법", "은행법"), ("업범", "업법"), ("은행범", "은행법"), ("위혐", "위험"), ("보협", "보험"), ("중권", "증권"),
    ("유가중권", "유가증권"), ("겸사", "검사"), ("제재에관함", "제재에관한"), ("빛비밀", "및비밀"), ("거래빛", "거래및"),
]


def apply_static(text: str, stats: Counter = None) -> str:
    out = text
    for a, b in STATIC_FIXES:
        if a in out:
            if stats is not None:
                stats["낱말 오인식"] += out.count(a)
            out = out.replace(a, b)
    return out


def fix_title(lex: Lexicon, t: str, stats: Counter = None) -> str:
    t = fix_acronyms(t, stats)
    t = apply_static(t, stats)

    def rep(m):
        w = m.group(0)
        v = lex.fix_word(w)
        if v and v != w:
            if stats is not None:
                stats["낱말 오인식"] += 1
            return v
        return w
    return re.sub(r"[가-힣]{3,}", rep, t)


def correct_cases(cases, stats: Counter = None):
    """OCR로 읽은 건(ocr=True)의 지적사항 제목·법조문을 보정한다. 제자리에서 고치고 stats를 돌려준다."""
    stats = stats if stats is not None else Counter()
    lex = Lexicon(cases)
    for c in cases:
        if not c.get("ocr"):
            continue
        for it in c.get("items", []):
            it["t"] = fix_title(lex, it["t"], stats)
            seen, out = set(), []
            for l in it.get("laws", []):
                r = fix_law_ref(lex, apply_static(l, stats), stats)
                if r and r not in seen:
                    seen.add(r)
                    out.append(r)
            it["laws"] = out
    return stats
