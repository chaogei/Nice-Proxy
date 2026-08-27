package com.niceproxy.feature.inbound

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.model.InboundType

@Composable
fun InboundEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: InboundEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onNavigateBack()
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
                text = if (state.isNew) "新建入站" else "编辑入站",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!state.isNew) {
                IconButton(onClick = viewModel::delete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text("类型", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InboundType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }
            Text(
                text = state.type.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text("监听地址", style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.selectableGroup().padding(top = 6.dp)) {
                ListenOption(
                    label = "所有接口（0.0.0.0）",
                    hint = "局域网内的其他设备可以连接",
                    selected = state.listenAll,
                    onSelect = { viewModel.setListenAll(true) },
                )
                ListenOption(
                    label = "仅本机（127.0.0.1）",
                    hint = "只有这台设备上的应用能连接",
                    selected = !state.listenAll,
                    onSelect = { viewModel.setListenAll(false) },
                )
            }

            OutlinedTextField(
                value = state.portText,
                onValueChange = viewModel::setPort,
                label = { Text("端口") },
                singleLine = true,
                isError = state.portError != null,
                supportingText = {
                    when (state.portError) {
                        PortError.OUT_OF_RANGE -> Text("端口需在 1025–65535 之间")
                        PortError.TAKEN -> Text("该端口已被其他入站占用")
                        null -> Unit
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }

        if (state.type == InboundType.PAC) {
            GlassPanel(
                hazeState = LocalHazeState.current,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 16.dp,
            ) {
                Text("使用方式", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "在客户端的「自动代理配置」里填入 " +
                        "http://<本机 IP>:${state.portText.ifBlank { "端口" }}/proxy.pac\n\n" +
                        "脚本会按客户端实际访问的地址动态生成，" +
                        "局域网与本机地址自动绕行。之后改端口或换网络都不必再动客户端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SwitchRow("启用", state.enabled, viewModel::setEnabled, topPadding = 12.dp)
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
            return@Column
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            SwitchRow("启用认证", state.authEnabled, viewModel::setAuthEnabled)
            if (state.authEnabled) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            } else if (state.listenAll) {
                Text(
                    text = "监听所有接口且未启用认证时，同一网络下的任何设备都能使用这个代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            SwitchRow("允许 UDP 转发", state.udpEnabled, viewModel::setUdpEnabled, topPadding = 10.dp)
            SwitchRow("启用", state.enabled, viewModel::setEnabled)
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun ListenOption(
    label: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点，而不是只有小圆点可点
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
