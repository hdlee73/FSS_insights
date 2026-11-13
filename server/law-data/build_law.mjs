// 스마트 법령검색 사전수집. 금융 관련 법령의 국가법령정보 응답(XML)을 그대로 모아 law-data 브랜치에 올린다.
// 웹·앱은 같은 요청이면 이 파일을 먼저 쓰고(서버 왕복 없음), 목록에 없는 법령만 실시간 조회한다.
//  - index.json : { updated, keys: { <요청 키>: <파일명> } }   - <해시>.xml : 응답 원문(OC 값은 지운다)
// 요청 키는 웹의 js/lib/law-core.js drf()와 같은 규칙이다: "law:<endpoint>?<type=XML&…>" (조문 번호 JO는 뺀다).
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { createHash } from "node:crypto";

const BASE = process.env.LAW_BASE || "https://news-proxy.hdlee73.workers.dev";
const ORIGIN = process.env.LAW_ORIGIN || "https://www.fssinsights.com";
const OUT = process.argv[2] || "out";
const cfg = JSON.parse(readFileSync(new URL("./laws.json", import.meta.url), "utf8"));
const compact = (s) => String(s || "").replace(/ /g, "");

export function keyOf(endpoint, params) {
  const p = { type: "XML", ...params };
  delete p.JO;
  return "law:" + endpoint + "?" + new URLSearchParams(Object.entries(p).filter(([, v]) => v != null)).toString();
}
const keys = {};
let bytes = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function get(endpoint, params) {
  const p = { type: "XML", ...params };
  const url = BASE + "/law/DRF/" + endpoint + "?" + new URLSearchParams(Object.entries(p).filter(([, v]) => v != null));
  for (let i = 0; i < 4; i++) {
    try {
      const r = await fetch(url, { headers: { Origin: ORIGIN } });
      if (r.ok) {
        const t = await r.text();
        if (/<(LawSearch|AdmRulSearch)\b/.test(t) || (t.startsWith("<?xml") && t.length > 200)) return t;
      } else if (r.status < 500 && r.status !== 429) throw new Error(r.status + " " + endpoint);
    } catch (e) { if (i === 3) throw e; }
    await sleep(1500 * (i + 1));
  }
  throw new Error("실패 " + endpoint);
}
function save(endpoint, params, xml) {
  const key = keyOf(endpoint, params);
  const name = createHash("sha1").update(key).digest("hex").slice(0, 16) + ".xml";
  // 응답의 상세 링크에 서버가 쓰는 인증값(OC=…)이 들어 있어 공개 저장소에 올리기 전에 지운다.
  const clean = xml.replace(/OC=[^&"<\s]*/g, "OC=");
  writeFileSync(`${OUT}/${name}`, clean);
  keys[key] = name;
  bytes += Buffer.byteLength(clean);
}
const tag = (blk, t) => (new RegExp(`<${t}>(?:<!\\[CDATA\\[)?([^<\\]]*)`).exec(blk) || [])[1]?.trim() || "";
const blocks = (xml, t) => [...xml.matchAll(new RegExp(`<${t} id="\\d+">([\\s\\S]*?)</${t}>`, "g"))].map((m) => m[1]);

mkdirSync(OUT, { recursive: true });
let failed = 0, laws = 0, rules = 0;
const fail = (m, e) => { console.error("실패:", m, e?.message || ""); failed++; };

async function collectLaw(query, withTiers) {
  const sp = { target: "law", query, search: 1, display: 20 };
  const xml = await get("lawSearch.do", sp);
  save("lawSearch.do", sp, xml);
  const hit = blocks(xml, "law").map((b) => ({ mst: tag(b, "법령일련번호"), name: tag(b, "법령명한글") })).find((l) => compact(l.name) === compact(query));
  if (!hit) { console.warn("경고: 정확히 일치하는 법령 없음:", query); return null; }
  save("lawService.do", { target: "law", MST: hit.mst }, await get("lawService.do", { target: "law", MST: hit.mst }));
  laws++;
  if (withTiers) {
    for (const [tg, extra] of [["thdCmp", { knd: 2 }], ["lsStmd", {}]]) {
      const p = { target: tg, MST: hit.mst, ...extra };
      try { save("lawService.do", p, await get("lawService.do", p)); } catch (e) { console.warn("경고:", query, tg, e.message); }
    }
  }
  return hit;
}
async function collectRule(name) {
  const sp = { target: "admrul", query: name, search: 1, display: 20 };
  const xml = await get("lawSearch.do", sp);
  save("lawSearch.do", sp, xml);
  const list = blocks(xml, "admrul").map((b) => ({ id: tag(b, "행정규칙일련번호"), name: tag(b, "행정규칙명") }));
  const hit = list.find((x) => compact(x.name) === compact(name)) || list[0];
  if (!hit) { console.warn("경고: 행정규칙 없음:", name); return; }
  save("lawService.do", { target: "admrul", ID: hit.id }, await get("lawService.do", { target: "admrul", ID: hit.id }));
  rules++;
}

for (const name of cfg.laws) {
  try {
    await collectLaw(name, true);
    await collectLaw(name + " 시행령", false).catch((e) => console.warn("경고:", name + " 시행령", e.message));
    await collectLaw(name + " 시행규칙", false).catch((e) => console.warn("경고:", name + " 시행규칙", e.message));
    console.log("법령", name);
  } catch (e) { fail(name, e); }
}
for (const name of cfg.adminRules) { try { await collectRule(name); console.log("행정규칙", name); } catch (e) { fail(name, e); } }

// 너무 적게 모였으면(서버 장애) 이전 자료를 덮지 않도록 실패로 끝낸다.
if (laws < cfg.laws.length * 0.8 || rules < cfg.adminRules.length * 0.6) { console.error(`수집 부족: 법령 ${laws}, 행정규칙 ${rules}`); process.exit(1); }
const updated = new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 16).replace("T", " ") + " KST";
writeFileSync(`${OUT}/index.json`, JSON.stringify({ updated, keys }));
console.log(`완료: 법령 ${laws}건, 행정규칙 ${rules}건, 파일 ${Object.keys(keys).length}개, 원문 ${(bytes / 1e6).toFixed(1)}MB`);
if (failed) console.error("일부 항목 실패(수집 기준은 충족해 계속 진행):", failed);
