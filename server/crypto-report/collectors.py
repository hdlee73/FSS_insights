"""국내 원화거래소 5곳과 글로벌 시세 수집기.

네트워크 호출(fetch_*)과 응답 해석(parse_*)을 나눠 두었다. parse_* 는 순수 함수라
tests/ 의 고정 응답으로 검증하고, fetch_* 는 GitHub Actions 에서 실제로 호출한다.
한 거래소가 실패해도 일보 전체가 멈추지 않도록 호출부(build_daily)에서 거래소별로 예외를 잡는다.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Iterable

import requests

log = logging.getLogger(__name__)

USER_AGENT = "FSS-Insights-CryptoReport/0.1 (+https://github.com/hdlee73/FSS_insights)"
TIMEOUT_SEC = 15

EXCHANGES = ["upbit", "bithumb", "coinone", "korbit", "gopax"]
EXCHANGE_NAMES = {
    "upbit": "업비트",
    "bithumb": "빗썸",
    "coinone": "코인원",
    "korbit": "코빗",
    "gopax": "고팍스",
}


@dataclass(frozen=True)
class Ticker:
    exchange: str
    symbol: str                 # 표준 심볼(대문자), 예: BTC
    price: float                # 원화 현재가
    change_rate: float | None   # 0.012 = +1.2%, 모르면 None
    value_24h: float            # 24시간 원화 거래대금
    value_estimated: bool = False  # 수량×현재가로 추정한 거래대금이면 True


# ---------------------------------------------------------------- 공통 유틸

def _num(x: Any) -> float | None:
    """문자열·숫자·None 을 float 로. 쉼표와 공백을 허용하고 해석 불가면 None."""
    if x is None:
        return None
    if isinstance(x, (int, float)):
        v = float(x)
    else:
        s = str(x).replace(",", "").strip()
        if not s:
            return None
        try:
            v = float(s)
        except ValueError:
            return None
    if v != v or v in (float("inf"), float("-inf")):  # NaN·무한대 차단
        return None
    return v


def _valid(t: Ticker) -> bool:
    return bool(t.symbol) and t.price > 0 and t.value_24h >= 0


def get_json(session: requests.Session, url: str, *, params: dict | None = None,
             headers: dict | None = None, retries: int = 3) -> Any:
    """GET 후 JSON. 429·5xx·네트워크 오류는 점점 길게 쉬며 재시도한다."""
    last: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            r = session.get(
                url,
                params=params,
                headers={"User-Agent": USER_AGENT, "Accept": "application/json", **(headers or {})},
                timeout=TIMEOUT_SEC,
            )
            if r.status_code == 429 or r.status_code >= 500:
                raise requests.HTTPError(f"HTTP {r.status_code}", response=r)
            r.raise_for_status()
            return r.json()
        except (requests.RequestException, ValueError) as e:
            last = e
            if attempt < retries:
                time.sleep(1.5 * attempt)
    raise RuntimeError(f"요청 실패 {url}: {last}")


def _chunks(items: list[str], n: int) -> Iterable[list[str]]:
    for i in range(0, len(items), n):
        yield items[i:i + n]


# ---------------------------------------------------------------- 업비트

def parse_upbit_markets(markets: list[dict]) -> list[str]:
    return [m["market"] for m in markets if str(m.get("market", "")).startswith("KRW-")]


def parse_upbit_tickers(rows: list[dict]) -> list[Ticker]:
    out = []
    for r in rows:
        market = str(r.get("market", ""))
        if not market.startswith("KRW-"):
            continue
        t = Ticker(
            exchange="upbit",
            symbol=market[4:].upper(),
            price=_num(r.get("trade_price")) or 0.0,
            change_rate=_num(r.get("signed_change_rate")),
            value_24h=_num(r.get("acc_trade_price_24h")) or 0.0,
        )
        if _valid(t):
            out.append(t)
    return out


def fetch_upbit(session: requests.Session) -> list[Ticker]:
    markets = parse_upbit_markets(get_json(session, "https://api.upbit.com/v1/market/all"))
    out: list[Ticker] = []
    for chunk in _chunks(markets, 100):
        rows = get_json(session, "https://api.upbit.com/v1/ticker", params={"markets": ",".join(chunk)})
        out.extend(parse_upbit_tickers(rows))
        time.sleep(0.15)  # 초당 10회 제한 여유
    return out


# ---------------------------------------------------------------- 빗썸

def parse_bithumb(payload: dict) -> list[Ticker]:
    if str(payload.get("status")) != "0000":
        raise ValueError(f"빗썸 응답 상태 {payload.get('status')}")
    out = []
    for sym, r in (payload.get("data") or {}).items():
        if not isinstance(r, dict):  # "date" 같은 메타 항목 건너뜀
            continue
        rate = _num(r.get("fluctate_rate_24H"))
        t = Ticker(
            exchange="bithumb",
            symbol=sym.upper(),
            price=_num(r.get("closing_price")) or 0.0,
            change_rate=rate / 100 if rate is not None else None,
            value_24h=_num(r.get("acc_trade_value_24H")) or 0.0,
        )
        if _valid(t):
            out.append(t)
    return out


def fetch_bithumb(session: requests.Session) -> list[Ticker]:
    return parse_bithumb(get_json(session, "https://api.bithumb.com/public/ticker/ALL_KRW"))


# ---------------------------------------------------------------- 코인원

def parse_coinone(payload: dict) -> list[Ticker]:
    if payload.get("result") not in (None, "success"):
        raise ValueError(f"코인원 응답 {payload.get('result')} {payload.get('error_code')}")
    out = []
    for r in payload.get("tickers") or []:
        if str(r.get("quote_currency", "KRW")).upper() != "KRW":
            continue
        last = _num(r.get("last")) or 0.0
        prev = _num(r.get("yesterday_last"))
        t = Ticker(
            exchange="coinone",
            symbol=str(r.get("target_currency", "")).upper(),
            price=last,
            change_rate=(last / prev - 1) if prev else None,
            value_24h=_num(r.get("quote_volume")) or 0.0,
        )
        if _valid(t):
            out.append(t)
    return out


def fetch_coinone(session: requests.Session) -> list[Ticker]:
    return parse_coinone(get_json(session, "https://api.coinone.co.kr/public/v2/ticker_new/KRW"))


# ---------------------------------------------------------------- 코빗

def parse_korbit_v2(payload: dict) -> list[Ticker]:
    if payload.get("success") is False:
        raise ValueError(f"코빗 응답 실패 {payload.get('error')}")
    out = []
    for r in payload.get("data") or []:
        sym = str(r.get("symbol", ""))
        if not sym.endswith("_krw"):
            continue
        pct = _num(r.get("priceChangePercent"))
        t = Ticker(
            exchange="korbit",
            symbol=sym[:-4].upper(),
            price=_num(r.get("close")) or 0.0,
            change_rate=pct / 100 if pct is not None else None,
            value_24h=_num(r.get("quoteVolume")) or 0.0,
        )
        if _valid(t):
            out.append(t)
    return out


def parse_korbit_v1(payload: dict) -> list[Ticker]:
    """구버전(v1) 응답. 거래량이 코인 수량이라 현재가를 곱해 거래대금을 추정한다."""
    out = []
    for pair, r in payload.items():
        if not pair.endswith("_krw") or not isinstance(r, dict):
            continue
        last = _num(r.get("last")) or 0.0
        pct = _num(r.get("changePercent"))
        t = Ticker(
            exchange="korbit",
            symbol=pair[:-4].upper(),
            price=last,
            change_rate=pct / 100 if pct is not None else None,
            value_24h=(_num(r.get("volume")) or 0.0) * last,
            value_estimated=True,
        )
        if _valid(t):
            out.append(t)
    return out


def fetch_korbit(session: requests.Session) -> list[Ticker]:
    try:
        return parse_korbit_v2(get_json(session, "https://api.korbit.co.kr/v2/tickers"))
    except Exception as e:  # v2 가 없거나 형식이 바뀌면 v1 로
        log.warning("코빗 v2 실패, v1 로 재시도: %s", e)
        return parse_korbit_v1(get_json(session, "https://api.korbit.co.kr/v1/ticker/detailed/all"))


# ---------------------------------------------------------------- 고팍스

def parse_gopax(rows: list[dict]) -> list[Ticker]:
    """고팍스 24시간 통계. 거래량이 코인 수량이라 종가를 곱해 거래대금을 추정한다."""
    out = []
    for r in rows:
        name = str(r.get("name", ""))
        if not name.endswith("-KRW"):
            continue
        close = _num(r.get("close")) or 0.0
        open_ = _num(r.get("open"))
        t = Ticker(
            exchange="gopax",
            symbol=name[:-4].upper(),
            price=close,
            change_rate=(close / open_ - 1) if open_ else None,
            value_24h=(_num(r.get("volume")) or 0.0) * close,
            value_estimated=True,
        )
        if _valid(t):
            out.append(t)
    return out


def fetch_gopax(session: requests.Session) -> list[Ticker]:
    rows = get_json(session, "https://api.gopax.co.kr/trading-pairs/stats")
    if not rows:
        raise ValueError("종목 목록이 비어 있음")   # 고팍스 API 가 빈 종목 목록을 돌려준다(원인은 알 수 없음)
    return parse_gopax(rows)


FETCHERS = {
    "upbit": fetch_upbit,
    "bithumb": fetch_bithumb,
    "coinone": fetch_coinone,
    "korbit": fetch_korbit,
    "gopax": fetch_gopax,
}


# ---------------------------------------------------------------- 글로벌

def parse_coingecko_global(payload: dict) -> dict:
    d = payload.get("data") or {}
    btc_pct = _num((d.get("market_cap_percentage") or {}).get("btc"))
    total_mcap = _num((d.get("total_market_cap") or {}).get("usd"))
    total_vol = _num((d.get("total_volume") or {}).get("usd"))
    if btc_pct is None or not total_vol:
        raise ValueError("CoinGecko /global 응답에 필요한 값이 없음")
    return {"btc_dom_mcap": btc_pct / 100, "total_mcap_usd": total_mcap, "total_volume_usd": total_vol}


def parse_coingecko_btc(rows: list[dict]) -> dict:
    for r in rows:
        if r.get("id") == "bitcoin":
            vol = _num(r.get("total_volume"))
            price = _num(r.get("current_price"))
            if vol and price:
                return {"btc_volume_usd": vol, "btc_price_usd_cg": price,
                        "btc_mcap_usd": _num(r.get("market_cap"))}
    raise ValueError("CoinGecko 응답에 bitcoin 이 없음")


def parse_cmc_global(payload: dict) -> dict:
    d = payload.get("data") or {}
    usd = (d.get("quote") or {}).get("USD") or {}
    btc_pct = _num(d.get("btc_dominance"))
    total_mcap = _num(usd.get("total_market_cap"))
    total_vol = _num(usd.get("total_volume_24h"))
    if btc_pct is None or not total_vol:
        raise ValueError("CoinMarketCap global-metrics 응답에 필요한 값이 없음")
    return {"btc_dom_mcap": btc_pct / 100, "total_mcap_usd": total_mcap, "total_volume_usd": total_vol}


def parse_cmc_btc(payload: dict) -> dict:
    rows = (payload.get("data") or {}).get("BTC")
    row = rows[0] if isinstance(rows, list) and rows else rows
    usd = ((row or {}).get("quote") or {}).get("USD") or {}
    vol, price = _num(usd.get("volume_24h")), _num(usd.get("price"))
    if not vol or not price:
        raise ValueError("CoinMarketCap 응답에 BTC 시세가 없음")
    return {"btc_volume_usd": vol, "btc_price_usd_cg": price, "btc_mcap_usd": _num(usd.get("market_cap"))}


CMC_BASE = "https://pro-api.coinmarketcap.com"


def fetch_global(session: requests.Session, coingecko_key: str | None = None,
                 cmc_key: str | None = None) -> dict:
    """글로벌 시총·도미넌스. CoinMarketCap 키가 있으면 그것을, 없으면 CoinGecko(키 선택)를 쓴다."""
    if cmc_key:
        h = {"X-CMC_PRO_API_KEY": cmc_key}
        g = parse_cmc_global(get_json(session, f"{CMC_BASE}/v1/global-metrics/quotes/latest",
                                      params={"convert": "USD"}, headers=h))
        b = parse_cmc_btc(get_json(session, f"{CMC_BASE}/v2/cryptocurrency/quotes/latest",
                                   params={"symbol": "BTC", "convert": "USD"}, headers=h))
        g["source"] = "CoinMarketCap"
    else:
        headers = {"x-cg-demo-api-key": coingecko_key} if coingecko_key else None
        g = parse_coingecko_global(get_json(session, "https://api.coingecko.com/api/v3/global", headers=headers))
        b = parse_coingecko_btc(get_json(
            session, "https://api.coingecko.com/api/v3/coins/markets",
            params={"vs_currency": "usd", "ids": "bitcoin"}, headers=headers))
        g["source"] = "CoinGecko"
    g.update(b)
    g["btc_dom_volume"] = b["btc_volume_usd"] / g["total_volume_usd"]
    return g


def parse_cmc_listings(payload: dict) -> dict[str, dict]:
    """CoinMarketCap listings → {심볼: {mcap, price}}. 같은 심볼은 시가총액이 큰 쪽만 남긴다."""
    out: dict[str, dict] = {}
    for r in payload.get("data") or []:
        sym = str(r.get("symbol") or "").upper()
        usd = ((r.get("quote") or {}).get("USD")) or {}
        mcap, price = _num(usd.get("market_cap")), _num(usd.get("price"))
        if not sym or not price:
            continue
        if sym not in out or (mcap or 0) > (out[sym]["mcap"] or 0):
            out[sym] = {"mcap": mcap or 0.0, "price": price}
    return out


def parse_coingecko_markets_all(rows: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for r in rows:
        sym = str(r.get("symbol") or "").upper()
        mcap, price = _num(r.get("market_cap")), _num(r.get("current_price"))
        if not sym or not price:
            continue
        if sym not in out or (mcap or 0) > (out[sym]["mcap"] or 0):
            out[sym] = {"mcap": mcap or 0.0, "price": price}
    return out


def fetch_symbol_caps(session: requests.Session, coingecko_key: str | None = None,
                      cmc_key: str | None = None) -> dict[str, dict]:
    """종목별 글로벌 시가총액·달러 가격(국내 시가총액 기준 도미넌스·코인별 김치 프리미엄 계산용)."""
    if cmc_key:
        payload = get_json(session, f"{CMC_BASE}/v1/cryptocurrency/listings/latest",
                           params={"limit": 1000, "convert": "USD", "sort": "market_cap"},
                           headers={"X-CMC_PRO_API_KEY": cmc_key})
        return parse_cmc_listings(payload)
    headers = {"x-cg-demo-api-key": coingecko_key} if coingecko_key else None
    out: dict[str, dict] = {}
    for page in (1, 2, 3):
        try:
            rows = get_json(session, "https://api.coingecko.com/api/v3/coins/markets",
                            params={"vs_currency": "usd", "order": "market_cap_desc", "per_page": 250, "page": page},
                            headers=headers)
        except Exception:
            if page == 1:
                raise
            break
        for sym, v in parse_coingecko_markets_all(rows).items():
            if sym not in out or v["mcap"] > out[sym]["mcap"]:
                out[sym] = v
        time.sleep(2.5)
    return out


def parse_coinbase_ticker(payload: dict) -> float:
    p = _num(payload.get("price"))
    if not p:
        raise ValueError("Coinbase 응답에 price 가 없음")
    return p


def fetch_coinbase_btc_usd(session: requests.Session) -> float:
    return parse_coinbase_ticker(get_json(session, "https://api.exchange.coinbase.com/products/BTC-USD/ticker"))


def parse_koreaexim(rows: list[dict]) -> float | None:
    for r in rows or []:
        if str(r.get("cur_unit", "")).upper() == "USD":
            return _num(r.get("deal_bas_r"))
    return None


def parse_er_api(payload: dict) -> float | None:
    return _num((payload.get("rates") or {}).get("KRW"))


def fetch_usdkrw(session: requests.Session, koreaexim_key: str | None) -> tuple[float, str]:
    """원/달러 환율과 출처. 수출입은행(영업일만 제공)을 먼저, 없으면 공개 환율 API."""
    if koreaexim_key:
        try:
            rows = get_json(session, "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON",
                            params={"authkey": koreaexim_key, "data": "AP01"})
            v = parse_koreaexim(rows)
            if v:
                return v, "한국수출입은행"
        except Exception as e:
            log.warning("수출입은행 환율 실패: %s", e)
    v = parse_er_api(get_json(session, "https://open.er-api.com/v6/latest/USD"))
    if not v:
        raise ValueError("환율을 가져오지 못함")
    return v, "ExchangeRate-API"


def parse_daily_candles(rows: list[dict]) -> list[dict]:
    """업비트·빗썸(v1) 일봉 → [{date, close, value}] 날짜 오름차순."""
    out = []
    for r in rows or []:
        d = str(r.get("candle_date_time_kst", ""))[:10]
        close = _num(r.get("trade_price"))
        value = _num(r.get("candle_acc_trade_price"))
        if d and close:
            out.append({"date": d, "close": close, "value": value or 0.0})
    out.sort(key=lambda x: x["date"])
    return out


def fetch_btc_candles(session: requests.Session, exchange: str, count: int = 30) -> list[dict]:
    base = {"upbit": "https://api.upbit.com", "bithumb": "https://api.bithumb.com"}[exchange]
    return parse_daily_candles(get_json(session, f"{base}/v1/candles/days",
                                        params={"market": "KRW-BTC", "count": count}))


# ---------------------------------------------------------------- 업비트 주요 코인 캔들

# 화면 '주요코인 시세(업비트)'에 쓰는 대표 코인(심볼, 한글 이름). 순서가 화면 순서.
COIN_SET = [("BTC", "비트코인"), ("ETH", "이더리움"), ("XRP", "리플"), ("SOL", "솔라나"), ("DOGE", "도지코인")]
UPBIT_CANDLE_URL = {
    "30m": "https://api.upbit.com/v1/candles/minutes/30",
    "1w": "https://api.upbit.com/v1/candles/weeks",
}


def parse_candles(rows: list[dict]) -> list[dict]:
    """업비트 캔들 응답(최신순) → [{t, open, high, low, close}] 시간 오름차순. t 는 KST 'YYYY-MM-DDTHH:MM'."""
    out = []
    for r in rows or []:
        t = str(r.get("candle_date_time_kst", ""))[:16]
        o, h, lo, c = (_num(r.get(k)) for k in ("opening_price", "high_price", "low_price", "trade_price"))
        if t and o and h and lo and c:
            out.append({"t": t, "open": o, "high": h, "low": lo, "close": c})
    out.sort(key=lambda x: x["t"])
    return out


def fetch_upbit_candles(session: requests.Session, symbol: str, unit: str, count: int) -> list[dict]:
    """unit: '30m'(30분 봉) 또는 '1w'(주봉)."""
    return parse_candles(get_json(session, UPBIT_CANDLE_URL[unit],
                                  params={"market": f"KRW-{symbol}", "count": count}))
