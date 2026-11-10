"""일보를 한글(HWPX) 문서로 저장한다. PDF와 같은 내용(요약·지표·표·그래프)을 담는다."""
from __future__ import annotations

from pathlib import Path

from analysis import summary_lines
from collectors import EXCHANGE_NAMES
from report_pdf import (btc_trend_chart, dominance_chart, history_chart, pct, price,
                        won_short)

HEADER_FILL = "#E8F1FC"
CONTENT_WIDTH_MM = 170


def _table(doc, rows: list[list[str]], weights: list[float]) -> None:
    t = doc.add_table(len(rows), len(rows[0]))
    t.set_column_widths(weights)
    for r, row in enumerate(rows):
        for c, text in enumerate(row):
            t.set_cell_text(r, c, str(text))
        if r == 0:
            for c in range(len(row)):
                t.set_cell_shading(0, c, HEADER_FILL)
    doc.add_paragraph("")


def _picture(doc, buf, width_mm: float = CONTENT_WIDTH_MM) -> None:
    if buf is None:
        return
    doc.add_picture(buf.getvalue(), "png", width_mm=width_mm)


def render_hwpx(s: dict, history: list[dict], prev: dict | None, out: Path, *,
                sample: bool = False) -> Path:
    from hwpx import HwpxDocument  # 선택 의존성: 없으면 호출 측이 오류를 기록하고 PDF만 낸다

    date = s.get("date") or s["as_of"][:10]
    when = f"{date} {s.get('slot_label') or '09:00'}"
    d, g = s["domestic"], s["global"]

    doc = HwpxDocument.new()
    doc.paragraphs[0].text = "국내 가상자산 시장 일보" + (" (예시 데이터)" if sample else "")
    doc.add_paragraph(f"기준 {when} KST · 수집 {s['collected_at']} · 업비트·빗썸·코인원·코빗·고팍스(원화마켓)")

    doc.add_heading("요약", level=2)
    for k, v in summary_lines(s, prev):
        doc.add_paragraph(f"• {k}: {v}")

    def delta(cur, key, ratio=True):
        if not prev or prev.get(key) is None or cur is None:
            return "-"
        return pct(cur / prev[key] - 1, signed=True) if ratio else f"{(cur - prev[key]) * 100:+.1f}%p"

    doc.add_heading("핵심 지표", level=2)
    kpi = [["지표", "값", "전일 대비"],
           ["국내 24h 거래대금", won_short(d["total_value_24h"]), delta(d["total_value_24h"], "total_value_24h")],
           ["BTC 가격(업비트)", price(d["btc_price_krw"]), delta(d["btc_price_krw"], "btc_price_krw")],
           ["BTC 도미넌스 · 국내 거래대금", pct(d["btc_dominance"]), delta(d["btc_dominance"], "btc_dominance_kr", False)],
           ["BTC 도미넌스 · 글로벌 거래대금", pct(g.get("btc_dom_volume")),
            delta(g.get("btc_dom_volume"), "btc_dom_global_volume", False)],
           ["BTC 도미넌스 · 글로벌 시가총액", pct(g.get("btc_dom_mcap")),
            delta(g.get("btc_dom_mcap"), "btc_dom_global_mcap", False)],
           ["김치 프리미엄(BTC)", pct(g.get("kimchi_premium"), signed=True),
            delta(g.get("kimchi_premium"), "kimchi_premium", False)],
           ["원/달러 환율", price(g.get("usdkrw")), "-"],
           ["글로벌 시가총액", f"${g['total_mcap_usd'] / 1e12:,.2f}T" if g.get("total_mcap_usd") else "-", "-"]]
    _table(doc, kpi, [3, 2, 2])

    doc.add_heading("거래소별 현황", level=2)
    ex = [["거래소", "종목 수", "24h 거래대금(원)", "점유율", "BTC 가격(원)", "BTC 도미넌스"]]
    for r in s["exchanges"]:
        if not r["ok"]:
            ex.append([r["name"], "수집 실패", "-", "-", "-", "-"])
            continue
        ex.append([r["name"] + ("*" if r["estimated"] else ""), f"{r['markets']:,}", won_short(r["value_24h"]),
                   pct(r["share"]), price(r["btc_price"]), pct(r["btc_dominance"])])
    ex.append(["합계", f"{d['markets']:,}", won_short(d["total_value_24h"]), "100.0%", "-", pct(d["btc_dominance"])])
    _table(doc, ex, [2, 1.5, 2.5, 1.5, 2.5, 2])

    doc.add_heading("BTC 도미넌스 비교: 국내 vs 글로벌", level=2)
    _picture(doc, dominance_chart(s, width_in=6.0), 130)
    _picture(doc, history_chart(history, width_in=7.0, height_in=2.2))
    doc.add_paragraph("국내 도미넌스 = BTC 원화 거래대금 ÷ 전체 원화 거래대금(24시간). "
                      f"글로벌은 {g.get('source') or 'CoinGecko'} 기준.")

    doc.add_heading("BTC 30일 추이", level=2)
    _picture(doc, btc_trend_chart(s))

    doc.add_heading("거래대금 상위 10 종목 (5개 거래소 합산)", level=2)
    top = [["순위", "종목", "현재가(원)", "등락률", "24h 거래대금(원)", "주 거래소(비중)"]]
    for i, r in enumerate(s["top"], 1):
        top.append([str(i), r["symbol"], price(r["price"]), pct(r["change_rate"], signed=True),
                    won_short(r["value_24h"]), f"{EXCHANGE_NAMES[r['lead_exchange']]} ({pct(r['lead_share'])})"])
    _table(doc, top, [1, 1.5, 2.5, 1.5, 2.5, 3])

    doc.add_heading("등락 상위 5 (24h 거래대금 10억 원 이상, 스테이블코인 제외)", level=2)
    for title, rows in (("상승 상위", s["gainers"]), ("하락 상위", s["losers"])):
        data = [[title, "등락률", "거래대금"]]
        data += [[r["symbol"], pct(r["change_rate"], signed=True), won_short(r["value_24h"])] for r in rows] or [["-", "-", "-"]]
        _table(doc, data, [2, 1.5, 2])

    lst = s.get("listings") or {}
    if lst:
        doc.add_heading("신규 상장·상장 폐지 (직전 집계 대비)", level=2)
        rows = [["거래소", "신규 상장", "상장 폐지"]]
        for ex_key, v in lst.items():
            rows.append([EXCHANGE_NAMES.get(ex_key, ex_key), ", ".join(v.get("listed", [])) or "-",
                         ", ".join(v.get("delisted", [])) or "-"])
        _table(doc, rows, [2, 4, 4])

    doc.add_paragraph("집계는 매일 KST 24:00 한 번. 24시간 지표는 해당 일 24:00 기준 직전 24시간이며 "
                      "전일 대비는 전날 24:00 집계와 비교. * 거래대금 추정치 포함.")

    report = doc.validate()
    errors = getattr(report, "errors", None)
    if errors:
        raise ValueError(f"HWPX 검증 실패: {errors}")
    out.parent.mkdir(parents=True, exist_ok=True)
    doc.save_to_path(out)
    return out
