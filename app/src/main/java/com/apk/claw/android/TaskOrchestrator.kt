package com.apk.claw.android

import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.AgentService
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog

/**
 * 任务编排器，负责 Agent 生命周期管理、任务锁、任务执行与回调处理。
 *
 * @param agentConfigProvider 延迟获取最新 AgentConfig 的回调
 * @param onTaskFinished 每次任务结束（成功/失败/取消）后的通知，用于刷新用户信息等
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService

    private val taskLock = Any()
    @Volatile
    var inProgressTaskMessageId: String = ""
        private set
    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set

    // ==================== Agent 生命周期 ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== 任务锁 ====================

    /**
     * 原子地尝试获取任务锁。如果当前无任务在执行，则标记为占用并返回 true；否则返回 false。
     */
    fun tryAcquireTask(messageId: String, channel: Channel): Boolean {
        synchronized(taskLock) {
            if (inProgressTaskMessageId.isNotEmpty()) return false
            inProgressTaskMessageId = messageId
            inProgressTaskChannel = channel
            return true
        }
    }

    /**
     * 释放任务锁，返回释放前的 (channel, messageId) 供调用方使用。
     */
    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(taskLock) {
            val ch = inProgressTaskChannel
            val id = inProgressTaskMessageId
            inProgressTaskMessageId = ""
            inProgressTaskChannel = null
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(taskLock) {
            return inProgressTaskMessageId.isNotEmpty()
        }
    }

    // ==================== 任务执行 ====================

    fun cancelCurrentTask() {
        if (!isTaskRunning()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        val (channel, messageId) = releaseTask()
        if (channel != null && messageId.isNotEmpty()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), messageId)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun startNewTask(channel: Channel, task: String, messageID: String) {
        XLog.i(TAG, "=== 开始新任务 ===")
        XLog.i(TAG, "通道: ${channel.displayName}")
        XLog.i(TAG, "任务内容: ${task.take(100)}${if (task.length > 100) "..." else ""}")
        XLog.i(TAG, "消息ID: ${messageID.take(20)}")
        
        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService 未初始化，尝试初始化")
            try {
                agentService = AgentServiceFactory.create()
                val config = agentConfigProvider()
                XLog.i(TAG, "LLM配置 - Provider: ${config.provider}, Model: ${config.modelName}, BaseURL: ${config.baseUrl}")
                agentService.initialize(config)
                XLog.i(TAG, "AgentService 初始化成功")
            } catch (e: Exception) {
                XLog.e(TAG, "AgentService 初始化失败", e)
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        XLog.i(TAG, "按下Home键，准备执行任务")
        ClawAccessibilityService.getInstance()?.pressHome()

        XLog.i(TAG, "显示悬浮窗通知")
        FloatingCircleManager.showTaskNotify(task, channel)

        // 每轮消息聚合缓冲：thinking + toolResult 攒成一条，减少发送次数
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                val content = roundBuffer.toString().trim()
                XLog.i(TAG, "发送本轮累积消息 (${content.length} 字符)")
                ChannelManager.sendMessage(channel, content, messageID)
                roundBuffer.clear()
            }
        }

        XLog.i(TAG, "开始执行Agent任务")
        agentService.executeTask(task, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                // 新一轮开始前，flush 上一轮积攒的消息
                flushRoundBuffer()
                XLog.i(TAG, "--- LLM推理轮次 $round 开始 ---")
                FloatingCircleManager.setRunningState(round, channel)
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    XLog.d(TAG, "LLM输出内容 (轮次$round): ${content.take(50)}${if (content.length > 50) "..." else ""}")
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.i(TAG, "[轮次$round] 调用工具: $toolName")
                XLog.i(TAG, "  参数: $parameters")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                
                XLog.i(TAG, "[轮次$round] 工具结果: $toolName - $status")
                
                if (data != null) {
                    val preview = if (data.length > 200) data.substring(0, 200) + "..." else data
                    XLog.i(TAG, "  结果数据: $preview")
                }
                
                if (!result.isSuccess) {
                    XLog.e(TAG, "  ❌ 工具执行失败: $toolName")
                    XLog.e(TAG, "  错误详情: $data")
                }
                
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    // finish 的结果单独发，不合并（这是最终回复）
                    XLog.i(TAG, "收到最终回复，立即发送")
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    // 追加到本轮缓冲
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(
                        app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status)
                    )
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                XLog.i(TAG, "=== 任务完成 ===")
                XLog.i(TAG, "总轮数: $round")
                XLog.i(TAG, "总Token数: $totalTokens")
                XLog.i(TAG, "最终答案长度: ${finalAnswer.length} 字符")
                XLog.i(TAG, "最终答案预览: ${finalAnswer.take(100)}${if (finalAnswer.length > 100) "..." else ""}")
                
                flushRoundBuffer()
                releaseTask()
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                XLog.i(TAG, "任务清理完成")
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "=== 任务出错 ===")
                XLog.e(TAG, "轮次: $round")
                XLog.e(TAG, "总Token数: $totalTokens")
                XLog.e(TAG, "错误信息: ${error.message}")
                XLog.e(TAG, "错误堆栈:", error)
                
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "=== 系统弹窗阻塞 ===")
                XLog.w(TAG, "轮次: $round")
                XLog.w(TAG, "总Token数: $totalTokens")
                
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked), messageID)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        XLog.i(TAG, "截取屏幕成功，发送截图")
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(channel, stream.toByteArray(), messageID)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "发送截图失败", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }
}
