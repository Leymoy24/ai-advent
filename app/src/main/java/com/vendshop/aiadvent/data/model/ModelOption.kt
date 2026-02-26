package com.vendshop.aiadvent.data.model

/** Вариант модели: слабая или сильная (2 модели DeepSeek). */
data class ModelOption(
    val id: String,
    val displayName: String,
    val tier: ModelTier,
    val maxTokens: Int = 1024,
    val temperature: Double = 0.7
)

enum class ModelTier {
    WEAK,   // Слабая — быстрая, дешёвая
    STRONG  // Сильная — рассуждения
}

object ModelOptions {
    val all = listOf(
        ModelOption(
            id = "deepseek-chat",
            displayName = "Слабая (deepseek-chat)",
            tier = ModelTier.WEAK,
            maxTokens = 256,
            temperature = 0.5
        ),
        ModelOption(
            id = "deepseek-reasoner",
            displayName = "Сильная (deepseek-reasoner)",
            tier = ModelTier.STRONG,
            maxTokens = 2048,
            temperature = 0.7
        )
    )

    /** Цены DeepSeek API ($/1M токенов): Input $0.28, Output $0.42 */
    const val PRICE_INPUT_PER_1M = 0.28
    const val PRICE_OUTPUT_PER_1M = 0.42

    fun calculateCost(promptTokens: Int, completionTokens: Int): Double =
        (promptTokens * PRICE_INPUT_PER_1M + completionTokens * PRICE_OUTPUT_PER_1M) / 1_000_000
}
