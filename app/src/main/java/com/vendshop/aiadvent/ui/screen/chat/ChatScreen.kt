package com.vendshop.aiadvent.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vendshop.aiadvent.data.model.Message
import com.vendshop.aiadvent.ui.theme.AIAdventTheme

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    var userInput by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChatScreenContent(
        uiState = uiState,
        userInput = userInput,
        onUserInputChange = { userInput = it },
        onSend = {
            viewModel.sendMessage(userInput, SendMode.NORMAL)
            userInput = ""
        }
    )
}

@Immutable
private data class ChatMessageUi(
    val role: String,
    val content: String
)

@Composable
private fun ChatScreenContent(
    uiState: ChatUiState,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val isBusy = uiState.isLoading || uiState.comparisonInProgress
    val lazyListState = rememberLazyListState()

    val visibleMessages = remember(uiState.messages) {
        uiState.messages.map { ChatMessageUi(role = it.role, content = it.content) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val hasContent = visibleMessages.isNotEmpty() ||
                uiState.streamingContent.isNotEmpty() ||
                uiState.error != null ||
                isBusy

            if (!hasContent) {
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.error != null) {
                        item(key = "error") {
                            Text(
                                text = uiState.error,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    itemsIndexed(
                        items = visibleMessages,
                        key = { index, _ -> "msg_$index" }
                    ) { _, msg ->
                        ChatBubble(
                            role = msg.role,
                            text = msg.content,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (uiState.streamingContent.isNotEmpty() || uiState.isLoading) {
                        item(key = "streaming") {
                            ChatBubble(
                                role = "assistant",
                                text = uiState.streamingContent,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (uiState.isLoading && uiState.streamingContent.isEmpty()) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = onUserInputChange,
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
                        IconButton(
                            onClick = onSend,
                            enabled = !isBusy
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ChatBubble(
    role: String,
    text: String,
    modifier: Modifier = Modifier
) {
    val isUser = role == "user"
    Row(
        modifier = modifier,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (isUser) "Вы" else "Ассистент",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = text.ifEmpty { if (isUser) "(пустое сообщение)" else "(ожидаем ответ)" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth(),
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview_Empty() {
    AIAdventTheme(dynamicColor = false) {
        ChatScreenContent(
            uiState = ChatUiState(),
            userInput = "",
            onUserInputChange = {},
            onSend = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview_WithHistory() {
    AIAdventTheme(dynamicColor = false) {
        ChatScreenContent(
            uiState = ChatUiState(
                messages = listOf(
                    Message(role = "user", content = "Привет! Запомни: меня зовут Дима."),
                    Message(role = "assistant", content = "Привет, Дима! Запомнил.")
                ),
                streamingContent = "Конечно, Дима. Чем помочь?"
            ),
            userInput = "Как меня зовут?",
            onUserInputChange = {},
            onSend = {}
        )
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