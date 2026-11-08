"""공시조사결과제재(증권선물위원회·금융위원회 공시위반 조치 내역) 첨부 문서를 회사별 기록으로 나눈다.

첨부(HWP)는 건마다 이런 틀이다.
  1. 제재대상 : A, B                    ← 회사(역할)
  2. 제재조치일 : 2024. 10. 2.
  3. 제재조치내용                        ← 표: 제재대상 / 제재내용
  4. 제재대상사실
  【 위반 제목 】 본문 … < 관련법규 > 조항 …   ← 위반행위마다 반복
  (별첨) 산정내역                         ← 버린다
한 문서에 회사가 여럿이면 표의 회사별 조치와 본문 중 그 회사가 나오는 문장만 골라 회사 단위로 쪼갠다.
순수 함수라 네트워크 없이 테스트할 수 있다.
"""
import re

MASK = "○◯◌●□■◎☆★△▲◇◆♧♤♡♢▽▼"
ROLE_WORDS = ("발행인", "주선인", "매출인", "신고인", "인수인", "대표주관", "공동주관", "인수업무", "대표이사", "前대표", "전대표", "임원", "지분", "최대주주", "보고의무자", "소유자", "공시책임자")


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", (s or "").replace("　", " ")).strip()


def _core(name: str) -> str:
    """회사 이름 비교용 핵심 글자: ㈜·(주)·주식회사·괄호 안 설명·공백을 뺀다."""
    s = re.sub(r"\([^)]*\)", "", name)
    s = re.sub(r"㈜|\(주\)|주식회사|\s+", "", s)
    return s


def split_names(text: str):
    """'㈜A(발행인), ㈜B(現 ㈜C), ○○○' → ['㈜A(발행인)', '㈜B(現 ㈜C)', '○○○'] (괄호 안 쉼표는 나누지 않는다)."""
    parts, depth, cur = [], 0, ""
    for ch in text:
        if ch in "([（":
            depth += 1
        elif ch in ")]）":
            depth = max(0, depth - 1)
        if ch in ",、" and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [_norm(p) for p in parts if _norm(p)]


def split_role(name: str):
    """이름과 역할 분리: '㈜A(발행인)' → ('㈜A', '발행인'). '(現 ㈜B)'는 이름에 남긴다."""
    m = re.search(r"\(([^()]*)\)\s*$", name)
    if m and not re.match(r"\s*(現|舊|구|현|前|전)\s", m.group(1) + " ") and not re.match(r"(現|舊)", m.group(1).strip()):
        inner = m.group(1).strip()
        if any(w in inner for w in ROLE_WORDS) or len(inner) <= 12 and not re.search(r"[㈜]", inner):
            return _norm(name[:m.start()]), inner
    return name, ""


def is_masked(name: str) -> bool:
    c = re.sub(r"[㈜()\s]|주식회사", "", name)
    c = re.sub(r"\([^)]*\)", "", c)
    return not re.search(r"[가-힣A-Za-z0-9]", re.sub("[" + MASK + "]", "", c)) or all(ch in MASK for ch in c if ch.strip())


def won(text: str):
    """'6,000,000원' '600만원' '1억 2천만원' '90,000,000' → 원 단위 정수(없으면 None)."""
    t = text.replace(",", "").replace(" ", "")
    m = re.search(r"(\d+(?:\.\d+)?억)?(\d+(?:\.\d+)?천)?(\d+(?:\.\d+)?백)?(\d+(?:\.\d+)?만)?(\d+)?원?", t)
    if not m or not m.group(0):
        return None
    mult = [(1, 1e8), (2, 1e3 * 1e4), (3, 100 * 1e4), (4, 1e4), (5, 1)]
    total, any_ = 0.0, False
    for i, k in mult:
        g = m.group(i)
        if g:
            any_ = True
            total += float(re.sub(r"[억천백만]", "", g)) * k
    return int(round(total)) if any_ and total > 0 else None


def parse_action(line: str):
    """제재내용 한 줄 → (종류, 금액원 또는 None, 개월 또는 None)"""
    l = _norm(line)
    m = re.search(r"(과징금|과태료)\s*([\d,.]+\s*[억천백만]*\s*[\d,]*\s*[억천백만]*\s*원?)", l)
    if m:
        v = won(m.group(2))
        if v:
            return m.group(1), v, None
    m = re.search(r"증권발행제한\s*(\d+)\s*(?:월|개월)", l)
    if m:
        return "증권발행제한", None, int(m.group(1))
    for kw in ("임원해임권고", "해임권고", "직무정지", "검찰고발", "고발", "검찰통보", "통보", "시정요구", "경고", "주의", "정정명령", "감사인지정", "과징금", "과태료"):
        if kw in l:
            return kw, None, None
    return "", None, None


