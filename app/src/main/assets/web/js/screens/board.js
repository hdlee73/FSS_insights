// 익명게시판: Cloudflare Worker(/board/*) 기반 익명 글·댓글 및 관리자 전용 공지사항.
import { setHeader } from "../app.js";
import { SERVER, dialog, errorText, h, icon, loading, message, searchBox, toast } from "../core.js";

const BOARD_BASE = (() => {
  try {
    return (localStorage.getItem("fss.boardServer") || SERVER).replace(/\/+$/, "");
  } catch {
    return SERVER;
  }
})();

const adminKey = () => {
  try {
    return sessionStorage.getItem("fss.boardAdmin") || "";
  } catch {
    return "";
  }
};
const setAdminKey = (k) => {
  try {
    if (k) sessionStorage.setItem("fss.boardAdmin", k);
    else sessionStorage.removeItem("fss.boardAdmin");
  } catch {}
};

// 익명 투표자 ID: 이 브라우저에서만 쓰는 무작위 값(로그인 없음). 지우면 새 사람으로 취급된다.
let memVoter = "";
function voterId() {
  try {
    let v = localStorage.getItem("fss.boardVoter");
    if (!v || !/^[A-Za-z0-9_-]{8,64}$/.test(v)) {
      v = (crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36)).replace(/[^A-Za-z0-9_-]/g, "");
      localStorage.setItem("fss.boardVoter", v);
    }
    return v;
  } catch {
    if (!memVoter) memVoter = Math.random().toString(36).slice(2) + Date.now().toString(36);
    return memVoter;
  }
}

