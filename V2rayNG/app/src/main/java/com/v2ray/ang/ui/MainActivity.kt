package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    // H2 VPN Stats
    private var connectStartTime = 0L
    private var serverStartUp = 0L
    private var serverStartDown = 0L
    private var serverLastUp = 0L
    private var serverLastDown = 0L
    private var lastFetchTime = 0L
    private var fetchCount = 0
    private val speedUpHistory = mutableListOf<Long>()
    private val speedDownHistory = mutableListOf<Long>()
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            statsHandler.postDelayed(this, 1000)
        }
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.visibility = android.view.View.GONE
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnSpeedtest.setOnClickListener { runSpeedTest() }
        binding.btnServer.setOnClickListener {
            startActivity(Intent(this, PanelActivity::class.java))
        }
        binding.btnDisconnect.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                connectStartTime = 0L
                serverStartUp = 0L
                serverStartDown = 0L
                serverLastUp = 0L
                serverLastDown = 0L
                lastFetchTime = 0L
                fetchCount = 0
                speedUpHistory.clear()
                speedDownHistory.clear()
                statsHandler.removeCallbacks(statsRunnable)
                getSharedPreferences("h2vpn_stats", 0).edit().clear().apply()
            }
            handleFabAction()
        }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.btnDisconnect.isEnabled = false
            binding.btnDisconnect.text = "..."
            return
        }

        binding.statsPanel.visibility = android.view.View.VISIBLE
        binding.btnDisconnect.isEnabled = true

        if (isRunning) {
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            binding.btnDisconnect.text = "\u041E\u0422\u041A\u041B\u042E\u0427\u0418\u0422\u042C\u0421\u042F"
            binding.btnDisconnect.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#EB5757"))
            binding.btnDisconnect.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            binding.tvStatusLabel.text = "\u041F\u041E\u0414\u041A\u041B\u042E\u0427\u0415\u041D\u041E"
            binding.tvStatusLabel.setTextColor(android.graphics.Color.parseColor("#00E5A0"))
            binding.pulseDot.setBackgroundResource(R.drawable.pulse_dot)
            binding.tvServerAddr.text = "45.38.190.244 : 2443 \u00B7 VLESS \u00B7 v3.7"
            if (connectStartTime == 0L) {
                val prefs = getSharedPreferences("h2vpn_stats", 0)
                val saved = prefs.getLong("connectStart", 0L)
                if (saved > 0L) {
                    connectStartTime = saved
                    serverStartUp = prefs.getLong("serverStartUp", 0L)
                    serverStartDown = prefs.getLong("serverStartDown", 0L)
                    val savedPing = prefs.getString("pingResult", null)
                    val savedSpeed = prefs.getString("speedResult", null)
                    if (savedPing != null) binding.tvPingVal.text = savedPing
                    if (savedSpeed != null) binding.tvSpeedVal.text = savedSpeed
                } else {
                    connectStartTime = System.currentTimeMillis()
                    serverStartUp = 0L
                    serverStartDown = 0L
                }
                serverLastUp = 0L
                serverLastDown = 0L
                lastFetchTime = 0L
                fetchCount = 0
                speedUpHistory.clear()
                speedDownHistory.clear()
            }
            statsHandler.removeCallbacks(statsRunnable)
            statsHandler.post(statsRunnable)
            checkVpnIp()
        } else {
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            binding.btnDisconnect.text = "\u041F\u041E\u0414\u041A\u041B\u042E\u0427\u0418\u0422\u042C\u0421\u042F"
            binding.btnDisconnect.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#00E5A0"))
            binding.btnDisconnect.setTextColor(android.graphics.Color.parseColor("#0A0E1A"))
            binding.tvStatusLabel.text = "\u041E\u0422\u041A\u041B\u042E\u0427\u0415\u041D\u041E"
            binding.tvStatusLabel.setTextColor(android.graphics.Color.parseColor("#5A6377"))
            binding.pulseDot.setBackgroundColor(android.graphics.Color.parseColor("#5A6377"))
            binding.tvServerAddr.text = "45.38.190.244 : 2443 \u00B7 VLESS \u00B7 v3.7"
            binding.tvStatsTime.text = "00:00"
            binding.tvUploadTotal.text = "0 \u0411"
            binding.tvDownloadTotal.text = "0 \u0411"
            binding.tvUploadSpeed.text = "0 \u0411/\u0441"
            binding.tvDownloadSpeed.text = "0 \u0411/\u0441"
            statsHandler.removeCallbacks(statsRunnable)
            connectStartTime = 0L
        }
    }

    private fun updateStats() {
        if (connectStartTime == 0L) return
        // Timer
        val elapsed = (System.currentTimeMillis() - connectStartTime) / 1000
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        val timeStr = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        binding.tvStatsTime.text = timeStr

        // Save to SharedPreferences every 10 seconds
        if (elapsed % 3 == 0L) {
            getSharedPreferences("h2vpn_stats", 0).edit()
                .putLong("connectStart", connectStartTime)
                .putLong("serverStartUp", serverStartUp)
                .putLong("serverStartDown", serverStartDown)
                .putString("pingResult", binding.tvPingVal.text?.toString())
                .putString("speedResult", binding.tvSpeedVal.text?.toString())
                .apply()
        }

        // Fetch server stats every 2 seconds
        fetchCount++
        if (fetchCount % 2 == 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("http://45.38.190.244/vpnstats.json").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val obj = org.json.JSONObject(body)
                    val up = obj.getLong("up")
                    val down = obj.getLong("down")
                    val now = System.currentTimeMillis()

                    launch(Dispatchers.Main) {
                        fetchFailCount = 0
                        binding.pulseDot.setBackgroundResource(R.drawable.pulse_dot)
                        binding.tvStatusLabel.text = "\u041F\u041E\u0414\u041A\u041B\u042E\u0427\u0415\u041D\u041E"
                        binding.tvStatusLabel.setTextColor(android.graphics.Color.parseColor("#00E5A0"))
                        if (serverStartUp == 0L) {
                            serverStartUp = up
                            serverStartDown = down
                        }
                        val totalUp = up - serverStartUp       // up = client upload
                        val totalDown = down - serverStartDown  // down = client download
                        binding.tvUploadTotal.text = formatBytes(totalUp)
                        binding.tvDownloadTotal.text = formatBytes(totalDown)

                        if (lastFetchTime > 0 && up >= serverLastUp && down >= serverLastDown) {
                            val dt = (now - lastFetchTime) / 1000.0
                            if (dt > 0.5) {
                                val sUp = ((up - serverLastUp) / dt).toLong()
                                val sDown = ((down - serverLastDown) / dt).toLong()
                                speedUpHistory.add(sUp)
                                speedDownHistory.add(sDown)
                                if (speedUpHistory.size > 3) speedUpHistory.removeAt(0)
                                if (speedDownHistory.size > 3) speedDownHistory.removeAt(0)
                                val avgUp = speedUpHistory.average().toLong()
                                val avgDown = speedDownHistory.average().toLong()
                                binding.tvUploadSpeed.text = "${formatBytes(avgUp)}/\u0441"
                                binding.tvDownloadSpeed.text = "${formatBytes(avgDown)}/\u0441"
                            }
                        } else {
                            binding.tvUploadSpeed.text = "0B/\u0441"
                            binding.tvDownloadSpeed.text = "0B/\u0441"
                        }
                        serverLastUp = up
                        serverLastDown = down
                        lastFetchTime = now
                    }
                } catch (_: Exception) {
                    launch(Dispatchers.Main) {
                        fetchFailCount++
                        if (fetchFailCount >= 3) {
                            binding.pulseDot.setBackgroundColor(android.graphics.Color.parseColor("#FF6B6B"))
                            binding.tvStatusLabel.text = "\u041D\u0415\u0422 \u0421\u0412\u042F\u0417\u0418"
                            binding.tvStatusLabel.setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
                        }
                    }
                }
            }
        }
    }

    private var fetchFailCount = 0

    private fun checkVpnIp() {
        // Initial check - will be updated by updateStats fetch results
        binding.tvStatusLabel.text = "\u041F\u041E\u0414\u041A\u041B\u042E\u0427\u0415\u041D\u041E"
        binding.tvStatusLabel.setTextColor(android.graphics.Color.parseColor("#00E5A0"))
        binding.pulseDot.setBackgroundResource(R.drawable.pulse_dot)
        fetchFailCount = 0
    }

    private fun runSpeedTest() {
        binding.tvPingVal.text = "\u23F3..."
        binding.tvSpeedVal.text = "\u23F3..."
        binding.btnSpeedtest.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Ping
            var pingResult = "\u2717"
            try {
                val pings = mutableListOf<Long>()
                repeat(3) {
                    val s = System.currentTimeMillis()
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress("www.google.com", 443), 5000)
                    pings.add(System.currentTimeMillis() - s)
                    sock.close()
                    Thread.sleep(100)
                }
                pingResult = String.format("%.0f \u043C\u0441", pings.average())
            } catch (_: Exception) {}

            launch(Dispatchers.Main) { binding.tvPingVal.text = pingResult }

            // 2. Speed - streaming test
            var speedResult = "\u2717"
            try {
                val threads = 6
                val testDurationMs = 5000L
                val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
                val t0 = System.currentTimeMillis()
                val jobs = (1..threads).map {
                    async(Dispatchers.IO) {
                        try {
                            val conn = java.net.URL("http://45.38.190.244:8888/speedtest").openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 5000
                            conn.readTimeout = 6000
                            conn.connect()
                            val input = conn.inputStream
                            val buf = ByteArray(131072)
                            while (System.currentTimeMillis() - t0 < testDurationMs) {
                                val r = input.read(buf)
                                if (r == -1) break
                                totalBytes.addAndGet(r.toLong())
                            }
                            input.close()
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }
                }
                jobs.forEach { it.await() }
                val dur = (System.currentTimeMillis() - t0) / 1000.0
                val total = totalBytes.get()
                if (dur > 0.5 && total > 50000) {
                    speedResult = String.format("%.0f \u041C\u0431/\u0441", (total * 8.0) / (dur * 1000000))
                }
            } catch (_: Exception) {}

            launch(Dispatchers.Main) {
                binding.tvSpeedVal.text = speedResult
                binding.btnSpeedtest.isEnabled = true
                // Save results
                getSharedPreferences("h2vpn_stats", 0).edit()
                    .putString("pingResult", pingResult)
                    .putString("speedResult", speedResult)
                    .apply()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
            else -> String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (connectStartTime > 0L) {
            getSharedPreferences("h2vpn_stats", 0).edit()
                .putLong("connectStart", connectStartTime)
                .putLong("serverStartUp", serverStartUp)
                .putLong("serverStartDown", serverStartDown)
                .putString("pingResult", binding.tvPingVal.text?.toString())
                .putString("speedResult", binding.tvSpeedVal.text?.toString())
                .apply()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}