def _find(text: str, pat: str, flags=re.S):
    m = re.search(pat, text, flags)
    return m.group(1) if m else ""


def parse_date(s: str):
    m = re.search(r"(\d{4})\s*\.\s*(\d{1,2})\s*\.\s*(\d{1,2})", s or "")
    return "%s-%02d-%02d" % (m.group(1), int(m.group(2)), int(m.group(3))) if m else ""


def _clean_line(l: str) -> str:
    l = _norm(l)
    l = re.sub(r"^[◦○ㅇ•·‧\-–▪▶□ⅰ\s]+(?=\S)", "", l)
    return l.strip()


def parse_blocks(body: str):
    """'4. 제재대상사실' 이후를 【제목】 단위로 나눈다 → [{title, facts[lines], laws[lines]}]"""
    cut = re.search(r"^\s*[\(（](별첨|붙임|별표)[\)）]", body, re.M)
    if cut:
        body = body[:cut.start()]
    pieces = re.split(r"【\s*(.*?)\s*】", body, flags=re.S)
    blocks = []
    # pieces = [앞부분, 제목1, 본문1, 제목2, 본문2 …]
    for i in range(1, len(pieces) - 1, 2):
        title, text = _norm(pieces[i]), pieces[i + 1]
        parts = re.split(r"<\s*관련\s*법규\s*>", text, maxsplit=1)
        facts = [_clean_line(x) for x in parts[0].splitlines() if _clean_line(x)]
        laws = [_clean_line(x) for x in (parts[1].splitlines() if len(parts) > 1 else []) if _clean_line(x)]
        blocks.append({"title": title, "facts": facts, "laws": laws})
    if not blocks:  # 【】 없는 문서: 본문 전체를 한 건으로
        parts = re.split(r"<\s*관련\s*법규\s*>", body, maxsplit=1)
        facts = [_clean_line(x) for x in parts[0].splitlines() if _clean_line(x)]
        laws = [_clean_line(x) for x in (parts[1].splitlines() if len(parts) > 1 else []) if _clean_line(x)]
        if facts:
            blocks.append({"title": "", "facts": facts, "laws": laws})
    return blocks


ACTION_RE = re.compile(r"과징금|과태료|증권발행제한|경고|주의|고발|통보|시정|정정|해임|직무정지|면제|감경|권고|제한|정지|요구|명령|부과")


def parse_table(table_text: str):
    """제재조치내용 표 → [(회사 이름, [제재내용 줄…])] (표의 순서 그대로, 이름이 두 줄이면 합친다)"""
    lines = [_norm(l) for l in table_text.splitlines() if _norm(l)]
    lines = [l for l in lines if l not in ("제재대상", "제재내용", "구분", "제재대상 제재내용")]
    out = []
    name_parts = []
    for l in lines:
        is_action = bool(ACTION_RE.search(l)) and not re.search(r"㈜|\(주\)|주식회사", l)
        if is_action or (ACTION_RE.search(l) and out and not name_parts and re.search(r"(원|월|부과|고발|통보)\s*$", l)):
            if name_parts:
                out.append((" ".join(name_parts), []))
                name_parts = []
            if out:
                out[-1][1].append(l)
        else:
            name_parts.append(l)
    return out


def split_for_company(blocks, name, others):
    """블록 중 한 회사에 해당하는 문장만 골라 반환한다. others = 같은 문서의 다른 회사 이름들."""
    c = _core(split_role(name)[0])
    alias = {c} if c else set()
    for m in re.finditer(r"\((?:現|舊|현|구)\s*([^)]+)\)", name):
        a = _core(m.group(1))
        if a:
            alias.add(a)
    other_alias = set()
    for o in others:
        oc = _core(split_role(o)[0])
        if oc and oc not in alias:
            other_alias.add(oc)
    res = []
    for b in blocks:
        mentions_me = [f for f in b["facts"] if any(a in re.sub(r"\s", "", f) for a in alias)]
        mentions_other = [f for f in b["facts"] if any(a in re.sub(r"\s", "", f) for a in other_alias)]
        title_hit = any(a in re.sub(r"\s", "", b["title"]) for a in alias)
        if others and mentions_other and not mentions_me and not title_hit and not is_masked(name):
            continue  # 이 블록은 다른 회사 이야기
        facts = []
        for f in b["facts"]:
            fn = re.sub(r"\s", "", f)
            shared = re.search(r"등\d+(개)?(사|社|명|인)", fn)  # '㈜A 등 6개사'처럼 여럿을 함께 가리키는 문장
            fs = re.sub(r"^(㈜|\(주\)|주식회사)", "", fn)
            starts_other = any(fs.startswith(a) for a in other_alias) and not any(fs.startswith(a) for a in alias)
            if others and not shared and ((any(a in fn for a in other_alias) and not any(a in fn for a in alias)) or starts_other):
                continue
            facts.append(f)
        res.append({"title": b["title"], "facts": facts, "laws": b["laws"]})
    if blocks and not any(v["facts"] for v in res):
        res = [{"title": b["title"], "facts": b["facts"], "laws": b["laws"]} for b in blocks]  # 가려 내지 못하면 전체를 공통으로
    return res


