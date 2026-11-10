import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import collectors as C  # noqa: E402
from analysis import (  # noqa: E402
    _josa_iga, build_snapshot, combine_symbols, merge_history, movers, previous, summary_lines,
)
from build_daily import exchange_snapshot  # noqa: E402
from slots import resolve_slot, slot_key, slot_time  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402


# ---------------------------------------------------------------- 파서

def test_upbit_parsers_keep_krw_only():
    markets = C.parse_upbit_markets([{"market": "KRW-BTC"}, {"market": "BTC-ETH"}, {"market": "KRW-XRP"}])
    assert markets == ["KRW-BTC", "KRW-XRP"]
    ts = C.parse_upbit_tickers([
        {"market": "KRW-BTC", "trade_price": 142000000, "signed_change_rate": -0.011,
         "acc_trade_price_24h": 794100000000.5},
        {"market": "KRW-BAD", "trade_price": 0, "signed_change_rate": 0, "acc_trade_price_24h": 1},
    ])
    assert len(ts) == 1 and ts[0].symbol == "BTC" and ts[0].change_rate == pytest.approx(-0.011)


def test_bithumb_skips_meta_and_converts_percent():
    ts = C.parse_bithumb({"status": "0000", "data": {
        "BTC": {"closing_price": "141,654,890", "acc_trade_value_24H": "280000000000.1",
                "fluctate_rate_24H": "1.25"},
        "date": "1762650000000"}})
    assert [t.symbol for t in ts] == ["BTC"]
    assert ts[0].price == 141654890 and ts[0].change_rate == pytest.approx(0.0125)


def test_bithumb_error_status_raises():
    with pytest.raises(ValueError):
        C.parse_bithumb({"status": "5600", "message": "점검"})


def test_coinone_change_from_yesterday():
    ts = C.parse_coinone({"result": "success", "tickers": [
        {"quote_currency": "KRW", "target_currency": "eth", "last": "5200000",
         "yesterday_last": "5000000", "quote_volume": "43170000000"}]})
    assert ts[0].symbol == "ETH" and ts[0].change_rate == pytest.approx(0.04)


def test_korbit_v2_and_v1():
    v2 = C.parse_korbit_v2({"success": True, "data": [
        {"symbol": "btc_krw", "close": "141755061", "priceChangePercent": "-0.5", "quoteVolume": "9.6e10"},
        {"symbol": "btc_usdt", "close": "1", "quoteVolume": "1"}]})
    assert [t.symbol for t in v2] == ["BTC"] and not v2[0].value_estimated
    v1 = C.parse_korbit_v1({"xrp_krw": {"last": "3400", "changePercent": "2", "volume": "1000"},
                            "timestamp": 1})
    assert v1[0].value_24h == 3_400_000 and v1[0].value_estimated


def test_gopax_estimates_value_and_change():
    ts = C.parse_gopax([{"name": "SOL-KRW", "open": 270000, "close": 280000, "volume": 10},
                        {"name": "BTC-USDT", "open": 1, "close": 1, "volume": 1}])
    assert len(ts) == 1 and ts[0].value_24h == 2_800_000
    assert ts[0].change_rate == pytest.approx(280000 / 270000 - 1)


def test_num_rejects_garbage():
    assert C._num("1,234.5") == 1234.5
    assert C._num("") is None and C._num("abc") is None and C._num(float("nan")) is None


def test_global_parsers():
    g = C.parse_coingecko_global({"data": {"market_cap_percentage": {"btc": 58.9},
                                           "total_market_cap": {"usd": 3.6e12},
                                           "total_volume": {"usd": 1.4e11}}})
    assert g["btc_dom_mcap"] == pytest.approx(0.589)
    b = C.parse_coingecko_btc([{"id": "bitcoin", "total_volume": 5e10, "current_price": 98000}])
    assert b["btc_volume_usd"] == 5e10
    cg = C.parse_cmc_global({"data": {"btc_dominance": 58.9, "quote": {"USD": {
        "total_market_cap": 3.6e12, "total_volume_24h": 1.4e11}}}})
    assert cg["btc_dom_mcap"] == pytest.approx(0.589) and cg["total_volume_usd"] == 1.4e11
    cb = C.parse_cmc_btc({"data": {"BTC": [{"quote": {"USD": {"price": 98000, "volume_24h": 5e10, "market_cap": 1.9e12}}}]}})
    assert cb["btc_volume_usd"] == 5e10 and cb["btc_price_usd_cg"] == 98000
    with pytest.raises(ValueError):
        C.parse_cmc_btc({"data": {}})
    assert C.parse_koreaexim([{"cur_unit": "USD", "deal_bas_r": "1,402.5"}]) == 1402.5
    assert C.parse_er_api({"rates": {"KRW": 1401.2}}) == 1401.2
    candles = C.parse_daily_candles([
        {"candle_date_time_kst": "2026-11-08T09:00:00", "trade_price": 2, "candle_acc_trade_price": 5},
        {"candle_date_time_kst": "2026-11-07T09:00:00", "trade_price": 1, "candle_acc_trade_price": 4}])
    assert [c["date"] for c in candles] == ["2026-11-07", "2026-11-08"]


