// 공지사항: 관리자가 /admin/notices 화면에서 작성하면 앱·웹 맨 위 회전 배너로 보인다. Cloudflare D1(binding: DB)에 저장.
//  - GET    /notices          : 지금 게시 중인 공지(앱 토큰 또는 웹 출처). 누구나 읽기만 가능.
//  - GET    /admin/notices   : 관리자 작성 화면(HTML). 토큰을 입력하면 아래 API를 부른다.
//  - GET/POST/DELETE /admin/notices/api : 목록·저장·삭제(관리자 토큰 X-Admin-Token 필요).

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const SCHEMA = "CREATE TABLE IF NOT EXISTS notices (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, level TEXT NOT NULL, start_day TEXT NOT NULL, end_day TEXT NOT NULL, created_at TEXT NOT NULL)";
let ready = false;

const reply = (data, status = 200, cache = "no-store") =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": cache } });

const kstDay = (ms = Date.now()) => new Date(ms + 9 * 3600000).toISOString().slice(0, 10);

async function ensure(db) {
  if (ready) return;
  await db.prepare(SCHEMA).run();
  ready = true;
}

/** 관리자가 보낸 값을 검증·정리한다. 잘못되면 null. */
export function sanitizeNotice(b, today = kstDay()) {
  if (!b || typeof b !== "object") return null;
  const title = String(b.title ?? "").trim().slice(0, 80);
  const body = String(b.body ?? "").trim().slice(0, 2000);
  if (!title) return null;
  const level = b.level === "urgent" ? "urgent" : "normal";
  const start = DAY.test(String(b.start ?? "")) ? b.start : today;
  const end = DAY.test(String(b.end ?? "")) ? b.end : "9999-12-31";
  if (end < start) return null;
  return { title, body, level, start, end };
}

const view = (r) => ({ id: r.id, title: r.title, body: r.body, level: r.level, start: r.start_day, end: r.end_day });

/** 지금 게시 중인 공지(중요 → 최신 순). DB가 없거나 오류면 빈 목록. */
export async function publicNotices(env) {
  if (!env.DB) return reply({ notices: [] }, 200, "public, max-age=60");
  await ensure(env.DB);
  const today = kstDay();
  const { results } = await env.DB.prepare("SELECT * FROM notices WHERE start_day <= ?1 AND end_day >= ?1 ORDER BY (level = 'urgent') DESC, id DESC LIMIT 20").bind(today).all();
  return reply({ notices: (results ?? []).map(view) }, 200, "public, max-age=60");
}

export async function adminNotices(request, env) {
  if (!env.ADMIN_TOKEN || request.headers.get("X-Admin-Token") !== env.ADMIN_TOKEN) return new Response("Unauthorized", { status: 401 });
  if (!env.DB) return reply({ error: "저장소(D1)가 연결되지 않았습니다" }, 503);
  await ensure(env.DB);
  if (request.method === "GET") {
    const { results } = await env.DB.prepare("SELECT * FROM notices ORDER BY id DESC LIMIT 200").all();
    return reply({ today: kstDay(), notices: (results ?? []).map(view) });
  }
  if (request.method === "POST") {
    const text = await request.text();
    if (text.length > 8192) return reply({ error: "too large" }, 413);
    let body;
    try { body = JSON.parse(text); } catch { return reply({ error: "bad json" }, 400); }
    const n = sanitizeNotice(body);
    if (!n) return reply({ error: "제목은 필수이며 종료일은 시작일 이후여야 합니다" }, 400);
    const id = Number.isInteger(body.id) ? body.id : null;
    if (id) {
      await env.DB.prepare("UPDATE notices SET title=?, body=?, level=?, start_day=?, end_day=? WHERE id=?").bind(n.title, n.body, n.level, n.start, n.end, id).run();
    } else {
      await env.DB.prepare("INSERT INTO notices (title, body, level, start_day, end_day, created_at) VALUES (?,?,?,?,?,?)").bind(n.title, n.body, n.level, n.start, n.end, new Date().toISOString()).run();
    }
    return reply({ ok: true });
  }
  if (request.method === "DELETE") {
    const id = parseInt(new URL(request.url).searchParams.get("id"), 10);
    if (!Number.isInteger(id)) return reply({ error: "id" }, 400);
    await env.DB.prepare("DELETE FROM notices WHERE id=?").bind(id).run();
    return reply({ ok: true });
  }
  return new Response("Method Not Allowed", { status: 405 });
}

