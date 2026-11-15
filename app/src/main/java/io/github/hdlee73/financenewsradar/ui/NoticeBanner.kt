package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.data.Notice
import io.github.hdlee73.financenewsradar.data.NoticeFeed
import kotlinx.coroutines.delay

/**
 * 화면 맨 위 공지 회전 배너. 관리자가 서버에서 쓴 공지를 5초마다 돌려 보여 주고, 누르면 본문을 펼친다.
 * '숨김'은 지금 보이는 공지를 하루 동안 숨긴다(새 공지는 다시 뜬다). 서버 연결이 안 되면 아무것도 그리지 않는다.
 */
@Composable
fun NoticeBanner() {
    val context = LocalContext.current
    var notices by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var hidden by remember { mutableStateOf(NoticeFeed.hidden(context)) }
    var index by rememberSaveable { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    // 앱을 켜는 동안 10분마다 새 공지를 확인한다. 화면을 떠나면 코루틴이 함께 취소된다.
    LaunchedEffect(Unit) {
        while (true) {
            NoticeFeed.fetch()?.let { notices = it }
            delay(10 * 60_000L)
        }
    }
    val visible = notices.filter { it.id !in hidden }
    // 목록이 줄어 index 가 범위를 벗어나는 일을 막는다.
    val shown = if (visible.isEmpty()) null else visible[index.coerceIn(0, visible.lastIndex)]
    LaunchedEffect(visible.size, expanded) {
        if (visible.size < 2 || expanded) return@LaunchedEffect
        while (true) {
            delay(5_000)
            index = (index + 1) % visible.size
        }
    }
    shown ?: return

    val bg = if (shown.urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (shown.urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.clickable(enabled = shown.body.isNotEmpty()) { expanded = !expanded }.padding(start = 14.dp, top = 2.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📢", style = MaterialTheme.typography.bodyMedium)
                Text(
                    shown.title, modifier = Modifier.weight(1f), maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (visible.size > 1) {
                    val n = visible.size
                    val cur = index.coerceIn(0, n - 1)
                    Text("‹", modifier = Modifier.clickable { index = (cur - 1 + n) % n }.padding(horizontal = 6.dp, vertical = 8.dp))
                    Text("${cur + 1}/$n", style = MaterialTheme.typography.labelSmall)
                    Text("›", modifier = Modifier.clickable { index = (cur + 1) % n }.padding(horizontal = 6.dp, vertical = 8.dp))
                }
                TextButton(onClick = {
                    NoticeFeed.hide(context, visible.map { it.id })
                    hidden = NoticeFeed.hidden(context)
                    index = 0
                    expanded = false
                }) { Text("숨김", style = MaterialTheme.typography.labelMedium) }
            }
            if (expanded && shown.body.isNotEmpty()) {
                Text(shown.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 10.dp, bottom = 2.dp))
            }
        }
    }
}
