package com.veid.coursewidget

import android.content.ContentUris
import android.content.ContentValues
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

    /**
     * 猜测哪个系统日历是课表:
     * 1) 名称里有“课表”;
     * 2) 否则用名称最短的那个(用户导入 .ics 时通常就是简单命名)。
     */
    fun guessCalendarIds(context: Context): List<Long> {
        val calendars = listCalendars(context)
        if (calendars.isEmpty()) return emptyList()
        val named = calendars.filter { it.second.contains(CALENDAR_DISPLAY_NAME) }
        if (named.isNotEmpty()) return named.map { it.first }
        return listOf(calendars.minByOrNull { it.second.length }!!.first)
    }

    /** 返回 (id, 显示名) 列表,按名称排序。 */
    fun listCalendars(context: Context): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    result += cursor.getLong(0) to (cursor.getString(1) ?: "(未命名日历)")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取日历列表被拒绝", e)
        }
        return result
    }

    // ---------------------------------------------------------------- 查询事件

    private fun queryEvents(context: Context, calendarIds: List<Long>, begin: Long, end: Long): List<ClassEvent> {
        if (calendarIds.isEmpty()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val idList = calendarIds.joinToString(",")
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($idList)" +
            " AND ${CalendarContract.Instances.END} > ?" +
            " AND ${CalendarContract.Instances.BEGIN} < ?"
        val args = arrayOf(begin.toString(), end.toString())

        val out = mutableListOf<ClassEvent>()
        try {
            context.contentResolver.query(
                CalendarContract.Instances.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1) ?: continue
                    if (TextUtils.isEmpty(title)) continue
                    out += ClassEvent(
                        id = c.getLong(0),
                        title = title,
                        location = c.getString(2) ?: "",
                        begin = c.getLong(3),
                        end = c.getLong(4),
                        allDay = c.getInt(5) == 1,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "查询日程被拒绝", e)
        }
        return out
    }

    /** 今天(整天)的课程。allDay 事件视为无效课表数据,直接忽略。 */
    fun today(context: Context, calendarIds: List<Long>, now: Long = System.currentTimeMillis()): TodayInfo {
        val events = queryEvents(context, calendarIds, startOfDay(now), endOfDay(now))
            .filter { !it.allDay }
        val upcoming = events.filter { it.end > now }
        return TodayInfo(events, upcoming, events.size - upcoming.size)
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

    /** 供未来扩展:把一条课程写回日历(当前未启用)。 */
    @Suppress("unused")
    fun insertEvent(context: Context, calendarId: Long, values: ContentValues): Long? {
        return try {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLong()
        } catch (e: SecurityException) {
            null
        }
    }

    private const val TAG = "ScheduleRepository"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