_LAW_TOKEN = re.compile(
    r"(동\s*법\s*시행령|동\s*법\s*시행규칙|동\s*법|동\s*령)"
    r"|[｢「『]\s*([^｣」』]+?)\s*[｣」』]"
    r"|([가-힣][가-힣0-9 ]{1,38}?(?:법률|법|규정|규칙|시행령))(?=\s*제\s*\d+\s*조)"
    r"|(제\s*\d+\s*조(?:\s*의\s*\d+)?)"
)


def law_refs(lines):
    """관련법규 줄들 → ['자본시장과 금융투자업에 관한 법률 제161조', '… 시행령 제171조', …] (스마트 법령검색에 그대로 넣을 수 있는 형태, 조 단위·중복 제거)"""
    refs = []
    for line in lines:
        cur = base = ""
        for m in _LAW_TOKEN.finditer(line):
            if m.group(1):
                k = re.sub(r"\s", "", m.group(1))
                if base:
                    cur = base + (" 시행령" if "시행령" in k or k == "동령" else " 시행규칙" if "시행규칙" in k else "")
            elif m.group(2) or m.group(3):
                nm = _norm(m.group(2) or m.group(3))
                base = cur = nm
            elif m.group(4) and cur:
                ref = "%s %s" % (cur, re.sub(r"\s", "", m.group(4)))
                if ref not in refs:
                    refs.append(ref)
    return refs


def parse_disclosure(text: str):
    """문서 한 개 → 회사별 기록 목록. 형식이 맞지 않으면 빈 목록."""
    t = text.replace("\r", "")
    names_raw = _find(t, r"1\.\s*제재대상\s*[:：](.*?)\n\s*2\.\s*제재조치일")
    if not names_raw:
        return []
    names_raw = re.sub(r"\s*\n\s*", ", ", names_raw.strip())
    names = split_names(re.sub(r",\s*,", ",", names_raw))
    date = parse_date(_find(t, r"2\.\s*제재조치일\s*[:：](.*?)\n"))
    m4 = re.search(r"4\.\s*제재\S*사실", t)
    table = _find(t, r"3\.\s*제재조치내용(.*?)(?=4\.\s*제재\S*사실)") if m4 else _find(t, r"3\.\s*제재조치내용(.*)")
    body = t[m4.end():] if m4 else ""
    blocks = parse_blocks(body)
    rows = parse_table(table)
    if not rows:  # 표를 읽지 못하면 머리말의 이름만 쓴다
        rows = [(n, []) for n in names]
    used = set()
    all_names = [r[0] for r in rows]
    out = []
    for n, acts_lines in rows:
        # 머리말에서 같은 회사를 찾아 역할(발행인·매출인 등)을 얻는다
        role, disp = "", n
        for i, h in enumerate(names):
            if i in used:
                continue
            hc = _core(split_role(h)[0])
            if hc and (hc == _core(n) or _core(n).startswith(hc) or hc.startswith(_core(n))) and _core(n):
                used.add(i)
                role = split_role(h)[1]
                break
        nm, r2 = split_role(disp)
        role = role or r2
        actions, fine, limit, kinds = [], None, None, []
        for l in acts_lines:
            actions.append(l)
            k, amount, months = parse_action(l)
            if k in ("과징금", "과태료") and amount:
                fine = (fine or 0) + amount
            if months:
                limit = months
            if k and k not in kinds:
                kinds.append(k)
        viol = split_for_company(blocks, disp, [x for x in all_names if x != n])
        laws = []
        for v in viol:
            for l in v["laws"]:
                if l not in laws:
                    laws.append(l)
        out.append({
            "company": nm, "role": role, "masked": is_masked(nm), "date": date,
            "actions": actions, "kinds": kinds, "fineWon": fine, "issueLimitMonths": limit,
            "violations": viol, "laws": laws, "lawRefs": law_refs(laws), "coCount": len(rows),
        })
    return out
