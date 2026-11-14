// 배포할 때(Deploy news proxy 워크플로) FISIS에서 권역별 회사 목록·총자산·통계표 위치를 미리 받아 fisis-data.json으로 만든다.
// 서버(Cloudflare Worker)는 한 번의 호출에 보낼 수 있는 하위 요청이 50건뿐이라, 많은 호출이 필요한 작업은 여기서 미리 해 둔다.
// FISIS_API_KEY가 없거나 실패하면 기존 파일을 그대로 둔다(서버는 회사 목록만 실시간으로 받는 제한 모드로 동작한다).
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CLOSED, FISIS_SECTORS, FISIS_TERMS, fetchTable, fisisFetch, metricFromTable, resolveSpec, rowsOf, specListNos, validateSpec } from "./fisis-lib.js";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "fisis-data.json");
const key = (process.env.FISIS_API_KEY || "").trim();
if (!key) {
  console.log("FISIS_API_KEY가 없어 건너뜁니다.");
  process.exit(0);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let quotaHit = false;
async function call(name, params) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await fisisFetch(key, name, params);
    } catch (e) {
      if (e.quota) {
        quotaHit = true;
        throw e;
      }
      if (attempt === 2) throw e;
      await sleep(500 * (attempt + 1));
    }
  }
}

async function buildSector(sector, div = sector) {
  const cs = await call("companySearch", { partDiv: div });
  const companies = rowsOf(cs.list)
    .map((c) => [String(c.finance_cd || ""), String(c.finance_nm || "").trim()])
    .filter(([code, name]) => code && name && !CLOSED.test(name));
  const spec = await resolveSpec(
    {
      lists: async (s) => rowsOf((await call("statisticsListSearch", { lrgDiv: div })).list).map((x) => ({ sml: String(x.sml_div_nm || ""), listNo: String(x.list_no || ""), listNm: String(x.list_nm || "") })).filter((x) => x.listNo),
      accounts: async (listNo) => rowsOf((await call("accountListSearch", { listNo })).list).map((a) => ({ code: String(a.account_cd || ""), name: String(a.account_nm || "").trim() })).filter((a) => a.code),
    },
    sector
  );
  // 표본 회사(고르게 6곳)로 실제 자료가 나오는 계정만 확정한다(후보가 여럿이면 값이 나오는 첫 후보).
  const step = Math.max(1, Math.floor(companies.length / 6));
  const samples = companies.filter((_, i) => i % step === 0).slice(0, 6).map(([code]) => code);
  const cache = new Map();
  const probe = (code, listNo, accountCd) => {
    const k = `${code}/${listNo}/${accountCd}`;
    if (!cache.has(k)) {
      cache.set(k, fetchTable((p) => call("statisticsInfoSearch", p), code, listNo, "Q", accountCd).then((t) => {
        // 단위 점검용: 통계표가 말하는 단위와 실제 값의 크기(Deploy 로그에서 확인)
        if (t) console.log(`  [${sector}] 단위 ${code} ${listNo} unit="${t.unit}" term=${t.term} 최대값=${t.max}`);
        return t;
      }));
    }
    return cache.get(k);
  };
  await validateSpec(spec, samples, probe, (line) => console.log(`  [${sector}] ${line}`));
  const ref = (spec.basis["별도"] || Object.values(spec.basis)[0] || []).find((m) => m.key === "assets");
  const assets = {};
  if (ref) {
    for (let i = 0; i < companies.length && !quotaHit; i += 4) {
      await Promise.all(
        companies.slice(i, i + 4).map(async ([code]) => {
          try {
            const t = await fetchTable((p) => call("statisticsInfoSearch", p), code, ref.listNo, "Q", ref.accountCd);
            const metric = t && metricFromTable({ ...ref, group: "fin", basis: "" }, t);
            const last = metric && metric.points[metric.points.length - 1];
            if (last) assets[code] = [last.value, last.month, metric.unit];
          } catch {
            /* 이 회사는 순위에서 뺀다 */
          }
        })
      );
    }
  }
  // 통계표마다 자료가 있는 주기(분기·반기·연)를 표본 회사로 확인해 둔다(서버가 불필요한 재시도를 하지 않도록).
  const sample = companies.filter(([code]) => assets[code]).slice(0, 3).map(([code]) => code);
  const probes = sample.length ? sample : companies.slice(0, 3).map(([code]) => code);
  for (const listNo of specListNos(spec)) {
    outer: for (const term of FISIS_TERMS) {
      for (const code of probes) {
        const t = await fetchTable((p) => call("statisticsInfoSearch", p), code, listNo, term).catch(() => null);
        if (t) {
          spec.terms[listNo] = t.term;
          break outer;
        }
      }
    }
  }
  console.log(`${sector}: 회사 ${companies.length}곳, 총자산 ${Object.keys(assets).length}곳, 통계표 ${specListNos(spec).length}개`);
  return { companies, assets, spec };
}

// 투자자문·일임사: 알려진 권역 코드 밖에서 이름에 자문·일임이 든 금융회사 권역을 찾아 "ADV"로 저장한다(Deploy 로그에 스캔 결과를 남긴다).
async function findAdvisorDiv() {
  const known = new Set(FISIS_SECTORS);
  for (const letter of "BDJKNOPQRSTUVXYZ") {
    if (known.has(letter) || quotaHit) continue;
    try {
      const cs = await call("companySearch", { partDiv: letter });
      const names = rowsOf(cs.list).map((c) => String(c.finance_nm || "").trim()).filter(Boolean);
      const hit = names.filter((n) => /자문|일임/.test(n)).length;
      console.log(`권역 탐색 ${letter}: ${names.length}곳, 자문·일임 ${hit}곳 예) ${names.slice(0, 3).join(", ")}`);
      if (names.length && hit >= Math.max(1, names.length * 0.3)) return letter;
    } catch (e) {
      console.log(`권역 탐색 ${letter}: ${e.message}`);
    }
  }
  return null;
}

const data = { builtAt: new Date().toISOString(), sectors: {} };
try {
  const previous = JSON.parse(readFileSync(OUT, "utf8"));
  if (previous && previous.sectors) data.sectors = { ...previous.sectors };
} catch {
  /* 처음 만드는 경우 */
}
let built = 0;
for (const sector of FISIS_SECTORS) {
  try {
    data.sectors[sector] = await buildSector(sector);
    built++;
  } catch (e) {
    console.log(`${sector}: 실패 - ${e.message}`);
  }
  if (quotaHit) break;
}
if (!quotaHit) {
  const div = await findAdvisorDiv();
  if (div) {
    try {
      data.sectors.ADV = await buildSector("ADV", div);
      built++;
    } catch (e) {
      console.log(`ADV: 실패 - ${e.message}`);
    }
  } else console.log("투자자문·일임사 권역을 FISIS에서 찾지 못했습니다.");
}
if (!built) {
  console.log("만든 권역이 없어 기존 파일을 그대로 둡니다.");
  process.exit(0);
}
writeFileSync(OUT, JSON.stringify(data));
console.log(`fisis-data.json 저장 완료(${built}개 권역)`);
