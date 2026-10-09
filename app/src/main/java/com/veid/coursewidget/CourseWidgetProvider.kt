package com.veid.coursewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/**
 * 小组件本体。
 *
 * 职责:
 *  1. 从 ScheduleRepository 取“今日课程 + 本周概览”,渲染进 RemoteViews;
 *  2. 在下一节课开始/明天 0 点时自动刷新;
 *  3. 监听日历数据变化(重新导入 .ics 后立即刷新)。
 */
class CourseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
        registerObserver(context)
    }

    override fun onEnabled(context: Context) {
        registerObserver(context)
    }

    override fun onDisabled(context: Context) {
        unregisterObserver(context)
        cancelRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.veid.coursewidget.REFRESH"

        private const val PREFS = "course_widget"
        private const val KEY_CALENDAR_IDS = "calendar_ids"

        private val io = Executors.newSingleThreadExecutor()
        private var observer: ContentObserver? = null

        // ------------------------------------------------------------ 配置读写

        fun saveCalendarIds(context: Context, ids: List<Long>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CALENDAR_IDS, ids.joinToString(","))
                .apply()
        }

        /** 返回用户选定的日历;没选过就自动猜一个(名称含“课表”或名称最短的)。 */
        fun calendarIds(context: Context): List<Long> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CALENDAR_IDS, null)
            val parsed = raw?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?.takeIf { it.isNotEmpty() }
            return parsed ?: ScheduleRepository.guessCalendarIds(context)
        }

        fun hasExplicitSelection(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CALENDAR_IDS, null) != null

        // ------------------------------------------------------------ 刷新入口

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CourseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            io.execute {
                val calendarIds = calendarIds(context)
                val now = System.currentTimeMillis()
                val today = ScheduleRepository.today(context, calendarIds, now)
                val week = ScheduleRepository.week(context, calendarIds, now)
                val next = ScheduleRepository.nextRefreshAt(context, calendarIds, now)
                Handler(Looper.getMainLooper()).post {
                    for (id in ids) {
                        manager.updateAppWidget(id, buildViews(context, today, week, calendarIds))
                    }
                    scheduleRefresh(context, next)
                }
            }
        }

        /** 用户在配置页保存后立即生效。 */
        fun refreshNow(context: Context) {
            refreshAll(context)
        }

        // ------------------------------------------------------------ 渲染

        private fun buildViews(
            context: Context,
            today: ScheduleRepository.TodayInfo,
            week: List<ScheduleRepository.DayInfo>,
            calendarIds: List<Long>,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_course)

            views.setTextViewText(R.id.header_title, context.getString(R.string.widget_title))
            views.setTextViewText(
                R.id.header_badge,
                "${ScheduleRepository.dateLabel(System.currentTimeMillis())} · " +
                    ScheduleRepository.timeLabel(System.currentTimeMillis()),
            )

            views.removeAllViews(R.id.today_list)
            views.removeAllViews(R.id.week_row)

            val noPermission = calendarIds.isEmpty()
            val upcoming = today.upcoming.take(ScheduleRepository.MAX_TODAY_ROWS)

            if (noPermission) {
                views.setViewVisibility(R.id.today_list, View.GONE)
                views.setViewVisibility(R.id.today_empty, View.VISIBLE)
                views.setTextViewText(R.id.today_empty, context.getString(R.string.widget_need_config))
            } else if (today.error != null && today.all.isEmpty()) {
                // 查询失败且无数据:明确告知原因,不要伪装成“今天没课”
                views.setViewVisibility(R.id.today_list, View.GONE)
                views.setViewVisibility(R.id.today_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.today_empty,
                    context.getString(R.string.widget_query_failed_fmt, today.error),
                )
            } else if (upcoming.isEmpty()) {
                views.setViewVisibility(R.id.today_list, View.GONE)
                views.setViewVisibility(R.id.today_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.today_empty,
                    if (today.all.isEmpty()) context.getString(R.string.widget_no_class)
                    else context.getString(R.string.widget_all_done),
                )
            } else {
                views.setViewVisibility(R.id.today_empty, View.GONE)
                views.setViewVisibility(R.id.today_list, View.VISIBLE)
                for (event in upcoming) {
                    views.addView(R.id.today_list, buildRow(context, event))
                }
            }

            for (index in 0..6) {
                views.addView(R.id.week_row, buildCell(context, index, week[index]))
            }

            // 点顶部标题 -> 打开今天的日历日视图
            views.setOnClickPendingIntent(
                R.id.header_title,
                PendingIntent.getActivity(
                    context, 100,
                    ScheduleRepository.dayViewIntent(System.currentTimeMillis()),
                    pendingFlags(),
                ),
            )
            return views
        }

        private fun buildRow(context: Context, event: ScheduleRepository.ClassEvent): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row_class)
            row.setTextViewText(R.id.row_time, ScheduleRepository.timeLabel(event.begin))
            row.setTextViewText(R.id.row_end, ScheduleRepository.timeLabel(event.end))
            row.setTextViewText(R.id.row_name, event.title)
            row.setTextViewText(R.id.row_room, event.location.ifBlank { "" })
            row.setViewVisibility(
                R.id.row_room,
                if (event.location.isBlank()) View.GONE else View.VISIBLE,
            )
            return row
        }

        private fun buildCell(context: Context, index: Int, info: ScheduleRepository.DayInfo): RemoteViews {
            val cell = RemoteViews(context.packageName, R.layout.widget_week_cell)
            val isToday = index == ScheduleRepository.todayIndex()
            cell.setTextViewText(R.id.cell_day, DAY_LABELS[index])
            cell.setTextViewText(
                R.id.cell_count,
                if (info.count == 0) "—" else info.count.toString(),
            )
            @Suppress("DEPRECATION")
            cell.setTextColor(R.id.cell_day, color(context, if (isToday) R.color.accent else R.color.text_secondary))
            @Suppress("DEPRECATION")
            cell.setTextColor(
                R.id.cell_count,
                color(
                    context,
                    when {
                        isToday -> R.color.accent
                        info.count == 0 -> R.color.text_secondary
                        else -> R.color.text_primary
                    },
                ),
            )
            if (isToday) {
                cell.setInt(R.id.cell_root, "setBackgroundResource", R.drawable.cell_today_bg)
            } else {
                cell.setInt(R.id.cell_root, "setBackgroundResource", android.R.color.transparent)
            }
            return cell
        }

        @Suppress("DEPRECATION")
        private fun color(context: Context, resId: Int): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.resources.getColor(resId, context.theme)
            } else {
                context.resources.getColor(resId)
            }

        // ------------------------------------------------------------ 定时刷新

        private fun pendingFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

        private fun scheduleRefresh(context: Context, atMillis: Long) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CourseWidgetProvider::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(context, 0, intent, pendingFlags())

            val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarm.canScheduleExactAlarms()
            } else {
                true
            }

            if (canBeExact) {
                // 精确到"下一节课开始";低电量下系统可能延后,所以加一个宽松兜底
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                // 没有精确闹钟权限(国产 ROM 常见),退化为不精确刷新,最多晚几分钟
                alarm.set(AlarmManager.RTC, atMillis, pending)
            }
            // 兜底:无论精确与否,5 分钟后都再刷一次
            alarm.set(AlarmManager.RTC, atMillis + 5 * 60 * 1000, pending)
        }

        private fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CourseWidgetProvider::class.java).setAction(ACTION_REFRESH)
            alarm.cancel(PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
        }

        // ------------------------------------------------------------ 数据变化监听

        private fun registerObserver(context: Context) {
            if (observer != null) return
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refreshAll(context.applicationContext)
                }
            }
            try {
                context.contentResolver.registerContentObserver(
                    android.provider.CalendarContract.CONTENT_URI, true, obs,
                )
                observer = obs
            } catch (e: SecurityException) {
                // 没权限时静默跳过,等用户授权后 onUpdate 会再试
            }
        }

        private fun unregisterObserver(context: Context) {
            observer?.let {
                try {
                    context.contentResolver.unregisterContentObserver(it)
                } catch (_: Exception) {
                }
            }
            observer = null
        }

        private val DAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")
    }
}
