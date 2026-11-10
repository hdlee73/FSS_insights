"""수집된 시세로 일보에 들어갈 지표를 계산한다(네트워크 없음, 순수 함수)."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import asdict

from collectors import EXCHANGE_NAMES, EXCHANGES, Ticker
from slots import SLOT_LABEL

# 상승·하락 순위에서 제외할 최소 거래대금(원). 거래가 거의 없는 종목의 급등락을 걸러낸다.
MIN_VALUE_FOR_MOVERS = 1_000_000_000
STABLECOINS = {"USDT", "USDC", "DAI", "USDS", "USDE", "FDUSD", "TUSD", "PYUSD", "USD1"}


def summarize_exchanges(tickers_by_ex: dict[str, list[Ticker]], caps: dict[str, dict] | None = None) -> list[dict]:
    rows = []
    total_all = sum(sum(t.value_24h for t in ts) for ts in tickers_by_ex.values())
    for ex in EXCHANGES:
        ts = tickers_by_ex.get(ex)
        if ts is None:
            rows.append({"exchange": ex, "name": EXCHANGE_NAMES[ex], "ok": False})
            continue
        total = sum(t.value_24h for t in ts)
        btc = next((t for t in ts if t.symbol == "BTC"), None)
        rows.append({
            "exchange": ex,
            "name": EXCHANGE_NAMES[ex],
            "ok": True,
            "markets": len(ts),
            "value_24h": total,
            "share": total / total_all if total_all else 0.0,
            "btc_price": btc.price if btc else None,
            "btc_value": btc.value_24h if btc else 0.0,
            "btc_dominance": (btc.value_24h / total) if (btc and total) else None,
            "btc_dom_mcap": mcap_dominance(ts, caps)[0],
            "estimated": any(t.value_estimated for t in ts),
        })
    return rows


PREMIUM_COINS = ["BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "USDT", "USDC"]


def mcap_dominance(tickers: list[Ticker], caps: dict[str, dict] | None) -> tuple[float | None, float | None]:
    """국내 거래소에 상장된 종목들의 글로벌 시가총액 합 대비 BTC 시가총액 비중과, 그 합에 반영된 종목의 거래대금 비중(커버리지)."""
    if not caps or "BTC" not in caps or not caps["BTC"].get("mcap"):
        return None, None
    listed = {t.symbol for t in tickers}
    total_cap = sum(caps[s]["mcap"] for s in listed if s in caps)
    if total_cap <= 0 or "BTC" not in listed:
        return None, None
    all_val = sum(t.value_24h for t in tickers)
    covered = sum(t.value_24h for t in tickers if t.symbol in caps)
    return caps["BTC"]["mcap"] / total_cap, (covered / all_val if all_val else None)


def coin_premiums(tickers_by_ex: dict[str, list[Ticker]], caps: dict[str, dict] | None,
                  usdkrw: float | None, btc_usd: float | None) -> tuple[dict, dict]:
    """코인별 김치 프리미엄(= 원화 가격 ÷ (달러 가격 × 환율) − 1). 기준 달러 가격은 글로벌 시세(BTC는 Coinbase).
    반환: (업비트 기준 코인별 요약, 거래소별 주요 코인 {거래소: {심볼: {...}}})"""
    def usd_of(sym: str) -> float | None:
        if sym == "BTC" and btc_usd:
            return btc_usd
        return (caps or {}).get(sym, {}).get("price")

    def prem(price: float, sym: str) -> float | None:
        u = usd_of(sym)
        return price / (u * usdkrw) - 1 if (u and usdkrw) else None

    ex_major: dict[str, dict] = {}
    for ex, ts in tickers_by_ex.items():
        by = {t.symbol: t for t in ts}
        ex_major[ex] = {sym: {"price": by[sym].price, "change_rate": by[sym].change_rate,
                              "value_24h": by[sym].value_24h, "premium": prem(by[sym].price, sym)}
                        for sym in PREMIUM_COINS if sym in by}
    ref = ex_major.get("upbit") or next(iter(ex_major.values()), {})
    summary = {sym: {"usd": usd_of(sym), "krw": v["price"], "premium": v["premium"]} for sym, v in ref.items()}
    return summary, ex_major


def combine_symbols(tickers_by_ex: dict[str, list[Ticker]]) -> list[dict]:
    """종목별로 5개 거래소 거래대금을 합친다. 가격·등락률은 거래대금이 가장 큰 거래소 기준."""
    acc: dict[str, dict] = defaultdict(lambda: {"value": 0.0, "lead": None, "by": {}})
    for ex, ts in tickers_by_ex.items():
        for t in ts:
            a = acc[t.symbol]
            a["value"] += t.value_24h
            a["by"][ex] = t.value_24h
            if a["lead"] is None or t.value_24h > a["lead"].value_24h:
                a["lead"] = t
    out = []
    for sym, a in acc.items():
        lead: Ticker = a["lead"]
        out.append({
            "symbol": sym,
            "value_24h": a["value"],
            "price": lead.price,
            "change_rate": lead.change_rate,
            "lead_exchange": lead.exchange,
            "lead_share": lead.value_24h / a["value"] if a["value"] else 0.0,
        })
    out.sort(key=lambda r: r["value_24h"], reverse=True)
    return out


def movers(symbols: list[dict], n: int = 5) -> tuple[list[dict], list[dict]]:
    pool = [s for s in symbols if s["change_rate"] is not None
            and s["value_24h"] >= MIN_VALUE_FOR_MOVERS and s["symbol"] not in STABLECOINS]
    up = sorted(pool, key=lambda s: s["change_rate"], reverse=True)[:n]
    down = sorted(pool, key=lambda s: s["change_rate"])[:n]
    return up, down


def build_snapshot(*, as_of: str, collected_at: str, tickers_by_ex: dict[str, list[Ticker]],
                   global_: dict | None, btc_usd: float | None, usdkrw: float | None,
                   fx_source: str | None, btc_candles: dict[str, list[dict]],
                   errors: dict[str, str], date: str | None = None, slot: str = "",
                   symbol_caps: dict[str, dict] | None = None) -> dict:
    exchanges = summarize_exchanges(tickers_by_ex, symbol_caps)
    symbols = combine_symbols(tickers_by_ex)
    total = sum(r.get("value_24h", 0.0) for r in exchanges if r["ok"])
    btc_row = next((s for s in symbols if s["symbol"] == "BTC"), None)
    btc_value = btc_row["value_24h"] if btc_row else 0.0
    upbit_btc = next((r["btc_price"] for r in exchanges if r["exchange"] == "upbit" and r["ok"]), None)
    ref_btc_krw = upbit_btc or (btc_row["price"] if btc_row else None)
    premium = None
    if ref_btc_krw and btc_usd and usdkrw:
        premium = ref_btc_krw / (btc_usd * usdkrw) - 1
    up, down = movers(symbols)
    stable_value = sum(s["value_24h"] for s in symbols if s["symbol"] in STABLECOINS)
    all_tickers = [t for ts in tickers_by_ex.values() for t in ts]
    dom_mcap, dom_cov = mcap_dominance(all_tickers, symbol_caps)
    premium_by_coin, ex_majors = coin_premiums(tickers_by_ex, symbol_caps, usdkrw, btc_usd)
    return {
        "as_of": as_of,
        "date": date or as_of[:10],
        "slot": slot,
        "slot_label": SLOT_LABEL.get(slot, ""),
        "collected_at": collected_at,
        "domestic": {
            "total_value_24h": total,
            "btc_value_24h": btc_value,
            "btc_dominance": btc_value / total if total else None,
            "btc_dom_mcap": dom_mcap,
            "mcap_coverage": dom_cov,
            "stable_share": stable_value / total if total else None,
            "btc_price_krw": ref_btc_krw,
            "markets": len(symbols),
        },
        "global": {
            **(global_ or {}),
            "btc_price_usd": btc_usd,
            "usdkrw": usdkrw,
            "fx_source": fx_source,
            "kimchi_premium": premium,
        },
        "exchanges": exchanges,
        "coin_premium": premium_by_coin,
        "exchange_majors": ex_majors,
        "top": symbols[:10],
        "gainers": up,
        "losers": down,
        "btc_candles": btc_candles,
        "errors": errors,
    }


def history_entry(s: dict) -> dict:
    d, g = s["domestic"], s["global"]
    return {
        "date": s.get("date") or s["as_of"][:10],
        "slot": s.get("slot", ""),
        "total_value_24h": d["total_value_24h"],
        "btc_dominance_kr": d["btc_dominance"],
        "btc_dominance_kr_mcap": d.get("btc_dom_mcap"),
        "coin_premium": {k: v.get("premium") for k, v in (s.get("coin_premium") or {}).items()},
        "btc_dom_global_volume": g.get("btc_dom_volume"),
        "btc_dom_global_mcap": g.get("btc_dom_mcap"),
        "btc_price_krw": d["btc_price_krw"],
        "kimchi_premium": g.get("kimchi_premium"),
        "total_mcap_usd": g.get("total_mcap_usd"),
        "by_exchange": {r["exchange"]: r.get("value_24h") for r in s["exchanges"] if r["ok"]},
    }


def _hkey(h: dict) -> tuple[str, str]:
    return h["date"], h.get("slot", "")


def merge_history(history: list[dict], entry: dict, keep: int = 800) -> list[dict]:
    """같은 (날짜, 슬롯)은 새 값으로 바꾸고 시간순 정렬, 최근 keep 개(하루 2개씩 약 400일)만 남긴다."""
    by_key = {_hkey(h): h for h in history if isinstance(h, dict) and "date" in h}
    by_key[_hkey(entry)] = entry
    return [by_key[k] for k in sorted(by_key)][-keep:]


def previous(history: list[dict], date: str, slot: str = "") -> dict | None:
    """'전일 대비'에 쓰는 비교 대상: 전날 같은 시각 집계. 같은 슬롯이 없으면 직전 기록."""
    prior = [h for h in history if h["date"] < date]
    if not prior:
        return None
    same = [h for h in prior if h.get("slot", "") == slot] if slot else []
    return max(same or prior, key=_hkey)


# ---------------------------------------------------------------- 주요 코인(업비트)

def _round_price(x: float) -> float:
    return round(x) if abs(x) >= 1000 else round(x, 4)


def _range_stats(candles: list[dict], price: float | None) -> dict:
    """캔들 목록(시간 오름차순) → 변동률·고저·그래프 점. 변동률은 구간 첫 시가 대비 현재가."""
    if not candles:
        return {"change_rate": None, "high": None, "low": None, "from": None, "to": None, "points": [], "times": []}
    last = price or candles[-1]["close"]
    first_open = candles[0]["open"]
    pts = [_round_price(c["close"]) for c in candles]
    if price:
        pts[-1] = _round_price(price)
    return {
        "change_rate": (last / first_open - 1) if first_open else None,
        "high": _round_price(max(c["high"] for c in candles)),
        "low": _round_price(min(c["low"] for c in candles)),
        "from": candles[0]["t"], "to": candles[-1]["t"],
        "points": pts,
        "times": [c["t"] for c in candles],  # 점마다 시각(그래프 가로축 라벨용)
    }


def build_coins(*, as_of: str, collected_at: str, date: str, slot: str,
                upbit_tickers: list[Ticker], day: dict[str, list[dict]],
                year: dict[str, list[dict]], coin_set: list[tuple[str, str]]) -> dict:
    """업비트 대표 코인의 현재가와 당일(최근 24시간, 30분 봉)·1년(주봉) 그래프 데이터."""
    by = {t.symbol: t for t in upbit_tickers}
    coins = []
    for sym, name in coin_set:
        t = by.get(sym)
        d, y = day.get(sym, []), year.get(sym, [])
        price = t.price if t else (d[-1]["close"] if d else (y[-1]["close"] if y else None))
        coins.append({
            "symbol": sym, "name": name,
            "price": _round_price(price) if price else None,
            "value_24h": t.value_24h if t else None,
            "day": _range_stats(d, price),
            "year": _range_stats(y, price),
        })
    return {"as_of": as_of, "date": date, "slot": slot, "slot_label": SLOT_LABEL.get(slot, ""),
            "collected_at": collected_at, "market": "KRW", "exchange": "upbit", "coins": coins}


def _josa_iga(word: str) -> str:
    """받침이 있으면 '이', 없으면 '가'."""
    ch = word[-1] if word else ""
    if "가" <= ch <= "힣":
        return "이" if (ord(ch) - 0xAC00) % 28 else "가"
    return "가"


def _won(v: float) -> str:
    if v >= 1e12:
        return f"{v / 1e12:,.2f}조 원"
    return f"{v / 1e8:,.0f}억 원"


def _pct(v: float, signed: bool = False) -> str:
    return f"{v * 100:+.1f}%" if signed else f"{v * 100:.1f}%"


def _updown(chg: float, up: str = "상승", down: str = "하락", flat: str = "보합") -> str:
    if abs(chg) < 0.0005:
        return flat
    return up if chg > 0 else down


def summary_lines(s: dict, prev: dict | None) -> list[tuple[str, str]]:
    """요약을 개조식 (항목, 내용) 목록으로. BTC 가격·김치 프리미엄·시가총액·거래금액은 기본,
    도미넌스·거래소 점유는 값이 있을 때만. 문장은 '~임/~함' 없이 명사형으로 끝낸다."""
    d, g = s["domestic"], s["global"]
    p = prev or {}
    out: list[tuple[str, str]] = []

    # 1) BTC 가격
    if d.get("btc_price_krw"):
        txt = f"{d['btc_price_krw']:,.0f}원(업비트)"
        if p.get("btc_price_krw"):
            chg = d["btc_price_krw"] / p["btc_price_krw"] - 1
            txt += f", 전일 대비 {_pct(chg, signed=True)} {_updown(chg)}"
        else:
            btc = next((r for r in s.get("top", []) if r["symbol"] == "BTC"), None)
            if btc and btc.get("change_rate") is not None:
                txt += f", 24시간 {_pct(btc['change_rate'], signed=True)} {_updown(btc['change_rate'])}"
        if g.get("btc_price_usd"):
            txt += f" / 해외 ${g['btc_price_usd']:,.0f}(Coinbase)"
        out.append(("BTC 가격", txt))

    # 2) 김치 프리미엄
    kp = g.get("kimchi_premium")
    if kp is not None:
        txt = f"{_pct(kp, signed=True)}"
        if p.get("kimchi_premium") is not None:
            diff = kp - p["kimchi_premium"]
            txt += f", 전일 대비 {diff * 100:+.1f}%p {_updown(diff, '확대', '축소', '유지')}"
        if g.get("usdkrw"):
            txt += f" (원/달러 {g['usdkrw']:,.1f}원 적용)"
        out.append(("김치 프리미엄", txt))

    # 3) 시가총액(글로벌)
    if g.get("total_mcap_usd"):
        txt = f"전체 {g['total_mcap_usd'] / 1e12:,.2f}조 달러"
        if p.get("total_mcap_usd"):
            chg = g["total_mcap_usd"] / p["total_mcap_usd"] - 1
            txt += f"(전일 대비 {_pct(chg, signed=True)})"
        if g.get("btc_mcap_usd"):
            txt += f", BTC {g['btc_mcap_usd'] / 1e12:,.2f}조 달러"
        if g.get("btc_dom_mcap") is not None:
            txt += f"(비중 {_pct(g['btc_dom_mcap'])})"
        out.append(("시가총액", txt))

    # 4) 거래금액(국내)
    if d.get("total_value_24h"):
        txt = f"국내 5개 원화거래소 24시간 {_won(d['total_value_24h'])}"
        if p.get("total_value_24h"):
            chg = d["total_value_24h"] / p["total_value_24h"] - 1
            txt += f", 전일 대비 {_pct(chg, signed=True)} {_updown(chg, '증가', '감소')}"
        ok = sorted((r for r in s["exchanges"] if r["ok"]), key=lambda r: r["value_24h"], reverse=True)
        if ok:
            txt += " / " + "·".join(f"{r['name']} {_pct(r['share'])}" for r in ok[:2])
        out.append(("거래금액", txt))

    # 5) BTC 도미넌스(국내 vs 글로벌)
    dom_kr, dom_gv = d.get("btc_dominance"), g.get("btc_dom_volume")
    if dom_kr is not None and dom_gv is not None:
        gap = dom_kr - dom_gv
        txt = (f"국내 {_pct(dom_kr)} vs 글로벌 {_pct(dom_gv)}(거래대금 기준), "
               f"{abs(gap) * 100:.1f}%p {'낮음' if gap < 0 else '높음'}")
        txt += " → 국내 알트코인 거래 쏠림" if gap < 0 else " → 국내 BTC 거래 비중 우위"
        out.append(("BTC 도미넌스", txt))
    return out


__all__ = ["build_snapshot", "history_entry", "merge_history", "previous", "summary_lines", "asdict"]