# ---------------------------------------------------------------- 지표

def _t(ex, sym, price, chg, val):
    return C.Ticker(ex, sym, price, chg, val)


SAMPLE = {
    "upbit": [_t("upbit", "BTC", 100, 0.01, 200e8), _t("upbit", "XRP", 1, 0.2, 600e8),
              _t("upbit", "USDT", 1, 0.05, 200e8)],
    "bithumb": [_t("bithumb", "BTC", 101, 0.02, 100e8), _t("bithumb", "XRP", 1, -0.1, 100e8)],
}


def test_combine_symbols_sums_and_picks_lead():
    rows = combine_symbols(SAMPLE)
    xrp = next(r for r in rows if r["symbol"] == "XRP")
    assert xrp["value_24h"] == 700e8 and xrp["lead_exchange"] == "upbit"
    assert rows[0]["symbol"] == "XRP"


def test_movers_excludes_stablecoins():
    up, down = movers(combine_symbols(SAMPLE))
    assert all(r["symbol"] != "USDT" for r in up + down)


def test_snapshot_dominance_and_premium():
    s = build_snapshot(as_of="2026-11-09T09:00:00+09:00", collected_at="x", tickers_by_ex=SAMPLE,
                       global_={"btc_dom_volume": 0.38, "btc_dom_mcap": 0.59}, btc_usd=0.07,
                       usdkrw=1400.0, fx_source="t", btc_candles={}, errors={})
    d = s["domestic"]
    assert d["total_value_24h"] == 1200e8
    assert d["btc_dominance"] == pytest.approx(300 / 1200)
    assert s["global"]["kimchi_premium"] == pytest.approx(100 / 98 - 1)
    failed = [r for r in s["exchanges"] if not r["ok"]]
    assert {r["exchange"] for r in failed} == {"coinone", "korbit", "gopax"}
    lines = dict(summary_lines(s, {"total_value_24h": 1000e8, "btc_price_krw": 50, "kimchi_premium": 0.05}))
    assert "+20.0% 증가" in lines["거래금액"] and "업비트 83.3%" in lines["거래금액"]
    assert "+100.0% 상승" in lines["BTC 가격"]
    assert "축소" in lines["김치 프리미엄"]
    assert "국내 25.0% vs 글로벌 38.0%" in lines["BTC 도미넌스"]
    assert all(not v.endswith("니다.") for v in lines.values())  # 개조식, 존댓말 없음


def test_josa():
    assert _josa_iga("빗썸") == "이" and _josa_iga("업비트") == "가" and _josa_iga("Upbit") == "가"


def test_history_merge_and_previous():
    h = merge_history([{"date": "2026-11-08", "v": 1}, {"date": "2026-11-09", "v": 2}],
                      {"date": "2026-11-09", "v": 3})
    assert [x["v"] for x in h] == [1, 3]
    assert previous(h, "2026-11-09")["v"] == 1 and previous(h, "2026-11-08") is None


def test_exchange_snapshot_listing_diff():
    prev = {"exchanges": {"upbit": {"symbols": ["BTC", "OLD"]}}}
    snap = exchange_snapshot("2026-11-09", SAMPLE, prev)
    up = snap["exchanges"]["upbit"]
    assert up["listed"] == ["USDT", "XRP"] and up["delisted"] == ["OLD"]
    # 전일 파일에 없던 거래소는 비교하지 않음(전 종목이 신규로 잡히지 않게)
    assert snap["exchanges"]["bithumb"]["listed"] == []
    assert up["majors"]["BTC"]["price"] == 100


