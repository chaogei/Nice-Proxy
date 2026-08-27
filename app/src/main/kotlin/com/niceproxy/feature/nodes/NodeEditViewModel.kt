package com.niceproxy.feature.nodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.config.share.ShareLinkParsers
import com.niceproxy.core.data.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodeEditUiState(
    val isNew: Boolean = true,
    val name: String = "",
    val server: String = "",
    val port: String = "",
    val protocolBadge: String? = null,
    val protocolName: String? = null,
    val linkInput: String = "",
    val linkError: String? = null,
    val finished: Boolean = false,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && server.isNotBlank() &&
            port.toIntOrNull()?.let { it in 1..65535 } == true
}

@HiltViewModel
class NodeEditViewModel @Inject constructor(
    private val repository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val nodeId: String? = savedStateHandle.get<String>("nodeId")
        ?.takeIf { it.isNotBlank() && it != "new" }

    private val _uiState = MutableStateFlow(NodeEditUiState(isNew = nodeId == null))
    val uiState: StateFlow<NodeEditUiState> = _uiState.asStateFlow()

    init {
        nodeId?.let { id ->
            viewModelScope.launch {
                repository.get(id)?.let { node ->
                    _uiState.value = NodeEditUiState(
                        isNew = false,
                        name = node.name,
                        server = node.server,
                        port = node.serverPort.toString(),
                        protocolBadge = node.protocol.badge,
                        protocolName = node.protocol.displayName,
                    )
                }
            }
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setServer(value: String) = _uiState.update { it.copy(server = value.trim()) }
    fun setPort(value: String) = _uiState.update {
        it.copy(port = value.filter(Char::isDigit).take(5))
    }

    fun setLink(value: String) = _uiState.update { it.copy(linkInput = value, linkError = null) }

    /**
     * 解析链接并直接落库。
     *
     * 不做「解析后填进表单让用户确认」这一步：分享链接携带的字段远多于表单能展示的
     * （传输层、REALITY、混淆等），走表单会把这些信息丢掉。
     */
    fun importLink() {
        val link = _uiState.value.linkInput.trim()
        viewModelScope.launch {
            val groupId = repository.ensureDefaultGroup()
            ShareLinkParsers.parse(link, groupId).fold(
                onSuccess = {
                    repository.save(it)
                    _uiState.update { state -> state.copy(finished = true) }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        state.copy(linkError = error.message ?: "无法解析该链接")
                    }
                },
            )
        }
    }

    fun save() {
        val state = _uiState.value
        val id = nodeId ?: return
        if (!state.canSave) return
        viewModelScope.launch {
            repository.get(id)?.let { existing ->
                repository.save(
                    existing.copy(
                        name = state.name.trim(),
                        server = state.server.trim(),
                        serverPort = state.port.toInt(),
                    ),
                )
            }
            _uiState.update { it.copy(finished = true) }
        }
    }
}
