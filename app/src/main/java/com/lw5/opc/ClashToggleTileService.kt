package com.lw5.opc

import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

class ClashToggleTileService : TileService() {

    private val TAG = "ClashToggleTileLog"
    private val scope = CoroutineScope(Dispatchers.IO)

    // SSH 配置数据类
    data class SshConfig(
        val host: String,
        val username: String,
        val password: String
    )

    private var sshConfig: SshConfig? = null // 用于存储从文件读取的 SSH 配置

    // SSH 端口号，通常是 22，这个不会从 config.json 读取
    private val SSH_PORT = 22

    private fun log(message: String) {
        Log.d(TAG, "${System.currentTimeMillis() % 100000} - $message")
    }

    override fun onCreate() {
        super.onCreate()
        log("ClashToggleTileService 创建。")
        // 在服务创建时尝试加载 SSH 配置
        loadSshConfig()
    }

    override fun onStartListening() {
        super.onStartListening()
        log("onStartListening 调用。")
        // 如果配置未加载或为空，尝试重新加载
        if (sshConfig == null) {
            loadSshConfig()
        }
        // 确保配置已加载后再执行 Clash 状态检查
        sshConfig?.let {
            scope.launch {
                checkClashStatusAndToggle(false) // 仅检查状态，不切换
            }
        } ?: run {
            log("SSH 配置未加载，无法检查 Clash 状态。请检查 config.json 文件。")
            updateTileUI(Tile.STATE_UNAVAILABLE, "配置错误", R.drawable.ic_clash_unknown) // 使用 unknown 图标
        }
    }

