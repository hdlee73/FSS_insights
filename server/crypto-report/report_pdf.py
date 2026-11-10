"""일보 스냅샷(dict)을 A4 1~2장 PDF 로 만든다. reportlab(문서) + matplotlib(그래프)."""
from __future__ import annotations

import io
import os
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # 화면 없는 서버용
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402
from reportlab.lib import colors  # noqa: E402
from reportlab.lib.enums import TA_LEFT  # noqa: E402
from reportlab.lib.pagesizes import A4  # noqa: E402
from reportlab.lib.styles import ParagraphStyle  # noqa: E402
from reportlab.lib.units import mm  # noqa: E402
from reportlab.pdfbase import pdfmetrics  # noqa: E402
from reportlab.pdfbase.ttfonts import TTFont  # noqa: E402
from reportlab.platypus import (  # noqa: E402
    Image, KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)

from analysis import summary_lines  # noqa: E402
from collectors import EXCHANGE_NAMES  # noqa: E402

# FSS_insights 톤
BLUE = "#2A7DE1"
BLUE_DARK = "#1A5FB4"
BLUE_SOFT = "#DCEBFC"
BG_SOFT = "#F2F7FC"
INK = "#1D2B3F"
MUTED = "#64778F"
ORANGE = "#F08A4B"
UP = "#D93A3A"      # 국내 관례: 상승 빨강
DOWN = "#2A6FD6"    # 하락 파랑
GRID = "#E3ECF6"

FONT_CANDIDATES = [
    os.environ.get("REPORT_FONT_DIR", ""),
    "/usr/share/fonts/truetype/nanum",
    str(Path(__file__).parent / "fonts"),
]


def _find_font() -> tuple[str, str]:
    for d in FONT_CANDIDATES:
        if not d:
            continue
        for reg, bold in (("NanumGothic.ttf", "NanumGothicBold.ttf"),
                          ("NanumGothic-Regular.ttf", "NanumGothic-Bold.ttf")):
            r, b = Path(d) / reg, Path(d) / bold
            if r.exists() and b.exists():
                return str(r), str(b)
    raise FileNotFoundError(
        "한글 글꼴(NanumGothic)을 찾지 못했습니다. `sudo apt-get install fonts-nanum` 또는 "
        "REPORT_FONT_DIR 환경변수로 글꼴 폴더를 지정하세요.")


def _setup_fonts() -> None:
    if "KR" in pdfmetrics.getRegisteredFontNames():
        return
    reg, bold = _find_font()
    pdfmetrics.registerFont(TTFont("KR", reg))
    pdfmetrics.registerFont(TTFont("KR-Bold", bold))
    from reportlab.pdfbase.pdfmetrics import registerFontFamily
    registerFontFamily("KR", normal="KR", bold="KR-Bold", italic="KR", boldItalic="KR-Bold")
    font_manager.fontManager.addfont(reg)
    font_manager.fontManager.addfont(bold)
    name = font_manager.FontProperties(fname=reg).get_name()
    plt.rcParams.update({
        "font.family": name, "axes.unicode_minus": False, "font.size": 8.5,
        "axes.edgecolor": GRID, "axes.labelcolor": MUTED, "xtick.color": MUTED, "ytick.color": MUTED,
        "axes.spines.top": False, "axes.spines.right": False,
    })


# ---------------------------------------------------------------- 서식

def won_short(v: float | None) -> str:
    if v is None:
        return "-"
    if abs(v) >= 1e12:
        return f"{v / 1e12:,.2f}조"
    if abs(v) >= 1e8:
        return f"{v / 1e8:,.0f}억"
    return f"{v:,.0f}"


def price(v: float | None) -> str:
    if v is None:
        return "-"
    if v >= 100:
        return f"{v:,.0f}"
    if v >= 1:
        return f"{v:,.2f}"
    return f"{v:,.4f}"


def pct(v: float | None, signed: bool = False) -> str:
    if v is None:
        return "-"
    return f"{v * 100:+.2f}%" if signed else f"{v * 100:.1f}%"


# ---------------------------------------------------------------- 그래프

