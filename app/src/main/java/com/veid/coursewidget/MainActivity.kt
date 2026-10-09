package com.veid.coursewidget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 启动页:申请日历权限 + 提供进入配置页的入口。
 * 小组件本身不需要打开这个 Activity,它只是首次使用的引导。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updateStatus()

        findViewById<Button>(R.id.main_pick).setOnClickListener {
            if (hasPermission()) {
                startActivity(Intent(this, ConfigActivity::class.java))
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR), REQ)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (hasPermission()) CourseWidgetProvider.refreshNow(this)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateStatus() {
        val status = findViewById<TextView>(R.id.main_status)
        status.text = if (hasPermission()) {
            getString(R.string.main_hint)
        } else {
            getString(R.string.config_need_permission)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        updateStatus()
    }

    companion object {
        private const val REQ = 1002
    }
}
