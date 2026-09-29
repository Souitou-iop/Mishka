package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TailscaleStatus(
    val state: String = "",
    val tailnet: String = "",
    val magicDnsSuffix: String = "",
    val ips: List<String> = emptyList(),
    val self: TailscaleDevice? = null,
    val devices: List<TailscaleDevice> = emptyList(),
)

@Serializable
data class TailscaleDevice(
    val name: String = "",
    val dnsName: String = "",
    val hostname: String = "",
    val os: String = "",
    val ips: List<String> = emptyList(),
    val online: Boolean = false,
    val active: Boolean = false,
    val lastSeen: String = "",
    val lastHandshake: String = "",
    val self: Boolean = false,
)
