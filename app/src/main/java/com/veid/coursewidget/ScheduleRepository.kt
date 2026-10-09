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

    /** 一周里的某一天:课程数 + 当天第一节课的开始时间(没有课则为 null)。 */
    data class DayInfo(val count: Int, val firstStart: Long?)

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
     * 1) 名称里含“课表”;
     * 2) 本周日程条数最多的那个(最可靠 —— 课表是一堆密集的定时日程);
     * 3) 名称最短的(导入的日历通常命名简单)。
     *
     * 早期版本只用 1) 和 3),在用户把课表导入到别的名字的日历时会猜错,
     * 导致小组件一直显示“今天没课”。加入条数启发式后基本不会错。
     */
    fun guessCalendarIds(context: Context, now: Long = System.currentTimeMillis()): List<Long> {
        val weekStart = startOfWeek(now)
        val infos = listCalendarsWithCounts(context, weekStart, weekStart + 7 * DAY_MILLIS)
        if (infos.isEmpty()) return emptyList()

        infos.filter { it.name.contains(CALENDAR_DISPLAY_NAME) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.map { c -> c.id } }

        infos.maxByOrNull { it.weekCount }
            ?.takeIf { it.weekCount > 0 }
            ?.let { return listOf(it.id) }

        return listOf(infos.minByOrNull { it.name.length }!!.id)
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
                    names += cursor.getLong(0) to (cursor.getString(1) ?: "(未命名日历)")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取日历列表被拒绝", e)
            return emptyList()
        }

        // 一次查询统计本周各日历的条数,避免逐个日历查询
        val counts = mutableMapOf<Long, Int>()
        try {
            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                arrayOf(CalendarContract.Instances.CALENDAR_ID),
                begin,
                end,
            )
            cursor.use { c ->
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
        val weekStart = startOfWeek(now)
        return listCalendarsWithCounts(context, weekStart, weekStart + 7 * DAY_MILLIS)
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

    /**
     * 查询 [begin, end) 区间内的日程实例。
     *
     * 注意:必须使用官方 helper [CalendarContract.Instances.query],它会拼出
     * `content://com.android.calendar/instances/when/{begin}/{end}`。
     * 直接用基址 [CalendarContract.Instances.CONTENT_URI] 查询会被日历提供者拒绝,
     * 报 "Unknown URL .../instances/when"(缺少 begin/end 两段)。
     */
    private fun queryEvents(context: Context, calendarIds: List<Long>, begin: Long, end: Long): List<ClassEvent> {
        if (calendarIds.isEmpty() || begin >= end) return emptyList()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val idList = calendarIds.joinToString(",")
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($idList)"

        val out = mutableListOf<ClassEvent>()
        try {
            // 官方 helper:内部构造 instances/when/{begin}/{end}
            val cursor = CalendarContract.Instances.query(context.contentResolver, projection, begin, end)
            cursor.use { readInstances(it, out) }
            lastQueryError = null
        } catch (e: SecurityException) {
            lastQueryError = "没有日历读取权限"
            Log.w(TAG, "查询日程被拒绝", e)
        } catch (e: Exception) {
            // 某些第三方日历提供者对 instances 的实现不完整,退化为直接查 Events 表
            Log.w(TAG, "instances 查询失败,改用 events 表", e)
            lastQueryError = "${e.javaClass.simpleName}: ${e.message}"
            readFromEventsTable(context, out, selection, begin, end)
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
    ) {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
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
                while (c.moveToNext()) {
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

    /** 解析 instances 查询结果。列名用 Instances 的常量。 */
    private fun readInstances(
        cursor: android.database.Cursor?,
        out: MutableList<ClassEvent>,
    ) {
        if (cursor == null) return
        val iId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val iTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val iLoc = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
        val iBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val iEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
        val iAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        if (iTitle < 0 || iBegin < 0) {
            lastQueryError = "日历提供者返回的列不完整"
            return
        }
        while (cursor.moveToNext()) {
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

    /** 今天(整天)的课程。allDay 事件视为无效课表数据,直接忽略。 */
    fun today(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): TodayInfo {
        val dayStart = startOfDay(now)
        val events = queryEvents(context, calendarIds, dayStart, dayStart + DAY_MILLIS)
            .filter { !it.allDay }
        val upcoming = events.filter { it.end > now }
        return TodayInfo(events, upcoming, events.size - upcoming.size, lastQueryError)
    }

    /** 本周概览:从周一到周日每天的课程数。 */
    fun week(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): List<DayInfo> {
        val weekStart = startOfWeek(now)
        val events = queryEvents(context, calendarIds, weekStart, weekStart + 7 * DAY_MILLIS)
            .filter { !it.allDay }

        val days = MutableList(7) { DayInfo(0, null) }
        for (e in events) {
            val shift = ((startOfDay(e.begin) - weekStart) / DAY_MILLIS).toInt()
            if (shift !in 0..6) continue
            val old = days[shift]
            days[shift] = DayInfo(
                count = old.count + 1,
                firstStart = old.firstStart?.let { minOf(it, e.begin) } ?: e.begin,
            )
        }
        return days
    }

    /** 下一次需要刷新小组件的时刻:下一节课开始,或明天 0 点。 */
    fun nextRefreshAt(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): Long {
        val tomorrow = startOfDay(now) + DAY_MILLIS
        val info = today(context, calendarIds, now)
        val nextStart = info.upcoming.firstOrNull()?.begin ?: tomorrow
        return minOf(nextStart + 1000, tomorrow)
    }

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