def _png(fig) -> io.BytesIO:
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=220, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    buf.seek(0)
    return buf


def dominance_chart(s: dict, width_in: float = 7.2) -> io.BytesIO | None:
    g, d = s["global"], s["domestic"]
    items = []
    if g.get("btc_dom_mcap") is not None:
        items.append(("글로벌 · 시가총액 기준", g["btc_dom_mcap"], ORANGE))
    if g.get("btc_dom_volume") is not None:
        items.append(("글로벌 · 거래대금 기준", g["btc_dom_volume"], "#F6B68E"))
    if d.get("btc_dominance") is not None:
        items.append(("국내 5개사 합계 · 거래대금", d["btc_dominance"], BLUE))
    for r in s["exchanges"]:
        if r["ok"] and r.get("btc_dominance") is not None:
            items.append((f"  {r['name']}", r["btc_dominance"], "#9DBEEA"))
    if not items:
        return None
    items.reverse()
    fig, ax = plt.subplots(figsize=(width_in, 0.27 * len(items) + 0.5))
    ys = range(len(items))
    vals = [v * 100 for _, v, _ in items]
    ax.barh(list(ys), vals, color=[c for *_, c in items], height=0.62)
    ax.set_yticks(list(ys), [n for n, *_ in items])
    for y, v in zip(ys, vals):
        ax.text(v + 0.8, y, f"{v:.1f}%", va="center", fontsize=8, color=INK)
    ax.set_xlim(0, max(100, max(vals) + 10) if max(vals) > 90 else max(vals) * 1.18 + 2)
    ax.xaxis.set_major_formatter(FuncFormatter(lambda x, _: f"{x:.0f}%"))
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.tick_params(axis="y", length=0)
    return _png(fig)