# ---------------------------------------------------------------- PDF(글꼴 있을 때만)

def test_sample_pdf_builds(tmp_path):
    try:
        import report_pdf
        report_pdf._find_font()
    except (FileNotFoundError, ImportError):
        pytest.skip("한글 글꼴 또는 PDF 라이브러리 없음")
    import build_daily
    assert build_daily.main(["--out", str(tmp_path), "--sample", "--date", "2026-11-09", "--slot", "1200"]) == 0
    pdf = tmp_path / "reports" / "2026" / "20261109-1200.pdf"
    assert pdf.exists() and pdf.stat().st_size > 50_000
    from pypdf import PdfReader
    assert len(PdfReader(str(pdf)).pages) == 2
    # 같은 집계의 HWPX 와 주요코인 시세 JSON
    hwpx = tmp_path / "reports" / "2026" / "20261109-1200.hwpx"
    assert hwpx.exists() and hwpx.stat().st_size > 10_000
    import json
    coins = json.loads((tmp_path / "data" / "coins.json").read_text(encoding="utf-8"))
    assert [c["symbol"] for c in coins["coins"]] == ["BTC", "ETH", "XRP", "SOL", "DOGE"]
    assert coins["slot"] == "1200" and len(coins["coins"][0]["year"]["points"]) == 53
    assert len(coins["coins"][0]["year"]["times"]) == 53
    idx = json.loads((tmp_path / "reports" / "index.json").read_text(encoding="utf-8"))
    assert idx[0]["hwpx"].endswith(".hwpx")


def test_hwpx_is_valid(tmp_path):
    pytest.importorskip("hwpx")
    try:
        import report_pdf
        report_pdf._find_font()
    except (FileNotFoundError, ImportError):
        pytest.skip("한글 글꼴 없음")
    import build_daily
    build_daily.main(["--out", str(tmp_path), "--sample", "--date", "2026-11-09", "--slot", "2400"])
    from hwpx import HwpxDocument
    doc = HwpxDocument.open(tmp_path / "reports" / "2026" / "20261109-2400.hwpx")
    assert not doc.validate().issues
    text = doc.text.plain()
    assert "국내 가상자산 시장 일보" in text and "2026-11-09 24:00" in text


# ---------------------------------------------------------------- 집계 시각(12:00·24:00)

KST = timezone(timedelta(hours=9))


def test_resolve_slot():
    # 하루 한 번(24:00) 집계: 낮 시각에 돌아도 직전에 끝난 날짜의 24:00
    assert resolve_slot(datetime(2026, 11, 10, 12, 3, tzinfo=KST)) == ("2026-11-09", "2400")
    # 24:00 집계는 자정 직후 돌지만 끝난 날짜로 기록
    d, s = resolve_slot(datetime(2026, 11, 10, 0, 3, tzinfo=KST))
    assert (d, s) == ("2026-11-09", "2400")
    d, s = resolve_slot(datetime(2026, 11, 10, 11, 59, tzinfo=KST))  # 자정 집계가 늦게 돌아도 같은 날짜
    assert (d, s) == ("2026-11-09", "2400")
    assert slot_time(d, "2400").isoformat() == "2026-11-10T00:00:00+09:00"
    assert slot_key(d, "1200") == "20261109-1200"


def test_history_is_keyed_by_slot_and_prefers_same_slot():
    h = []
    for date, slot, v in [("2026-11-08", "1200", 1), ("2026-11-08", "2400", 2), ("2026-11-09", "1200", 3)]:
        h = merge_history(h, {"date": date, "slot": slot, "v": v})
    assert [x["v"] for x in h] == [1, 2, 3]
    assert previous(h, "2026-11-09", "1200")["v"] == 1   # 전날 같은 시각
    assert previous(h, "2026-11-09", "2400")["v"] == 2   # 같은 시각이 없으면 직전 집계


def test_parse_candles_sorted_ascending():
    rows = [{"candle_date_time_kst": "2026-11-09T10:30:00", "opening_price": 2, "high_price": 3,
             "low_price": 1, "trade_price": 2.5},
            {"candle_date_time_kst": "2026-11-09T10:00:00", "opening_price": 1, "high_price": 2,
             "low_price": 1, "trade_price": 2}]
    out = C.parse_candles(rows)
    assert [c["t"] for c in out] == ["2026-11-09T10:00", "2026-11-09T10:30"]
    assert out[0]["close"] == 2 and out[1]["open"] == 2