async function req(path, { method = "GET", body, admin = false } = {}) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const key = adminKey();
  if (admin && key) headers["X-Admin-Token"] = key;
  let res;
  try {
    res = await fetch(BOARD_BASE + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new Error("게시판 서버에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data?.error || `서버 오류 (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace("T", " ");
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function openWriteDialog(ctx) {
  const isNotice = false; // 게시판 공지는 서버 /admin/notices 화면에서만 작성한다.
  const titleEl = h("input", { type: "text", maxlength: 120, placeholder: isNotice ? "공지 제목을 입력하세요" : "제목을 입력하세요" });
  const authorEl = h("input", { type: "text", maxlength: 20, placeholder: isNotice ? "관리자" : "닉네임 (비우면 익명)" });
  const bodyEl = h("textarea", { maxlength: 5000, placeholder: "내용을 입력하세요 (최대 5,000자)" });
  const pwEl = isNotice ? null : h("input", { type: "password", maxlength: 32, placeholder: "삭제·수정용 비밀번호 (선택, 4자 이상)" });

  const form = h("div", { class: "bd-form" },
    h("label", null, "제목", titleEl),
    isNotice ? null : h("label", null, "작성자", authorEl),
    h("label", null, "내용", bodyEl),
    pwEl ? h("label", null, "삭제·수정 비밀번호", pwEl) : null
  );
  const ok = await dialog({
    title: isNotice ? "공지사항 작성 (관리자 전용)" : "익명 글쓰기",
    body: form,
    ok: "등록",
  });
  if (!ok) return;
  const title = titleEl.value.trim();
  const body = bodyEl.value.trim();
  const author = (authorEl.value || "").trim() || (isNotice ? "관리자" : "익명");
  const password = pwEl ? pwEl.value : "";
  if (!title || !body) return toast("제목과 내용을 모두 입력해 주세요.");
  if (password && password.length < 4) return toast("비밀번호는 4자 이상으로 입력해 주세요.");

  try {
    const created = await req("/board/posts", {
      method: "POST",
      admin: isNotice,
      body: { title, body, author, password, is_notice: isNotice },
    });
    toast(isNotice ? "공지사항을 등록했습니다." : "글을 등록했습니다.");
    ctx.go({ id: created.id });
  } catch (e) {
    toast(errorText(e));
  }
}

export async function render(root, params, ctx) {
  if (params.id) return renderDetail(root, params.id, ctx);
  return renderList(root, params, ctx);
}

async function renderList(root, params, ctx) {
  setHeader("익명게시판", "자유로운 의견·질문", [
    h("button", { class: "pill", type: "button", onclick: () => openWriteDialog(ctx) }, icon("pencil"), h("span", { class: "lbl" }, "글쓰기")),
  ]);

  const q = (params.q || "").trim();
  const page = Math.max(1, +params.page || 1);
  root.append(searchBox({
    placeholder: "제목·내용 검색",
    value: q,
    onSearch: (v) => ctx.go({ q: v.trim() || null }),
  }));

  const box = h("div", null, loading("게시글을 불러오는 중…"));
  root.append(box);

  let data;
  try {
    const qs = new URLSearchParams({ page: String(page), ...(q ? { q } : {}) }).toString();
    data = await req("/board/posts?" + qs);
  } catch (e) {
    if (!ctx.alive()) return;
    if (e.status === 404) {
      box.replaceChildren(
        h("div", { class: "card", style: { padding: "18px" } },
          message("게시판 Worker 엔드포인트(/board/posts)가 아직 연결되지 않았습니다.", { error: true }),
          h("p", { class: "note", style: { marginTop: "10px" } },
            "FSS_insights(server/news-proxy)에 worker/board-worker.js 의 /board 라우트를 연결하면 기존 DB와 ADMIN_TOKEN 그대로 바로 동작합니다.")
        )
      );
      return;
    }
    box.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
    return;
  }
  if (!ctx.alive()) return;

  const notices = data.notices || [];
  const posts = data.posts || [];
  const total = data.total || 0;
  const pageSize = data.pageSize || 20;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const row = (p, notice) => h("div", {
    class: "item" + (notice ? " bd-notice" : ""),
    role: "link",
    tabindex: 0,
    onclick: () => ctx.go({ id: p.id }),
    onkeydown: (e) => (e.key === "Enter" || e.key === " ") && (e.preventDefault(), ctx.go({ id: p.id })),
  },
  h("div", { class: "body" },
    h("div", { class: "t" },
      notice ? h("span", { class: "tag blue", style: { marginRight: "6px" } }, "공지") : null,
      p.title,
      p.comment_count ? h("span", { class: "note", style: { marginLeft: "6px" } }, `[${p.comment_count}]`) : null
    ),
    h("div", { class: "m" }, `${p.author || "익명"} · ${fmtDate(p.created_at)}` +
      (p.like_count || p.dislike_count ? ` · 👍 ${p.like_count || 0} 👎 ${p.dislike_count || 0}` : ""))
  ));

  const cards = [];
  if (notices.length) {
    cards.push(h("section", { class: "card" }, h("div", { class: "list" }, notices.map((p) => row(p, true)))));
  }
  cards.push(
    h("section", { class: "card" },
      posts.length
        ? h("div", { class: "list" }, posts.map((p) => row(p, false)))
        : h("p", { class: "note", style: { padding: "18px" } }, q ? "검색 결과가 없습니다." : "아직 작성된 글이 없습니다. 첫 글을 남겨 보세요!"))
  );

  if (totalPages > 1) {
    cards.push(h("div", { class: "row-gap", style: { justifyContent: "center" } },
      h("button", { class: "btn ghost sm", type: "button", disabled: page <= 1, onclick: () => ctx.go({ ...(q ? { q } : {}), page: page - 1 }) }, "이전"),
      h("span", { class: "note", style: { alignSelf: "center" } }, `${page} / ${totalPages}`),
      h("button", { class: "btn ghost sm", type: "button", disabled: page >= totalPages, onclick: () => ctx.go({ ...(q ? { q } : {}), page: page + 1 }) }, "다음")
    ));
  }

  box.replaceChildren(h("div", { class: "stack" }, ...cards));
}

async function renderDetail(root, id, ctx) {
  setHeader("익명게시판", "게시글 보기", [
    h("button", { class: "pill", type: "button", onclick: () => ctx.go({}) }, icon("chevron-left"), h("span", { class: "lbl" }, "목록")),
  ]);

  const box = h("div", null, loading("글을 불러오는 중…"));
  root.append(box);

  let data;
  try {
    data = await req(`/board/posts/${encodeURIComponent(id)}?voter=${encodeURIComponent(voterId())}`);
  } catch (e) {
    if (!ctx.alive()) return;
    box.replaceChildren(message(errorText(e), { error: true, action: "목록으로", onAction: () => ctx.go({}) }));
    return;
  }
  if (!ctx.alive()) return;

  const p = data.post;
  const comments = data.comments || [];

  const deletePost = async () => {
    const isAdmin = !!adminKey();
    let password = "";
    if (!isAdmin) {
      const out = await dialog({
        title: p.is_notice ? "공지사항 삭제 (관리자 전용)" : "게시글 삭제",
        text: p.is_notice ? "관리자 인증이 필요합니다." : "작성 시 입력한 비밀번호를 입력하세요.",
        fields: [{ name: "pw", label: p.is_notice ? "관리자 키" : "비밀번호", type: "password" }],
        ok: "삭제",
      });
      if (!out || !out.pw) return;
      if (p.is_notice) setAdminKey(out.pw.trim());
      else password = out.pw;
    } else {
      const ok = await dialog({ title: "게시글 삭제", text: "이 글을 삭제할까요?", ok: "삭제" });
      if (!ok) return;
    }
    try {
      await req(`/board/posts/${encodeURIComponent(id)}`, {
        method: "DELETE",
        admin: !!adminKey(),
        body: { password },
      });
      toast("삭제했습니다.");
      ctx.go({});
    } catch (e) {
      toast(errorText(e));
    }
  };

  const deleteComment = async (cid) => {
    const isAdmin = !!adminKey();
    let password = "";
    if (!isAdmin) {
      const out = await dialog({
        title: "댓글 삭제",
        text: "댓글 작성 시 입력한 비밀번호를 입력하세요.",
        fields: [{ name: "pw", label: "비밀번호", type: "password" }],
        ok: "삭제",
      });
      if (!out || !out.pw) return;
      password = out.pw;
    }
    try {
      await req(`/board/comments/${encodeURIComponent(cid)}`, {
        method: "DELETE",
        admin: isAdmin,
        body: { password },
      });
      toast("댓글을 삭제했습니다.");
      ctx.go({ id }, { replace: true });
    } catch (e) {
      toast(errorText(e));
    }
  };

  const editPost = async () => {
    const isAdmin = !!adminKey();
    const out = await dialog({
      title: "게시글 수정",
      text: isAdmin ? "관리자 권한으로 수정합니다." : "작성 시 입력한 비밀번호가 필요합니다.",
      fields: [
        { name: "title", label: "제목", value: p.title },
        { name: "author", label: "작성자", value: p.author || "" },
        { name: "body", label: "내용", value: p.body, multiline: true },
        ...(isAdmin ? [] : [{ name: "pw", label: "비밀번호", type: "password" }]),
      ],
      ok: "저장",
    });
    if (!out) return;
    if (!out.title || !out.body) return toast("제목과 내용을 모두 입력해 주세요.");
    if (!isAdmin && !out.pw) return toast("비밀번호를 입력해 주세요.");
    try {
      await req(`/board/posts/${encodeURIComponent(id)}`, {
        method: "PUT",
        admin: isAdmin,
        body: { title: out.title, body: out.body, author: out.author, password: out.pw || "" },
      });
      toast("수정했습니다.");
      ctx.go({ id }, { replace: true });
    } catch (e) {
      toast(errorText(e));
    }
  };

  const editComment = async (c) => {
    const isAdmin = !!adminKey();
    const out = await dialog({
      title: "댓글 수정",
      text: isAdmin ? "관리자 권한으로 수정합니다." : "댓글 작성 시 입력한 비밀번호가 필요합니다.",
      fields: [
        { name: "body", label: "내용", value: c.body, multiline: true },
        ...(isAdmin ? [] : [{ name: "pw", label: "비밀번호", type: "password" }]),
      ],
      ok: "저장",
    });
    if (!out) return;
    if (!out.body) return toast("댓글 내용을 입력해 주세요.");
    if (!isAdmin && !out.pw) return toast("비밀번호를 입력해 주세요.");
    try {
      await req(`/board/comments/${encodeURIComponent(c.id)}`, {
        method: "PUT",
        admin: isAdmin,
        body: { body: out.body, password: out.pw || "" },
      });
      toast("댓글을 수정했습니다.");
      ctx.go({ id }, { replace: true });
    } catch (e) {
      toast(errorText(e));
    }
  };

  // 좋아요·싫어요: 같은 단추를 다시 누르면 취소, 반대쪽을 누르면 변경. 서버 응답으로 숫자를 갱신한다.
  const vote = { likes: p.likes || 0, dislikes: p.dislikes || 0, mine: p.my_vote || 0, busy: false };
  const likeBtn = h("button", { type: "button", class: "btn ghost sm bd-vote" });
  const dislikeBtn = h("button", { type: "button", class: "btn ghost sm bd-vote" });
  const paintVote = () => {
    likeBtn.textContent = `👍 좋아요 ${vote.likes}`;
    dislikeBtn.textContent = `👎 싫어요 ${vote.dislikes}`;
    likeBtn.classList.toggle("on", vote.mine === 1);
    dislikeBtn.classList.toggle("on", vote.mine === -1);
    likeBtn.setAttribute("aria-pressed", vote.mine === 1);
    dislikeBtn.setAttribute("aria-pressed", vote.mine === -1);
    likeBtn.disabled = dislikeBtn.disabled = vote.busy;
  };
  const castVote = async (v) => {
    if (vote.busy) return;
    vote.busy = true;
    paintVote();
    try {
      const r = await req(`/board/posts/${encodeURIComponent(id)}/vote`, {
        method: "POST",
        body: { voter: voterId(), vote: vote.mine === v ? 0 : v },
      });
      vote.likes = r.likes;
      vote.dislikes = r.dislikes;
      vote.mine = r.my_vote;
    } catch (e) {
      toast(errorText(e));
    }
    vote.busy = false;
    paintVote();
  };
  likeBtn.onclick = () => castVote(1);
  dislikeBtn.onclick = () => castVote(-1);
  paintVote();

  const cAuthor = h("input", { type: "text", maxlength: 20, placeholder: "닉네임 (비우면 익명)" });
  const cPw = h("input", { type: "password", maxlength: 32, placeholder: "삭제·수정 비밀번호 (선택)" });
  const cBody = h("textarea", { maxlength: 1000, placeholder: "댓글을 입력하세요 (최대 1,000자)", style: { minHeight: "72px" } });
  const submitComment = async (e) => {
    e.preventDefault();
    const body = cBody.value.trim();
    if (!body) return toast("댓글 내용을 입력해 주세요.");
    if (cPw.value && cPw.value.length < 4) return toast("비밀번호는 4자 이상으로 입력해 주세요.");
    try {
      await req(`/board/posts/${encodeURIComponent(id)}/comments`, {
        method: "POST",
        body: { author: cAuthor.value.trim() || "익명", body, password: cPw.value },
      });
      toast("댓글을 등록했습니다.");
      ctx.go({ id }, { replace: true });
    } catch (err) {
      toast(errorText(err));
    }
  };

  box.replaceChildren(
    h("div", { class: "stack" },
      h("section", { class: "card" + (p.is_notice ? " bd-notice" : ""), style: { padding: "18px" } },
        h("div", { class: "bd-meta" },
          p.is_notice ? h("span", { class: "tag blue" }, "공지") : null,
          h("b", null, p.author || "익명"),
          h("span", null, fmtDate(p.created_at) + (p.edited_at ? " (수정됨)" : "")),
          h("span", { class: "bd-acts" },
            p.is_notice || p.has_pw ? h("button", { class: "btn ghost sm", type: "button", onclick: editPost }, icon("pencil"), "수정") : null,
            h("button", { class: "btn ghost sm", type: "button", onclick: deletePost }, icon("trash-2"), "삭제")
          )
        ),
        h("h2", { style: { margin: "10px 0 6px", fontSize: "18px" } }, p.title),
        h("div", { class: "bd-body" }, p.body),
        h("div", { class: "bd-votes" }, likeBtn, dislikeBtn)
      ),
      h("section", { class: "card", style: { padding: "16px" } },
        h("div", { class: "section-label" }, `댓글 ${comments.length}`),
        comments.length
          ? h("div", null, comments.map((c) => h("div", { class: "bd-cmt" },
              h("div", { class: "bd-meta" },
                h("b", null, c.author || "익명"),
                h("span", null, fmtDate(c.created_at) + (c.edited_at ? " (수정됨)" : "")),
                h("span", { class: "bd-acts" },
                  c.has_pw ? h("button", { class: "ib", type: "button", title: "댓글 수정", onclick: () => editComment(c) }, icon("pencil")) : null,
                  h("button", { class: "ib", type: "button", title: "댓글 삭제", onclick: () => deleteComment(c.id) }, icon("trash-2"))
                )
              ),
              h("div", { class: "bd-cmt-txt" }, c.body)
            )))
          : h("p", { class: "note" }, "첫 댓글을 남겨 보세요."),
        h("form", { class: "bd-form", style: { marginTop: "14px" }, onsubmit: submitComment },
          h("div", { class: "row-gap" }, cAuthor, cPw),
          cBody,
          h("div", { style: { display: "flex", justifyContent: "flex-end" } }, h("button", { class: "btn sm", type: "submit" }, "댓글 등록"))
        )
      )
    )
  );
}
