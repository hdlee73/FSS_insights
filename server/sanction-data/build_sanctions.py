"""금융감독원 '금융회사 제재공시'를 2011년부터 모아 지적사항 단위로 정리한 sanctions.json을 만든다.

목록(list.do) → 상세(view.do) → 첨부(PDF·HWP)를 읽어 다음을 뽑는다.
  권역, 회사명, 제재조치일(조치요구일), 담당부서, 기관/임원/직원 조치, 과태료·과징금 금액,
  지적사항별 제목·구분(문책/주의/개선 등)·위반 법률과 조항

사용법:
  python3 build_sanctions.py OUT.json [--prev PREV.json] [--from 2011] [--limit N] [--workers 4]
--prev가 있으면 이미 있는 건은 다시 받지 않는다(신규만 추가).
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import html
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hwp_text  # noqa: E402

BASE = "https://www.fss.or.kr"
LIST = BASE + "/fss/job/openInfo/list.do?menuNo=200476&sdate={s}&edate={e}&pageIndex={p}&pageUnit=100"
VIEW = BASE + "/fss/job/openInfo/view.do?menuNo=200476&examMgmtNo={m}&emOpenSeq={q}"
UA = {"User-Agent": "Mozilla/5.0 (FSS Insights sanction-data builder)"}
PARSER_VERSION = 1


def fetch(url: str, tries: int = 4) -> bytes:
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise last


# ---------------------------------------------------------------- 목록

ROW = re.compile(
    r"<tr>\s*<td>\s*\d+\s*</td>\s*<td>([^<]*)</td>\s*<td>\s*(\d{8})\s*</td>.*?"
    r"examMgmtNo=(\d+)&(?:amp;)?emOpenSeq=(\d+).*?</td>\s*<td>([^<]*)</td>",
    re.S,
)


def list_year(year: int, today: dt.date):
    s = f"{year}-01-01"
    e = f"{year}-12-31" if year < today.year else today.isoformat()
    out, p = [], 1
    while True:
        h = fetch(LIST.format(s=s, e=e, p=p)).decode("utf-8", "ignore")
        rows = ROW.findall(h)
        if not rows:
            break
        for co, d, m, q, dept in rows:
            out.append({
                "id": f"{m}-{q}", "m": m, "q": q,
                "co": html.unescape(co).strip(),
                "date": f"{d[:4]}-{d[4:6]}-{d[6:]}",
                "dept": html.unescape(dept).strip(),
            })
        if len(rows) < 100:
            break
        p += 1
        time.sleep(0.3)
    return out


# ---------------------------------------------------------------- 상세·첨부

DD = re.compile(r'<dt class="form-tit">([^<]+)</dt>\s*<dd class="form-conts">(.*?)</dd>', re.S)
FILE = re.compile(r'href="(/fss\.hpdownload[^"]+)"')


def clean_html(s: str) -> str:
    s = re.sub(r"<br\s*/?>", "\n", s)
    s = re.sub(r"<[^>]+>", " ", s)
    s = html.unescape(s).replace("\xa0", " ")
    return re.sub(r"[ \t]+", " ", s).strip()


def attachment_text(path: str, name: str) -> str:
    low = name.lower()
    if low.endswith(".pdf"):
        r = subprocess.run(["pdftotext", "-raw", "-enc", "UTF-8", path, "-"], capture_output=True, timeout=120)
        return r.stdout.decode("utf-8", "ignore")
    if low.endswith(".hwp"):
        return hwp_text.extract(path)
    if low.endswith(".hwpx"):
        import zipfile
        with zipfile.ZipFile(path) as z:
            parts = sorted(n for n in z.namelist() if re.match(r"Contents/section\d+\.xml", n))
            xml = "\n".join(z.read(n).decode("utf-8", "ignore") for n in parts)
        xml = re.sub(r"</hp:p>", "\n", xml)
        return html.unescape(re.sub(r"<[^>]+>", "", xml))
    return ""


CACHE_DIR = None


def load_case(row: dict) -> dict:
    url = VIEW.format(m=row["m"], q=row["q"])
    cpath = os.path.join(CACHE_DIR, row["id"] + ".json") if CACHE_DIR else None
    raw = None
    if cpath and os.path.exists(cpath):
        try:
            raw = json.load(open(cpath, encoding="utf-8"))
        except ValueError:
            raw = None
    if raw is not None:
        case = {"id": row["id"], "co": row["co"], "date": row["date"], "dept": row["dept"], "view": url, "file": raw["file"]}
        case.update(parse_case(raw["text"], raw["fields"], row))
        return case
    h = fetch(url).decode("utf-8", "ignore")
    fields = {k.strip(): clean_html(v) for k, v in DD.findall(h)}
    files = [html.unescape(f) for f in FILE.findall(h)]
    names = [urllib.parse.unquote_plus(f.split("file=")[-1]) for f in files]
    text, fname = "", None
    # PDF > HWPX > HWP 순으로 하나만 읽는다.
    order = sorted(range(len(files)), key=lambda i: {".pdf": 0, "hwpx": 1, ".hwp": 2}.get(names[i].lower()[-4:], 9))
    for i in order:
        try:
            data = fetch(BASE + files[i])
            with tempfile.NamedTemporaryFile(suffix=os.path.splitext(names[i])[1], delete=False) as tf:
                tf.write(data)
                tmp = tf.name
            try:
                text = attachment_text(tmp, names[i])
            finally:
                os.unlink(tmp)
            if len(text.strip()) > 50:
                fname = files[i]
                break
        except Exception as e:  # noqa: BLE001
            print(f"  ! {row['id']} 첨부 실패: {e}", file=sys.stderr)
    if cpath:
        tmp_path = cpath + ".tmp"
        with open(tmp_path, "w", encoding="utf-8", errors="replace") as f:
            json.dump({"file": (BASE + fname) if fname else None, "fields": fields, "text": text}, f, ensure_ascii=False)
        os.replace(tmp_path, cpath)
    if fname is None and files:
        fname = files[order[0]] if order else files[0]
    case = {
        "id": row["id"], "co": row["co"], "date": row["date"], "dept": row["dept"],
        "view": url, "file": (BASE + fname) if fname else None,
    }
    case.update(parse_case(text, fields, row))
    return case


# ---------------------------------------------------------------- 파싱

def norm_space(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


AMOUNT = re.compile(
    r"(과태료|과징금)(?![^\d]{0,20}면제)[^\d/,()]{0,14}?[(:：]?\s*"
    r"((?:\d[\d,]*(?:\.\d+)?\s*(?:조|억|천만|백만|십만|만|천)?\s*)+원)"
)
UNIT = {"조": 10**12, "억": 10**8, "천만": 10**7, "백만": 10**6, "십만": 10**5, "만": 10**4, "천": 10**3, "": 1}


def parse_won(s: str):
    """"17억 3,050만원", "39백만원", "1천만원", "12.5백만원" → 원."""
    s = s.replace(",", "").replace(" ", "").rstrip("원")
    total, found = 0, False
    for num, unit in re.findall(r"(\d+(?:\.\d+)?)(조|억|천만|백만|십만|만|천)?", s):
        total += float(num) * UNIT[unit or ""]
        found = True
    return int(round(total)) if found else None


def money(text: str):
    fine = penalty = 0
    for kind, amt in AMOUNT.findall(text):
        v = parse_won(amt)
        if not v:
            continue
        if kind == "과태료":
            fine += v
        else:
            penalty += v
    return fine, penalty


INST_KINDS = ["인가취소", "등록취소", "영업정지", "업무정지", "영업점폐쇄", "영업의 일부정지", "기관경고", "기관주의",
              "시정명령", "과징금", "과태료", "경영유의", "개선", "주의"]
EXEC_KINDS = ["해임권고", "직무정지", "업무집행정지", "문책경고", "주의적경고", "주의적 경고", "견책", "감봉", "주의",
              "퇴직자", "자율처리", "과태료"]
STAFF_KINDS = ["면직", "정직", "감봉", "견책", "주의", "퇴직자", "자율처리", "등록취소", "업무정지", "과태료"]


def kinds(text: str, vocab):
    found = []
    t = text.replace(" ", "")
    for k in vocab:
        kk = k.replace(" ", "")
        if kk in t and k not in found:
            # '주의'는 '기관주의·주의적경고'의 일부일 때 따로 세지 않는다.
            if k == "주의":
                rest = t.replace("기관주의", "").replace("주의적경고", "").replace("주의상당", "").replace("주의수준", "")
                if "주의" not in rest:
                    continue
            if k == "개선" and "개선사항" not in t and "개선" not in t:
                continue
            name = "주의적경고" if k == "주의적 경고" else k
            if name not in found:
                found.append(name)
    return found


LABEL = re.compile(r"^\s*[◦○•ㆍ\-]?\s*(기\s*관|임\s*원|직\s*원(?:\s*등)?|임\s*직\s*원|설\s*계\s*사|보험대리점|대리점|"
                   r"보험설계사|금융회사|회\s*사|법\s*인)\s*(.*)$")


def split_actions(sec: str):
    """3. 제재조치내용 표를 기관/임원/직원 문자열로 나눈다."""
    buckets = {"inst": [], "exec": [], "staff": []}
    cur = None
    for line in sec.splitlines():
        line = line.strip()
        if not line or re.match(r"^제\s*재\s*대\s*상", line):
            continue
        m = LABEL.match(line)
        if m:
            lab = m.group(1).replace(" ", "")
            cur = "exec" if lab == "임원" else "staff" if lab in ("직원", "직원등", "임직원", "설계사", "보험설계사") else "inst"
            line = m.group(2).strip()
        if cur and line:
            buckets[cur].append(line.lstrip("◦○•ㆍ￭▪- ").strip())
    return {k: norm_space(" / ".join(v)) for k, v in buckets.items()}


GROUP_WORDS = re.compile(r"(문책|주의|개선|경영유의|자율처리|조치의뢰|변상|권고|시정|요구|지적|제재|기관|조치|통보)\s*(?:및\s*\S+\s*)?(?:필요\s*)?사항")
CAT_OF = [("문책", "문책"), ("자율처리", "자율처리"), ("경영유의", "경영유의"), ("개선", "개선"), ("주의", "주의"), ("변상", "변상")]

# 제목 번호 모양별 단계: 가. → 1, (1)·⑴ → 2, (가)·㈎ → 3, 1) → 4
HEADS = [
    (1, re.compile(r"^\s*([가-하])\s*[.．]\s*(.+)$")),
    (2, re.compile(r"^\s*\(\s*(\d{1,2})\s*\)\s*(.+)$")),
    (2, re.compile(r"^\s*([⑴-⒇])\s*(.+)$")),
    (3, re.compile(r"^\s*\(\s*([가-하])\s*\)\s*(.+)$")),
    (3, re.compile(r"^\s*([㈎-㈛])\s*(.+)$")),
    (4, re.compile(r"^\s*(\d{1,2})\s*\)\s*(.+)$")),
]


def category(group_title: str):
    for key, name in CAT_OF:
        if key in group_title:
            return name
    return None


def is_group(title: str) -> bool:
    return bool(GROUP_WORDS.search(title)) and len(title) <= 30


LAW_BRACKET = re.compile(r"[「｢『]\s*([^」｣』]{2,60}?)\s*[」｣』]")
ARTICLE = re.compile(r"제\s*(\d+)\s*조(?:\s*의\s*(\d+))?(?:\s*제\s*(\d+)\s*항)?(?:\s*제\s*(\d+)\s*호)?")
PLAIN_LAW = re.compile(r"((?:[가-힣A-Za-z·ㆍ]+\s?){1,9}?(?:법률|법|시행령|시행규칙|감독규정|업무시행세칙|시행세칙|규정|세칙|규칙))\s*[」｣』]?\s*(?=제\s*\d+\s*조)")
PARTICLE_TOKEN = re.compile(r"(에|는|은|를|을|면|며|서|및|등|이|가|도|로|으로|하여|하고|하는|된|있는|위반|사항|규정상|따라|의하면|따르면|경우|때|중|또는|있고|있으며|하며|바|같이)$")
NOT_LAW = {"시행령", "시행규칙", "동법", "같은법", "동규정", "같은규정", "동시행령", "같은법시행령", "법", "이법", "관련법", "관계법", "관련규정", "규정", "내부규정", "업무규정", "세칙"}

LAW_CANON = {
    "자본시장과금융투자업에관한법률": "자본시장법", "자통법": "자본시장법",
    "자본시장과금융투자업에관한법률시행령": "자본시장법 시행령", "자본시장법시행령": "자본시장법 시행령",
    "금융소비자보호에관한법률": "금융소비자보호법", "금소법": "금융소비자보호법",
    "금융소비자보호에관한법률시행령": "금융소비자보호법 시행령", "금융소비자보호법시행령": "금융소비자보호법 시행령",
    "특정금융거래정보의보고및이용등에관한법률": "특정금융정보법", "특금법": "특정금융정보법",
    "특정금융정보의보고및이용등에관한법률": "특정금융정보법",
    "신용정보의이용및보호에관한법률": "신용정보법",
    "금융실명거래및비밀보장에관한법률": "금융실명법",
    "주식회사등의외부감사에관한법률": "외부감사법", "외감법": "외부감사법",
    "금융회사의지배구조에관한법률": "금융회사지배구조법", "지배구조법": "금융회사지배구조법",
    "금융산업의구조개선에관한법률": "금융산업구조개선법",
    "대부업등의등록및금융이용자보호에관한법률": "대부업법",
    "금융위원회의설치등에관한법률": "금융위원회법", "금융위원회설치법": "금융위원회법",
    "저축은행법": "상호저축은행법",
    "전금법": "전자금융거래법", "여전법": "여신전문금융업법",
    "개인정보보호법": "개인정보 보호법",
    "채권의공정한추심에관한법률": "채권추심법",
    "자본시장과금융투자에관한법률": "자본시장법", "자본시장과금융투자에관한법률시행령": "자본시장법 시행령",
    "근로자퇴직급여보장법": "근로자퇴직급여 보장법",
    "주식회사의외부감사에관한법률": "외부감사법",
    "신용협동조합시행령": "신용협동조합법 시행령",
}


def canon_law(name: str):
    n = norm_space(name.strip(" .,·:"))
    n = re.sub(r"^\d+\s*[.)]\s*", "", n)
    n = re.sub(r"^(?:\(?\s*(?:구|舊)\s*\)?)\s*", "", n)
    tokens = n.split(" ")
    # 정식 명칭이 끝에 들어 있으면 그것을 쓴다("…에 의하면 자본시장과 금융투자업에 관한 법률").
    for k in range(len(tokens)):
        tail = "".join(tokens[k:])
        if tail in LAW_CANON:
            return LAW_CANON[tail]
    # "등에 의하면 보험업법" 같은 앞말을 떼어 낸다.
    while len(tokens) > 1 and PARTICLE_TOKEN.search(tokens[0]):
        tokens.pop(0)
    n = " ".join(tokens)
    n = re.sub(r"^(?:구|舊)\s*", "", n)
    key = n.replace(" ", "")
    if key in LAW_CANON:
        return LAW_CANON[key]
    # 시행령·시행규칙은 모법 약칭 + 시행령으로 맞춘다.
    for suffix in ("시행령", "시행규칙"):
        if key.endswith(suffix):
            base = key[: -len(suffix)]
            if base in LAW_CANON:
                return LAW_CANON[base] + " " + suffix
            if base.endswith("법"):
                return base + " " + suffix
    return n


def art_label(m):
    return f"제{m.group(1)}조" + (f"의{m.group(2)}" if m.group(2) else "")


def _marks(t: str):
    br = [(m.start(), m.end(), m.group(1)) for m in LAW_BRACKET.finditer(t)]
    pl = [(m.start(1), m.end(), m.group(1)) for m in PLAIN_LAW.finditer(t)]
    pl = [x for x in pl if not any(b0 <= x[0] < b1 or x[0] <= b0 < x[1] for b0, b1, _ in br)]
    return sorted(br + pl)


def cites_in(text: str, per_line: bool = False):
    """'「보험업법」 제95조의2, 제97조 제1항' → ['보험업법 제95조의2', '보험업법 제97조']"""
    out = []
    chunks = text.splitlines() if per_line else [text.replace("\n", " ")]
    last = None
    for t in chunks:
        marks = _marks(t)
        for i, (s, e, raw) in enumerate(marks):
            same = re.search(r"(?:^|\s)(?:동|같은)\s*법\s*(시행령|시행규칙)?\s*$", raw)
            if same and last:
                base = re.sub(r" (시행령|시행규칙)$", "", last)
                law = base + (" " + same.group(1) if same.group(1) else "")
            else:
                law = canon_law(raw)
            if law.replace(" ", "") in NOT_LAW or not re.search(r"(법|법률|령|규칙|규정|세칙)$", law) or len(law) < 3:
                continue
            nxt = marks[i + 1][0] if i + 1 < len(marks) else len(t)
            seg = t[e:nxt]
            # 다른 말이 끼기 전, 조문 번호가 이어지는 부분만 본다.
            seg = re.split(r"(?:에\s*의하면|에\s*따르면|등에|에서|하여야|하고|않|위반|으로|에\s*따라|에\s*의해|의\s*규정)", seg)[0]
            last = law
            for am in ARTICLE.finditer(seg[:240]):
                c = f"{law} {art_label(am)}"
                if c not in out:
                    out.append(c)
    return out


BOX = re.compile(r"<\s*관\s*련\s*(?:규\s*정|법\s*규|법\s*령|조\s*문)\s*>|【\s*관\s*련\s*(?:규\s*정|법\s*규)\s*】|\[\s*관\s*련\s*(?:규\s*정|법\s*규)\s*\]|관\s*련\s*(?:규\s*정|법\s*규)\s*[:：]")


def items_of(sec: str):
    lines = [l.rstrip() for l in sec.splitlines()]
    heads = []  # (줄 번호, 단계, 제목)
    for i, l in enumerate(lines):
        for level, rx in HEADS:
            m = rx.match(l)
            body = m.group(2).strip() if m else ""
            # 문장(…하였음, …있음)이나 아주 긴 줄은 제목이 아니라 본문의 나열 항목이다.
            if m and len(body) >= 5 and len(body) <= 90 and "(생략)" not in body and not body.startswith("~") \
                    and not re.search(r"부터\s*\S{0,4}\s*까지", body) and not re.match(r"^[\d,.\s]+(?:원|건|명|%)", body) \
                    and not re.search(r"(음|함|임|였으며|하였고|하고|있고|었고|으며|였는데|는데|바|등|,)\s*$", body):
                heads.append((i, level, m.group(2).strip()))
                break
    items = []
    group, item_level = None, None
    for k, (i, level, title) in enumerate(heads):
        if is_group(title):
            # 묶음 제목(문책사항·경영유의사항 등): 구분을 정하고, 다음 항목의 단계를 다시 정한다.
            group = category(title) or group
            item_level = None
            continue
        if item_level is None or level < item_level:
            item_level = level
        if level != item_level:
            continue  # 항목 아래의 세부 단락
        # 항목 본문: 같은 단계 이상의 다음 제목이나 묶음 제목 전까지
        end = len(lines)
        for j, lv, tt in heads[k + 1:]:
            if lv <= level or is_group(tt):
                end = j
                break
        body = "\n".join(lines[i + 1:end])
        t = norm_space(title)
        if t.count("(") > t.count(")") and i + 1 < len(lines):
            t = norm_space(t + " " + lines[i + 1].strip())
        parts = BOX.split(body, maxsplit=1)
        laws = cites_in(parts[1], per_line=True) if len(parts) > 1 else []
        if not laws:
            laws = cites_in(body)
        items.append({"t": t[:150], "cat": group, "laws": laws[:12]})
    return items


def old_brackets(t: str) -> str:
    """2011~2013년 공시는 「」가 '?'로 깨져 있다: ?은행법? → 「은행법」."""
    return re.sub(r"\?\s*([가-힣A-Za-z·ㆍ\s]{2,40}?(?:법률|법|시행령|시행규칙|규정|세칙|규칙|기준))\s*\?", r"「\1」", t)


def parse_case(text: str, fields: dict, row: dict) -> dict:
    # 첨부가 없는 옛 공시(2011~2013년 무렵)는 상세 화면의 '제재대상사실' 칸에 본문이 있다.
    if len(text.strip()) < 50:
        fact = fields.get("제재대상사실", "")
        if len(fact) > 80:
            text = old_brackets(fact)
    t = text.replace("\r", "")
    t = re.sub(r"\n\s*-\s*\d+\s*-\s*\n", "\n", t)  # 쪽 번호
    a = re.search(r"제\s*재\s*조\s*치\s*내\s*용", t)
    b = re.search(r"\n\s*4\s*\.\s*제\s*재\s*대\s*상\s*사\s*실|제\s*재\s*대\s*상\s*사\s*실", t[a.end():]) if a else None
    act_sec = t[a.end(): a.end() + b.start()] if a and b else ""
    fact_sec = t[a.end() + b.end():] if a and b else t
    acts = split_actions(act_sec) if act_sec else {"inst": "", "exec": "", "staff": ""}
    # 상세 화면에 적힌 값이 있으면 그것을 우선한다(2021년 이전 공시는 대개 채워져 있다).
    for key, label in (("inst", "기관 제재대상"), ("exec", "임원 제재대상"), ("staff", "직원 제재대상")):
        v = fields.get(label, "").strip()
        if v and v not in ("-", "해당사항 없음"):
            acts[key] = norm_space(v)
    for key in acts:
        if re.fullmatch(r"[-–—\s]*|해당\s*사항\s*없음|없음", acts[key] or ""):
            acts[key] = ""
    fine, penalty = money(acts["inst"] or act_sec)
    items = items_of(fact_sec) if text else []
    return {
        "inst": acts["inst"], "exec": acts["exec"], "staff": acts["staff"],
        "instK": kinds(acts["inst"], INST_KINDS),
        "execK": kinds(acts["exec"], EXEC_KINDS),
        "staffK": kinds(acts["staff"], STAFF_KINDS),
        "fine": fine, "penalty": penalty,
        "items": items,
        "ok": bool(text) and bool(items),
    }


# ---------------------------------------------------------------- 권역

SECTOR_BY_NAME = [
    ("저축은행", r"저축은행"),
    ("대부", r"대부|크레디트|파이낸셜대부"),
    ("상호금융", r"농업협동조합|축산업협동조합|수산업협동조합|산림조합|신용협동조합|신협|새마을금고|[가-힣]+농협(?!은행|생명|손해|금융|캐피탈|지주)|[가-힣]+수협(?!은행)|조합$"),
    ("여신전문", r"카드|캐피탈|캐피털|리스|할부금융|신기술"),
    ("보험", r"보험|손해사정|라이프|금융서비스|금융판매|GA|에셋(?!.*운용)|인슈|재보험|공제"),
    ("자문일임", r"투자자문|자문|일임"),
    ("운용", r"자산운용|운용|인베스트먼트|파트너스|매니지먼트|어드바이저"),
    ("증권", r"증권|투자증권|선물|종합금융"),
    ("은행", r"은행"),
]
SECTOR_BY_DEPT = [
    ("저축은행", r"저축은행"), ("상호금융", r"상호금융"), ("여신전문", r"여신"), ("대부", r"대부|서민금융|불법금융"),
    ("보험", r"보험|손해사정"), ("운용", r"자산운용"), ("증권", r"금융투자|증권|자본시장"), ("은행", r"은행|일반은행|특수은행|외국계"),
]


def sector(co: str, dept: str, text_hint: str = "") -> str:
    name = co.replace(" ", "")
    for s, rx in SECTOR_BY_NAME:
        if re.search(rx, name):
            # 은행·보험 지주/계열 구분: '농협은행', '농협생명보험'은 위 규칙으로 처리된다.
            return s
    for s, rx in SECTOR_BY_DEPT:
        if re.search(rx, dept):
            return s
    return "기타"


def unify_law_names(cases):
    """띄어쓰기만 다른 법령명("근로자퇴직급여보장법"/"근로자퇴직급여 보장법")을 가장 많이 쓴 표기로 맞춘다."""
    from collections import Counter, defaultdict
    variants = defaultdict(Counter)
    for c in cases:
        for it in c.get("items", []):
            for l in it["laws"]:
                name = l.rsplit(" 제", 1)[0]
                variants[name.replace(" ", "")][name] += 1
    best = {k: v.most_common(1)[0][0] for k, v in variants.items()}
    for c in cases:
        for it in c.get("items", []):
            out = []
            for l in it["laws"]:
                name, art = l.rsplit(" 제", 1)
                fixed = f"{best[name.replace(' ', '')]} 제{art}"
                if fixed not in out:
                    out.append(fixed)
            it["laws"] = out


# ---------------------------------------------------------------- 실행

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--prev")
    ap.add_argument("--from", dest="start", type=int, default=2011)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--reparse", action="store_true", help="이전 자료를 버리고 모두 다시 받는다")
    ap.add_argument("--cache", help="첨부에서 뽑은 글을 보관할 폴더(다시 파싱할 때 재다운로드 방지)")
    args = ap.parse_args()
    global CACHE_DIR
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)
        CACHE_DIR = args.cache

    today = dt.date.today()
    prev = {}
    if args.prev and os.path.exists(args.prev) and not args.reparse:
        try:
            old = json.load(open(args.prev, encoding="utf-8"))
            if old.get("parser") == PARSER_VERSION:
                prev = {c["id"]: c for c in old.get("cases", [])}
        except Exception as e:  # noqa: BLE001
            print("이전 자료를 읽지 못했습니다:", e, file=sys.stderr)

    rows = []
    for y in range(args.start, today.year + 1):
        r = list_year(y, today)
        print(f"{y}: {len(r)}건", file=sys.stderr)
        rows += r
    seen, uniq = set(), []
    for r in rows:
        if r["id"] not in seen:
            seen.add(r["id"])
            uniq.append(r)
    # 새 공시와, 글을 보관해 둔 공시(다시 파싱만 하므로 빠르다 → 파서 개선이 기존 자료에도 반영된다)
    cached_ids = {f[:-5] for f in os.listdir(CACHE_DIR) if f.endswith(".json")} if CACHE_DIR else set()
    todo = [r for r in uniq if r["id"] not in prev or r["id"] in cached_ids]
    if args.limit:
        todo = todo[: args.limit]
    print(f"전체 {len(uniq)}건, 처리할 {len(todo)}건(보관 글 {len(cached_ids)}건)", file=sys.stderr)

    done = {}
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(load_case, r): r for r in todo}
        for n, f in enumerate(cf.as_completed(futs), 1):
            r = futs[f]
            try:
                done[r["id"]] = f.result()
            except Exception as e:  # noqa: BLE001
                print(f"  ! {r['id']} {r['co']} 실패: {e}", file=sys.stderr)
            if n % 100 == 0:
                print(f"  {n}/{len(todo)}", file=sys.stderr)

    cases = []
    for r in uniq:
        c = done.get(r["id"]) or prev.get(r["id"])
        if not c:
            continue
        c["sector"] = sector(c["co"], c.get("dept", ""))
        cases.append(c)
    unify_law_names(cases)
    cases.sort(key=lambda c: (c["date"], c["id"]), reverse=True)
    if args.limit == 0 and prev and len(cases) < len(prev) * 0.9:
        sys.exit("수집 건수가 이전보다 크게 줄어 저장하지 않습니다.")
    out = {
        "parser": PARSER_VERSION,
        "generatedAt": dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).isoformat(timespec="minutes"),
        "from": args.start,
        "source": BASE + "/fss/job/openInfo/list.do?menuNo=200476",
        "cases": cases,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    ok = sum(1 for c in cases if c.get("ok"))
    print(f"저장: {len(cases)}건 (지적사항 추출 {ok}건, {ok * 100 // max(1, len(cases))}%)", file=sys.stderr)


if __name__ == "__main__":
    main()
