"""HWP/HWPX 첨부에서 본문 텍스트 추출."""
import re, zipfile, glob

from hwp_text import extract as hwp_extract


def hwpx_text(path):
    out = []
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if re.match(r"Contents/section\d+\.xml", n))
        for n in names:
            s = z.read(n).decode("utf-8", "ignore")
            s = re.sub(r"</hp:p>", "\n", s)
            s = re.sub(r"<hp:tab[^>]*/>", "\t", s)
            s = re.sub(r"<hp:lineBreak[^>]*/>", "\n", s)
            s = re.sub(r"<[^>]+>", "", s)
            out.append(s)
    import html
    return html.unescape("\n".join(out))


def doc_text(path):
    if path.lower().endswith(".hwpx"):
        return hwpx_text(path)
    return hwp_extract(path)
