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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (state.isNew) "添加节点" else "编辑节点",
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
                Text("粘贴分享链接", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "支持 ss / vmess / vless / trojan / hysteria2 / tuic / anytls / socks / http",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = state.linkInput,
                    onValueChange = viewModel::setLink,
                    label = { Text("分享链接") },
                    minLines = 3,
                    isError = state.linkError != null,
                    supportingText = state.linkError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::importLink,
                    enabled = state.linkInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text("解析并保存")
                }
            }

            Text(
                text = "或手动填写",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
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
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::setServer,
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::setPort,
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }

        if (!state.isNew) {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}