def btc_trend_chart(s: dict) -> io.BytesIO | None:
    up = s["btc_candles"].get("upbit") or []
    bt = s["btc_candles"].get("bithumb") or []
    if len(up) < 2 and len(bt) < 2:
        return None
    dates = sorted({c["date"] for c in up} | {c["date"] for c in bt})
    upv = {c["date"]: c["value"] for c in up}
    btv = {c["date"]: c["value"] for c in bt}
    close = {c["date"]: c["close"] for c in (up or bt)}
    x = list(range(len(dates)))
    a = [upv.get(dt, 0) / 1e8 for dt in dates]
    b = [btv.get(dt, 0) / 1e8 for dt in dates]
    fig, ax = plt.subplots(figsize=(7.2, 2.3))
    ax.bar(x, a, color="#9DBEEA", width=0.7, label="업비트 BTC 거래대금")
    ax.bar(x, b, bottom=a, color="#C9DCF5", width=0.7, label="빗썸 BTC 거래대금")
    ax.set_ylabel("거래대금(억 원)")
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:,.0f}"))
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax2 = ax.twinx()
    cy = [close.get(dt) / 1e6 if close.get(dt) else None for dt in dates]
    ax2.plot(x, cy, color=BLUE_DARK, linewidth=1.8, label="BTC 종가(업비트)")
    last = next((i for i in range(len(cy) - 1, -1, -1) if cy[i] is not None), None)
    if last is not None:
        ax2.scatter([x[last]], [cy[last]], color=BLUE_DARK, s=14, zorder=3)
        ax2.annotate(f"{cy[last]:,.1f}백만", (x[last], cy[last]), textcoords="offset points",
                     xytext=(-4, 7), ha="right", fontsize=8, color=BLUE_DARK)
    ax2.set_ylabel("종가(백만 원)")
    ax2.spines["right"].set_visible(True)
    ax2.spines["top"].set_visible(False)
    step = max(1, len(dates) // 8)
    ax.set_xticks(x[::step], [dt[5:].replace("-", "/") for dt in dates[::step]])
    h1, l1 = ax.get_legend_handles_labels()
    h2, l2 = ax2.get_legend_handles_labels()
    ax.legend(h1 + h2, l1 + l2, loc="lower left", fontsize=7.5, frameon=False, ncol=3,
              bbox_to_anchor=(0, 1.0))
    ax.set_ylim(0, max(1, max(ai + bi for ai, bi in zip(a, b))) * 1.1)
    return _png(fig)


def _hist_label(r: dict) -> str:
    """차트 가로축 라벨. 예: 11/10 12시, 11/10 24시(슬롯 없는 옛 기록은 날짜만)."""
    d = r["date"][5:].replace("-", "/")
    slot = r.get("slot", "")
    return f"{d} {slot[:2]}시" if slot else d


def history_chart(history: list[dict], width_in: float = 7.2, height_in: float = 1.75) -> io.BytesIO | None:
    h = [r for r in history if r.get("btc_dominance_kr") is not None][-60:]  # 하루 2회 집계 × 30일
    if len(h) < 3:
        return None
    x = list(range(len(h)))
    fig, ax = plt.subplots(figsize=(width_in, height_in))
    series = [
        ("btc_dom_global_mcap", "글로벌 시가총액 기준", ORANGE, "-"),
        ("btc_dom_global_volume", "글로벌 거래대금 기준", "#F6B68E", "--"),
        ("btc_dominance_kr", "국내 거래대금 기준", BLUE, "-"),
    ]
    for key, label, color, ls in series:
        ys = [r[key] * 100 if r.get(key) is not None else None for r in h]
        ax.plot(x, ys, color=color, linestyle=ls, linewidth=1.8, label=label)
        last = next((i for i in range(len(ys) - 1, -1, -1) if ys[i] is not None), None)
        if last is not None:
            ax.annotate(f"{ys[last]:.1f}%", (x[last], ys[last]), textcoords="offset points",
                        xytext=(4, 0), va="center", fontsize=7.5, color=color)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:.0f}%"))
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_xlim(-0.5, len(h) - 1 + 3)
    step = max(1, len(h) // (4 if width_in < 5 else 8))
    ax.set_xticks(x[::step], [_hist_label(r) for r in h[::step]])
    ax.legend(loc="lower left", fontsize=7, frameon=False, ncol=1 if width_in < 5 else 3,
              bbox_to_anchor=(0, 1.0))
    return _png(fig)


# ---------------------------------------------------------------- 문서

def _styles() -> dict[str, ParagraphStyle]:
    base = ParagraphStyle("base", fontName="KR", fontSize=9, leading=13.5, textColor=colors.HexColor(INK))
    return {
        "title": ParagraphStyle("title", parent=base, fontName="KR-Bold", fontSize=19, leading=24),
        "meta": ParagraphStyle("meta", parent=base, fontSize=8.5, textColor=colors.HexColor(MUTED)),
        "h2": ParagraphStyle("h2", parent=base, fontName="KR-Bold", fontSize=11, leading=15,
                             textColor=colors.HexColor(BLUE_DARK), spaceBefore=7, spaceAfter=4),
        "body": base,
        "bullet": ParagraphStyle("bullet", parent=base, leftIndent=9, bulletIndent=0, alignment=TA_LEFT),
        "note": ParagraphStyle("note", parent=base, fontSize=7.3, leading=10.5, textColor=colors.HexColor(MUTED)),
        "cell": ParagraphStyle("cell", parent=base, fontSize=8, leading=10.5),
    }


def _table(data: list[list], widths: list[float], *, align_right_from: int = 1,
           color_cells: list[tuple[int, int, str]] | None = None, total_row: bool = False) -> Table:
    t = Table(data, colWidths=widths, repeatRows=1)
    st = [
        ("FONT", (0, 0), (-1, -1), "KR", 8),
        ("FONT", (0, 0), (-1, 0), "KR-Bold", 8),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor(BLUE)),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("TEXTCOLOR", (0, 1), (-1, -1), colors.HexColor(INK)),
        ("ALIGN", (align_right_from, 0), (-1, -1), "RIGHT"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 3.2),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3.2),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, colors.HexColor(GRID)),
    ]
    for r in range(1, len(data)):
        if r % 2 == 0:
            st.append(("BACKGROUND", (0, r), (-1, r), colors.HexColor(BG_SOFT)))
    if total_row:
        st += [("FONT", (0, -1), (-1, -1), "KR-Bold", 8),
               ("BACKGROUND", (0, -1), (-1, -1), colors.HexColor(BLUE_SOFT))]
    for (r, c, col) in color_cells or []:
        st.append(("TEXTCOLOR", (c, r), (c, r), colors.HexColor(col)))
    t.setStyle(TableStyle(st))
    return t


