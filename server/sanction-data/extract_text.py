"""제재공시 첨부파일(PDF·HWP·HWPX·이미지)에서 글을 뽑는다.

'스캔 이미지라 읽을 수 없다'고 잘못 판정하던 문제를 없애려고 여러 방법을 차례로 시도한다.
  1. PDF: pdftotext -raw → pdftotext -layout → pdfminer (글자 층이 있는 PDF는 여기서 끝)
  2. HWPX: Contents/section*.xml, HWP: hwp_text (둘 다 안 되면 문서 안 그림을 OCR)
  3. 글자 층이 없거나 깨진 PDF(예: 'Microsoft Print to PDF'로 만들어 글자가 그림 조각으로 들어간 것), 이미지(tif·jpg·png)
     → tesseract(한국어)로 OCR. 사람 눈으로 읽을 수 있는 쪽만 OCR하고, OCR로 얻은 글은 method에 'ocr'를 붙여 구분한다.
반환: (text, method, quality)  — quality는 글의 한글 비율 등으로 본 0~1 점수.
"""
import html
import io
import os
import re
import subprocess
import tempfile
import zipfile

import hwp_text

HANGUL = re.compile(r"[가-힣]")
MAX_OCR_PAGES = 40
IMAGE_EXT = (".tif", ".tiff", ".jpg", ".jpeg", ".png", ".gif", ".bmp")


def quality(text: str) -> float:
    """읽을 만한 글인지 0~1로 본다: 한글이 충분하고 깨진 글자(□·�·사설 영역)가 적을수록 높다."""
    t = re.sub(r"\s+", "", text or "")
    if len(t) < 30:
        return 0.0
    hangul = len(HANGUL.findall(t))
    bad = len(re.findall(r"[�-]|\(cid:\d+\)", t))
    # 문서 하나에 '제재'·'조치'·'위반' 같은 낱말이 하나도 없으면 읽은 글이 아닐 가능성이 크다.
    key = 1 if re.search(r"제\s*재|조\s*치|위\s*반|지\s*적|검\s*사|금융", text) else 0.4
    # 글자 한두 개씩 줄이 갈라진 글(일부 PDF에서 pdftotext -raw가 이렇게 뽑는다)은 읽을 수 없으므로 점을 깎는다.
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    frag = sum(1 for l in lines if len(l) <= 2) / max(1, len(lines))
    frag_pen = 1.0 if frag < 0.2 else max(0.0, 1.0 - (frag - 0.2) * 3)
    return round(min(1.0, hangul / max(1, len(t)) * 2.2) * (1 - min(1.0, bad / max(1, len(t)) * 5)) * key * frag_pen, 3)


KEY_TERMS = ("제재", "조치", "위반", "관련", "금융", "사실", "규정", "법", "경우", "하여", "업무", "대하여", "있음", "않")


