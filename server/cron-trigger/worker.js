// 커뮤니티 핫이슈 수집 워크플로(Community data)를 정시에 실행시키는 작은 타이머 Worker.
// GitHub 예약 실행이 지연·누락되는 경우를 대비해 Cloudflare 크론이 workflow_dispatch 를 호출한다.
// 필요한 시크릿: GH_TOKEN (FSS_insights 저장소 한 곳, Actions 쓰기 권한만 가진 fine-grained 토큰)
const OWNER = "hdlee73";
const REPO = "FSS_insights";
const WORKFLOW = "community-data.yml";
const REF = "main";

// 업무시간(평일 KST 09~22시)에는 30분 주기만, 그 밖에는 2시간 주기만 실행한다(워크플로의 gate와 같은 규칙).
function shouldRun(cron, now) {
  if (cron !== "7 */2 * * *") return true;
  const kst = new Date(now.getTime() + 9 * 3600 * 1000);
  const dow = kst.getUTCDay() === 0 ? 7 : kst.getUTCDay();
  const hour = kst.getUTCHours();
  return !(dow <= 5 && hour >= 9 && hour <= 22);
}

async function dispatch(env) {
  const res = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/actions/workflows/${WORKFLOW}/dispatches`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GH_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "fss-cron-trigger",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ ref: REF }),
  });
  if (res.status !== 204) throw new Error(`dispatch 실패 HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
}

export default {
  async scheduled(event, env, ctx) {
    if (!env.GH_TOKEN) { console.error("GH_TOKEN 시크릿이 없습니다."); return; }
    if (!shouldRun(event.cron, new Date(event.scheduledTime))) return;
    ctx.waitUntil(dispatch(env).catch((e) => console.error(String(e.message || e))));
  },
  async fetch() {
    return new Response("fss-cron-trigger: 예약 실행 전용(요청 처리 없음)", { status: 404 });
  },
};
