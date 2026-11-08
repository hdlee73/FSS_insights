"""금감원 '조사·감리결과 지적사항 및 조치내역' 첨부 텍스트 파서 (순수 함수, 테스트 가능)."""
import re

CIRC = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
ACCOUNTS = [
    ("매출", ["매출", "수익인식", "수익 인식"]), ("재고자산", ["재고자산", "재고"]), ("매출채권", ["매출채권", "채권"]),
    ("대여금", ["대여금", "대여"]), ("유형자산", ["유형자산", "건설중인자산"]), ("무형자산", ["무형자산", "개발비", "영업권"]),
    ("지분법", ["지분법"]), ("특수관계자", ["특수관계자"]), ("금융상품", ["금융상품", "파생", "전환사채", "전환상환", "상환전환", "신주인수권", "투자주식", "공정가치"]),
    ("현금", ["현금", "예금"]), ("종속기업·연결", ["연결", "종속"]), ("주식보상", ["주식보상", "주식매수선택권"]),
    ("손상차손", ["손상", "대손"]), ("충당부채", ["충당부채", "우발"]), ("차입금", ["차입금", "부채"]),
]
VIOLATIONS = [
    ("허위계상", ["허위"]), ("과대계상", ["과대"]), ("과소계상", ["과소"]), ("미계상", ["미계상", "누락", "미인식", "미반영"]),
    ("주석 미기재", ["주석"]), ("분류 오류", ["분류"]), ("평가 오류", ["평가", "회수가능"]), ("외부감사 방해", ["감사 방해", "감사방해", "방해"]),
]


def _unit_mult(s):
    return 100_000_000 if "억" in s else 1_000_000 if "백만" in s else 10_000 if "만" in s else 1


def money_to_won(text):
    """'1,606백만원' '3.2억원' → 원 단위 정수 목록."""
    out = []
    for m in re.finditer(r"([\d,]+(?:\.\d+)?)\s*(백만원|억원|천만원|만원|원)", text):
        try:
            v = float(m.group(1).replace(",", ""))
        except ValueError:
            continue
        unit = m.group(2)
        mult = {"백만원": 1_000_000, "억원": 100_000_000, "천만원": 10_000_000, "만원": 10_000, "원": 1}[unit]
        out.append(int(round(v * mult)))
    return out


def parse_decision(text):
    m = re.search(r"제\s*(\d+)\s*차\s*(임시\s*)?증권선물위원회\s*,?\s*(\d{4})\s*\.\s*(\d{1,2})\s*\.\s*(\d{1,2})\s*\.?\s*(의결|보고)", text)
    if not m:
        return None
    return {"round": int(m.group(1)), "temporary": bool(m.group(2)), "date": f"{m.group(3)}{int(m.group(4)):02d}{int(m.group(5)):02d}", "kind": m.group(6)}


def classify(title, table):
    hay = title
    found = []
    for name, keys in table:
        if any(k in hay for k in keys):
            found.append(name)
    return found


def parse_findings(body):
    """① … ② … 형태 지적사항. 번호가 없으면 빈 목록."""
    parts = re.split(rf"(?m)^\s*([{CIRC}○●◦])\s*", body)
    items = []
    for i in range(1, len(parts) - 1, 2):
        chunk = parts[i + 1].strip()
        lines = [l.strip() for l in chunk.split("\n") if l.strip()]
        if not lines:
            continue
        title = lines[0]
        rest = "\n".join(lines[1:])
        amounts = money_to_won(rest[:400]) if re.match(r"\(", lines[1] if len(lines) > 1 else "") else []
        desc = " ".join(l.lstrip("- ").strip() for l in lines[1:] if l.startswith("-"))
        items.append({
            "no": (CIRC.index(parts[i]) + 1) if parts[i] in CIRC else (i // 2 + 1), "title": re.sub(r"\s+", " ", title),
            "amountWon": max(amounts) if amounts else None,
            "amountNote": lines[1] if len(lines) > 1 and lines[1].startswith("(") else "",
            "summary": desc[:600],
            "accounts": classify(title + " " + desc, ACCOUNTS), "violations": classify(title + " " + desc, VIOLATIONS),
        })
    return items


def parse_actions(text):
    """'회 사 :' 이후 조치 항목."""
    m = re.search(r"회\s*사\s*:\s*\n", text)
    seg = text[m.end():] if m else text[text.rfind("조   치"):] if "조   치" in text else ""
    seg = seg.split("(감리집행기관")[0]
    notes = [l.strip().lstrip("※").strip() for l in seg.split("\n") if l.strip().startswith("※")]
    flat = re.sub(r"\n\s+(?=\()", " ", seg)
    items = [re.sub(r"\s+", " ", l.strip()[1:].strip()) for l in flat.split("\n") if l.strip().startswith("-")]
    flags = {
        "fine": any(i.startswith("과징금") for i in items),
        "fineWon": None, "fineFinal": False,
        "auditorDesignationYears": None, "issuanceLimitMonths": None,
        "prosecution": any(("고발" in i) for i in items), "referral": any(("통보" in i and "검찰" in i or "수사기관 통보" in i) for i in items),
        "correction": any("시정요구" in i for i in items),
        "dismissalRec": any(("해임" in i or "면직" in i) for i in items),
        "dutySuspension": any("직무정지" in i for i in items),
        "auditorRestriction": any("감사업무제한" in i for i in items),
    }
    for i in items:
        if i.startswith("과징금"):
            w = money_to_won(i)
            if w:
                flags["fineWon"], flags["fineFinal"] = max(w), True
        m1 = re.search(r"감사인지정\s*(\d+)\s*년", i)
        if m1:
            flags["auditorDesignationYears"] = int(m1.group(1))
        m2 = re.search(r"증권발행제한\s*(\d+)\s*월", i)
        if m2:
            flags["issuanceLimitMonths"] = int(m2.group(1))
    flags["fineNote"] = next((n for n in notes if "과징금" in n), "")
    return items, flags, notes


def parse_doc(text):
    t = text.replace("\r", "")
    decision = parse_decision(t)
    head = re.search(r"결산기\s*:\s*(.+)", t)
    market = re.search(r"(코스닥|유가증권|코넥스|비상장)\s*(?:시장)?\s*(?:상장)?\s*법인", t)
    industry = re.search(r"업종\s*:\s*(.+)", t)
    body = t
    cut = re.search(r"(?m)^\s*회\s*사\s*:\s*$", t)
    if cut:
        body = t[:cut.start()]
    findings = parse_findings(body)
    actions, flags, notes = parse_actions(t)
    status = "ok" if findings and actions else "partial" if (findings or actions) else "failed"
    return {
        "marketFromDoc": market.group(1) + ("시장" if market.group(1) != "비상장" else "") if market else "",
        "decision": decision, "fiscalYearEnds": re.sub(r"\s+", " ", head.group(1)).strip() if head else "",
        "industry": industry.group(1).strip() if industry else "",
        "findings": findings, "actions": actions, "flags": flags, "notes": notes, "parseStatus": status,
        "violationTypes": sorted({v for f in findings for v in f["violations"]}),
        "accounts": sorted({a for f in findings for a in f["accounts"]}),
    }