def ocr_readable(text: str) -> bool:
    """OCR 결과가 쓸 만한지: 한글 비율이 충분하고 공시에 나오는 낱말이 여럿 들어 있어야 한다(글자가 뭉개지면 둘 다 낮다)."""
    t = re.sub(r"\s+", "", text or "")
    if len(t) < 120:
        return False
    hangul = len(HANGUL.findall(t)) / len(t)
    hits = sum(text.count(k) for k in KEY_TERMS)
    return hangul >= 0.45 and hits >= max(8, len(t) // 150)


def _run(cmd, timeout=180, **kw):
    return subprocess.run(cmd, capture_output=True, timeout=timeout, **kw)


def _page_text(page) -> str:
    tp = page.get_textpage()
    try:
        return tp.get_text_range()
    finally:
        tp.close()
        page.close()


def pdf_texts(path: str):
    """PDF에서 글자 층을 여러 방법으로 뽑는다(generator: 앞 방법이 부족하면 다음을 쓰도록)."""
    try:
        r = _run(["pdftotext", "-raw", "-enc", "UTF-8", path, "-"])
        yield "pdftotext-raw", r.stdout.decode("utf-8", "ignore")
    except Exception:  # noqa: BLE001
        pass
    try:  # 글자마다 위치가 따로 박힌 PDF도 줄 순서대로 읽는다
        import pypdfium2
        doc = pypdfium2.PdfDocument(path)
        try:
            txt = "\n".join(_page_text(doc[i]) for i in range(len(doc)))
        finally:
            doc.close()
        yield "pdfium", txt.replace("\ufffe", "").replace("\uffff", "")
    except Exception:  # noqa: BLE001
        pass
    try:
        r = _run(["pdftotext", "-layout", "-enc", "UTF-8", path, "-"])
        yield "pdftotext-layout", r.stdout.decode("utf-8", "ignore")
    except Exception:  # noqa: BLE001
        pass
    try:
        from pdfminer.high_level import extract_text
        yield "pdfminer", extract_text(path) or ""
    except Exception:  # noqa: BLE001
        pass


def hwpx_text(path: str) -> str:
    with zipfile.ZipFile(path) as z:
        parts = sorted(n for n in z.namelist() if re.match(r"Contents/section\d+\.xml", n))
        xml = "\n".join(z.read(n).decode("utf-8", "ignore") for n in parts)
    xml = re.sub(r"</hp:p>", "\n", xml)
    return html.unescape(re.sub(r"<[^>]+>", "", xml))


# ---------------------------------------------------------------- OCR

def have_ocr() -> bool:
    try:
        r = _run(["tesseract", "--list-langs"], timeout=20)
        return b"kor" in r.stdout
    except Exception:  # noqa: BLE001
        return False


def _tesseract(img: str, psm: int) -> str:
    # 스레드를 1개로 묶고(여러 건을 동시에 돌리므로) 쪽당 시간 제한을 둔다.
    try:
        r = _run(["tesseract", img, "-", "-l", "kor", "--psm", str(psm)], timeout=120,
                 env={**os.environ, "OMP_THREAD_LIMIT": "1"})
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.decode("utf-8", "ignore")


def ocr_numfix(text: str) -> str:
    """OCR가 천 단위 쉼표를 마침표로 읽은 숫자('2.195,009원'·'9.760만원'·'225.061주')를 바로잡는다. 마침표 뒤가 정확히 세 자리일 때만."""
    return re.sub(r"(?<![\d.])(\d{1,3})\.(\d{3})(?!\d)(?!\.\d)", r"\1,\2", text)


def ocr_fix(text: str) -> str:
    """OCR가 자주 틀리는 번호 머리를 바로잡는다: '(3 제목'·'(01) 제목'·'0) 제목' → '(1) 제목'. 번호 값은 중요하지 않고 모양만 맞춘다."""
    out = []
    for l in text.splitlines():
        l = re.sub(r"^(\s*)\(\s*0?(\d{1,2})\s*(?:\)|(?=\s+[가-힣]))\s*", r"\1(\2) ", l)
        l = re.sub(r"^(\s*)[0OoＯ]\)\s+(?=[가-힣])", r"\1(1) ", l)
        out.append(l)
    return "\n".join(out)


def ocr_image_file(img: str) -> str:
    """한 쪽을 OCR한다. 표가 있는 쪽을 읽는 데는 psm 4가 낫고, 본문 단락은 6이 나아서 둘 중 점수 높은 쪽을 쓴다."""
    t = _tesseract(img, 4)
    if quality(t) >= 0.6:
        return ocr_fix(t)
    t6 = _tesseract(img, 6)
    return ocr_fix(t6 if quality(t6) > quality(t) else t)


def ocr_variants_pages(pages) -> list:
    """쪽 그림 목록을 psm 4·6·3으로 각각 읽어 [(psm, 전체 글)]을 돌려준다. 금액처럼 한 글자 오인식이 통계 오류가 되는 값을 교차 확인하는 데 쓴다."""
    outs = []
    for psm in (4, 6, 3):
        outs.append((psm, ocr_fix("\n".join(_tesseract(p, psm) for p in pages))))
    return outs


def ocr_pdf(path: str) -> str:
    with tempfile.TemporaryDirectory() as td:
        _run(["pdftoppm", "-r", "250", "-gray", "-l", str(MAX_OCR_PAGES), "-png", path, os.path.join(td, "p")], timeout=600)
        pages = sorted(f for f in os.listdir(td) if f.endswith(".png"))
        return "\n".join(ocr_image_file(os.path.join(td, f)) for f in pages)


def ocr_image(path: str) -> str:
    """tif(여러 쪽 가능)·jpg·png를 쪽마다 OCR한다."""
    from PIL import Image, ImageSequence
    out = []
    with Image.open(path) as im, tempfile.TemporaryDirectory() as td:
        for n, fr in enumerate(ImageSequence.Iterator(im)):
            if n >= MAX_OCR_PAGES:
                break
            g = fr.convert("L")
            # 해상도가 낮은 스캔은 글자가 작아 잘 안 읽히므로 키운다(가로 2000px 미만이면 2배).
            if g.width < 2000:
                g = g.resize((g.width * 2, g.height * 2))
            p = os.path.join(td, f"f{n}.png")
            g.save(p)
            out.append(ocr_image_file(p))
    return "\n".join(out)


def hwp_images_ocr(path: str) -> str:
    """HWP 안에 본문이 그림으로만 들어 있을 때(BinData) 그림을 꺼내 OCR한다."""
    import olefile
    out = []
    ole = olefile.OleFileIO(path)
    try:
        for e in ole.listdir():
            if len(e) == 2 and e[0] == "BinData" and re.search(r"\.(jpg|jpeg|png|bmp|tif|tiff|gif)$", e[1], re.I):
                data = ole.openstream(e).read()
                if data[:2] == b"\x78\x9c" or data[:1] == b"\x78":
                    import zlib
                    try:
                        data = zlib.decompress(data, -15)
                    except Exception:  # noqa: BLE001
                        pass
                with tempfile.NamedTemporaryFile(suffix=os.path.splitext(e[1])[1], delete=False) as tf:
                    tf.write(data)
                    tmp = tf.name
                try:
                    out.append(ocr_image(tmp))
                except Exception:  # noqa: BLE001
                    pass
                finally:
                    os.unlink(tmp)
    finally:
        ole.close()
    return "\n".join(out)


# ---------------------------------------------------------------- 진입점

def extract_multi(path: str, name: str, ocr: bool = True):
    """[(text, method, quality)]: 대표 결과 하나 + OCR로 읽었으면 방식만 다른 OCR 결과들(method 'ocr-alt')."""
    best = extract(path, name, ocr)
    res = [best]
    if ocr and best[1].replace("zip:", "").startswith("ocr") and best[1] != "ocr-hwp":
        try:
            ext = os.path.splitext((name or path).lower())[1]
            with tempfile.TemporaryDirectory() as td:
                if ext == ".pdf":
                    _run(["pdftoppm", "-r", "250", "-gray", "-l", str(MAX_OCR_PAGES), "-png", path, os.path.join(td, "p")], timeout=600)
                    pages = [os.path.join(td, f) for f in sorted(os.listdir(td)) if f.endswith(".png")]
                else:
                    from PIL import Image, ImageSequence
                    pages = []
                    with Image.open(path) as im:
                        for n, fr in enumerate(ImageSequence.Iterator(im)):
                            if n >= MAX_OCR_PAGES:
                                break
                            g = fr.convert("L")
                            if g.width < 2000:
                                g = g.resize((g.width * 2, g.height * 2))
                            p = os.path.join(td, f"f{n}.png")
                            g.save(p)
                            pages.append(p)
                for psm, t in ocr_variants_pages(pages):
                    res.append((t, "ocr-alt", quality(t)))
        except Exception:  # noqa: BLE001
            pass
    return res


def extract(path: str, name: str, ocr: bool = True):
    """(text, method, quality). 못 읽으면 ('', 'none', 0)."""
    low = (name or path).lower()
    ext = os.path.splitext(low)[1]
    best = ("", "none", 0.0)

    def consider(t, m):
        nonlocal best
        q = quality(t)
        if q > best[2]:
            best = (t, m, q)
        return q

    try:
        if ext == ".pdf":
            for m, t in pdf_texts(path):
                if consider(t, m) >= 0.6:
                    break
            pages = max(1, best[0].count("\f")) if best[0] else 1
            sparse = len(re.sub(r"\s+", "", best[0])) / pages < 350  # 쪽당 글자가 너무 적으면 일부가 그림일 수 있다
            if ocr and (best[2] < 0.6 or sparse):
                o = ocr_pdf(path)
                if best[2] < 0.6:
                    consider(o, "ocr-pdf")
                elif ocr_readable(o) and len(re.sub(r"\s+", "", o)) > 1.5 * len(re.sub(r"\s+", "", best[0])):
                    best = (o, "ocr-pdf", quality(o))
        elif ext == ".hwpx":
            if consider(hwpx_text(path), "hwpx") < 0.6 and ocr:
                pass
        elif ext == ".hwp":
            try:
                consider(hwp_text.extract(path), "hwp")
            except Exception:  # noqa: BLE001
                pass
            if best[2] < 0.6 and ocr:
                consider(hwp_images_ocr(path), "ocr-hwp")
        elif ext in IMAGE_EXT:
            if ocr:
                consider(ocr_image(path), "ocr-image")
        elif ext == ".zip":
            with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if n.endswith("/"):
                        continue
                    p = z.extract(n, td)
                    t, m, q = extract(p, n, ocr)
                    if q > best[2]:
                        best = (t, "zip:" + m, q)
    except Exception:  # noqa: BLE001
        pass
    return best
