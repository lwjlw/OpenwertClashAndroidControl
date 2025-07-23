package com.lw5.opc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jcraft.jsch.*
import org.json.JSONObject
import java.io.OutputStream
import java.io.PrintStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.File // 导入 File 类
import java.io.FileWriter // 导入 FileWriter 类
import java.io.IOException // 导入 IOException 类
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope // 导入 lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts // 导入 ActivityResultContracts
import android.os.Build // 导入 Build

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastRefreshTimeText: TextView
    private lateinit var btnToggleClash: Button
    private lateinit var btnSpeedTestUdp: Button
    private lateinit var btnSpeedTestTcp: Button
    private lateinit var btnExit: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var lastKnownClashRunningStatus: Boolean? = null

    private lateinit var config: JSONObject

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    private var sshSession: Session? = null
    private var clashStatusJob: Job? = null // 新增：用于管理定时任务的协程Job

    private val CLASH_STATUS_CHECK_INTERVAL: Long = 5000 // 5秒检查一次
    private val SSH_CONNECT_TIMEOUT: Int = 10000 // SSH连接超时时间，增加到10秒
    // 新增：SSH 连接进行中的标志，防止重复连接
    @Volatile private var isConnectingSsh: Boolean = false
    // 新增网络相关变量
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    // 新增权限请求码
    private val REQUEST_STORAGE_PERMISSION = 100

    // **新增：通知权限请求启动器**
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission() // **已修正這裡**
    ) { isGranted: Boolean ->
        if (isGranted) {
            toast("通知权限已授予。")
            logImportant("通知权限已授予。", android.R.color.holo_green_light)
        } else {
            toast("通知权限被拒绝，部分功能可能受限。")
            logImportant("通知权限被拒绝，前台服务通知可能无法显示。", android.R.color.holo_orange_light)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ***关键改动：将 connectivityManager 初始化移到最前面***
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setupNetworkCallback() // 确保网络回调也提前设置

        statusText = findViewById(R.id.statusText)
        lastRefreshTimeText = findViewById(R.id.lastRefreshTimeText)
        btnToggleClash = findViewById(R.id.btnToggleClash)
        btnSpeedTestUdp = findViewById(R.id.btnSpeedTestUdp)
        btnSpeedTestTcp = findViewById(R.id.btnSpeedTestTcp)
        btnExit = findViewById(R.id.btnExit)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        redirectOutputToLogView()
        log("应用已启动。正在加载配置...")

        // **新增：检查并请求 POST_NOTIFICATIONS 权限**
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU 是 Android 13 的 API 级别
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                logImportant("正在请求通知权限...", android.R.color.holo_orange_light)
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                logImportant("通知权限已存在。", android.R.color.holo_green_light)
            }
        }

        // 在加载配置前请求存储权限
        checkAndRequestPermissions() // 这会处理 READ/WRITE_EXTERNAL_STORAGE

        // 初始状态，等待网络连接
        updateStatusText("Clash 状态未知 (等待网络)", android.R.color.darker_gray, "N/A")
        setButtonsEnabled(false)
        log("等待网络连接状态更新以建立 SSH 连接和启动监测。")

        btnToggleClash.setOnClickListener {
            log("点击：切换 Clash 开关")
            toggleClash()
        }
        btnSpeedTestUdp.setOnClickListener {
            log("点击：测速（UDP）")
            runSpeedTest(isUdp = true)
        }
        btnSpeedTestTcp.setOnClickListener {
            log("点击：测速（TCP）")
            runSpeedTest(isUdp = false)
        }
        btnExit.setOnClickListener {
            log("点击：退出并清理缓存")
            clearUserData()
            finishAffinity()
        }
    }
    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    logImportant(
                        "网络已连接/可用。具备互联网能力。尝试建立 SSH 连接。",
                        android.R.color.holo_blue_light
                    )
                    connectSSHAndStartStatusCheck()
                } else {
                    log("网络可用但无互联网能力。暂不尝试SSH连接。")
                }
            }
            override fun onLost(network: Network) {
                super.onLost(network)
                logImportant("网络已断开连接。SSH 会话将断开。", android.R.color.holo_orange_light)
                disconnectSshAndStopMonitoring("网络断开")
            }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    if (sshSession?.isConnected != true && !isConnectingSsh) {
                        log("网络能力变化：检测到互联网连接。尝试SSH连接。")
                        connectSSHAndStartStatusCheck()
                    }
                } else {
                    log("网络能力变化：互联网连接丢失。")
                    if (sshSession?.isConnected == true) {
                        logImportant(
                            "检测到互联网连接丢失，SSH 会话将断开。",
                            android.R.color.holo_orange_light
                        )
                        disconnectSshAndStopMonitoring("网络无互联网")
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        log("应用进入前台。")
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        val currentNetwork = connectivityManager.activeNetwork
        if (currentNetwork != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(currentNetwork)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                if (sshSession?.isConnected != true && !isConnectingSsh) {
                    log("应用在前台恢复，网络已连接，尝试SSH连接。")
                    connectSSHAndStartStatusCheck()
                } else if (sshSession?.isConnected == true) {
                    log("应用在前台恢复，SSH已连接，继续监测。")
                    startClashStatusCheckTimer()
                } else {
                    log("应用在前台恢复，SSH未连接但正在等待或SSH已断开。")
                    updateStatusText("Clash 状态未知 (SSH 未连接)", android.R.color.darker_gray, "SSH 未连接")
                    setButtonsEnabled(false)
                }
            } else {
                log("应用在前台恢复，但网络无互联网能力。")
                updateStatusText("Clash 状态未知 (网络无互联网)", android.R.color.darker_gray, "网络无互联网")
                setButtonsEnabled(false)
            }
        } else {
            log("应用在前台恢复，但无可用网络。")
            updateStatusText("Clash 状态未知 (无网络)", android.R.color.darker_gray, "无网络")
            setButtonsEnabled(false)
        }
    }

    override fun onPause() {
        super.onPause()
        log("应用进入后台。")
        connectivityManager.unregisterNetworkCallback(networkCallback)
        disconnectSshAndStopMonitoring("应用进入后台")
        clashStatusJob?.cancel() // 明确取消定时任务协程
        log("SSH 会话已断开，状态检查已暂停。网络监测已停止。")
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope 会自动处理这里的取消，但显式取消也是安全的
        clashStatusJob?.cancel() // 明确取消定时任务协程
        sshSession?.disconnect()
        log("应用销毁，SSH 会话已断开，状态检查已停止。")
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
    }
    private fun redirectOutputToLogView() {
        originalOut = System.out
        originalErr = System.err

        val outputStream = object : OutputStream() {
            private val stringBuilder = StringBuilder()
            override fun write(b: Int) {
                if (b == '\n'.toInt()) {
                    val line = stringBuilder.toString()
                    runOnUiThread {
                        logTextView.append(line + "\n")
                        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                    stringBuilder.clear()
                } else {
                    stringBuilder.append(b.toChar())
                }
            }
        }
        System.setOut(PrintStream(outputStream))
        System.setErr(PrintStream(outputStream))
    }
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "$timestamp - $message"
        runOnUiThread {
            logTextView.append(formattedMessage + "\n")
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
    private fun logImportant(message: String, colorResId: Int) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "$timestamp - $message"
        runOnUiThread {
            val color = ContextCompat.getColor(this, colorResId)
            val spannableString = SpannableString(formattedMessage)
            spannableString.setSpan(ForegroundColorSpan(color), 0, formattedMessage.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            logTextView.append(spannableString)
            logTextView.append("\n")
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
    private fun updateStatusText(status: String, statusColorResId: Int, refreshText: String) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            statusText.text = status
            statusText.setTextColor(ContextCompat.getColor(this, statusColorResId))
            lastRefreshTimeText.text = "上次刷新: $refreshText"
            lastRefreshTimeText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
    private fun setButtonsEnabled(enabled: Boolean) {
        runOnUiThread {
            btnToggleClash.isEnabled = enabled
            btnSpeedTestUdp.isEnabled = enabled
            btnSpeedTestTcp.isEnabled = enabled
            // btnExit 按钮通常不需要禁用
        }
    }
    private fun disconnectSshAndStopMonitoring(reason: String) {
        sshSession?.disconnect()
        sshSession = null
        clashStatusJob?.cancel() // 替换为协程 Job 取消
        clashStatusJob = null // 替换为协程 Job 设为空
        log("SSH 会话已断开 ($reason)，状态检查已暂停。")
        runOnUiThread {
            updateStatusText("Clash 状态未知 ($reason)", android.R.color.darker_gray, reason)
            setButtonsEnabled(false)
            lastKnownClashRunningStatus = null
        }
    }
    private fun checkAndRequestPermissions() {
        // 先检查存储权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            logImportant("未获取存储权限，正在请求...", android.R.color.holo_orange_light)
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION)
        } else {
            logImportant("已获取存储权限，尝试加载配置。", android.R.color.holo_green_light)
            loadConfig()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logImportant("存储权限已授予。", android.R.color.holo_green_light)
                loadConfig()
            } else {
                logImportant("存储权限被拒绝。无法从外部存储加载配置文件。", android.R.color.holo_red_dark)
                runOnUiThread { toast("存储权限被拒绝，无法加载配置文件。") }
                setButtonsEnabled(false)
                btnExit.isEnabled = false
            }
        }
    }
    private fun loadConfig() {
        try {
            val appSpecificExternalDir = getExternalFilesDir(null)
            if (appSpecificExternalDir == null) {
                logImportant("错误：无法获取外部文件目录。请检查设备存储状态。", android.R.color.holo_red_dark)
                runOnUiThread { toast("无法获取存储目录！") }
                setButtonsEnabled(false)
                btnExit.isEnabled = false
                return
            }
            val configDir = File(appSpecificExternalDir.absolutePath)
            val configFile = File(configDir, "config.json")
            // 检查并创建目录
            if (!configDir.exists()) {
                log("配置目录不存在，正在尝试创建: ${configDir.absolutePath}")
                val created = configDir.mkdirs()
                if (created) {
                    log("配置目录创建成功。")
                } else {
                    logImportant("错误：无法创建配置目录: ${configDir.absolutePath}", android.R.color.holo_red_dark)
                    runOnUiThread { toast("无法创建配置目录！") }
                    setButtonsEnabled(false)
                    btnExit.isEnabled = false
                    return
                }
            }
            // 检查配置文件是否存在，如果不存在则创建默认文件
            if (!configFile.exists()) {
                logImportant("配置文件 config.json 不存在。正在创建默认文件...", android.R.color.holo_orange_light)
                val defaultConfigContent = "{\n" +
                        "  \"host\": \"\",\n" +
                        "  \"username\": \"\",\n" +
                        "  \"password\": \"\"\n" +
                        "}"
                try {
                    FileWriter(configFile).use { writer ->
                        writer.write(defaultConfigContent)
                        writer.flush()
                    }
                    logImportant("默认配置文件 config.json 已创建。请手动编辑并填入您的SSH连接信息。", android.R.color.holo_green_light)
                    runOnUiThread { toast("config.json 文件已创建，请填写您的SSH信息。") }
                    // 创建了空文件后，无法立即连接，需要用户手动填写，所以这里不尝试连接SSH
                    setButtonsEnabled(false) // 禁用按钮直到用户填写配置
                    return // 不继续加载配置，等待用户填写
                } catch (e: IOException) {
                    logImportant("错误：创建默认配置文件失败: ${e.message}", android.R.color.holo_red_dark)
                    runOnUiThread { toast("创建默认配置文件失败！") }
                    setButtonsEnabled(false)
                    btnExit.isEnabled = false
                    return
                }
            }
            val jsonText = configFile.readText(StandardCharsets.UTF_8)
            config = JSONObject(jsonText)
            log("配置文件加载成功。路径: ${configFile.absolutePath}")
            // 配置文件加载成功后，检查网络并尝试连接 SSH
            // 由于 connectivityManager 现在已在 onCreate 中初始化，此处不再会报错
            val currentNetwork = connectivityManager.activeNetwork
            if (currentNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(currentNetwork)
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    connectSSHAndStartStatusCheck()
                } else {
                    log("网络已连接但无互联网能力。等待网络连接以建立 SSH 连接。")
                }
            } else {
                log("当前无可用网络。等待网络连接以建立 SSH 连接。")
            }
        } catch (e: Exception) {
            logImportant("错误：加载配置文件失败: ${e.message}", android.R.color.holo_red_dark)
            runOnUiThread { toast("加载配置文件失败！请检查config.json文件。") }
            setButtonsEnabled(false)
            btnExit.isEnabled = false
        }
    }
    private fun connectSSHAndStartStatusCheck() {
        if (isConnectingSsh) {
            log("SSH 连接尝试已在进行中，跳过本次连接请求。")
            return
        }
        if (sshSession?.isConnected == true) {
            logImportant("SSH 会话已存在且连接中，无需重复连接。确保监测运行。", R.color.ssh_already_connected_color)
            startClashStatusCheckTimer()
            runOnUiThread {
                updateStatusText("SSH 已连接，正在检查Clash状态...", R.color.ssh_success_color, "连接成功")
                setButtonsEnabled(true)
            }
            return
        }
        if (!::config.isInitialized || config.length() == 0) {
            logImportant("配置未加载或为空，无法建立 SSH 连接。", android.R.color.holo_red_dark)
            runOnUiThread { toast("配置未加载，无法建立SSH连接。") }
            setButtonsEnabled(false)
            return
        }
        isConnectingSsh = true
        logImportant("尝试建立 SSH 长连接...", android.R.color.holo_blue_light)
        thread {
            val host = config.optString("host", "")
            val user = config.optString("username", "")
            val password = config.optString("password", "")
            val port = 22 // 端口固定为22

            if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
                logImportant("配置信息不完整 (主机、用户名或密码为空)，无法建立 SSH 连接。", android.R.color.holo_red_dark)
                runOnUiThread { toast("SSH 配置信息不完整，请检查 config.json。") }
                isConnectingSsh = false
                setButtonsEnabled(false)
                return@thread
            }
            try {
                val jsch = JSch()
                sshSession = jsch.getSession(user, host, port)
                sshSession?.setPassword(password)
                sshSession?.setConfig("StrictHostKeyChecking", "no")
                sshSession?.connect(SSH_CONNECT_TIMEOUT)

                if (sshSession?.isConnected == true) {
                    logImportant("SSH 长连接建立成功！", R.color.ssh_success_color)
                    runOnUiThread { toast("SSH 连接成功！") }
                    startClashStatusCheckTimer()
                    log("开始长监测 Clash 状态。")
                    runOnUiThread {
                        updateStatusText("SSH 已连接，正在检查Clash状态...", R.color.ssh_success_color, "连接成功")
                        setButtonsEnabled(true)
                    }
                } else {
                    logImportant("SSH 长连接建立失败：无法建立会话。", android.R.color.holo_red_dark)
                    runOnUiThread { toast("SSH 连接失败，请检查配置和网络。") }
                    setButtonsEnabled(false)
                }
            } catch (e: Exception) {
                logImportant("SSH 长连接错误: ${e.message}", android.R.color.holo_red_dark)
                runOnUiThread { toast("SSH 连接错误: ${e.message}") }
                setButtonsEnabled(false)
            } finally {
                isConnectingSsh = false
            }
        }
    }

    // 使用协程替代 Timer 进行定时任务
    private fun startClashStatusCheckTimer() {
        // 如果任务已经在运行，先取消它
        clashStatusJob?.cancel()

        // 使用 lifecycleScope.launch 创建一个协程，它会随着Activity的生命周期自动管理
        clashStatusJob = lifecycleScope.launch(Dispatchers.IO) { // 在IO调度器上执行后台任务
            while (isActive) { // 检查协程是否活跃，未被取消
                try {
                    // 执行后台任务
                    checkClashStatus()
                } catch (e: Exception) {
                    // 协程内部的异常处理
                    // 如果是 CancellationException，说明是被正常取消，无需处理
                    if (e !is CancellationException) {
                        logImportant("Clash状态检查协程发生错误: ${e.message}", android.R.color.holo_red_dark)
                    }
                }
                delay(CLASH_STATUS_CHECK_INTERVAL) // 延迟，非阻塞
            }
            log("Clash状态检查协程已停止。")
        }
        log("Clash 状态检查已启动 (协程)。")
    }

    private fun checkClashStatus() {
        if (sshSession?.isConnected != true) {
            runOnUiThread {
                if (statusText.text.toString().contains("Clash 状态未知 (SSH 断开)").not()) {
                    logImportant("SSH 会话未连接，无法检查 Clash 状态。", android.R.color.darker_gray)
                    updateStatusText("Clash 状态未知 (SSH 断开)", android.R.color.darker_gray, "SSH 断开")
                    setButtonsEnabled(false)
                    lastKnownClashRunningStatus = null
                }
            }
            return
        }

        var isClashActuallyRunning: Boolean? = null
        var commandExecutionMessage = ""

        try {
            val statusCommand = "/etc/init.d/openclash status"
            val statusRawResult = execSSHCommandInternal(sshSession!!, statusCommand)
            val statusOutputLower = statusRawResult.lowercase().trim()

            if (!statusOutputLower.contains("执行失败") && !statusOutputLower.contains("命令错误") && !statusOutputLower.contains("not found") && !statusOutputLower.contains("no such file or directory")) {
                if (statusOutputLower.contains("stopped") || statusOutputLower.contains("inactive") ||
                    statusOutputLower.contains("not running") || statusOutputLower.contains("failed")) {
                    isClashActuallyRunning = false
                } else if (statusOutputLower.contains("running") || statusOutputLower.contains("active")) {
                    isClashActuallyRunning = true
                } else {
                    commandExecutionMessage = "警告：/etc/init.d/openclash status 命令输出不明确。\n原始输出: ${statusRawResult.trim()}"
                    isClashActuallyRunning = null
                }
            } else {
                commandExecutionMessage = "警告：/etc/init.d/openclash status 命令执行失败。\n错误信息: ${statusRawResult.trim()}"
                isClashActuallyRunning = null
            }
        } catch (e: Exception) {
            commandExecutionMessage = "检查 Clash 状态时发生异常: ${e.message}"
            isClashActuallyRunning = null
        }
        runOnUiThread {
            if (commandExecutionMessage.isNotEmpty()) {
                logImportant(commandExecutionMessage, android.R.color.holo_orange_dark)
            }
            if (lastKnownClashRunningStatus != isClashActuallyRunning) {
                lastKnownClashRunningStatus = isClashActuallyRunning
                when (isClashActuallyRunning) {
                    true -> {
                        updateStatusText("Clash 正在运行 (持续监测)", R.color.clash_running_color, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                        logImportant("Clash 状态已更新为：运行中", R.color.clash_running_color)
                    }
                    false -> {
                        updateStatusText("Clash 未启动 (持续监测)", R.color.clash_stopped_color, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                        logImportant("Clash 状态已更新为：未启动", R.color.clash_stopped_color)
                    }
                    null -> {
                        updateStatusText("Clash 状态未知 (检查失败)", android.R.color.darker_gray, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                        if (commandExecutionMessage.isEmpty()) {
                            logImportant("Clash 状态未知。", android.R.color.darker_gray)
                        }
                    }
                }
            }
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            lastRefreshTimeText.text = "上次刷新: $currentTime"
            lastRefreshTimeText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            val isSshConnected = sshSession?.isConnected == true
            setButtonsEnabled(isSshConnected)
        }
    }
    private fun toggleClash() {
        val currentStateIsRunning = lastKnownClashRunningStatus ?: false
        log("当前Clash状态 (根据上次检查): ${if (currentStateIsRunning) "运行中" else "未启动"}")
        log("正在发送切换 Clash 命令...")
        val command: String
        val intendedNewState: Boolean
        if (currentStateIsRunning) {
            command = "uci set openclash.config.enable=0 && uci commit && /etc/init.d/openclash stop"
            intendedNewState = false
        } else {
            command = "uci set openclash.config.enable=1 && uci commit && /etc/init.d/openclash start"
            intendedNewState = true
        }

        runOnUiThread {
            if (intendedNewState) {
                updateStatusText("Clash 正在启动中...", R.color.clash_pending_start_color, "命令已发送")
                logImportant("Clash 状态预更新为：启动中...", R.color.clash_pending_start_color)
            } else {
                updateStatusText("Clash 正在停止中...", R.color.clash_pending_stop_color, "命令已发送")
                logImportant("Clash 状态预更新为：停止中...", R.color.clash_pending_stop_color)
            }
        }
        thread {
            val result = execSSHCommand(command)

            runOnUiThread {
                if (!result.contains("执行失败") && !result.contains("命令错误") && !result.contains("not found") && !result.contains("no such file or directory")) {
                    logImportant("Clash 切换命令已成功发送。等待后台状态检查更新UI...", R.color.clash_command_sent_color)
                    toast("Clash 切换命令已发送。")
                } else {
                    logImportant("错误：切换 Clash 状态失败。\n${result.trim()}", android.R.color.holo_red_dark)
                    toast("切换 Clash 失败，请查看日志。")
                }
            }
            Thread.sleep(3000)
            checkClashStatus()
        }
    }
    private fun runSpeedTest(isUdp: Boolean) {
        if (sshSession?.isConnected != true) {
            logImportant("SSH 未连接，无法执行测速。", android.R.color.holo_red_dark)
            runOnUiThread { toast("SSH 未连接，无法测速。") }
            return
        }
        log("正在准备${if (isUdp) "UDP" else "TCP"}测速...")
        thread {
            try {
                val host = config.getString("host")
                log("尝试在服务器上启动 iperf3 服务端...")
                val startServerCommand = "iperf3 -s -D"
                val startServerResult = execSSHCommandInternal(sshSession!!, startServerCommand)
                log("启动 iperf3 服务端结果:\n$startServerResult")
                if (startServerResult.contains("error") || startServerResult.contains("failed") || startServerResult.contains("not found") || startServerResult.contains("No such file or directory")) {
                    logImportant("错误：启动 iperf3 服务端失败。请确认服务器已安装 iperf3。\n${startServerResult.trim()}", android.R.color.holo_red_light)
                    runOnUiThread { toast("启动 iperf3 服务端失败，请查看日志。") }
                    return@thread
                }
                logImportant("iperf3 服务端已在服务器上启动。等待片刻...", R.color.iperf_server_start_color)
                Thread.sleep(1000)
                val clientCommand = if (isUdp) {
                    "iperf3 -c $host -u -b 10M -t 5"
                } else {
                    "iperf3 -c $host -t 5"
                }
                log("正在执行 iperf3 ${if (isUdp) "UDP" else "TCP"} 测速命令: $clientCommand")
                val speedTestResult = execSSHCommandInternal(sshSession!!, clientCommand)
                log("${if (isUdp) "UDP" else "TCP"} 测速完成。原始输出:\n$speedTestResult")
                if (speedTestResult.contains("error") || speedTestResult.contains("failed") || speedTestResult.contains("not found") || speedTestResult.contains("No such file or directory")) {
                    logImportant("警告：${if (isUdp) "UDP" else "TCP"} 测速命令返回错误。可能原因：iperf3客户端未安装，或服务器端未正常启动。\n${speedTestResult.trim()}", android.R.color.holo_orange_light)
                }
                var uploadSpeed: String? = null
                var downloadSpeed: String? = null
                var avgLatency: String? = null
                val speedRegex = "(\\d+\\.?\\d*)\\s*(K|M|G)?bits/sec".toRegex()
                if (isUdp) {
                    val senderLine = speedTestResult.lines().lastOrNull { it.contains("sender") }
                    val senderMatch = senderLine?.let { speedRegex.find(it) }
                    uploadSpeed = senderMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { value ->
                        val unit = senderMatch.groupValues.get(2)
                        convertSpeedToMbps(value, unit)
                    }?.toString()
                    val receiverLine = speedTestResult.lines().lastOrNull { it.contains("receiver") }
                    val receiverMatch = receiverLine?.let { speedRegex.find(it) }
                    downloadSpeed = receiverMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { value ->
                        val unit = receiverMatch.groupValues.get(2)
                        convertSpeedToMbps(value, unit)
                    }?.toString()
                    log("正在进行延时（Ping）测试...")
                    val pingCommand = "ping -c 4 $host"
                    val pingResult = execSSHCommandInternal(sshSession!!, pingCommand)
                    log("延时（Ping）测试完成。原始输出:\n$pingResult")
                    if (pingResult.contains("not found") || pingResult.contains("No such file or directory")) {
                        logImportant("警告：ping 命令未找到。请确认服务器已安装 ping 工具。", android.R.color.holo_orange_light)
                    } else {
                        val avgDelayRegex = "(?:round-trip\\s+)?min/avg/max\\s*=\\s*[0-9.]+/([0-9.]+)/[0-9.]+\\s*ms".toRegex()
                        val matchResult = avgDelayRegex.find(pingResult)
                        avgLatency = matchResult?.groupValues?.get(1)

                        if (avgLatency == null) {
                            logImportant("未能从 Ping 结果中解析出平均延时。请检查 Ping 命令输出格式。\n${pingResult.trim()}", android.R.color.holo_orange_light)
                        }
                    }
                    var udpSummary = "UDP 测速结果：\n"
                    udpSummary += "发送速度 (上传): ${uploadSpeed ?: "N/A"} Mbits/sec\n"
                    udpSummary += "接收速度 (下载): ${downloadSpeed ?: "N/A"} Mbits/sec\n"
                    udpSummary += "平均延时 (Ping): ${avgLatency ?: "N/A"} ms"
                    logImportant(udpSummary, R.color.speed_test_result_color)
                } else {
                    val summaryLine = speedTestResult.lines().lastOrNull {
                        it.contains("receiver") || it.contains("SUM") || it.contains("bits/sec")
                    }
                    val downloadMatch = summaryLine?.let { speedRegex.find(it) }
                    downloadSpeed = downloadMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { value ->
                        val unit = downloadMatch.groupValues.get(2)
                        convertSpeedToMbps(value, unit)
                    }?.toString()
                    var tcpSummary = "TCP 测速结果：\n"
                    tcpSummary += "接收速度 (下载): ${downloadSpeed ?: "N/A"} Mbits/sec"
                    logImportant(tcpSummary, R.color.speed_test_result_color)
                }

            } catch (e: Exception) {
                logImportant("${if (isUdp) "UDP" else "TCP"} 测速过程中发生错误: ${e.message}", android.R.color.holo_red_light)
                runOnUiThread { toast("${if (isUdp) "UDP" else "TCP"} 测速发生错误，请查看日志。") }
            } finally {
                log("尝试在服务器上关闭 iperf3 服务端...")
                val stopServerCommand = "killall iperf3"
                val stopServerResult = execSSHCommandInternal(sshSession!!, stopServerCommand)
                log("关闭 iperf3 服务端结果:\n$stopServerResult")
                if (stopServerResult.contains("error") || stopServerResult.contains("failed") || stopServerResult.contains("not found") || stopServerResult.contains("No such file or directory")) {
                    logImportant("警告：关闭 iperf3 服务端可能失败，请手动检查或其未运行。\n${stopServerResult.trim()}", android.R.color.holo_orange_light)
                } else {
                    logImportant("iperf3 服务端关闭命令已发送。", R.color.iperf_server_stop_color)
                }
                Thread.sleep(1000)
                val checkRunningCommand = "ps | grep iperf3 | grep -v grep"
                val checkRunningResult = execSSHCommandInternal(sshSession!!, checkRunningCommand)
                if (checkRunningResult.trim().isEmpty()) {
                    logImportant("iperf3 服务已确认停止。", R.color.iperf_server_stop_color)
                } else {
                    logImportant("警告：iperf3 服务可能未能完全停止，请手动检查。进程信息:\n${checkRunningResult.trim()}", android.R.color.holo_orange_light)
                }
            }
        }
    }
    private fun convertSpeedToMbps(value: Double, unit: String?): Double {
        return when (unit?.uppercase()) {
            "K" -> value / 1024.0
            "G" -> value * 1024.0
            "M", null -> value
            else -> value
        }
    }
    private fun execSSHCommand(command: String): String {
        if (sshSession?.isConnected != true) {
            logImportant("警告：SSH 会话未连接，无法执行命令：$command。", android.R.color.holo_red_dark)
            runOnUiThread { toast("SSH 未连接，无法执行操作。") }
            return "执行失败: SSH 未连接。"
        }
        return execSSHCommandInternal(sshSession!!, command)
    }
    private fun execSSHCommandInternal(session: Session, command: String): String {
        var channel: ChannelExec? = null
        try {
            channel = session.openChannel("exec") as ChannelExec

            channel.setPty(false)

            channel.setCommand(command)
            channel.inputStream = null

            channel.connect()

            val input = channel.inputStream
            val errStream = channel.extInputStream

            val outputBytes = readAllBytes(input)
            val errorBytes = readAllBytes(errStream)

            val output = String(outputBytes, StandardCharsets.UTF_8)
            val error = String(errorBytes, StandardCharsets.UTF_8)

            return if (error.isNotEmpty()) {
                "命令执行过程中发生错误:\n$error\n\n命令输出:\n$output"
            } else {
                output
            }
        } catch (e: Exception) {
            logImportant("SSH 通道执行命令失败: ${e.message}", android.R.color.holo_red_dark)
            if (e is java.net.SocketException || e is com.jcraft.jsch.JSchException) {
                disconnectSshAndStopMonitoring("SSH 连接错误")
            }
            return "执行失败: ${e.message}"
        } finally {
            channel?.disconnect()
        }
    }
    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(1024)
        val byteArrayOutputStream = ByteArrayOutputStream()
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead)
        }
        return byteArrayOutputStream.toByteArray()
    }
    private fun clearUserData() {
        log("正在清理用户数据...")
        val prefs = getSharedPreferences("data", MODE_PRIVATE)
        prefs.edit().clear().apply()
        log("用户数据已清除。")
        Toast.makeText(this, "用户数据已清除", Toast.LENGTH_SHORT).show()
    }
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}