export function noticesPage() {
  return new Response(PAGE, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

const PAGE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FSS Insights 공지사항 관리</title>
<style>
:root{--bg:#f6f8fa;--card:#fff;--fg:#1c2530;--mut:#6b7785;--a:#1f6feb;--r:#d14343;--ln:#e2e7ec}
@media(prefers-color-scheme:dark){:root{--bg:#12171d;--card:#1b222a;--fg:#e6ebf0;--mut:#93a0ad;--ln:#2a343e}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}main{max-width:760px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:8px 0 12px}h2{font-size:15px;margin:0 0 8px}.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:12px;border:1px solid var(--ln)}
input,select,textarea,button{font:inherit;padding:8px 10px;border-radius:8px;border:1px solid var(--ln);background:var(--card);color:var(--fg);box-sizing:border-box}
textarea{width:100%;min-height:110px}input.w{width:100%}button{background:var(--a);color:#fff;border:0;cursor:pointer}button.g{background:transparent;color:var(--fg);border:1px solid var(--ln)}button.d{background:var(--r)}
label{display:block;font-size:12px;color:var(--mut);margin:8px 0 2px}.row{display:flex;gap:8px;flex-wrap:wrap}.row>*{flex:1;min-width:130px}
.n{border-top:1px solid var(--ln);padding:8px 0}.n:first-child{border:0}.t{font-weight:600}.m{font-size:12px;color:var(--mut)}.u{color:var(--r)}.leg{font-size:12px;color:var(--mut)}
</style></head><body><main>
<h1>공지사항 관리 <a class="leg" href="/admin">· 사용 통계</a></h1>
<div class="card"><input id="tok" type="password" placeholder="관리자 토큰" autocomplete="off"> <button id="go">불러오기</button> <span id="msg" class="leg"></span></div>
<div id="out" hidden>
<div class="card"><h2 id="fh">새 공지 작성</h2>
<label>제목 (배너에 한 줄로 보입니다)</label><input id="ti" class="w" maxlength="80">
<label>본문 (눌렀을 때 펼쳐 보입니다. 비워도 됩니다)</label><textarea id="bo" maxlength="2000"></textarea>
<div class="row"><div><label>게시 시작일</label><input id="st" type="date" class="w"></div><div><label>게시 종료일 (비우면 계속)</label><input id="en" type="date" class="w"></div><div><label>중요도</label><select id="lv" class="w"><option value="normal">일반</option><option value="urgent">중요(빨간색)</option></select></div></div>
<p><button id="save">저장</button> <button id="reset" class="g">새로 쓰기</button></p>
<div class="leg">저장하면 1~2분 안에 앱·웹 맨 위 배너에 나타납니다. 종료일이 지나면 자동으로 사라지며, 이용자가 닫은 공지는 그 기기에서 다시 뜨지 않습니다.</div></div>
<div class="card"><h2>등록된 공지</h2><div id="list"></div></div>
</div></main>
<script>
const $=i=>document.getElementById(i);$("tok").value=sessionStorage.getItem("t")||"";let items=[],editId=null,today="";
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const api=(m,q="",b)=>fetch("/admin/notices/api"+q,{method:m,headers:{"X-Admin-Token":$("tok").value,"Content-Type":"application/json"},body:b?JSON.stringify(b):undefined});
function reset(){editId=null;$("fh").textContent="새 공지 작성";$("ti").value="";$("bo").value="";$("st").value=today;$("en").value="";$("lv").value="normal"}
function draw(){$("list").innerHTML=items.length?items.map(n=>{const st=n.end<today?"종료":n.start>today?"예정":"게시 중";return '<div class="n"><div class="t'+(n.level==="urgent"?' u':'')+'">'+esc(n.title)+'</div><div class="m">'+st+' · '+n.start+' ~ '+(n.end==="9999-12-31"?"계속":n.end)+(n.level==="urgent"?" · 중요":"")+'</div><div class="m">'+esc(n.body).slice(0,120)+'</div><p><button class="g" data-e="'+n.id+'">수정</button> <button class="d" data-d="'+n.id+'">삭제</button></p></div>'}).join(""):'<span class="leg">등록된 공지가 없습니다</span>'}
async function load(){$("msg").textContent="불러오는 중…";sessionStorage.setItem("t",$("tok").value);const r=await api("GET");
if(!r.ok){$("msg").textContent=r.status==401?"토큰이 맞지 않습니다":"오류 "+r.status;return}
const d=await r.json();today=d.today;items=d.notices;$("msg").textContent="";$("out").hidden=false;if(editId===null&&!$("st").value)reset();draw()}
$("go").onclick=load;$("reset").onclick=reset;
$("save").onclick=async()=>{const b={title:$("ti").value,body:$("bo").value,start:$("st").value,end:$("en").value,level:$("lv").value};if(editId)b.id=editId;
const r=await api("POST","",b);if(!r.ok){alert((await r.json()).error||"저장하지 못했습니다");return}reset();load()};
$("list").onclick=async e=>{const t=e.target;if(t.dataset.e){const n=items.find(x=>x.id==t.dataset.e);editId=n.id;$("fh").textContent="공지 수정";$("ti").value=n.title;$("bo").value=n.body;$("st").value=n.start;$("en").value=n.end==="9999-12-31"?"":n.end;$("lv").value=n.level;scrollTo(0,0)}
if(t.dataset.d&&confirm("이 공지를 삭제할까요?")){await api("DELETE","?id="+t.dataset.d);load()}};
if($("tok").value)load();
</script></body></html>`;
