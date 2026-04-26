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
                val dy = (e2.y) - (e1?.y ?: 0f)
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80 && Math.abs(vX) > 150) {
                    val cur = binding.flipper.displayedChild
                    if (dx < 0 && cur < 2) switchTab(cur + 1)
                    else if (dx > 0 && cur > 0) switchTab(cur - 1)
                    return true
                }
                return false
            }
        })
        for (i in 0 until binding.flipper.childCount) {
            binding.flipper.getChildAt(i).setOnTouchListener { v, event ->
                gesture.onTouchEvent(event)
                false
            }
        }

        loadData()
    }

    private fun switchTab(index: Int) {
        val cur = binding.flipper.displayedChild
        if (index == cur) return
        if (index > cur) {
            binding.flipper.setInAnimation(this, R.anim.slide_in_left)
            binding.flipper.setOutAnimation(this, R.anim.slide_out_left)
        } else {
            binding.flipper.setInAnimation(this, R.anim.slide_in_right)
            binding.flipper.setOutAnimation(this, R.anim.slide_out_right)
        }
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
            for (key in listOf("x-ui", "h2", "nginx", "echobot", "vpnstats", "speedserver")) {
                val state = services.optString(key, "unknown")
                addServiceRow(binding.servicesContainer, serviceNames[key] ?: key, state, key)
            }
        }

        val sslDays = status.optInt("ssl_days", -1)
        val sslColor = if (sslDays in 0..14) "#FF6B6B" else "#00E5A0"
        val sslDot = if (sslDays in 0..14) R.drawable.dot_red else R.drawable.dot_green
        val sshToday = status.optInt("ssh_today", 0)
        val sshColor = if (sshToday > 10000) "#FF6B6B" else "#FFB800"
        val sshDot = if (sshToday > 10000) R.drawable.dot_red else R.drawable.dot_green
        binding.securityContainer.removeAllViews()
        addInfoRow(binding.securityContainer, "SSL \u0441\u0435\u0440\u0442\u0438\u0444\u0438\u043A\u0430\u0442", if (sslDays >= 0) "${sslDays} \u0434\u043D" else "\u2014", sslColor, sslDot)
        // SSH row with reset button
        val sshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        sshRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply { marginEnd = dpToPx(12) }
            setBackgroundResource(sshDot)
        })
        sshRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "SSH (\u0441\u0435\u0433\u043E\u0434\u043D\u044F)"; textSize = 14f; setTextColor(Color.WHITE)
        })
        sshRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(50), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = "$sshToday"; textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setTextColor(Color.parseColor(sshColor))
        })
        val btnReset = android.widget.Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(28)).apply { marginStart = dpToPx(8) }
            text = "\u0421\u0431\u0440\u043E\u0441"; textSize = 10f; setTextColor(Color.parseColor("#FF6B6B"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FF6B6B"))
            minimumWidth = 0; minWidth = 0; setPadding(dpToPx(10), 0, dpToPx(10), 0)
            isAllCaps = false
        }
        btnReset.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("\u0421\u0431\u0440\u043E\u0441 SSH")
                .setMessage("\u0421\u0431\u0440\u043E\u0441\u0438\u0442\u044C \u0441\u0447\u0451\u0442\u0447\u0438\u043A SSH \u043F\u043E\u043F\u044B\u0442\u043E\u043A?")
                .setPositiveButton("\u0414\u0430") { _, _ ->
                    Thread {
                        try {
                            val conn = java.net.URL("http://45.38.190.244:8080/reset_ssh").openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 5000; conn.readTimeout = 5000
                            conn.inputStream.bufferedReader().readText(); conn.disconnect()
                            runOnUiThread { loadData() }
                        } catch (e: Exception) {
                            runOnUiThread { android.widget.Toast.makeText(this, "\u041E\u0448\u0438\u0431\u043A\u0430: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }.start()
                }
                .setNegativeButton("\u041D\u0435\u0442", null)
                .show()
        }
        sshRow.addView(btnReset)
        binding.securityContainer.addView(sshRow)
        binding.securityContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
        })

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
        val fcmOk = status.optBoolean("fcm_ok", false)
        binding.infraContainer.removeAllViews()
        addInfoRow(binding.infraContainer, "DuckDNS", if (duckOk) "OK" else "\u043E\u0448\u0438\u0431\u043A\u0430", if (duckOk) "#00E5A0" else "#FF6B6B", if (duckOk) R.drawable.dot_green else R.drawable.dot_red)
        addInfoRow(binding.infraContainer, "FCM Push", if (fcmOk) "OK" else "\u043E\u0448\u0438\u0431\u043A\u0430", if (fcmOk) "#00E5A0" else "#FF6B6B", if (fcmOk) R.drawable.dot_green else R.drawable.dot_red)

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

    private val restartableServices = mapOf("x-ui" to "vpn", "h2" to "h2", "nginx" to "nginx")
    private val ADMIN_KEY = "H2admin9090secret"

    private fun addServiceRow(container: LinearLayout, name: String, state: String, svcKey: String = "") {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply { marginEnd = dpToPx(12) }
            setBackgroundResource(if (state == "active") R.drawable.dot_green else R.drawable.dot_red)
        }
        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 14f; setTextColor(Color.WHITE)
        }
        val tvState = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = state; textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setTextColor(if (state == "active") Color.parseColor("#00E5A0") else Color.parseColor("#FF6B6B"))
        }
        row.addView(dot); row.addView(tvName); row.addView(tvState)

        if (restartableServices.containsKey(svcKey)) {
            val btn = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply { marginStart = dpToPx(10) }
                text = "\u21BB"; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#4ECDC4"))
                setBackgroundResource(R.drawable.btn_restart_bg)
                setOnClickListener { confirmRestart(name, restartableServices[svcKey]!!) }
            }
            row.addView(btn)
        } else {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(1)).apply { marginStart = dpToPx(10) }
            })
        }

        container.addView(row)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
        })
    }

    private fun confirmRestart(name: String, endpoint: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("\u041F\u0435\u0440\u0435\u0437\u0430\u043F\u0443\u0441\u0442\u0438\u0442\u044C $name?")
            .setMessage("\u0421\u0435\u0440\u0432\u0438\u0441 \u0431\u0443\u0434\u0435\u0442 \u043F\u0435\u0440\u0435\u0437\u0430\u043F\u0443\u0449\u0435\u043D")
            .setPositiveButton("\u0414\u0430") { _, _ -> restartService(endpoint) }
            .setNegativeButton("\u041E\u0442\u043C\u0435\u043D\u0430", null)
            .show()
    }

    private fun restartService(endpoint: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = java.net.URL("http://45.38.190.244/admin-api/restart/$endpoint").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Key", ADMIN_KEY)
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                conn.disconnect()
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@PanelActivity,
                        if (code == 200) "\u2705 \u041F\u0435\u0440\u0435\u0437\u0430\u043F\u0443\u0449\u0435\u043D" else "\u274C \u041E\u0448\u0438\u0431\u043A\u0430: $code",
                        android.widget.Toast.LENGTH_SHORT).show()
                    loadData()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@PanelActivity, "\u274C ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addUserRow(name: String, online: Boolean, seenText: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply { marginEnd = dpToPx(12) }
            setBackgroundResource(if (online) R.drawable.dot_green else R.drawable.dot_gray)
        }
        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        val tvSeen = TextView(this).apply {
            text = seenText; textSize = 11f; typeface = Typeface.MONOSPACE
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
        val dotRes = when (level) {
            "critical" -> R.drawable.dot_red
            "ok" -> R.drawable.dot_green
            "warning" -> R.drawable.dot_yellow
            else -> R.drawable.dot_gray
        }
        val textColor = when (level) {
            "critical" -> "#FF6B6B"
            "warning" -> "#FFB800"
            "ok" -> "#00E5A0"
            else -> "#8993A4"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply { marginEnd = dpToPx(12) }
            setBackgroundResource(dotRes)
        }
        val tvText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.text = text; textSize = 13f
            setTextColor(Color.parseColor(textColor))
        }
        val tvTime = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.text = formatEventTime(ts); textSize = 11f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setTextColor(Color.parseColor("#5A6377"))
        }
        row.addView(dot); row.addView(tvText); row.addView(tvTime)
        binding.logContainer.addView(row)
        binding.logContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
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

    private fun addInfoRow(container: LinearLayout, name: String, value: String, valueColor: String, dotRes: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply { marginEnd = dpToPx(12) }
            setBackgroundResource(dotRes)
        }
        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 14f; setTextColor(Color.WHITE)
        }
        val tvVal = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = value; textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setTextColor(Color.parseColor(valueColor))
        }
        row.addView(dot); row.addView(tvName); row.addView(tvVal)
        container.addView(row)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1F2738"))
        })
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
