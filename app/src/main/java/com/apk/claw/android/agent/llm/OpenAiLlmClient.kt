package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import com.apk.claw.android.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class OpenAiLlmClient(
    private val config: AgentConfig,
    private val httpClientBuilder: OkHttpClientBuilderAdapter
) : LlmClient {

    companion object {
        private const val TAG = "OllamaAPI"
    }

    private val chatModel: ChatModel by lazy { buildChatModel() }
    private val streamingChatModel: StreamingChatModel by lazy { buildStreamingChatModel() }

    private fun buildChatModel(): ChatModel {
        val builder = OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    private fun buildStreamingChatModel(): StreamingChatModel {
        val builder = OpenAiStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        XLog.i(TAG, "\n========== 📤 Ollama API 请求 (非流式) ==========")
        XLog.i(TAG, "🔗 Base URL: ${config.baseUrl}")
        XLog.i(TAG, "🤖 Model: ${config.modelName}")
        XLog.i(TAG, "🌡️ Temperature: ${config.temperature}")
        XLog.i(TAG, "📝 Messages 数量: ${messages.size}")
        
        messages.forEachIndexed { index, msg ->
            val role = when (msg) {
                is SystemMessage -> "🟦 SYSTEM"
                is UserMessage -> "🟩 USER"
                is AiMessage -> "🟨 AI"
                is ToolExecutionResultMessage -> "🟪 TOOL_RESULT"
                else -> "⬜ UNKNOWN"
            }
            
            val content = when (msg) {
                is SystemMessage -> msg.text()
                is UserMessage -> msg.singleText()
                is AiMessage -> msg.text() ?: "[null]"
                is ToolExecutionResultMessage -> {
                    val resultText = msg.text()
                    if (resultText.length > 500) {
                        resultText.take(500) + "\n... (已截断，总长度: ${resultText.length}字符)"
                    } else {
                        resultText
                    }
                }
                else -> "[unsupported message type]"
            }
            
            XLog.i(TAG, "--- 消息 [$index] $role ---")
            XLog.i(TAG, content)
            
            if (msg is AiMessage && msg.toolExecutionRequests().isNotEmpty()) {
                msg.toolExecutionRequests().forEachIndexed { reqIndex, req ->
                    XLog.i(TAG, "  🔧 工具调用 [$reqIndex]: ${req.name()}")
                    XLog.i(TAG, "     参数: ${req.arguments()}")
                }
            }
        }
        
        XLog.i(TAG, "🛠️ Tool Specifications 数量: ${toolSpecs.size}")
        XLog.i(TAG, "================================================\n")
        
        val startTime = System.currentTimeMillis()
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()
        
        val response = try {
            val resp = chatModel.chat(request)
            val duration = System.currentTimeMillis() - startTime
            XLog.i(TAG, "✅ Ollama API 调用成功，耗时: ${duration}ms")
            resp
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            XLog.e(TAG, "❌ Ollama API 调用失败，耗时: ${duration}ms")
            XLog.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            XLog.e(TAG, "错误信息: ${e.message}")
            XLog.e(TAG, "堆栈跟踪:", e)
            throw e
        }
        
        XLog.i(TAG, "\n========== 📥 Ollama API 响应 ==========")
        
        val aiMessage = response.aiMessage()
        val responseText = aiMessage.text()
        val toolRequests = aiMessage.toolExecutionRequests()
        val toolRequestsList = toolRequests ?: emptyList()
        
        if (!responseText.isNullOrEmpty()) {
            XLog.i(TAG, "💭 AI 思考内容 (${responseText.length}字符):")
            XLog.i(TAG, responseText)
        } else {
            XLog.i(TAG, "💭 AI 思考内容: [空]")
        }
        
        if (toolRequestsList.isNotEmpty()) {
            XLog.i(TAG, "🔧 工具调用数量: ${toolRequestsList.size}")
            toolRequestsList.forEachIndexed { index, req ->
                XLog.i(TAG, "--- 工具 [$index] ---")
                XLog.i(TAG, "名称: ${req.name()}")
                XLog.i(TAG, "ID: ${req.id()}")
                XLog.i(TAG, "参数: ${req.arguments()}")
            }
        } else {
            XLog.i(TAG, "🔧 工具调用: [无]")
        }
        
        val tokenUsage = response.tokenUsage()
        if (tokenUsage != null) {
            XLog.i(TAG, "📊 Token 使用:")
            XLog.i(TAG, "   Input Tokens: ${tokenUsage.inputTokenCount()}")
            XLog.i(TAG, "   Output Tokens: ${tokenUsage.outputTokenCount()}")
            XLog.i(TAG, "   Total Tokens: ${tokenUsage.totalTokenCount()}")
        }
        
        XLog.i(TAG, "=========================================\n")
        
        return response.toLlmResponse()
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        XLog.i(TAG, "\n========== 📤 Ollama API 请求 (流式) ==========")
        XLog.i(TAG, "🔗 Base URL: ${config.baseUrl}")
        XLog.i(TAG, "🤖 Model: ${config.modelName}")
        XLog.i(TAG, "🌡️ Temperature: ${config.temperature}")
        XLog.i(TAG, "📝 Messages 数量: ${messages.size}")
        
        messages.forEachIndexed { index, msg ->
            val role = when (msg) {
                is SystemMessage -> "🟦 SYSTEM"
                is UserMessage -> "🟩 USER"
                is AiMessage -> "🟨 AI"
                is ToolExecutionResultMessage -> "🟪 TOOL_RESULT"
                else -> "⬜ UNKNOWN"
            }
            
            val content = when (msg) {
                is SystemMessage -> msg.text()
                is UserMessage -> msg.singleText()
                is AiMessage -> msg.text() ?: "[null]"
                is ToolExecutionResultMessage -> {
                    val resultText = msg.text()
                    if (resultText.length > 500) {
                        resultText.take(500) + "\n... (已截断)"
                    } else {
                        resultText
                    }
                }
                else -> "[unsupported message type]"
            }
            
            XLog.i(TAG, "--- 消息 [$index] $role ---")
            XLog.i(TAG, content)
            
            if (msg is AiMessage && msg.toolExecutionRequests().isNotEmpty()) {
                msg.toolExecutionRequests().forEachIndexed { reqIndex, req ->
                    XLog.i(TAG, "  🔧 工具调用 [$reqIndex]: ${req.name()}")
                    XLog.i(TAG, "     参数: ${req.arguments()}")
                }
            }
        }
        
        XLog.i(TAG, "🛠️ Tool Specifications 数量: ${toolSpecs.size}")
        XLog.i(TAG, "================================================\n")
        
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()
        val startTime = System.currentTimeMillis()

        XLog.i(TAG, "🔄 开始接收流式响应...")
        
        streamingChatModel.chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(token: String) {
                listener.onPartialText(token)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                val duration = System.currentTimeMillis() - startTime
                XLog.i(TAG, "✅ 流式响应完成，耗时: ${duration}ms")
                
                val llmResponse = response.toLlmResponse()
                
                XLog.i(TAG, "\n========== 📥 Ollama API 响应 (流式) ==========")
                
                val aiMessage = response.aiMessage()
                val responseText = aiMessage.text()
                val toolRequests = aiMessage.toolExecutionRequests()
                val toolRequestsList = toolRequests ?: emptyList()
                
                if (!responseText.isNullOrEmpty()) {
                    XLog.i(TAG, "💭 AI 完整回复 (${responseText.length}字符):")
                    XLog.i(TAG, responseText)
                } else {
                    XLog.i(TAG, "💭 AI 完整回复: [空]")
                }
                
                if (toolRequestsList.isNotEmpty()) {
                    XLog.i(TAG, "🔧 工具调用数量: ${toolRequestsList.size}")
                    toolRequestsList.forEachIndexed { index, req ->
                        XLog.i(TAG, "--- 工具 [$index] ---")
                        XLog.i(TAG, "名称: ${req.name()}")
                        XLog.i(TAG, "ID: ${req.id()}")
                        XLog.i(TAG, "参数: ${req.arguments()}")
                    }
                } else {
                    XLog.i(TAG, "🔧 工具调用: [无]")
                }
                
                val tokenUsage = response.tokenUsage()
                if (tokenUsage != null) {
                    XLog.i(TAG, "📊 Token 使用:")
                    XLog.i(TAG, "   Input Tokens: ${tokenUsage.inputTokenCount()}")
                    XLog.i(TAG, "   Output Tokens: ${tokenUsage.outputTokenCount()}")
                    XLog.i(TAG, "   Total Tokens: ${tokenUsage.totalTokenCount()}")
                }
                
                XLog.i(TAG, "============================================\n")
                
                resultRef.set(llmResponse)
                listener.onComplete(llmResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                val duration = System.currentTimeMillis() - startTime
                XLog.e(TAG, "❌ 流式响应错误，耗时: ${duration}ms")
                XLog.e(TAG, "错误类型: ${error.javaClass.simpleName}")
                XLog.e(TAG, "错误信息: ${error.message}")
                XLog.e(TAG, "堆栈跟踪:", error)
                
                errorRef.set(error)
                listener.onError(error)
                latch.countDown()
            }
        })

        latch.await()
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }
}

internal fun ChatResponse.toLlmResponse(): LlmResponse {
    val aiMessage = aiMessage()
    return LlmResponse(
        text = aiMessage.text(),
        toolExecutionRequests = aiMessage.toolExecutionRequests() ?: emptyList(),
        tokenUsage = tokenUsage()
    )
}
