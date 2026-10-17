package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DnsOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

/**
 * NetworkSettingsScreen 的 ViewModel：端口 / LAN / IPv6 / 绑定地址 / 日志级别 / DNS。
 */
class NetworkSettingsViewModel(
    private val store: OverrideJsonStore,
    private val storage: PlatformStorage,
) : ViewModel() {

    val state: StateFlow<NetworkSettingsUiState> = store.state
        .map { it.toNetworkUi() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.state.value.toNetworkUi(),
        )

    private val _domesticDirect = MutableStateFlow(
        storage.getString(StorageKeys.DOMESTIC_DIRECT, "true") == "true"
    )

    /**
     * rule 模式下自动国内直连，默认开。
     *
     * 该行为只在 rule 模式生效：global 模式下全部流量走选中出口、direct 模式全部直连，
     * 两者本来就不存在分流问题，因此那两个模式同时就是关掉它的方式。这里的开关额外提供一个
     * 「rule 模式下也不注入」的选项，供订阅自带完整分流规则的用户使用。
     */
    val domesticDirect: StateFlow<Boolean> = _domesticDirect.asStateFlow()

    fun setDomesticDirect(enabled: Boolean) {
        storage.putString(StorageKeys.DOMESTIC_DIRECT, if (enabled) "true" else "false")
        _domesticDirect.value = enabled
    }

    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        store.update(transform)
    }

    fun updateDns(transform: (DnsOverride) -> DnsOverride) {
        store.update { state ->
            val current = state.dns ?: DnsOverride()
            val next = transform(current)
            val allNull = next == DnsOverride()
            state.copy(dns = if (allNull) null else next)
        }
    }
}

@Immutable
data class NetworkSettingsUiState(
    val httpPort: Int? = null,
    val socksPort: Int? = null,
    val redirPort: Int? = null,
    val tproxyPort: Int? = null,
    val mixedPort: Int? = null,
    val allowLan: Boolean? = null,
    val ipv6: Boolean? = null,
    val bindAddress: String? = null,
    val logLevel: String? = null,
    val dns: DnsOverride? = null,
)

private fun ConfigurationOverride.toNetworkUi() = NetworkSettingsUiState(
    httpPort = httpPort,
    socksPort = socksPort,
    redirPort = redirPort,
    tproxyPort = tproxyPort,
    mixedPort = mixedPort,
    allowLan = allowLan,
    ipv6 = ipv6,
    bindAddress = bindAddress,
    logLevel = logLevel,
    dns = dns,
)
