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
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/**
 * 小组件本体。
 *
 * 渲染策略(重要,别改回动态构建):
 *  所有行与格子都在 widget_course.xml 里静态声明,这里只用
 *  setTextViewText / setViewVisibility 两个 API。
 *  不用 RemoteViews.addView() —— MIUI 等桌面宿主对它的支持很差,
 *  会直接导致桌面显示 "Can't load widget"。
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

        private const val TAG = "CourseWidget"
        private const val PREFS = "course_widget"
        private const val KEY_CALENDAR_IDS = "calendar_ids"

        /** 与 widget_course.xml 里静态声明的控件一一对应 */
        private val ROW_IDS = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
        private val ROW_TIME_IDS = intArrayOf(R.id.row1_time, R.id.row2_time, R.id.row3_time, R.id.row4_time)
        private val ROW_NAME_IDS = intArrayOf(R.id.row1_name, R.id.row2_name, R.id.row3_name, R.id.row4_name)
        private val ROW_ROOM_IDS = intArrayOf(R.id.row1_room, R.id.row2_room, R.id.row3_room, R.id.row4_room)
        private val CELL_DAY_IDS = intArrayOf(
            R.id.cell1_day, R.id.cell2_day, R.id.cell3_day, R.id.cell4_day,
            R.id.cell5_day, R.id.cell6_day, R.id.cell7_day,
        )
        private val CELL_COUNT_IDS = intArrayOf(
            R.id.cell1_count, R.id.cell2_count, R.id.cell3_count, R.id.cell4_count,
            R.id.cell5_count, R.id.cell6_count, R.id.cell7_count,
        )

        private val io = Executors.newSingleThreadExecutor()
        private var observer: ContentObserver? = null

        // ------------------------------------------------------------ 配置读写

        fun saveCalendarIds(context: Context, ids: List<Long>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CALENDAR_IDS, ids.joinToString(","))
                .apply()
        }

        /** 返回用户选定的日历;没选过就自动猜一个(名称含"课表"或名称最短的)。 */
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
                try {
                    val calendarIds = calendarIds(context)
                    val now = System.currentTimeMillis()
                    val today = ScheduleRepository.today(context, calendarIds, now)
                    val week = ScheduleRepository.week(context, calendarIds, now)
                    val next = ScheduleRepository.nextRefreshAt(context, calendarIds, now)
                    Handler(Looper.getMainLooper()).post {
                        for (id in ids) {
                            try {
                                manager.updateAppWidget(id, buildViews(context, today, week, calendarIds))
                            } catch (e: Exception) {
                                Log.e(TAG, "更新小组件失败", e)
                            }
                        }
                        scheduleRefresh(context, next)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "刷新失败", e)
                }
            }
        }

        fun refreshNow(context: Context) = refreshAll(context)

        // ------------------------------------------------------------ 渲染

        private fun buildViews(
            context: Context,
            today: ScheduleRepository.TodayInfo,
            week: List<ScheduleRepository.DayInfo>,
            calendarIds: List<Long>,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_course)

            val now = System.currentTimeMillis()
            views.setTextViewText(R.id.header_title, context.getString(R.string.widget_title))
            views.setTextViewText(
                R.id.header_badge,
                ScheduleRepository.dateLabel(now) + " · " + ScheduleRepository.timeLabel(now),
            )

            val noCalendar = calendarIds.isEmpty()
            val upcoming = today.upcoming.take(ROW_IDS.size)

            // ---- 今日课程行:逐行设文本与可见性,不用 addView ----
            for (i in ROW_IDS.indices) {
                val event = upcoming.getOrNull(i)
                if (event == null) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                    continue
                }
                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(
                    ROW_TIME_IDS[i],
                    ScheduleRepository.timeLabel(event.begin) + "\n" + ScheduleRepository.timeLabel(event.end),
                )
                views.setTextViewText(ROW_NAME_IDS[i], event.title)
                if (event.location.isBlank()) {
                    views.setViewVisibility(ROW_ROOM_IDS[i], View.GONE)
                } else {
                    views.setViewVisibility(ROW_ROOM_IDS[i], View.VISIBLE)
                    views.setTextViewText(ROW_ROOM_IDS[i], event.location)
                }
            }

            // ---- 空状态 / 错误提示 ----
            val message: String? = when {
                noCalendar -> context.getString(R.string.widget_need_config)
                today.error != null && today.all.isEmpty() ->
                    context.getString(R.string.widget_query_failed_fmt, today.error)
                today.all.isEmpty() -> context.getString(R.string.widget_no_class)
                upcoming.isEmpty() -> context.getString(R.string.widget_all_done)
                else -> null
            }
            if (message != null) {
                views.setViewVisibility(R.id.today_empty, View.VISIBLE)
                views.setTextViewText(R.id.today_empty, message)
            } else {
                views.setViewVisibility(R.id.today_empty, View.GONE)
            }

            // ---- 本周概览 ----
            for (i in 0..6) {
                val info = week.getOrNull(i) ?: ScheduleRepository.DayInfo(0, null)
                views.setTextViewText(CELL_DAY_IDS[i], DAY_LABELS[i])
                views.setTextViewText(CELL_COUNT_IDS[i], if (info.count == 0) "—" else info.count.toString())
            }

            // 点标题 -> 打开今天的日历日视图
            views.setOnClickPendingIntent(
                R.id.header_title,
                PendingIntent.getActivity(
                    context, 100,
                    ScheduleRepository.dayViewIntent(now),
                    pendingFlags(),
                ),
            )
            return views
        }

        // ------------------------------------------------------------ 定时刷新

        private fun pendingFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

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
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarm.set(AlarmManager.RTC, atMillis, pending)
            }
            // 兜底:5 分钟后再刷一次
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
                // 没权限时静默跳过,授权后 onUpdate 会再试
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
