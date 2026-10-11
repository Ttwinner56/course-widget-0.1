package com.veid.coursewidget

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 本周课表全景视图 Activity:
 * 直观完整地按天（周一至周日）展示整周每天的全部具体课程、时间与教室。
 */
class WeekScheduleActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_week_schedule)

        if (!hasPermission()) {
            showEmpty("需要日历读取权限才能查看课表")
            return
        }

        loadWeekSchedule()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun showEmpty(message: String) {
        findViewById<ProgressBar>(R.id.week_loading).visibility = View.GONE
        findViewById<TextView>(R.id.week_empty_msg).apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    private fun loadWeekSchedule() {
        val loading = findViewById<ProgressBar>(R.id.week_loading)
        val container = findViewById<LinearLayout>(R.id.week_container)
        val subtitle = findViewById<TextView>(R.id.week_subtitle)
        loading.visibility = View.VISIBLE
        container.removeAllViews()

        io.execute {
            val now = System.currentTimeMillis()
            val calendarIds = CourseWidgetProvider.calendarIds(this)
            val scheduleData = ScheduleRepository.loadSchedule(this, calendarIds, now)
            val todayIdx = ScheduleRepository.todayIndex(now)
            val (weekBegin, weekEnd) = ScheduleRepository.currentWeekRange(now)

            val weekRangeStr = ScheduleRepository.dateLabel(weekBegin) + " - " + ScheduleRepository.dateLabel(weekEnd - 1)

            runOnUiThread {
                loading.visibility = View.GONE
                subtitle.text = weekRangeStr

                val totalWeekCourses = scheduleData.week.sumOf { it.count }
                if (totalWeekCourses == 0) {
                    showEmpty("本周暂无课程安排\n$weekRangeStr")
                    return@runOnUiThread
                }

                val dp8 = (8 * resources.displayMetrics.density).toInt()
                val dp12 = (12 * resources.displayMetrics.density).toInt()
                val dp16 = (16 * resources.displayMetrics.density).toInt()

                for (dayInfo in scheduleData.week) {
                    val isToday = (dayInfo.dayIndex == todayIdx)
                    val dayHeader = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp16, 0, dp8)
                    }

                    val titleTv = TextView(this).apply {
                        val prefix = if (isToday) "● [今天] " else ""
                        text = "$prefix${ScheduleRepository.dayIndexLabel(dayInfo.dayIndex)} (${ScheduleRepository.dateLabel(dayInfo.dateMillis)})"
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isToday) ContextCompat.getColor(context, R.color.accent) else ContextCompat.getColor(context, R.color.text_primary))
                    }
                    val countTv = TextView(this).apply {
                        text = if (dayInfo.count > 0) "共 ${dayInfo.count} 节" else "无课"
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        setPadding(dp12, 0, 0, 0)
                    }
                    dayHeader.addView(titleTv)
                    dayHeader.addView(countTv)
                    container.addView(dayHeader)

                    if (dayInfo.events.isEmpty()) {
                        val emptyTv = TextView(this).apply {
                            text = "无课程"
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                            setPadding(dp16, dp8, dp16, dp8)
                            setBackgroundResource(R.drawable.row_bg)
                        }
                        container.addView(emptyTv)
                    } else {
                        for (e in dayInfo.events) {
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setBackgroundResource(R.drawable.row_bg)
                                setPadding(dp12, dp8, dp12, dp8)
                                val params = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    topMargin = (4 * resources.displayMetrics.density).toInt()
                                }
                                layoutParams = params
                                setOnClickListener {
                                    startActivity(ScheduleRepository.eventViewIntent(e.id))
                                }
                            }

                            val timeTv = TextView(this).apply {
                                text = "${ScheduleRepository.timeLabel(e.begin)}\n${ScheduleRepository.timeLabel(e.end)}"
                                textSize = 13f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(ContextCompat.getColor(context, R.color.accent))
                                minWidth = (55 * resources.displayMetrics.density).toInt()
                            }

                            val infoLayout = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                setPadding(dp8, 0, 0, 0)
                            }

                            val nameTv = TextView(this).apply {
                                text = e.title
                                textSize = 15f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            }
                            infoLayout.addView(nameTv)

                            if (e.location.isNotBlank()) {
                                val roomTv = TextView(this).apply {
                                    text = "📍 ${e.location}"
                                    textSize = 12f
                                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                                }
                                infoLayout.addView(roomTv)
                            }

                            card.addView(timeTv)
                            card.addView(infoLayout)
                            container.addView(card)
                        }
                    }
                }
            }
        }
    }
}