    override fun onClick() {
        super.onClick()
        log("瓷块被点击。")
        // 立即将磁贴UI更新为执行中状态，提供即时反馈
        updateTileUI(Tile.STATE_UNAVAILABLE, "执行中...", R.drawable.ic_clash_unknown) // 切换中也使用 unknown 图标

        // 确保配置已加载后再执行 Clash 状态切换
        sshConfig?.let {
            scope.launch {
                checkClashStatusAndToggle(true) // 检查状态并根据当前状态切换
            }
        } ?: run {
            log("SSH 配置未加载，无法切换 Clash 状态。请检查 config.json 文件。")
            updateTileUI(Tile.STATE_UNAVAILABLE, "配置错误", R.drawable.ic_clash_unknown) // 使用 unknown 图标
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        log("onStopListening 调用。")
    }

    override fun onDestroy() {
        super.onDestroy()
        log("ClashToggleTileService 销毁。")
        scope.cancel() // 取消所有协程
    }

    /**
     * 更新快速设置磁贴的 UI (状态, 文本标签, 图标)。
     * @param state 磁贴的状态 (Tile.STATE_ACTIVE, Tile.STATE_INACTIVE, Tile.STATE_UNAVAILABLE)
     * @param label 磁贴上显示的文本标签
     * @param iconResId 磁贴图标的资源ID
     */
    private fun updateTileUI(state: Int, label: String, iconResId: Int) {
        qsTile?.apply {
            this.state = state
            this.label = label
            this.icon = Icon.createWithResource(this@ClashToggleTileService, iconResId)
            updateTile()
            log("瓷块UI已更新: $label ($state) with icon $iconResId") // 添加日志以确认 UI 更新情况
        } ?: log("qsTile 为空，无法更新 UI。")
    }

    // 重载 updateTileUI，方便在只有状态和标签时调用，使用默认图标逻辑
    private fun updateTileUI(state: Int, label: String) {
        val iconResId = when (state) {
            Tile.STATE_ACTIVE -> R.drawable.ic_clash_active
            Tile.STATE_INACTIVE -> R.drawable.ic_clash_inactive
            else -> R.drawable.ic_clash_unknown // 默认使用 unknown 图标
        }
        updateTileUI(state, label, iconResId)
    }


    /**
     * 从指定路径读取 config.json 文件并解析 SSH 配置。
     */
    private fun loadSshConfig() {
        val configFilePath = File(getExternalFilesDir(null), "config.json")
        log("尝试从文件读取 SSH 配置: ${configFilePath.absolutePath}")

        if (!configFilePath.exists()) {
            log("配置文件不存在: ${configFilePath.absolutePath}")
            updateTileUI(Tile.STATE_UNAVAILABLE, "无配置", R.drawable.ic_clash_unknown) // 使用 unknown 图标
            return
        }

        try {
            val inputStream = FileInputStream(configFilePath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }
            reader.close()

            val jsonObject = JSONObject(jsonString)
            val host = jsonObject.getString("host")
            val username = jsonObject.getString("username")
            val password = jsonObject.getString("password") // 读取密码

            sshConfig = SshConfig(host, username, password)
            log("SSH 配置加载成功。Host: ${sshConfig?.host}, User: ${sshConfig?.username}")
        } catch (e: Exception) {
            Log.e(TAG, "加载或解析 SSH 配置文件失败: ${e.message}", e)
            sshConfig = null // 加载失败，清空配置
            updateTileUI(Tile.STATE_UNAVAILABLE, "配置错误", R.drawable.ic_clash_unknown) // 使用 unknown 图标
        }
    }

    /**
     * 连接到 SSH，检查 Clash 状态，并根据 `shouldToggle` 参数决定是否切换 Clash 状态。
     */
    private suspend fun checkClashStatusAndToggle(shouldToggle: Boolean) {
        val currentSshConfig = sshConfig // 获取当前配置
        if (currentSshConfig == null) {
            log("SSH 配置为空，无法执行 Clash 操作。")
            updateTileUI(Tile.STATE_UNAVAILABLE, "配置丢失", R.drawable.ic_clash_unknown) // 使用 unknown 图标
            return
        }

        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(currentSshConfig.username, currentSshConfig.host, SSH_PORT)
            session.setPassword(currentSshConfig.password) // 使用从文件读取的密码
            session.setConfig("StrictHostKeyChecking", "no")
            session.setTimeout(10000) // 10秒超时

            log("尝试连接 SSH (host: ${currentSshConfig.host}, user: ${currentSshConfig.username}, port: $SSH_PORT)")
            session.connect()
            log("SSH 连接成功。")

            // 检查 Clash 当前状态
            val checkCommand = "uci -q get openclash.config.enable"
            val checkChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            checkChannel.setCommand(checkCommand)
            checkChannel.connect()

            val reader = BufferedReader(InputStreamReader(checkChannel.inputStream))
            val output = reader.readLine()?.trim()
            checkChannel.disconnect()

            val isClashRunning = output == "1"
            log("Clash 状态原始输出: '$output' -> 运行中: $isClashRunning")

            if (shouldToggle) {
                // 如果需要切换，根据当前状态决定执行哪个命令
                val toggleCommand = if (isClashRunning) {
                    "/etc/init.d/openclash stop && uci set openclash.config.enable='0' && uci commit openclash"
                } else {
                    "uci set openclash.config.enable='1' && uci commit openclash && /etc/init.d/openclash start"
                }
                log("执行切换命令: $toggleCommand")

                val toggleChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                toggleChannel.setCommand(toggleCommand)
                toggleChannel.connect()
                val toggleOutput = BufferedReader(InputStreamReader(toggleChannel.inputStream)).readText()
                log("切换命令输出: $toggleOutput")
                toggleChannel.disconnect()

                // 再次检查 Clash 状态以确认切换结果
                val newCheckChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                newCheckChannel.setCommand(checkCommand)
                newCheckChannel.connect()
                val newOutput = BufferedReader(InputStreamReader(newCheckChannel.inputStream)).readLine()?.trim()
                newCheckChannel.disconnect()

                val newIsClashRunning = newOutput == "1"
                val newStatus = if (newIsClashRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                val newLabel = if (newIsClashRunning) "Clash 运行中" else "Clash 未启动"
                updateTileUI(newStatus, newLabel)
                log("Clash 状态已切换并更新 UI: $newLabel ($newStatus)")

            } else {
                // 仅检查状态，不切换
                val status = if (isClashRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                val label = if (isClashRunning) "Clash 运行中" else "Clash 未启动"
                updateTileUI(status, label)
                log("Clash 状态已更新 UI (仅检查): $label ($status)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "SSH 操作失败: ${e.message}", e)
            updateTileUI(Tile.STATE_UNAVAILABLE, "SSH 错误", R.drawable.ic_clash_unknown) // 使用 unknown 图标
        } finally {
            session?.disconnect()
            log("SSH 连接已断开。")
        }
    }
}