package com.vendshop.aiadvent.ui.screen.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vendshop.aiadvent.data.model.ModelOptions

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    var userInput by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val isBusy = uiState.isLoading || uiState.comparisonInProgress || uiState.modelComparisonInProgress

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Верхняя панель с выбором модели (кнопка в правом верхнем углу)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            IconButton(onClick = { modelMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Выбор модели",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false }
            ) {
                ModelOptions.all.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.displayName,
                                fontWeight = if (option == uiState.selectedModel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            viewModel.selectModel(option)
                            modelMenuExpanded = false
                        }
                    )
                }
            }
        }

        // Верхняя часть: область ответа на весь экран (не перекрывая карточку ввода)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val hasResponse = uiState.response.isNotEmpty() ||
                    uiState.responseUnrestricted != null ||
                    uiState.responseRestricted != null ||
                    uiState.modelComparisonResults != null ||
                    uiState.error != null ||
                    isBusy

            if (!hasResponse) {
                // Пустое состояние: иконка и "Чем могу помочь?"
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QuestionAnswer,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Чем могу помочь?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Прокручиваемая область с ответом (и при сравнении — два блока)
                val scrollState = rememberScrollState()
                LaunchedEffect(
                    uiState.response,
                    uiState.responseUnrestricted,
                    uiState.responseRestricted,
                    uiState.modelComparisonResults
                ) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Заголовок — вопрос пользователя
                    if (uiState.lastUserQuestion.isNotEmpty()) {
                        Text(
                            text = uiState.lastUserQuestion,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    when {
                        uiState.error != null -> {
                            Text(
                                text = "Ошибка: ${uiState.error}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        uiState.modelComparisonResults != null -> {
                            uiState.modelComparisonResults!!.forEach { result ->
                                ModelComparisonCard(
                                    result = result,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            // Краткий вывод и ссылки
                            if (!uiState.modelComparisonInProgress) {
                                ComparisonSummaryCard(
                                    results = uiState.modelComparisonResults!!,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            if (uiState.modelComparisonInProgress) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.size(12.dp))
                                        Text(
                                            "Сравниваем 2 модели...",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        uiState.responseUnrestricted != null || uiState.responseRestricted != null -> {
                            if (uiState.responseUnrestricted != null) {
                                ResponseCard(
                                    title = "Без ограничений",
                                    text = uiState.responseUnrestricted!!,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            if (uiState.responseRestricted != null) {
                                ResponseCard(
                                    title = "С ограничениями (формат, max_tokens=150, stop=\"---\")",
                                    text = uiState.responseRestricted!!,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            if (uiState.comparisonInProgress) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.size(12.dp))
                                        Text(
                                            "Загружаем второй ответ...",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            Text(
                                text = uiState.response,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                lineHeight = 24.sp
                            )
                            if (uiState.isLoading) {
                                Spacer(modifier = Modifier.height(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Нижняя часть: поле ввода
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Введите ваш запрос...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                enabled = !isBusy,
                shape = RoundedCornerShape(20.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                trailingIcon = {
                    if (userInput.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    viewModel.sendMessage(userInput, SendMode.MODEL_COMPARISON)
                                    userInput = ""
                                },
                                enabled = !isBusy
                            ) {
                                Text(
                                    text = "2×",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.sendMessage(userInput, SendMode.NORMAL)
                                    userInput = ""
                                },
                                enabled = !isBusy
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Отправить",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

private val DEEPSEEK_DOCS_URL = "https://api-docs.deepseek.com"
private val DEEPSEEK_PRICING_URL = "https://api-docs.deepseek.com/quick_start/pricing"

@Composable
fun ComparisonSummaryCard(
    results: List<ModelComparisonResult>,
    modifier: Modifier = Modifier
) {
    val fastest = results.minByOrNull { it.elapsedMs }
    val cheapest = results.minByOrNull { it.costUsd }
    val summary = buildString {
        appendLine("Вывод:")
        if (results.none { it.error != null }) {
            fastest?.let { appendLine("• Самая быстрая: ${it.modelOption.displayName} (${it.elapsedMs} мс)") }
            cheapest?.let { appendLine("• Самая дешёвая: ${it.modelOption.displayName} ($${"%.6f".format(it.costUsd)})") }
            appendLine("• deepseek-chat — быстрый, прямой ответ.")
            appendLine("• deepseek-reasoner — рассуждения, точнее на сложных задачах.")
        }
        appendLine()
        appendLine("Ссылки:")
        appendLine("• API Docs: $DEEPSEEK_DOCS_URL")
        appendLine("• Pricing: $DEEPSEEK_PRICING_URL")
    }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Сравнение моделей",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun ModelComparisonCard(result: ModelComparisonResult, modifier: Modifier = Modifier) {
    val text = if (result.error != null) "Ошибка: ${result.error}" else result.response
    val metrics = buildString {
        append("⏱ ${result.elapsedMs} мс")
        append(" • ")
        append("📊 ${result.promptTokens + result.completionTokens} токенов")
        append(" • ")
        append("💰 $%.6f".format(result.costUsd))
    }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = result.modelOption.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = metrics,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = text.ifEmpty { "(пустой ответ)" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun ResponseCard(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = text.ifEmpty { "(пустой ответ)" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = 22.sp
            )
        }
    }
}