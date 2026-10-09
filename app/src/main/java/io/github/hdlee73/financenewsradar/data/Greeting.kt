package io.github.hdlee73.financenewsradar.data

import java.time.DayOfWeek
import java.time.Month
import java.time.ZonedDateTime
import java.util.Random

/**
 * 오늘의 브리핑 머리말 인삿말. 시간대·요일·계절·날씨별 문구 풀을 합쳐 놓고 하나를 고른다.
 * 같은 날 같은 시간대에는 같은 문구(앱을 다시 열어도 바뀌지 않음), 날짜나 시간대가 바뀌면 다른 문구가 나온다.
 */
object Greeting {
    private val dawn = listOf(
        "이른 아침이네요. 조용한 시간에 오늘 이슈부터 훑어보세요.",
        "일찍 시작하셨네요. 오늘도 차분하게 살펴보시죠.",
        "새벽 공기만큼 맑은 머리로 시장을 먼저 확인해 보세요."
    )
    private val morning = listOf(
        "좋은 아침입니다. 밤사이 소식부터 정리해 두었습니다.",
        "커피 한 잔과 함께 오늘의 흐름을 가볍게 짚어 보세요.",
        "아침 회의 전에 알아 둘 이슈를 모았습니다.",
        "오늘 하루도 든든하게 시작하시길 바랍니다."
    )
    private val lunch = listOf(
        "점심시간입니다. 식사 전에 헤드라인만 가볍게 보세요.",
        "든든히 드시고, 오후 일정도 힘차게 이어 가세요.",
        "한숨 돌리는 시간, 오전 사이 바뀐 소식을 확인해 보세요."
    )
    private val afternoon = listOf(
        "오후도 힘내세요. 장중 흐름을 확인해 보세요.",
        "나른한 오후, 새로 나온 자료로 환기해 보세요.",
        "퇴근 전 놓친 소식이 없는지 한 번 더 살펴보세요.",
        "오후 업무에 도움이 될 소식을 모았습니다."
    )
    private val evening = listOf(
        "수고 많으셨습니다. 하루를 마무리하며 주요 소식을 정리해 보세요.",
        "퇴근길 가벼운 마음으로 오늘의 마감 시황을 확인해 보세요.",
        "오늘도 고생하셨습니다. 편안한 저녁 보내세요."
    )
    private val night = listOf(
        "늦은 시간까지 수고 많으십니다. 무리하지 마세요.",
        "고요한 밤, 내일을 위해 오늘 소식만 가볍게 살펴보세요.",
        "하루의 끝자락입니다. 충분히 쉬시길 바랍니다."
    )

    private val monday = listOf("새로운 한 주의 시작입니다. 이번 주도 힘차게 출발해 보세요.", "월요일 아침, 이번 주 주요 일정부터 점검해 보세요.")
    private val midWeek = listOf("한 주의 중간입니다. 지치지 않게 호흡을 가다듬으세요.", "수요일, 반환점을 돌았습니다. 조금만 더 힘내세요.")
    private val friday = listOf("금요일입니다. 한 주 마무리까지 조금만 더 힘내세요.", "주말이 코앞입니다. 마지막까지 꼼꼼하게 챙겨 보세요.")
    private val weekend = listOf("주말에도 챙겨 보시는군요. 여유 있게 훑어보세요.", "쉬는 날, 부담 없이 주요 소식만 확인하세요.")

    private val spring = listOf("봄기운이 완연합니다. 오늘도 산뜻하게 시작하세요.", "꽃 소식이 들려오는 계절, 시장에도 좋은 소식이 있기를 바랍니다.")
    private val summer = listOf("무더운 날씨에 건강 잘 챙기세요.", "여름 한가운데, 시원한 물 한 잔 드시며 보세요.")
    private val autumn = listOf("선선한 가을입니다. 결실의 계절에 좋은 하루 되세요.", "높은 하늘만큼 맑은 하루가 되길 바랍니다.")
    private val winter = listOf("추운 날씨에 감기 조심하세요.", "따뜻한 차 한 잔과 함께 오늘을 열어 보세요.")

    private val rainy = listOf("비가 오는 날입니다. 우산 챙기셨나요?", "빗소리와 함께 차분하게 하루를 시작해 보세요.")
    private val snowy = listOf("눈이 내립니다. 길 미끄러우니 조심하세요.", "눈 오는 날, 따뜻하게 입고 안전하게 다니세요.")
    private val hot = listOf("오늘은 많이 덥습니다. 수분 보충 잊지 마세요.")
    private val cold = listOf("오늘은 쌀쌀합니다. 따뜻하게 챙겨 입으세요.")
    private val clear = listOf("하늘이 맑은 날입니다. 점심 산책도 추천드려요.")

    private val monthStart = listOf("새달의 첫날입니다. 이번 달도 잘 부탁드립니다.")
    private val monthEnd = listOf("이번 달 마무리 시점입니다. 놓친 일정은 없는지 살펴보세요.")

    /** 시간대·요일·계절·날씨에 맞는 후보를 모아 하나를 고른다. */
    fun pick(now: ZonedDateTime, weather: Weather? = null): String {
        val pool = candidates(now, weather)
        val seed = now.toLocalDate().toEpochDay() * 31 + bucket(now.hour)
        // 이웃한 날짜의 시드는 첫 난수가 비슷하게 나오므로 섞은 뒤 한 번 건너뛰고 뽑는다.
        val random = Random(seed * -7046029254386353131L)
        random.nextInt()
        return pool[random.nextInt(pool.size)]
    }

    internal fun candidates(now: ZonedDateTime, weather: Weather? = null): List<String> {
        val pool = mutableListOf<String>()
        pool += when (bucket(now.hour)) {
            0 -> dawn
            1 -> morning
            2 -> lunch
            3 -> afternoon
            4 -> evening
            else -> night
        }
        pool += when (now.dayOfWeek) {
            DayOfWeek.MONDAY -> monday
            DayOfWeek.WEDNESDAY -> midWeek
            DayOfWeek.FRIDAY -> friday
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> weekend
            else -> emptyList()
        }
        pool += when (now.month) {
            Month.MARCH, Month.APRIL, Month.MAY -> spring
            Month.JUNE, Month.JULY, Month.AUGUST -> summer
            Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER -> autumn
            else -> winter
        }
        if (now.dayOfMonth == 1) pool += monthStart
        if (now.dayOfMonth == now.toLocalDate().lengthOfMonth()) pool += monthEnd
        if (weather != null) {
            val text = weather.summary
            when {
                text.contains("눈") -> pool += snowy
                text.contains("비") || text.contains("소나기") || text.contains("뇌우") || text.contains("이슬비") -> pool += rainy
                weather.max >= 30 -> pool += hot
                weather.min <= 0 -> pool += cold
                text.contains("맑음") -> pool += clear
            }
        }
        return pool
    }

    /** 0 새벽(5~8시), 1 오전(9~11), 2 점심(12~13), 3 오후(14~17), 4 저녁(18~21), 5 밤(22~4시). */
    internal fun bucket(hour: Int): Int = when (hour) {
        in 5..8 -> 0
        in 9..11 -> 1
        in 12..13 -> 2
        in 14..17 -> 3
        in 18..21 -> 4
        else -> 5
    }
}
