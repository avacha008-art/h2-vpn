package com.v2ray.ang.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityPanelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PanelActivity : BaseActivity() {
    private val binding by lazy { ActivityPanelBinding.inflate(layoutInflater) }

    private val serviceNames = mapOf(
        "x-ui" to "VPN (x-ui + xray)",
        "h2" to "H2+ Messenger",
        "echobot" to "Echo Bot",
        "nginx" to "Nginx",
        "vpnstats" to "VPN Stats",
        "speedserver" to "Speed Server"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "\u041F\u0430\u043D\u0435\u043B\u044C")

        binding.tabServer.setOnClickListener { switchTab(0) }
        binding.tabH2.setOnClickListener { switchTab(1) }
        binding.tabLog.setOnClickListener { switchTab(2) }
        binding.btnRefresh.setOnClickListener { loadData() }
        binding.btnRefreshH2.setOnClickListener { loadData() }
        binding.btnRefreshLog.setOnClickListener { loadData() }

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                val dx = (e2.x) - (e1?.x ?: 0f)
                if (Math.abs(dx) > 100 && Math.abs(vX) > 200) {
                    val cur = binding.flipper.displayedChild
                    if (dx < 0 && cur < 2) switchTab(cur + 1)
                    else if (dx > 0 && cur > 0) switchTab(cur - 1)
                    return true
                }
                return false
            }
        })
        binding.flipper.setOnTouchListener { _, event -> gesture.onTouchEvent(event); true }

        loadData()
    }

    private fun switchTab(index: Int) {
        binding.flipper.displayedChild = index
        val tabs = listOf(binding.tabServer, binding.tabH2, binding.tabLog)
        val indicators = listOf(binding.indicatorServer, binding.indicatorH2, binding.indicatorLog)
        tabs.forEachIndexed { i, tab -> tab.setTextColor(Color.parseColor(if (i == index) "#4ECDC4" else "#5A6377")) }
        indicators.forEachIndexed { i, ind -> ind.setBackgroundColor(Color.parseColor(if (i == index) "#4ECDC4" else "#1F2738")) }
    }

    private fun loadData() {
        binding.btnRefresh.isEnabled = false
        binding.btnRefreshH2.isEnabled = false
        binding.btnRefreshLog.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn1 = java.net.URL("http://45.38.190.244/serverstatus.json").openConnection() as java.net.HttpURLConnection
                conn1.connectTimeout = 5000
                conn1.readTimeout = 5000
                val statusBody = conn1.inputStream.bufferedReader().readText()
                conn1.disconnect()
                val status = org.json.JSONObject(statusBody)

                val conn2 = java.net.URL("http://45.38.190.244/vpnstats.json").openConnection() as java.net.HttpURLConnection
                conn2.connectTimeout = 5000
                conn2.readTimeout = 5000
                val vpnBody = conn2.inputStream.bufferedReader().readText()
                conn2.disconnect()
                val vpn = org.json.JSONObject(vpnBody)

                var logEvents = org.json.JSONArray()
                try {
                    val conn3 = java.net.URL("http://45.38.190.244/serverlog.json").openConnection() as java.net.HttpURLConnection
                    conn3.connectTimeout = 5000
                    conn3.readTimeout = 5000
                    val logBody = conn3.inputStream.bufferedReader().readText()
                    conn3.disconnect()
                    logEvents = org.json.JSONArray(logBody)
                } catch (_: Exception) {}

                launch(Dispatchers.Main) {
                    updateServerTab(status, vpn)
                    updateH2Tab(status)
                    updateLogTab(logEvents)
                    binding.btnRefresh.isEnabled = true
                    binding.btnRefreshH2.isEnabled = true
                    binding.btnRefreshLog.isEnabled = true
                }
            } catch (_: Exception) {
                launch(Dispatchers.Main) {
                    binding.tvServerStatus.text = "\u041D\u0415\u0414\u041E\u0421\u0422\u0423\u041F\u0415\u041D"
                    binding.tvServerStatus.setTextColor(Color.parseColor("#FF6B6B"))
                    binding.serverDot.setBackgroundColor(Color.parseColor("#FF6B6B"))
                    binding.tvUpdated.text = "\u041E\u0448\u0438\u0431\u043A\u0430"
                    binding.tvH2Status.text = "\u041D\u0415\u0414\u041E\u0421\u0422\u0423\u041F\u0415\u041D"
                    binding.tvH2Status.setTextColor(Color.parseColor("#FF6B6B"))
                    binding.h2Dot.setBackgroundColor(Color.parseColor("#FF6B6B"))
                    binding.tvUpdatedH2.text = "\u041E\u0448\u0438\u0431\u043A\u0430"
                    binding.tvUpdatedLog.text = "\u041E\u0448\u0438\u0431\u043A\u0430"
                    binding.btnRefresh.isEnabled = true
                    binding.btnRefreshH2.isEnabled = true
                    binding.btnRefreshLog.isEnabled = true
                }
            }
        }
    }

    private fun updateServerTab(status: org.json.JSONObject, vpn: org.json.JSONObject) {
        binding.tvServerStatus.text = "\u041E\u041D\u041B\u0410\u0419\u041D"
        binding.tvServerStatus.setTextColor(Color.parseColor("#00E5A0"))
        binding.serverDot.setBackgroundResource(R.drawable.pulse_dot)
        binding.tvUptime.text = "uptime ${status.optString("uptime", "\u2014")}"

        val cpu = status.optInt("cpu", 0)
        binding.tvCpu.text = "${cpu}%"
        setBarWidth(binding.barCpu, cpu)

        val ramUsed = status.optInt("ram_used", 0)
        val ramTotal = status.optInt("ram_total", 1)
        binding.tvRam.text = "${String.format("%.1f", ramUsed / 1024.0)}/${String.format("%.0f", ramTotal / 1024.0)}\u0413\u0411"
        setBarWidth(binding.barRam, (ramUsed * 100) / ramTotal)

        val diskUsed = status.optInt("disk_used", 0)
        val diskTotal = status.optInt("disk_total", 1)
        binding.tvDisk.text = "${diskUsed}/${diskTotal}\u0413\u0411"
        setBarWidth(binding.barDisk, (diskUsed * 100) / diskTotal)

        binding.servicesContainer.removeAllViews()
        val services = status.optJSONObject("services")
        if (services != null) {
            for (key in listOf("x-ui", "h2", "echobot", "nginx", "vpnstats", "speedserver")) {
                val state = services.optString(key, "unknown")
                addServiceRow(binding.servicesContainer, serviceNames[key] ?: key, state)
            }
        }

        val sslDays = status.optInt("ssl_days", -1)
        binding.tvSslDays.text = if (sslDays >= 0) "${sslDays} \u0434\u043D\u0435\u0439" else "\u2014"
        binding.tvSslDays.setTextColor(Color.parseColor(if (sslDays in 0..14) "#FF6B6B" else "#00E5A0"))

        val sshToday = status.optInt("ssh_today", 0)
        binding.tvSshToday.text = "$sshToday"
        binding.tvSshToday.setTextColor(Color.parseColor(if (sshToday > 100) "#FF6B6B" else "#FFB800"))

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvUpdated.text = "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u043E: ${sdf.format(Date())}"
    }

    private fun updateH2Tab(status: org.json.JSONObject) {
        val h2Status = status.optString("h2_status", "down")
        if (h2Status == "ok") {
            binding.tvH2Status.text = "H2+ \u041E\u041D\u041B\u0410\u0419\u041D"
            binding.tvH2Status.setTextColor(Color.parseColor("#00E5A0"))
            binding.h2Dot.setBackgroundResource(R.drawable.pulse_dot)
        } else {
            binding.tvH2Status.text = "H2+ \u041D\u0415\u0414\u041E\u0421\u0422\u0423\u041F\u0415\u041D"
            binding.tvH2Status.setTextColor(Color.parseColor("#FF6B6B"))
            binding.h2Dot.setBackgroundColor(Color.parseColor("#FF6B6B"))
        }

        // DuckDNS
        val duckOk = status.optBoolean("duckdns_ok", false)
        val duckIp = status.optString("duckdns_ip", "?")
        if (duckOk) {
            binding.tvDuckdns.text = "\u2705 IP \u0441\u043E\u0432\u043F\u0430\u0434\u0430\u0435\u0442"
            binding.tvDuckdns.setTextColor(Color.parseColor("#00E5A0"))
        } else {
            binding.tvDuckdns.text = "\u274C $duckIp"
            binding.tvDuckdns.setTextColor(Color.parseColor("#FF6B6B"))
        }

        // FCM
        val fcmOk = status.optBoolean("fcm_ok", false)
        if (fcmOk) {
            binding.tvFcm.text = "\u2705 \u0430\u043A\u0442\u0438\u0432\u0435\u043D"
            binding.tvFcm.setTextColor(Color.parseColor("#00E5A0"))
        } else {
            binding.tvFcm.text = "\u274C \u043D\u0435 \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442"
            binding.tvFcm.setTextColor(Color.parseColor("#FF6B6B"))
        }

        binding.tvH2Version.text = "v${status.optString("h2_version", "?")}"

        val users = status.optJSONArray("h2_users")
        val totalUsers = users?.length() ?: 0
        var onlineCount = 0
        binding.usersContainer.removeAllViews()

        if (users != null) {
            for (i in 0 until users.length()) {
                val u = users.getJSONObject(i)
                val online = u.optBoolean("online", false)
                if (online) onlineCount++
                val name = u.optString("name", "?")
                val lastSeen = u.optString("last_seen", "")
                val seenText = if (online) "\u0441\u0435\u0439\u0447\u0430\u0441" else formatLastSeen(lastSeen)
                addUserRow(name, online, seenText)
            }
        }

        binding.tvH2Total.text = "$totalUsers"
        binding.tvH2Online.text = "$onlineCount"

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvUpdatedH2.text = "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u043E: ${sdf.format(Date())}"
    }

    private fun formatLastSeen(iso: String): String {
        if (iso.isNullOrEmpty() || iso == "null") return "\u2014"
        return try {
            val parts = iso.replace("T", " ").split(" ")
            if (parts.size >= 2) {
                val dateParts = parts[0].split("-")
                val timeParts = parts[1].split(":").take(2)
                "${dateParts[2]}.${dateParts[1]} ${timeParts.joinToString(":")}"
            } else iso
        } catch (_: Exception) { iso }
    }

    private fun setBarWidth(bar: View, percent: Int) {
        val parent = bar.parent as View
        bar.post {
            val params = bar.layoutParams
            params.width = (parent.width * percent.coerceIn(0, 100)) / 100
            bar.layoutParams = params
        }
    }

    private fun addServiceRow(container: LinearLayout, name: String, state: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply { marginEnd = dpToPx(10) }
            setBackgroundColor(if (state == "active") Color.parseColor("#00E5A0") else Color.parseColor("#FF6B6B"))
        }
        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 13f; setTextColor(Color.WHITE)
        }
        val tvState = TextView(this).apply {
            text = state; textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(if (state == "active") Color.parseColor("#00E5A0") else Color.parseColor("#FF6B6B"))
        }
        row.addView(dot); row.addView(tvName); row.addView(tvState)
        container.addView(row)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
        })
    }

    private fun addUserRow(name: String, online: Boolean, seenText: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(7), 0, dpToPx(7))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply { marginEnd = dpToPx(10) }
            setBackgroundColor(if (online) Color.parseColor("#00E5A0") else Color.parseColor("#5A6377"))
        }
        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        val tvSeen = TextView(this).apply {
            text = seenText; textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#5A6377"))
        }
        row.addView(dot); row.addView(tvName); row.addView(tvSeen)
        binding.usersContainer.addView(row)
        binding.usersContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
        })
    }

    private fun updateLogTab(events: org.json.JSONArray) {
        binding.logContainer.removeAllViews()
        if (events.length() == 0) {
            binding.tvNoEvents.visibility = View.VISIBLE
        } else {
            binding.tvNoEvents.visibility = View.GONE
            for (i in events.length() - 1 downTo 0) {
                val e = events.getJSONObject(i)
                addLogRow(e.optString("icon", ""), e.optString("text", ""), e.optString("level", "info"), e.optLong("ts", 0))
            }
        }
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvUpdatedLog.text = "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u043E: ${sdf.format(Date())}"
    }

    private fun addLogRow(icon: String, text: String, level: String, ts: Long) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        val tvIcon = TextView(this).apply {
            this.text = icon; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvText = TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(Color.parseColor(when (level) {
                "critical" -> "#FF6B6B"
                "warning" -> "#FFB800"
                "ok" -> "#00E5A0"
                else -> "#8993A4"
            }))
        }
        val tvTime = TextView(this).apply {
            this.text = formatEventTime(ts); textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#5A6377"))
        }
        contentLayout.addView(tvText)
        contentLayout.addView(tvTime)
        row.addView(tvIcon)
        row.addView(contentLayout)
        binding.logContainer.addView(row)
        binding.logContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1A1F2E"))
        })
    }

    private fun formatEventTime(ts: Long): String {
        if (ts == 0L) return "\u2014"
        val now = System.currentTimeMillis() / 1000
        val diff = now - ts
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sdfDate = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return if (diff < 86400) "\u0441\u0435\u0433\u043E\u0434\u043D\u044F ${sdf.format(Date(ts * 1000))}"
        else sdfDate.format(Date(ts * 1000))
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
