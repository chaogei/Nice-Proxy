package com.niceproxy.feature.inbound

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboundEditUiState(
    val isNew: Boolean = true,
    val type: InboundType = InboundType.MIXED,
    val listenAll: Boolean = true,
    val portText: String = InboundService.DEFAULT_MIXED_PORT.toString(),
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val udpEnabled: Boolean = true,
    val enabled: Boolean = true,
    val portError: PortError? = null,
    val saved: Boolean = false,
) {
    val canSave: Boolean
        get() = portError == null &&
            portText.toIntOrNull() != null &&
            (!authEnabled || (username.isNotBlank() && password.isNotBlank()))
}

enum class PortError { OUT_OF_RANGE, TAKEN }

@HiltViewModel
class InboundEditViewModel @Inject constructor(
    private val repository: InboundRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val inboundId: String? = savedStateHandle.get<String>(ARG_INBOUND_ID)
        ?.takeIf { it.isNotBlank() && it != NEW_INBOUND }

    private val _uiState = MutableStateFlow(InboundEditUiState(isNew = inboundId == null))
    val uiState: StateFlow<InboundEditUiState> = _uiState.asStateFlow()

    init {
        inboundId?.let { id ->
            viewModelScope.launch {
                repository.get(id)?.let { inbound ->
                    _uiState.value = InboundEditUiState(
                        isNew = false,
                        type = inbound.type,
                        listenAll = inbound.listen != InboundService.LISTEN_LOOPBACK,
                        portText = inbound.listenPort.toString(),
                        authEnabled = inbound.auth != null,
                        username = inbound.auth?.username.orEmpty(),
                        password = inbound.auth?.password.orEmpty(),
                        udpEnabled = inbound.udpEnabled,
                        enabled = inbound.enabled,
                    )
                }
            }
        }
    }

    fun setType(type: InboundType) = _uiState.update {
        // 换类型时把端口一并换成该类型的默认值，除非用户已经改过。
        val portWasDefault = it.portText == it.type.defaultPort.toString()
        it.copy(
            type = type,
            portText = if (portWasDefault) type.defaultPort.toString() else it.portText,
        )
    }

    fun setListenAll(value: Boolean) = _uiState.update { it.copy(listenAll = value) }

    fun setPort(text: String) {
        val digits = text.filter(Char::isDigit).take(5)
        _uiState.update { it.copy(portText = digits, portError = null) }
        validatePort(digits)
    }

    fun setAuthEnabled(value: Boolean) = _uiState.update { it.copy(authEnabled = value) }
    fun setUsername(value: String) = _uiState.update { it.copy(username = value) }
    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }
    fun setUdpEnabled(value: Boolean) = _uiState.update { it.copy(udpEnabled = value) }
    fun setEnabled(value: Boolean) = _uiState.update { it.copy(enabled = value) }

    private fun validatePort(text: String) {
        val port = text.toIntOrNull() ?: return
        if (port !in InboundService.PORT_RANGE) {
            _uiState.update { it.copy(portError = PortError.OUT_OF_RANGE) }
            return
        }
        viewModelScope.launch {
            val taken = repository.isPortTaken(port, inboundId.orEmpty())
            _uiState.update {
                if (it.portText == text) {
                    it.copy(portError = if (taken) PortError.TAKEN else null)
                } else {
                    it
                }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        val port = state.portText.toIntOrNull() ?: return
        if (!state.canSave) return

        viewModelScope.launch {
            val base = inboundId?.let { repository.get(it) } ?: repository.newInbound(state.type)
            repository.save(
                base.copy(
                    type = state.type,
                    listen = if (state.listenAll) {
                        InboundService.LISTEN_ALL
                    } else {
                        InboundService.LISTEN_LOOPBACK
                    },
                    listenPort = port,
                    auth = if (state.authEnabled) {
                        InboundAuth(state.username.trim(), state.password)
                    } else {
                        null
                    },
                    udpEnabled = state.udpEnabled,
                    enabled = state.enabled,
                ),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val id = inboundId ?: return
        viewModelScope.launch {
            repository.delete(id)
            _uiState.update { it.copy(saved = true) }
        }
    }

    companion object {
        const val ARG_INBOUND_ID = "inboundId"
        const val NEW_INBOUND = "new"
    }
}
