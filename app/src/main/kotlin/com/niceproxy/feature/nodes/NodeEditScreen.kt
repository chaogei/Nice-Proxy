package com.niceproxy.feature.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.ProtocolBadge

@Composable
fun NodeEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: NodeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onNavigateBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    bottom = 24.dp,
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                text = stringResource(
                    if (state.isNew) R.string.node_edit_title_new else R.string.node_edit_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (state.isNew) {
            GlassPanel(
                hazeState = LocalHazeState.current,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 16.dp,
            ) {
                Text(
                    text = stringResource(R.string.node_edit_paste_link),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.node_edit_supported_schemes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                // 解析器的原文来自 core:config，可能是 null。那种情况下不能把
                // 输入框的辅助文字留空 —— 边框变红却不说为什么，比不变红更糟。
                val linkError = state.linkError?.let { error ->
                    error.reason?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.node_edit_link_unparsable)
                }
                OutlinedTextField(
                    value = state.linkInput,
                    onValueChange = viewModel::setLink,
                    label = { Text(stringResource(R.string.node_edit_link)) },
                    minLines = 3,
                    isError = linkError != null,
                    supportingText = linkError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::importLink,
                    enabled = state.linkInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.node_edit_parse_and_save))
                }
            }

            // 这里原来是一组「或手动填写」的输入框，但新建路径根本没有保存按钮，
            // 用户认真填完找不到提交入口，返回后全丢。与其留一个填了也没用的表单，
            // 不如说清楚为什么只收链接：ServerProfile 还需要协议类型与凭据，
            // 而那些字段只有分享链接、二维码或订阅里才带得全。
            Text(
                text = stringResource(R.string.node_edit_no_manual_entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            return@Column
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            state.protocolBadge?.let { badge ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    ProtocolBadge(badge)
                    Text(
                        text = state.protocolName.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.node_edit_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::setServer,
                label = { Text(stringResource(R.string.node_edit_server)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::setPort,
                label = { Text(stringResource(R.string.node_edit_port)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}
