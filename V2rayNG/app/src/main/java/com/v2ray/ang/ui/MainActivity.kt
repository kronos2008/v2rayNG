package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
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
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startV2Ray()
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) restartV2Ray()
        if (SettingsChangeManager.consumeSetupGroupTab()) setupGroupTab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, "")

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
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

        binding.fab.setOnClickListener { handleFabAction() }

        binding.tvPing.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                binding.tvPing.text = "Проверка..."
                mainViewModel.testCurrentServerRealPing()
            }
        }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        // Автодобавление подписки True VPN если её нет
        // Автодобавление подписки True VPN если её нет

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val ping = result?.let {
                val match = Regex("(\\d+)ms").find(it)
                if (match != null) "🟢 ${match.groupValues[1]} ms" else it
            }
            binding.tvPing.text = ping ?: ""
        }
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

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }
            .takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)
        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
    if (mainViewModel.isRunning.value == true) {
        V2RayServiceManager.stopVService(this)
        return
    }
    if (MmkvManager.getSelectServer().isNullOrEmpty()) {
        toast(R.string.title_file_chooser)
        return
    }
    if (SettingsManager.isVpnMode()) {
        try {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                V2RayServiceManager.startVService(this)
            } else {
                requestVpnPermission.launch(intent)
            }
        } catch (e: Exception) {
            V2RayServiceManager.startVService(this)
        }
    } else {
        V2RayServiceManager.startVService(this)
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
        if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            return
        }
        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_fab_active)
            )
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            binding.tvPing.text = "Нажмите для проверки пинга"
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_fab_inactive)
            )
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            binding.tvPing.text = getString(R.string.connection_not_connected)
        }
    }

    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })
            searchView.setOnCloseListener { mainViewModel.filterConfig(""); false }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> { importQRcode(); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_local -> { importConfigLocal(); true }
        R.id.import_manually_policy_group -> { importManually(EConfigType.POLICYGROUP.value); true }
        R.id.import_manually_vmess -> { importManually(EConfigType.VMESS.value); true }
        R.id.import_manually_vless -> { importManually(EConfigType.VLESS.value); true }
        R.id.import_manually_ss -> { importManually(EConfigType.SHADOWSOCKS.value); true }
        R.id.import_manually_socks -> { importManually(EConfigType.SOCKS.value); true }
        R.id.import_manually_http -> { importManually(EConfigType.HTTP.value); true }
        R.id.import_manually_trojan -> { importManually(EConfigType.TROJAN.value); true }
        R.id.import_manually_wireguard -> { importManually(EConfigType.WIREGUARD.value); true }
        R.id.import_manually_hysteria2 -> { importManually(EConfigType.HYSTERIA2.value); true }
        R.id.export_all -> { exportAll(); true }
        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping(); true
        }
        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing(); true
        }
        R.id.service_restart -> { restartV2Ray(); true }
        R.id.del_all_config -> { delAllConfig(); true }
        R.id.del_duplicate_config -> { delDuplicateConfig(); true }
        R.id.del_invalid_config -> { delInvalidConfig(); true }
        R.id.sort_by_test_results -> { sortByTestResults(); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        R.id.locate_selected_config -> { locateSelectedServer(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(Intent().putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerGroupActivity::class.java))
        } else {
            startActivity(Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java))
        }
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { if (it != null) importBatchConfig(it) }
        return true
    }

    private fun importClipboard(): Boolean {
        return try {
            importBatchConfig(Utils.getClipboard(this)); true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "clipboard error", e); false
        }
    }

    private fun importBatchConfig(server: String?) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> { toast(getString(R.string.title_import_config_count, count)); mainViewModel.reloadServerList() }
                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure); hideLoading() }
                Log.e(AppConfig.TAG, "import error", e)
            }
        }
    }

    private fun importConfigLocal(): Boolean {
        return try { showFileChooser(); true } catch (e: Exception) { false }
    }

    fun importConfigViaSub(): Boolean {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                when {
                    result.successCount + result.failureCount + result.skipCount == 0 ->
                        toast(R.string.title_update_subscription_no_subscription)
                    result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                        toast(getString(R.string.title_update_config_count, result.configCount))
                    else -> toast(getString(R.string.title_update_subscription_result,
                        result.configCount, result.successCount, result.failureCount, result.skipCount))
                }
                if (result.configCount > 0) mainViewModel.reloadServerList()
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
                if (ret > 0) toast(getString(R.string.title_export_config_count, ret))
                else toastError(R.string.toast_failure)
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
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); hideLoading() }
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_duplicate_config_count, ret)); hideLoading() }
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); hideLoading() }
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) { mainViewModel.reloadServerList(); hideLoading() }
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri -> if (uri != null) readContentFromUri(uri) }
    }

    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { importBatchConfig(it?.bufferedReader()?.readText()) }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "URI read error", e)
        }
    }

    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect() ?: run {
            toast(R.string.title_file_chooser); return
        }
        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) { toast(R.string.toast_server_not_found_in_group); return }
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else scrollToSelectedServer(targetGroupIndex)
    }

    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment
        if (fragment?.isAdded == true && fragment.view != null) fragment.scrollToSelectedServer()
        else toast(R.string.toast_fragment_not_available)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}
