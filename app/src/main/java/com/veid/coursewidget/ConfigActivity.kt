package com.veid.coursewidget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 添加小组件(或点击小组件里的提示)时打开的配置页:
 * 让用户勾选"哪几个系统日历是课表"。
 */
class ConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val io = Executors.newSingleThreadExecutor()
    private val boxes = mutableListOf<Pair<Long, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContentView(R.layout.activity_config)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.btn_confirm).setOnClickListener { save() }

        if (!hasPermission()) {
            showPermissionGate()
        } else {
            loadCalendars()
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionGate() {
        findViewById<TextView>(R.id.config_status).visibility = View.VISIBLE
        findViewById<TextView>(R.id.config_status).setText(R.string.config_need_permission)
        findViewById<Button>(R.id.btn_grant).visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_grant).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR), REQ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            findViewById<Button>(R.id.btn_grant).visibility = View.GONE
            findViewById<TextView>(R.id.config_status).visibility = View.GONE
            loadCalendars()
        }
    }

    private fun loadCalendars() {
        val container = findViewById<LinearLayout>(R.id.config_list)
        container.removeAllViews()
        boxes.clear()

        io.execute {
            val (weekBegin, weekEnd) = ScheduleRepository.currentWeekRange()
            // 按本周日程条数从多到少排序:课表那个日历通常会排在最上面,好认
            val calendars = ScheduleRepository
                .listCalendarsWithCounts(this, weekBegin, weekEnd)
                .sortedWith(compareByDescending<ScheduleRepository.CalendarInfo> { it.weekCount }
                    .thenBy { it.name })
            val selected = CourseWidgetProvider.calendarIds(this).toSet()
            runOnUiThread {
                if (calendars.isEmpty()) {
                    findViewById<TextView>(R.id.config_status).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.config_status).setText(R.string.widget_empty_calendar)
                    return@runOnUiThread
                }
                for (info in calendars) {
                    val box = CheckBox(this)
                    // 显示本周条数,方便一眼认出哪个是课表
                    box.text = getString(R.string.config_calendar_item, info.name, info.weekCount)
                    box.id = View.generateViewId()
                    box.isChecked = info.id in selected
                    container.addView(box)
                    boxes += info.id to box
                }
            }
        }
    }

    private fun save() {
        val picked = boxes.filter { it.second.isChecked }.map { it.first }
        if (picked.isEmpty()) {
            // 没勾任何日历:不清空原设置,直接返回
            finish()
            return
        }
        CourseWidgetProvider.saveCalendarIds(this, picked)
        CourseWidgetProvider.refreshNow(this)

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            )
        }
        finish()
    }

    companion object {
        private const val REQ = 1001
    }
}
