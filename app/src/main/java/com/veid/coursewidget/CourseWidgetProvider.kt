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
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                setDisplayDayIndex(context, -1) // 跨天时自动切回今天
                refreshAll(context)
            }
            ACTION_SELECT_DAY -> {
                val dayIdx = intent.getIntExtra(EXTRA_DAY_INDEX, -1)
                setDisplayDayIndex(context, dayIdx)
                refreshAll(context)
            }
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.veid.coursewidget.REFRESH"
        const val ACTION_SELECT_DAY = "com.veid.coursewidget.SELECT_DAY"
        const val EXTRA_DAY_INDEX = "day_index"

        private const val TAG = "CourseWidget"
        private const val PREFS = "course_widget"
        /** 用户显式勾选的日历 */
        private const val KEY_SELECTED_IDS = "selected_calendar_ids"
        /** 上次验证过确实有数据的日历(缓存) */
        private const val KEY_RESOLVED_IDS = "resolved_calendar_ids"
        /** 用户在小组件上点选查看的周几下标(-1 为默认看今天, 0..6 为周一到周日) */
        private const val KEY_DISPLAY_DAY = "display_day_index"

        /** 与 widget_course.xml 里静态声明的控件一一对应 */
        private val ROW_IDS = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
        private val ROW_TIME_IDS = intArrayOf(R.id.row1_time, R.id.row2_time, R.id.row3_time, R.id.row4_time)
        private val ROW_NAME_IDS = intArrayOf(R.id.row1_name, R.id.row2_name, R.id.row3_name, R.id.row4_name)
        private val ROW_ROOM_IDS = intArrayOf(R.id.row1_room, R.id.row2_room, R.id.row3_room, R.id.row4_room)
        private val CELL_CONTAINER_IDS = intArrayOf(
            R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4,
            R.id.cell5, R.id.cell6, R.id.cell7,
        )
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
        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingRefreshRunnable: Runnable? = null

        // ------------------------------------------------------------ 配置读写

        /** 用户在配置页显式勾选的日历(最强依据)。 */
        private fun userSelectedIds(context: Context): List<Long>? =
            parseIds(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED_IDS, null))

        /** 上一次成功解析出、且确实有数据的日历(缓存,避免每次刷新都重新猜)。 */
        private fun cachedResolvedIds(context: Context): List<Long>? =
            parseIds(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESOLVED_IDS, null))

        private fun parseIds(raw: String?): List<Long>? = raw
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.takeIf { it.isNotEmpty() && it.all { id -> id > 0 } }

        private fun writeIds(context: Context, key: String, ids: List<Long>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, ids.joinToString(","))
                .apply()
        }

        /** 配置页点"确定"时调用:同时写入用户选择与缓存。 */
        fun saveCalendarIds(context: Context, ids: List<Long>) {
            writeIds(context, KEY_SELECTED_IDS, ids)
            writeIds(context, KEY_RESOLVED_IDS, ids)
        }

        fun getDisplayDayIndex(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DISPLAY_DAY, -1)

        fun setDisplayDayIndex(context: Context, dayIndex: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DISPLAY_DAY, dayIndex)
                .apply()
        }

        /**
         * 解析出"这次到底该读哪些日历"。
         *
         * 1. 若用户在配置页手动选择过日历，拥有最高且绝对的优先级，直接采用（绝不因本周暂无课而丢弃）。
         * 2. 若有历史成功解析缓存，优先采用缓存。
         * 3. 首次启动或无缓存时，通过名称匹配与日程条数自动猜测，猜中后立即写入缓存。
         */
        fun resolveCalendarIds(context: Context, now: Long = System.currentTimeMillis()): List<Long> {
            val user = userSelectedIds(context)
            if (!user.isNullOrEmpty()) {
                return user
            }

            val cached = cachedResolvedIds(context)
            val guessed = ScheduleRepository.guessCalendarIds(context, now)

            val (begin, end) = ScheduleRepository.currentWeekRange(now)
            val candidates = listOfNotNull(cached, guessed.takeIf { it.isNotEmpty() }).distinct()

            for (candidate in candidates) {
                if (ScheduleRepository.countEvents(context, candidate, begin, end) > 0) {
                    writeIds(context, KEY_RESOLVED_IDS, candidate)
                    return candidate
                }
            }

            // 若本周恰逢无课(如假期)，依然保留已有缓存或猜测结果
            val fallback = cached ?: guessed
            if (fallback.isNotEmpty()) {
                writeIds(context, KEY_RESOLVED_IDS, fallback)
            }
            return fallback
        }

        /** 兼容旧调用。 */
        fun calendarIds(context: Context): List<Long> = resolveCalendarIds(context)

        // ------------------------------------------------------------ 刷新入口

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CourseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            io.execute {
                var fallbackError: Throwable? = null
                var mainViews: RemoteViews? = null
                var next = System.currentTimeMillis() + 30 * 60 * 1000L
                try {
                    val calendarIds = calendarIds(context)
                    val now = System.currentTimeMillis()
                    // 一次性加载今日与本周课表，杜绝高频并发 IPC 数据库查询导致锁死
                    val scheduleData = ScheduleRepository.loadSchedule(context, calendarIds, now)
                    next = scheduleData.nextRefreshAt
                    val names = ScheduleRepository.calendarNames(context, calendarIds)
                    mainViews = buildViews(context, scheduleData.today, scheduleData.week, calendarIds, names)
                } catch (e: Exception) {
                    Log.e(TAG, "构建小组件内容失败", e)
                    fallbackError = e
                }

                val views = mainViews ?: runCatching { buildFallback(context, fallbackError!!) }
                    .getOrElse { RemoteViews(context.packageName, R.layout.widget_fallback) }

                val refreshAt = next
                mainHandler.post {
                    for (id in ids) {
                        try {
                            manager.updateAppWidget(id, views)
                        } catch (e: Exception) {
                            Log.e(TAG, "更新小组件失败", e)
                        }
                    }
                    scheduleRefresh(context, refreshAt)
                }
            }
        }

        fun refreshNow(context: Context) = refreshAll(context)

        // ------------------------------------------------------------ 小工具

        /**
         * 决定"今日课程"里显示哪几条:
         * 按时间显示今天全部课程(含已结束的,带勾号和"已结束"字样);
         * 若超过行数上限,优先保留还没结束的课,再用最近的已结束课补齐。
         */
        private fun pickTodayRows(
            all: List<ScheduleRepository.ClassEvent>,
            now: Long,
        ): List<ScheduleRepository.ClassEvent> {
            val limit = ROW_IDS.size
            if (all.size <= limit) return all
            val upcoming = all.filter { it.end > now }
            val finished = all.filter { it.end <= now }
            val keepFinished = maxOf(0, limit - upcoming.size)
            return (finished.takeLast(keepFinished) + upcoming)
                .sortedBy { it.begin }
                .take(limit)
        }

        /**
         * 兜底界面:只有一个 TextView。
         *
         * 存在的意义:如果正常布局因为任何原因构建失败,桌面会显示 "Can't load widget",
         * 用户完全看不到原因。用这个最简布局把错误文字显示出来,才能定位问题。
         */
        private fun buildFallback(context: Context, error: Throwable): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_fallback)
            val detail = error.javaClass.simpleName + ": " + (error.message ?: "未知错误")
            views.setTextViewText(R.id.fallback_text, context.getString(R.string.widget_build_failed, detail))
            return views
        }

        // ------------------------------------------------------------ 渲染

        private fun buildViews(
            context: Context,
            today: ScheduleRepository.TodayInfo,
            week: List<ScheduleRepository.DayInfo>,
            calendarIds: List<Long>,
            calendarNames: List<String>,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_course)

            val now = System.currentTimeMillis()
            val todayIndex = ScheduleRepository.todayIndex(now)
            val activeDayIndex = getDisplayDayIndex(context)
            val isViewingToday = (activeDayIndex == -1 || activeDayIndex == todayIndex)
            val effectiveDayIndex = if (isViewingToday) todayIndex else activeDayIndex
            val selectedDayInfo = week.getOrNull(effectiveDayIndex)

            val noCalendar = calendarIds.isEmpty()
            val weekHasData = week.any { it.count > 0 }

            if (isViewingToday) {
                views.setTextViewText(R.id.header_title, context.getString(R.string.widget_title))
                views.setTextViewText(
                    R.id.header_badge,
                    ScheduleRepository.dateLabel(now) + " · " + ScheduleRepository.timeLabel(now),
                )
                views.setOnClickPendingIntent(
                    R.id.header_title,
                    PendingIntent.getActivity(
                        context, 100,
                        ScheduleRepository.dayViewIntent(now),
                        pendingFlags(),
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.header_badge,
                    PendingIntent.getActivity(
                        context, 101,
                        ScheduleRepository.dayViewIntent(now),
                        pendingFlags(),
                    ),
                )
            } else {
                val dayLabel = ScheduleRepository.dayIndexLabel(effectiveDayIndex)
                val dayDate = selectedDayInfo?.dateMillis ?: now
                views.setTextViewText(
                    R.id.header_title,
                    "${dayLabel}课表 (点此回今日)",
                )
                views.setTextViewText(
                    R.id.header_badge,
                    ScheduleRepository.dateLabel(dayDate) + " · 共 ${selectedDayInfo?.count ?: 0} 节课",
                )
                val resetIntent = Intent(context, CourseWidgetProvider::class.java).apply {
                    action = ACTION_SELECT_DAY
                    putExtra(EXTRA_DAY_INDEX, -1)
                }
                val resetPending = PendingIntent.getBroadcast(context, 110, resetIntent, pendingFlags())
                views.setOnClickPendingIntent(R.id.header_title, resetPending)
                views.setOnClickPendingIntent(R.id.header_badge, resetPending)
            }

            // ---- 右上角"周课表"按钮 -> 打开全周课表全景页 ----
            val weekIntent = Intent(context, WeekScheduleActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.btn_week_view,
                PendingIntent.getActivity(context, 200, weekIntent, pendingFlags()),
            )

            // ---- 决定当前展示哪些课程 ----
            val display: List<ScheduleRepository.ClassEvent> = if (isViewingToday) {
                pickTodayRows(today.all, now)
            } else {
                (selectedDayInfo?.events ?: emptyList()).take(ROW_IDS.size)
            }

            // ---- 课程行渲染:逐行设文本与可见性,不用 addView ----
            for (i in ROW_IDS.indices) {
                val event = display.getOrNull(i)
                if (event == null) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                    continue
                }
                val finished = isViewingToday && (event.end <= now)
                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(
                    ROW_TIME_IDS[i],
                    ScheduleRepository.timeLabel(event.begin) + "\n" + ScheduleRepository.timeLabel(event.end),
                )
                views.setTextViewText(
                    ROW_NAME_IDS[i],
                    if (finished) context.getString(R.string.widget_done_mark) + event.title else event.title,
                )
                val roomText = when {
                    !finished -> event.location
                    event.location.isBlank() -> context.getString(R.string.widget_finished)
                    else -> context.getString(R.string.widget_finished) + " · " + event.location
                }
                if (roomText.isBlank()) {
                    views.setViewVisibility(ROW_ROOM_IDS[i], View.GONE)
                } else {
                    views.setViewVisibility(ROW_ROOM_IDS[i], View.VISIBLE)
                    views.setTextViewText(ROW_ROOM_IDS[i], roomText)
                }
            }

            // ---- 空状态 / 错误提示 ----
            val message: String? = when {
                noCalendar -> context.getString(R.string.widget_need_config)
                isViewingToday && today.error != null && today.all.isEmpty() ->
                    context.getString(R.string.widget_query_failed_fmt, today.error)
                display.isNotEmpty() -> null
                isViewingToday && !weekHasData -> if (calendarNames.isNotEmpty()) {
                    context.getString(R.string.widget_no_data_fmt, calendarNames.joinToString("、"))
                } else {
                    context.getString(R.string.widget_need_config)
                }
                isViewingToday -> context.getString(R.string.widget_no_class)
                else -> "${ScheduleRepository.dayIndexLabel(effectiveDayIndex)}暂无课程安排"
            }
            if (message != null) {
                views.setViewVisibility(R.id.today_empty, View.VISIBLE)
                views.setTextViewText(R.id.today_empty, message)
            } else {
                views.setViewVisibility(R.id.today_empty, View.GONE)
            }

            // ---- 本周概览 (7 格点击切换) ----
            for (i in 0..6) {
                val info = week.getOrNull(i) ?: ScheduleRepository.DayInfo(dayIndex = i)
                val isCurrentToday = (i == todayIndex)
                val isSelected = (effectiveDayIndex == i)

                val dayText = when {
                    isSelected && isCurrentToday -> "[今]"
                    isSelected -> "[${DAY_LABELS[i]}]"
                    isCurrentToday -> "◉"
                    else -> DAY_LABELS[i]
                }

                views.setTextViewText(CELL_DAY_IDS[i], dayText)
                views.setTextViewText(CELL_COUNT_IDS[i], if (info.count == 0) "·" else info.count.toString())

                // 绑定点击事件: 点击该格子切换小组件展示该天课程
                val dayClickIntent = Intent(context, CourseWidgetProvider::class.java).apply {
                    action = ACTION_SELECT_DAY
                    putExtra(EXTRA_DAY_INDEX, i)
                }
                val cellPending = PendingIntent.getBroadcast(
                    context,
                    1000 + i,
                    dayClickIntent,
                    pendingFlags(),
                )
                views.setOnClickPendingIntent(CELL_CONTAINER_IDS[i], cellPending)
            }

            return views
        }

        // ------------------------------------------------------------ 定时刷新

        private fun pendingFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun scheduleRefresh(context: Context, atMillis: Long) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CourseWidgetProvider::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(context, 0, intent, pendingFlags())

            // 严格确保刷新时间位于未来，严禁设置过去时间引发 AlarmManager 即时自激死循环
            val safeAt = maxOf(atMillis, System.currentTimeMillis() + 30_000L)

            val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarm.canScheduleExactAlarms()
            } else {
                true
            }
            if (canBeExact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeAt, pending)
            } else {
                alarm.set(AlarmManager.RTC, safeAt, pending)
            }
        }

        private fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CourseWidgetProvider::class.java).setAction(ACTION_REFRESH)
            alarm.cancel(PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
        }

        // ------------------------------------------------------------ 数据变化监听

        private fun registerObserver(context: Context) {
            if (observer != null) return
            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    // 2 秒防抖，严禁日历数据库高频变动引发雪崩自激刷新
                    pendingRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable { refreshAll(context.applicationContext) }
                    pendingRefreshRunnable = r
                    mainHandler.postDelayed(r, 2000L)
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
            pendingRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRefreshRunnable = null
        }

        private val DAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")
    }
}
