"""금감원 회계감리결과제재 목록 수집 (회사기준 acntnWrkCode=02, 회계법인 acntnWrkCode=01)."""
import re, time, urllib.request, html

BASE = "https://www.fss.or.kr"
UA = {"User-Agent": "FSS-Insights-Accounting/1.0 (+https://github.com/hdlee73/FSS_insights)"}


def get(url, tries=3, binary=False, timeout=60):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = r.read()
            return data if binary else data.decode("utf-8", "ignore")
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise last


def list_url(code, menu, page=1):
    return f"{BASE}/fss/job/accnutAdtorInfo/list.do?acntnWrkCode={code}&menuNo={menu}&pageIndex={page}&pageUnit=100"


def parse_list(page_html, code, menu):
    body = page_html[page_html.find("<tbody"):page_html.find("</tbody>")]
    rows = []
    for tr in re.findall(r"<tr.*?</tr>", body, re.S):
        m = re.search(r'<td class="num">\s*(\d+)\s*</td>\s*<td class="title"><a href="([^"]*acntnWrkSlno=(\d+)[^"]*)">(.*?)</a>', tr, re.S)
        if not m:
            continue
        tds = [re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", x))).strip() for x in re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)]
        files = [(html.unescape(u).replace("&amp;", "&"), html.unescape(n).strip()) for u, n in
                 re.findall(r'<a href="(/fss/cmmn/file/fileDown\.do[^"]*)">.*?<span class="name">(.*?)</span>', tr, re.S)]
        rows.append({
            "no": int(m.group(1)), "slno": m.group(3),
            "company": html.unescape(re.sub(r"<[^>]+>", "", m.group(4))).strip(),
            "auditPeriod": tds[2] if len(tds) > 2 else "",
            "actionDate": tds[3] if len(tds) > 3 else "",
            "market": tds[4] if len(tds) > 4 else "",
            "viewUrl": f"{BASE}/fss/job/accnutAdtorInfo/view.do?acntnWrkCode={code}&menuNo={menu}&acntnWrkSlno={m.group(3)}",
            "files": [{"url": BASE + u, "name": n} for u, n in files],
        })
    return rows


def fetch_all(code="02", menu="200617"):
    rows, page = [], 1
    while True:
        r = parse_list(get(list_url(code, menu, page)), code, menu)
        if not r:
            break
        rows += r
        if len(r) < 100:
            break
        page += 1
    return rows
