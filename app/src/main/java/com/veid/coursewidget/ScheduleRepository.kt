package com.veid.coursewidget

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.Log
import java.util.Calendar

/**
 * 课表数据来源:系统 CalendarProvider。
 *
 * 小组件本身不保存课表,只从你导入 .ics 的那个日历里读取事件。
 * 因此课表更新后,只要重新导入日历,小组件会自动跟着变。
 */
object ScheduleRepository {

    /** 订阅到系统日历里显示的名称。小米日历和 Google 日历都认这个字段。 */
    private const val CALENDAR_DISPLAY_NAME = "课表"

    /** 显示在“今日课程”和本周概览里的最大条数(避免超高的小组件无限增长)。 */
    const val MAX_TODAY_ROWS = 4

    data class ClassEvent(
        val id: Long,
        val title: String,
        val location: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
    )

    data class TodayInfo(
        val all: List<ClassEvent>,
        val upcoming: List<ClassEvent>,
        val finishedCount: Int,
        /** 查询失败时的错误描述(null 表示正常)。 */
        val error: String? = null,
    )

    /** 一周里的某一天:下标(0..6)、当天零点时间、课程数、第一节课时间、当天的全部课程列表。 */
    data class DayInfo(
        val dayIndex: Int = 0,
        val dateMillis: Long = 0L,
        val count: Int = 0,
        val firstStart: Long? = null,
        val events: List<ClassEvent> = emptyList(),
    )

    // ---------------------------------------------------------------- 时区与时间

    private fun calendar(): Calendar = Calendar.getInstance()

