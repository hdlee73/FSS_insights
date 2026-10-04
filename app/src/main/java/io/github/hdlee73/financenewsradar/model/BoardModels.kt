package io.github.hdlee73.financenewsradar.model

/** 게시판 말머리. 서버의 CATEGORIES와 같아야 한다. */
val BOARD_CATEGORIES = listOf("검사", "감독", "질문", "자료")

data class BoardPost(
    val id: Long,
    val category: String,
    val title: String,
    val nick: String,
    val created: Long,
    val views: Int,
    val preview: String,
    val comments: Int,
    val attachments: Int,
    val thumb: String?,
    val mine: Boolean
)

data class BoardFile(val key: String, val name: String, val type: String, val size: Long) {
    val isImage: Boolean get() = type.startsWith("image/")
}

data class BoardComment(val id: Long, val body: String, val nick: String, val created: Long, val mine: Boolean)

data class BoardDetail(
    val post: BoardPost,
    val body: String,
    val files: List<BoardFile>,
    val comments: List<BoardComment>
)

/** 목록 보기 방식: 최신 / 인기(7일) / 내가 쓴 글. */
enum class BoardSort(val label: String, val wire: String) {
    NEW("전체", "new"),
    POPULAR("인기", "popular"),
    MINE("내글", "mine")
}