# ---------------------------------------------------------------- 거래소 재무(DART)

def test_exchange_fin_parsers():
    import build_exchange_fin as F
    v = F.parse_api_accounts([
        {"account_nm": "자산총계", "sj_div": "BS", "thstrm_amount": "1,234,000"},
        {"account_nm": "영업수익", "sj_div": "IS", "thstrm_amount": "500"},
        {"account_nm": "당기순이익", "sj_div": "IS", "thstrm_amount": "-30"}])
    assert v["assets"] == 1234000 and v["revenue"] == 500 and v["net_income"] == -30 and v["equity"] is None
    xml = ("<P>(단위 : 백만원)</P><TABLE><TR><TD>자산총계</TD><TD>2,000</TD><TD>1,500</TD></TR>"
           "<TR><TD>Ⅰ. 영업수익</TD><TD>300</TD></TR><TR><TD>당기순이익(손실)</TD><TD>(40)</TD></TR></TABLE>")
    r = F.parse_audit_xml(xml)
    assert r["assets"] == 2000e6 and r["revenue"] == 300e6 and r["net_income"] == -40e6


def test_backfill_aggregate():
    import backfill_history as B
    per = {"upbit": {"BTC": {"2020-01-02": (100.0, 10.0)}, "ETH": {"2020-01-02": (100.0, 1.0)}}, "coinone": {"BTC": {"2020-01-02": (50.0, 10.0)}}}
    r = B.aggregate(per, {"2020-01-02": 0.01}, {"2019-12-31": 1000.0})[0]
    assert r[0] == "2020-01-02" and r[1] == 200 and r[3] == 50 and r[6] == 150 and r[7] == 0.6 and abs(r[8]) < 1e-9


def test_parse_report_xml_basis_and_interim():
    import build_exchange_fin as F
    xml = ("<TITLE>2. 연결재무제표</TITLE><TITLE>연결 재무상태표</TITLE><P>(단위 : 백만원)</P>"
           "<TABLE><TR><TD>자산총계</TD><TD>2,000</TD><TD>1,500</TD></TR><TR><TD>부채총계</TD><TD>500</TD><TD>400</TD></TR></TABLE>"
           "<TITLE>연결 손익계산서</TITLE><TABLE><TR><TD>영업수익</TD><TD>30</TD><TD>300</TD><TD>20</TD><TD>200</TD></TR>"
           "<TR><TD>반기순이익(손실)</TD><TD>(4)</TD><TD>(40)</TD><TD>1</TD><TD>2</TD></TR></TABLE>"
           "<TITLE>연결 현금흐름표</TITLE><TABLE><TR><TD>영업수익</TD><TD>999</TD></TR></TABLE>"
           "<TITLE>4. 재무제표</TITLE><TITLE>재무상태표</TITLE><P>(단위 : 원)</P><TABLE><TR><TD>자산총계</TD><TD>7</TD></TR></TABLE>")
    r = F.parse_report_xml(xml, interim=True)
    assert r["연결"]["assets"] == 2000e6 and r["연결"]["liabilities"] == 500e6
    assert r["연결"]["revenue"] == 300e6 and r["연결"]["net_income"] == -40e6   # 누적(두 번째) 열, 현금흐름표는 무시
    assert r["별도"]["assets"] == 7


def test_revenue_falls_back_to_sales_and_cleans_labels():
    import build_exchange_fin as F
    xml = ("<TITLE>손익계산서</TITLE><P>(단위 : 원)</P><TABLE><TR><TD>Ⅰ. 매출액 (주석 25)</TD><TD>50</TD></TR>"
           "<TR><TD>Ⅱ. 영업이익(손실)</TD><TD>9</TD></TR></TABLE>")
    assert F.parse_report_xml(xml)["별도"]["revenue"] == 50
    xml2 = xml.replace("<TR><TD>Ⅱ", "<TR><TD>영업수익</TD><TD>70</TD></TR><TR><TD>Ⅱ")
    assert F.parse_report_xml(xml2)["별도"]["revenue"] == 70   # 영업수익 우선
    rows = [{"account_nm": "매출액", "sj_div": "IS", "thstrm_amount": "5"}]
    assert F.parse_api_accounts(rows)["revenue"] == 5
