package com.vendshop.aiadvent.data.model

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<Message>,
    val temperature: Double = 0.7,
    val stream: Boolean = true,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null
)

data class ResponseFormat(
    val type: String = "text" // "text" or "json_object"
)

data class Message(
    val role: String,
    val content: String
)

data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val message: Message,
    @SerializedName("finish_reason") val finishReason: String?
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0
)