    private fun startOfDay(timeMillis: Long): Long {
        val c = calendar()
        c.timeInMillis = timeMillis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun endOfDay(timeMillis: Long): Long = startOfDay(timeMillis) + DAY_MILLIS - 1

    /** 本周一 00:00。Calendar.MONDAY..SUNDAY 与我们的数组下标 0..6 对应。 */
    private fun startOfWeek(now: Long): Long {
        val c = calendar()
        c.timeInMillis = startOfDay(now)
        val shift = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一 -> 0
        c.add(Calendar.DAY_OF_MONTH, -shift)
        return c.timeInMillis
    }

    /** 今天在星期数组里的下标(周一 = 0)。 */
    fun todayIndex(now: Long = System.currentTimeMillis()): Int {
        val c = calendar()
        c.timeInMillis = now
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    fun timeLabel(timeMillis: Long): String {
        val c = calendar()
        c.timeInMillis = timeMillis
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun dateLabel(timeMillis: Long): String {
        val c = calendar()
        c.timeInMillis = timeMillis
        return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }

    fun dayIndexLabel(dayIndex: Int): String = when (dayIndex) {
        0 -> "周一"
        1 -> "周二"
        2 -> "周三"
        3 -> "周四"
        4 -> "周五"
        5 -> "周六"
        6 -> "周日"
        else -> "周一"
    }

    fun dayOfWeekLabel(timeMillis: Long): String {
        val c = calendar()
        c.timeInMillis = timeMillis
        return dayIndexLabel((c.get(Calendar.DAY_OF_WEEK) + 5) % 7)
    }

    // ---------------------------------------------------------------- 日历来源

    /** 一个系统日历:ID、显示名、本周日程条数(用于识别哪个是课表)。 */
    data class CalendarInfo(val id: Long, val name: String, val weekCount: Int)

    /** 当前自然周的 [周一 00:00, 下周一 00:00) 毫秒区间。 */
    fun currentWeekRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val start = startOfWeek(now)
        return start to (start + 7 * DAY_MILLIS)
    }

    /**
     * 猜测哪个系统日历是课表,按优先级:
     * 1) 名称(非空)里含“课表”、“课程”、“Course”、“Schedule”、“Class”;
     * 2) 本周日程条数最多的那个(课表通常为高频密集的定时日程);
     * 3) 若系统仅有一个日历则直接采用;
     * 4) 都不成立时返回空,交给界面提示用户手动选择。
     */
    fun guessCalendarIds(context: Context, now: Long = System.currentTimeMillis()): List<Long> {
        val (begin, end) = currentWeekRange(now)
        val infos = listCalendarsWithCounts(context, begin, end)
        if (infos.isEmpty()) return emptyList()

        val keywords = listOf(CALENDAR_DISPLAY_NAME, "课程", "Course", "Schedule", "Class")
        infos.filter { info -> info.name.isNotBlank() && keywords.any { k -> info.name.contains(k, ignoreCase = true) } }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.map { c -> c.id } }

        infos.maxByOrNull { it.weekCount }
            ?.takeIf { it.weekCount > 0 }
            ?.let { return listOf(it.id) }

        if (infos.size == 1) {
            return listOf(infos.first().id)
        }

        return emptyList()
    }

    /** 统计给定日历在 [begin, end) 内的日程条数(用于验证候选日历是否真的有数据)。 */
    fun countEvents(context: Context, calendarIds: List<Long>, begin: Long, end: Long): Int {
        if (calendarIds.isEmpty() || begin >= end) return 0
        return try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, begin)
            ContentUris.appendId(builder, end)
            val uri = builder.build()
            val projection = arrayOf(CalendarContract.Instances._ID, CalendarContract.Instances.CALENDAR_ID)
            val idList = calendarIds.joinToString(",")
            val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($idList)"
            val cursor = runCatching {
                context.contentResolver.query(uri, projection, selection, null, null)
            }.getOrNull() ?: CalendarContract.Instances.query(context.contentResolver, projection, begin, end)

            cursor?.use { c ->
                val idx = c.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                var n = 0
                while (c.moveToNext()) {
                    if (idx < 0 || calendarIds.contains(c.getLong(idx))) n++
                }
                n
            } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "统计日程条数失败", e)
            0
        }
    }

    /**
     * 列出所有日历,并统计 [begin, end) 内各自的日程条数。
     *
     * 注意:不加 VISIBLE 过滤 —— 用户导入课表的日历有可能在系统日历里是隐藏的,
     * 过滤掉就会“看不到任何日历可选”,反而更难排查。
     */
    fun listCalendarsWithCounts(context: Context, begin: Long, end: Long): List<CalendarInfo> {
        val names = mutableListOf<Pair<Long, String>>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "(未命名日历)"
                    names += cursor.getLong(0) to displayName
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取日历列表被拒绝", e)
            return emptyList()
        }

        val counts = mutableMapOf<Long, Int>()
        try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, begin)
            ContentUris.appendId(builder, end)
            val uri = builder.build()
            val instProj = arrayOf(CalendarContract.Instances.CALENDAR_ID)
            val cursor = runCatching {
                context.contentResolver.query(uri, instProj, null, null, null)
            }.getOrNull() ?: CalendarContract.Instances.query(context.contentResolver, instProj, begin, end)

            cursor?.use { c ->
                val idx = c.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                if (idx >= 0) {
                    while (c.moveToNext()) {
                        val id = c.getLong(idx)
                        counts[id] = (counts[id] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "统计日历条数失败", e)
        }

        return names.map { (id, name) -> CalendarInfo(id, name, counts[id] ?: 0) }
    }

    /** 兼容旧调用:只返回 (id, 显示名)。 */
    fun listCalendars(context: Context): List<Pair<Long, String>> {
        val now = System.currentTimeMillis()
        val (begin, end) = currentWeekRange(now)
        return listCalendarsWithCounts(context, begin, end)
            .map { it.id to it.name }
    }

    /** 取这些日历的显示名,用于在小组件上显示“正在读取哪个日历”。 */
    fun calendarNames(context: Context, ids: List<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val names = mutableListOf<String>()
        val selection = "${CalendarContract.Calendars._ID} IN (${ids.joinToString(",")})"
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                selection, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    names += cursor.getString(0) ?: "(未命名)"
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取日历名被拒绝", e)
        }
        return names
    }

    // ---------------------------------------------------------------- 查询事件

    /** 最近一次查询失败的原因,供界面显示(不要静默失败)。 */
    @Volatile
    var lastQueryError: String? = null
        private set

    data class ScheduleData(
        val today: TodayInfo,
        val week: List<DayInfo>,
        val nextRefreshAt: Long,
    )

    /**
     * 一次性加载今日与本周课表数据，避免高频并发跨进程查询引发 SQLite 忙锁。
     */
    fun loadSchedule(
        context: Context,
        calendarIds: List<Long>,
        now: Long = System.currentTimeMillis(),
    ): ScheduleData {
        val (weekBegin, weekEnd) = currentWeekRange(now)
        val allWeekEvents = queryEvents(context, calendarIds, weekBegin, weekEnd)
            .filter { !it.allDay }

        val dayStart = startOfDay(now)
        val dayEnd = dayStart + DAY_MILLIS
        val todayEvents = allWeekEvents
            .filter { it.begin in dayStart until dayEnd || (it.begin < dayStart && it.end > dayStart) }
            .sortedBy { it.begin }
        val upcoming = todayEvents.filter { it.end > now }
        val todayInfo = TodayInfo(todayEvents, upcoming, todayEvents.size - upcoming.size, lastQueryError)

        val dayBuckets = Array(7) { mutableListOf<ClassEvent>() }
        for (e in allWeekEvents) {
            val c = calendar().apply { timeInMillis = e.begin }
            val dayIdx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一 -> 0 ... 周日 -> 6
            if (dayIdx in 0..6) {
                dayBuckets[dayIdx].add(e)
            }
        }

        val days = (0..6).map { i ->
            val sorted = dayBuckets[i].sortedBy { it.begin }
            DayInfo(
                dayIndex = i,
                dateMillis = weekBegin + i * DAY_MILLIS,
                count = sorted.size,
                firstStart = sorted.firstOrNull()?.begin,
                events = sorted,
            )
        }

        val tomorrow = dayStart + DAY_MILLIS
        val ongoingCourse = todayEvents.firstOrNull { it.begin <= now && now < it.end }
        val nextCourse = upcoming.firstOrNull { it.begin > now }
        val targetTime = when {
            ongoingCourse != null -> ongoingCourse.end + 1000L
            nextCourse != null -> nextCourse.begin + 1000L
            else -> tomorrow + 1000L
        }
        // 关键安全机制：刷新时间必须在当前时间之后（至少 30 秒），严禁过去时间导致的 Alarm 死循环
        val nextRefreshAt = minOf(maxOf(targetTime, now + 30_000L), tomorrow + 5000L)

        return ScheduleData(todayInfo, days, nextRefreshAt)
    }

    /**
     * 查询 [begin, end) 区间内的日程实例，并严格过滤目标日历。
     */
    fun queryEvents(context: Context, calendarIds: List<Long>, begin: Long, end: Long): List<ClassEvent> {
        if (calendarIds.isEmpty() || begin >= end) return emptyList()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val idList = calendarIds.joinToString(",")
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($idList)"

        val out = mutableListOf<ClassEvent>()
        try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, begin)
            ContentUris.appendId(builder, end)
            val uri = builder.build()
            val cursor = runCatching {
                context.contentResolver.query(
                    uri, projection, selection, null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )
            }.getOrNull() ?: CalendarContract.Instances.query(context.contentResolver, projection, begin, end)

            cursor?.use { readInstances(it, out, calendarIds) }
            lastQueryError = null
        } catch (e: SecurityException) {
            lastQueryError = "没有日历读取权限"
            Log.w(TAG, "查询日程被拒绝", e)
        } catch (e: Exception) {
            Log.w(TAG, "instances 查询失败,改用 events 表", e)
            lastQueryError = "${e.javaClass.simpleName}: ${e.message}"
            readFromEventsTable(context, out, selection, begin, end, calendarIds)
        }
        return out
    }

    /** 兜底:不展开重复日程,只按 DTSTART 取区间内的事件。 */
    private fun readFromEventsTable(
        context: Context,
        out: MutableList<ClassEvent>,
        calendarFilter: String,
        begin: Long,
        end: Long,
        calendarIds: List<Long>,
    ) {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
        )
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "$calendarFilter AND ${CalendarContract.Events.DTSTART} >= ?" +
                    " AND ${CalendarContract.Events.DTSTART} < ?",
                arrayOf(begin.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { c ->
                val iId = c.getColumnIndex(CalendarContract.Events._ID)
                val iTitle = c.getColumnIndex(CalendarContract.Events.TITLE)
                val iLoc = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val iStart = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val iEnd = c.getColumnIndex(CalendarContract.Events.DTEND)
                val iAllDay = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val iCal = c.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
                while (c.moveToNext()) {
                    if (iCal >= 0 && !calendarIds.contains(c.getLong(iCal))) continue
                    val title = if (iTitle >= 0) c.getString(iTitle) else null
                    if (TextUtils.isEmpty(title)) continue
                    val start = if (iStart >= 0) c.getLong(iStart) else 0L
                    out += ClassEvent(
                        id = if (iId >= 0) c.getLong(iId) else 0L,
                        title = title!!,
                        location = if (iLoc >= 0) (c.getString(iLoc) ?: "") else "",
                        begin = start,
                        end = if (iEnd >= 0 && !c.isNull(iEnd)) c.getLong(iEnd) else start,
                        allDay = iAllDay >= 0 && c.getInt(iAllDay) == 1,
                    )
                }
            }
        } catch (e: Exception) {
            lastQueryError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "events 表查询也失败", e)
        }
    }

    /** 解析 instances 查询结果并验证 calendarId。 */
    private fun readInstances(
        cursor: android.database.Cursor?,
        out: MutableList<ClassEvent>,
        calendarIds: List<Long>,
    ) {
        if (cursor == null) return
        val iId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val iTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val iLoc = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
        val iBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val iEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
        val iAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        val iCal = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
        if (iTitle < 0 || iBegin < 0) {
            lastQueryError = "日历提供者返回的列不完整"
            return
        }
        while (cursor.moveToNext()) {
            if (iCal >= 0 && !calendarIds.contains(cursor.getLong(iCal))) continue
            val title = cursor.getString(iTitle)
            if (TextUtils.isEmpty(title)) continue
            out += ClassEvent(
                id = if (iId >= 0) cursor.getLong(iId) else 0L,
                title = title!!,
                location = if (iLoc >= 0) (cursor.getString(iLoc) ?: "") else "",
                begin = cursor.getLong(iBegin),
                end = if (iEnd >= 0) cursor.getLong(iEnd) else cursor.getLong(iBegin),
                allDay = iAllDay >= 0 && cursor.getInt(iAllDay) == 1,
            )
        }
    }

    /** 兼容旧调用。 */
    fun today(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): TodayInfo =
        loadSchedule(context, calendarIds, now).today

    /** 兼容旧调用。 */
    fun week(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): List<DayInfo> =
        loadSchedule(context, calendarIds, now).week

    /** 兼容旧调用。 */
    fun nextRefreshAt(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): Long =
        loadSchedule(context, calendarIds, now).nextRefreshAt

    // ---------------------------------------------------------------- 点击跳转

    /** 点击小组件时跳到“某一天”的日历视图。 */
    fun dayViewIntent(dayStartMillis: Long): Intent {
        val millis = dayStartMillis
        val builder = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(millis.toString())
        return Intent(Intent.ACTION_VIEW, builder.build())
            .putExtra("beginTime", millis)
            .putExtra("VIEW", "DAY")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun eventViewIntent(eventId: Long): Intent {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)
        // 兜底:日期时间视图,防止某些日历 App 不处理 Events URI
        intent.putExtra("beginTime", System.currentTimeMillis())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private const val TAG = "ScheduleRepository"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
