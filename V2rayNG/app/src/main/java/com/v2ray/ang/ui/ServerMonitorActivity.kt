package com.v2ray.ang.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.databinding.ActivityServerMonitorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServerMonitorActivity : BaseActivity() {
    private val binding by lazy { ActivityServerMonitorBinding.inflate(layoutInflater) }

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
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "\u0421\u0435\u0440\u0432\u0435\u0440 DE")

        binding.btnRefresh.setOnClickListener { loadData() }
        loadData()
    }

    private fun loadData() {
        binding.btnRefresh.isEnabled = false
        binding.tvUpdated.text = "\u0417\u0430\u0433\u0440\u0443\u0437\u043A\u0430..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Server status
                val conn1 = java.net.URL("http://45.38.190.244/serverstatus.json").openConnection() as java.net.HttpURLConnection
                conn1.connectTimeout = 5000
                conn1.readTimeout = 5000
                val statusBody = conn1.inputStream.bufferedReader().readText()
                conn1.disconnect()
                val status = org.json.JSONObject(statusBody)

                // VPN traffic
                val conn2 = java.net.URL("http://45.38.190.244/vpnstats.json").openConnection() as java.net.HttpURLConnection
                conn2.connectTimeout = 5000
                conn2.readTimeout = 5000
                val vpnBody = conn2.inputStream.bufferedReader().readText()
                conn2.disconnect()
                val vpn = org.json.JSONObject(vpnBody)

                launch(Dispatchers.Main) {
                    updateUI(status, vpn)
                    binding.btnRefresh.isEnabled = true
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    binding.tvServerStatus.text = "\u041D\u0415\u0414\u041E\u0421\u0422\u0423\u041F\u0415\u041D"
                    binding.tvServerStatus.setTextColor(Color.parseColor("#FF6B6B"))
                    binding.serverDot.setBackgroundColor(Color.parseColor("#FF6B6B"))
                    binding.tvUpdated.text = "\u041E\u0448\u0438\u0431\u043A\u0430 \u043F\u043E\u0434\u043A\u043B\u044E\u0447\u0435\u043D\u0438\u044F"
                    binding.btnRefresh.isEnabled = true
                }
            }
        }
    }

    private fun updateUI(status: org.json.JSONObject, vpn: org.json.JSONObject) {
        // Server status
        binding.tvServerStatus.text = "\u041E\u041D\u041B\u0410\u0419\u041D"
        binding.tvServerStatus.setTextColor(Color.parseColor("#00E5A0"))
        binding.serverDot.setBackgroundResource(com.v2ray.ang.R.drawable.pulse_dot)
        binding.tvUptime.text = "uptime ${status.optString("uptime", "—")}"

        // CPU
        val cpu = status.optInt("cpu", 0)
        binding.tvCpu.text = "${cpu}%"
        setBarWidth(binding.barCpu, cpu)

        // RAM
        val ramUsed = status.optInt("ram_used", 0)
        val ramTotal = status.optInt("ram_total", 1)
        val ramPct = (ramUsed * 100) / ramTotal
        binding.tvRam.text = "${String.format("%.1f", ramUsed / 1024.0)}/${String.format("%.0f", ramTotal / 1024.0)}\u0413\u0411"
        setBarWidth(binding.barRam, ramPct)

        // Disk
        val diskUsed = status.optInt("disk_used", 0)
        val diskTotal = status.optInt("disk_total", 1)
        val diskPct = (diskUsed * 100) / diskTotal
        binding.tvDisk.text = "${diskUsed}/${diskTotal}\u0413\u0411"
        setBarWidth(binding.barDisk, diskPct)

        // Services
        binding.servicesContainer.removeAllViews()
        val services = status.optJSONObject("services")
        if (services != null) {
            for (key in listOf("x-ui", "h2", "echobot", "nginx", "vpnstats", "speedserver")) {
                val state = services.optString(key, "unknown")
                addServiceRow(serviceNames[key] ?: key, state)
            }
        }

        // VPN traffic
        val up = vpn.optLong("up", 0)
        val down = vpn.optLong("down", 0)
        binding.tvTotalUp.text = formatBytes(up)
        binding.tvTotalDown.text = formatBytes(down)
        binding.tvVpnConns.text = "${status.optInt("vpn_conns", 0)}"

        // Security
        val sslDays = status.optInt("ssl_days", -1)
        binding.tvSslDays.text = if (sslDays >= 0) "${sslDays} \u0434\u043D\u0435\u0439" else "—"
        if (sslDays in 0..14) {
            binding.tvSslDays.setTextColor(Color.parseColor("#FF6B6B"))
        } else {
            binding.tvSslDays.setTextColor(Color.parseColor("#00E5A0"))
        }

        val sshToday = status.optInt("ssh_today", 0)
        binding.tvSshToday.text = "$sshToday"
        if (sshToday > 100) {
            binding.tvSshToday.setTextColor(Color.parseColor("#FF6B6B"))
        } else {
            binding.tvSshToday.setTextColor(Color.parseColor("#FFB800"))
        }

        // Updated time
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvUpdated.text = "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u043E: ${sdf.format(Date())}"
    }

    private fun setBarWidth(bar: View, percent: Int) {
        val parent = bar.parent as View
        bar.post {
            val params = bar.layoutParams
            params.width = (parent.width * percent.coerceIn(0, 100)) / 100
            bar.layoutParams = params
        }
    }

    private fun addServiceRow(name: String, state: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
                marginEnd = dpToPx(10)
            }
            setBackgroundColor(if (state == "active") Color.parseColor("#00E5A0") else Color.parseColor("#FF6B6B"))
        }

        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        val tvState = TextView(this).apply {
            text = state
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (state == "active") Color.parseColor("#00E5A0") else Color.parseColor("#FF6B6B"))
        }

        row.addView(dot)
        row.addView(tvName)
        row.addView(tvState)
        binding.servicesContainer.addView(row)

        // Divider
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 0
            }
            setBackgroundColor(Color.parseColor("#1F2738"))
        }
        binding.servicesContainer.addView(divider)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> String.format("%.1f \u0413\u0411", bytes / 1073741824.0)
            bytes >= 1048576 -> String.format("%.1f \u041C\u0411", bytes / 1048576.0)
            bytes >= 1024 -> String.format("%.1f \u041A\u0411", bytes / 1024.0)
            else -> "$bytes \u0411"
        }
    }
}