def _chg_color(v: float | None) -> str:
    if v is None or v == 0:
        return INK
    return UP if v > 0 else DOWN


def _img(buf: io.BytesIO | None, width: float) -> Image | None:
    if buf is None:
        return None
    from reportlab.lib.utils import ImageReader
    iw, ih = ImageReader(buf).getSize()
    buf.seek(0)
    return Image(buf, width=width, height=width * ih / iw)


def render_pdf(s: dict, history: list[dict], prev: dict | None, out: Path, *, sample: bool = False) -> Path:
    _setup_fonts()
    st = _styles()
    W = A4[0] - 30 * mm
    date = s.get("date") or s["as_of"][:10]
    slot_label = s.get("slot_label") or "09:00"
    when = f"{date} {slot_label}"
    d, g = s["domestic"], s["global"]
    story: list = []

    # 제목
    story.append(Paragraph("국내 가상자산 시장 일보", st["title"]))
    story.append(Spacer(1, 3))
    story.append(Paragraph(
        f"기준 {when} KST · 수집 {s['collected_at']} · 대상 업비트·빗썸·코인원·코빗·고팍스(원화마켓)",
        st["meta"]))
    story.append(Spacer(1, 8))

    # 요약
    lines = summary_lines(s, prev)
    if lines:
        box = Table([[Paragraph(f"• <b>{k}</b>: {v}", st["body"])] for k, v in lines], colWidths=[W])
        box.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(BG_SOFT)),
            ("LINEBEFORE", (0, 0), (0, -1), 2.2, colors.HexColor(BLUE)),
            ("LEFTPADDING", (0, 0), (-1, -1), 9), ("RIGHTPADDING", (0, 0), (-1, -1), 9),
            ("TOPPADDING", (0, 0), (-1, -1), 2.5), ("BOTTOMPADDING", (0, 0), (-1, -1), 2.5),
            ("TOPPADDING", (0, 0), (-1, 0), 7), ("BOTTOMPADDING", (0, -1), (-1, -1), 7),
        ]))
        story.append(box)
        story.append(Spacer(1, 8))

    # 핵심 지표
    def delta(cur, key, ratio=True):
        if not prev or prev.get(key) is None or cur is None:
            return "-"
        return pct(cur / prev[key] - 1, signed=True) if ratio else f"{(cur - prev[key]) * 100:+.1f}%p"

    def usd_t(v):
        return f"${v / 1e12:,.2f}T" if v else "-"

    kpis = [
        ("국내 24h 거래대금", won_short(d["total_value_24h"]), delta(d["total_value_24h"], "total_value_24h")),
        ("BTC 가격(업비트)", price(d["btc_price_krw"]), delta(d["btc_price_krw"], "btc_price_krw")),
        ("도미넌스 · 국내 거래대금", pct(d["btc_dominance"]), delta(d["btc_dominance"], "btc_dominance_kr", False)),
        ("도미넌스 · 글로벌 거래대금", pct(g.get("btc_dom_volume")),
         delta(g.get("btc_dom_volume"), "btc_dom_global_volume", False)),
        ("도미넌스 · 글로벌 시가총액", pct(g.get("btc_dom_mcap")),
         delta(g.get("btc_dom_mcap"), "btc_dom_global_mcap", False)),
        ("김치 프리미엄(BTC)", pct(g.get("kimchi_premium"), signed=True),
         delta(g.get("kimchi_premium"), "kimchi_premium", False)),
        ("원/달러 환율", price(g.get("usdkrw")), "-"),
        ("글로벌 시가총액", usd_t(g.get("total_mcap_usd")), "-"),
    ]
    half = (len(kpis) + 1) // 2
    kpi = [["지표", "값", "전일 대비", "지표", "값", "전일 대비"]]
    for i in range(half):
        a = kpis[i]
        b = kpis[i + half] if i + half < len(kpis) else ("", "", "")
        kpi.append([*a, *b])
    kpi_t = _table(kpi, [W * 0.22, W * 0.14, W * 0.12, W * 0.24, W * 0.14, W * 0.14])
    kpi_t.setStyle(TableStyle([("ALIGN", (3, 0), (3, -1), "LEFT"),
                               ("LINEBEFORE", (3, 0), (3, -1), 0.8, colors.HexColor(GRID))]))
    story.append(Paragraph("핵심 지표", st["h2"]))
    story.append(kpi_t)

    # 거래소별
    ex_rows = [["거래소", "종목 수", "24h 거래대금(원)", "점유율", "BTC 가격(원)", "BTC 도미넌스"]]
    for r in s["exchanges"]:
        if not r["ok"]:
            ex_rows.append([r["name"], "수집 실패", "-", "-", "-", "-"])
            continue
        ex_rows.append([r["name"] + ("*" if r["estimated"] else ""), f"{r['markets']:,}",
                        won_short(r["value_24h"]), pct(r["share"]), price(r["btc_price"]),
                        pct(r["btc_dominance"])])
    ex_rows.append(["합계", f"{d['markets']:,}", won_short(d["total_value_24h"]), "100.0%",
                    "-", pct(d["btc_dominance"])])
    story.append(Paragraph("거래소별 현황", st["h2"]))
    story.append(_table(ex_rows, [W * 0.16, W * 0.13, W * 0.2, W * 0.13, W * 0.2, W * 0.18], total_row=True))

    # 도미넌스 비교(왼쪽: 오늘 막대, 오른쪽: 30일 추이)
    half = W / 2 - 4
    bar = _img(dominance_chart(s, width_in=3.7), half)
    trend = _img(history_chart(history, width_in=3.7, height_in=2.35), half)
    if bar or trend:
        cells = [["오늘: 국내 vs 글로벌", "최근 30일 추이"],
                 [bar or Paragraph("-", st["note"]),
                  trend or Paragraph("일보가 3일 이상 쌓이면 추이 그래프가 나타납니다.", st["note"])]]
        pair = Table(cells, colWidths=[W / 2, W / 2])
        pair.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"),
                                  ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                                  ("FONT", (0, 0), (-1, 0), "KR-Bold", 8.5),
                                  ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor(MUTED))]))
        story.append(KeepTogether([
            Paragraph("BTC 도미넌스 비교: 국내 vs 글로벌", st["h2"]),
            pair,
            Paragraph("국내 도미넌스 = BTC 원화 거래대금 ÷ 전체 원화 거래대금(24시간). 글로벌 거래대금 기준은 "
                      f"{g.get('source') or 'CoinGecko'} BTC 거래대금 ÷ 전체 가상자산 거래대금, 시가총액 기준은 {g.get('source') or 'CoinGecko'} BTC 시가총액 비중.",
                      st["note"]),
        ]))

    # 2쪽: BTC 추이
    story.append(PageBreak())
    img = _img(btc_trend_chart(s), W)
    if img:
        story.append(KeepTogether([Paragraph("BTC 30일 추이 (일봉, 업비트 09:00 KST 마감 기준)", st["h2"]), img]))

    # 거래대금 상위 10
    top = [["순위", "종목", "현재가(원)", "등락률", "24h 거래대금(원)", "주 거래소(비중)"]]
    colored = []
    for i, r in enumerate(s["top"], 1):
        top.append([str(i), r["symbol"], price(r["price"]), pct(r["change_rate"], signed=True),
                    won_short(r["value_24h"]), f"{EXCHANGE_NAMES[r['lead_exchange']]} ({pct(r['lead_share'])})"])
        colored.append((i, 3, _chg_color(r["change_rate"])))
    story.append(KeepTogether([
        Paragraph("거래대금 상위 10 종목 (5개 거래소 합산)", st["h2"]),
        _table(top, [W * 0.08, W * 0.14, W * 0.2, W * 0.14, W * 0.2, W * 0.24],
               color_cells=colored, align_right_from=2),
    ]))

    # 상승·하락
    def mv(rows, title):
        data = [[title, "등락률", "거래대금"]]
        cc = []
        for i, r in enumerate(rows, 1):
            data.append([r["symbol"], pct(r["change_rate"], signed=True), won_short(r["value_24h"])])
            cc.append((i, 1, _chg_color(r["change_rate"])))
        if len(data) == 1:
            data.append(["-", "-", "-"])
        return _table(data, [W * 0.18, W * 0.14, W * 0.15], color_cells=cc)

    pair = Table([[mv(s["gainers"], "상승 상위"), mv(s["losers"], "하락 상위")]], colWidths=[W / 2, W / 2])
    pair.setStyle(TableStyle([("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                              ("VALIGN", (0, 0), (-1, -1), "TOP")]))
    story.append(KeepTogether([
        Paragraph("등락 상위 5 (24h 거래대금 10억 원 이상, 스테이블코인 제외)", st["h2"]), pair]))

    # 신규 상장·상장 폐지(전일 대비)
    lst = s.get("listings") or {}
    if lst:
        rows = [["거래소", "신규 상장", "상장 폐지"]]
        for ex, v in lst.items():
            rows.append([EXCHANGE_NAMES.get(ex, ex),
                         Paragraph(", ".join(v["listed"]) or "-", st["cell"]),
                         Paragraph(", ".join(v["delisted"]) or "-", st["cell"])])
        story.append(KeepTogether([
            Paragraph("신규 상장·상장 폐지 (직전 집계 원화마켓 목록 대비)", st["h2"]),
            _table(rows, [W * 0.16, W * 0.42, W * 0.42], align_right_from=3),
        ]))

    # 주석
    notes = [
        "* 표시 거래소는 거래대금을 API 의 코인 수량 × 현재가로 추정했습니다.",
        "24시간 값은 집계 시각(매일 12:00·24:00 KST) 직전 24시간 누적치이며, 거래소별 집계 기준이 조금씩 다릅니다. 전일 대비는 전날 같은 시각 집계와 비교합니다.",
        f"출처: 각 거래소 공개 API, {g.get('source') or 'CoinGecko'}, Coinbase Exchange, 환율 {g.get('fx_source') or '-'}. "
        "본 자료는 정보 제공용이며 투자 권유가 아닙니다.",
    ]
    if s.get("errors"):
        notes.append("수집 실패: " + ", ".join(f"{k}({v[:60]})" for k, v in s["errors"].items()))
    story.append(Spacer(1, 6))
    for n in notes:
        story.append(Paragraph(n, st["note"]))

    def on_page(canvas, doc):
        canvas.saveState()
        canvas.setFillColor(colors.HexColor(BLUE))
        canvas.rect(0, A4[1] - 6 * mm, A4[0], 6 * mm, stroke=0, fill=1)
        canvas.setFont("KR", 7.5)
        canvas.setFillColor(colors.HexColor(MUTED))
        canvas.drawString(15 * mm, 9 * mm, f"FSS Insights · 국내 가상자산 시장 일보 {when}")
        canvas.drawRightString(A4[0] - 15 * mm, 9 * mm, f"{doc.page}쪽")
        if sample:
            canvas.setFont("KR-Bold", 54)
            canvas.setFillColor(colors.Color(0.85, 0.23, 0.23, alpha=0.10))
            canvas.translate(A4[0] / 2, A4[1] / 2)
            canvas.rotate(35)
            canvas.drawCentredString(0, 0, "샘플 데이터")
        canvas.restoreState()

    out.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(str(out), pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm,
                            topMargin=14 * mm, bottomMargin=15 * mm,
                            title=f"국내 가상자산 시장 일보 {when}", author="FSS Insights",
                            subject="국내 원화거래소 5곳 시장 현황과 BTC 도미넌스 비교")
    doc.build(story, onFirstPage=on_page, onLaterPages=on_page)
    return out
