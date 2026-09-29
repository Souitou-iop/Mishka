package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.mishka.domain.model.TailscaleDevice
import top.yukonga.mishka.domain.model.TailscaleStatus
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TailscaleSettingsScreen(
    storage: PlatformStorage,
    mihomoRepository: kotlinx.coroutines.flow.StateFlow<MihomoRepository?>? = null,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val currentRepository = mihomoRepository?.collectAsStateWithLifecycle()?.value
    var enabled by remember { mutableStateOf(storage.getString(StorageKeys.TAILSCALE_ENABLED, "false") == "true") }
    var acceptRoutes by remember { mutableStateOf(storage.getString(StorageKeys.TAILSCALE_ACCEPT_ROUTES, "true") != "false") }
    var udp by remember { mutableStateOf(storage.getString(StorageKeys.TAILSCALE_UDP, "true") != "false") }
    var ephemeral by remember { mutableStateOf(storage.getString(StorageKeys.TAILSCALE_EPHEMERAL, "false") == "true") }
    var allowLan by remember { mutableStateOf(storage.getString(StorageKeys.TAILSCALE_EXIT_NODE_ALLOW_LAN, "false") == "true") }
    var tailscaleStatus by remember { mutableStateOf<TailscaleStatus?>(null) }
    // 手动刷新走 conflated 信号而非用作 LaunchedEffect key：重启 effect 会先清空
    // tailscaleStatus 再等 fetch 返回，设备区卸载重挂，表现为点刷新时整页闪烁。
    val refreshSignal = remember { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(mihomoRepository, enabled) {
        if (!enabled || mihomoRepository == null) {
            tailscaleStatus = null
            return@LaunchedEffect
        }
        mihomoRepository.collectLatest { repository ->
            if (repository == null) {
                tailscaleStatus = null
                return@collectLatest
            }
            while (true) {
                repository.getTailscaleStatus().onSuccess { tailscaleStatus = it }
                // 失败保留旧数据，周期轮询与手动刷新都不因瞬时失败清空页面
                withTimeoutOrNull(5_000) { refreshSignal.receive() }
            }
        }
    }

    var editing by remember { mutableStateOf<Field?>(null) }
    val textState = rememberTextFieldState()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    fun open(field: Field) {
        editing = field
        textState.edit { replace(0, length, storage.getString(field.key, "")) }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_tailscale),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val direction = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (direction == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            item { SmallTitle(text = stringResource(R.string.tailscale_devices_group)) }
            item {
                TailscaleDeviceStatusCard(
                    status = tailscaleStatus,
                    isRunning = currentRepository != null,
                    onRefresh = { refreshSignal.trySend(Unit) },
                )
            }
            groupedCardItems(
                keyPrefix = "tsdevice",
                outerBottomPadding = 12.dp,
                items = buildList {
                    val current = tailscaleStatus
                    if (current != null && currentRepository != null) {
                        current.self?.let { self ->
                            add(CardItem("self") { TailscaleDeviceRow(self, isCurrent = true) })
                        }
                        current.devices.forEach { device ->
                            add(
                                CardItem(device.name.ifBlank { device.ips.firstOrNull().orEmpty().ifBlank { "peer" } }) {
                                    TailscaleDeviceRow(device, isCurrent = false)
                                },
                            )
                        }
                        if (current.self == null && current.devices.isEmpty()) {
                            add(
                                CardItem("none") {
                                    Text(
                                        text = stringResource(R.string.tailscale_device_none),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                },
                            )
                        }
                    }
                },
            )
            item { SmallTitle(text = stringResource(R.string.tailscale_group)) }
            groupedCardItems(
                keyPrefix = "tailscale",
                items = listOf(
                    CardItem("enabled") {
                        SwitchPreference(
                            title = stringResource(R.string.tailscale_enabled),
                            summary = stringResource(R.string.tailscale_enabled_summary),
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                storage.putString(StorageKeys.TAILSCALE_ENABLED, it.toString())
                            },
                        )
                    },
                    CardItem("authKey") {
                        ArrowPreference(
                            title = stringResource(R.string.tailscale_auth_key),
                            summary = if (storage.getString(StorageKeys.TAILSCALE_AUTH_KEY, "").isBlank()) {
                                stringResource(R.string.tailscale_auth_key_empty)
                            } else "••••••••",
                            onClick = { open(Field.AuthKey) },
                        )
                    },
                    CardItem("controlUrl") {
                        ArrowPreference(
                            title = stringResource(R.string.tailscale_control_url),
                            summary = storage.getString(StorageKeys.TAILSCALE_CONTROL_URL, "").ifBlank {
                                stringResource(R.string.tailscale_control_url_default)
                            },
                            onClick = { open(Field.ControlUrl) },
                        )
                    },
                    CardItem("hostname") {
                        ArrowPreference(
                            title = stringResource(R.string.tailscale_hostname),
                            summary = storage.getString(StorageKeys.TAILSCALE_HOSTNAME, "").ifBlank {
                                stringResource(R.string.tailscale_hostname_default)
                            },
                            onClick = { open(Field.Hostname) },
                        )
                    },
                    CardItem("exitNode") {
                        ArrowPreference(
                            title = stringResource(R.string.tailscale_exit_node),
                            summary = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE, "").ifBlank {
                                stringResource(R.string.tailscale_exit_node_none)
                            },
                            onClick = { open(Field.ExitNode) },
                        )
                    },
                    CardItem("acceptRoutes") {
                        SwitchPreference(
                            title = stringResource(R.string.tailscale_accept_routes),
                            summary = stringResource(R.string.tailscale_accept_routes_summary),
                            checked = acceptRoutes,
                            onCheckedChange = {
                                acceptRoutes = it
                                storage.putString(StorageKeys.TAILSCALE_ACCEPT_ROUTES, it.toString())
                            },
                        )
                    },
                    CardItem("udp") {
                        SwitchPreference(
                            title = stringResource(R.string.tailscale_udp),
                            summary = stringResource(R.string.tailscale_udp_summary),
                            checked = udp,
                            onCheckedChange = {
                                udp = it
                                storage.putString(StorageKeys.TAILSCALE_UDP, it.toString())
                            },
                        )
                    },
                    CardItem("ephemeral") {
                        SwitchPreference(
                            title = stringResource(R.string.tailscale_ephemeral),
                            summary = stringResource(R.string.tailscale_ephemeral_summary),
                            checked = ephemeral,
                            onCheckedChange = {
                                ephemeral = it
                                storage.putString(StorageKeys.TAILSCALE_EPHEMERAL, it.toString())
                            },
                        )
                    },
                    CardItem("allowLan") {
                        SwitchPreference(
                            title = stringResource(R.string.tailscale_exit_node_allow_lan),
                            summary = stringResource(R.string.tailscale_exit_node_allow_lan_summary),
                            checked = allowLan,
                            onCheckedChange = {
                                allowLan = it
                                storage.putString(StorageKeys.TAILSCALE_EXIT_NODE_ALLOW_LAN, it.toString())
                            },
                        )
                    },
                ),
            )
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tailscale_notice),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }

    WindowDialog(
        show = editing != null,
        title = editing?.let { stringResource(it.titleRes) }.orEmpty(),
        onDismissRequest = { editing = null },
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.network_input_value),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { editing = null },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    editing?.let { storage.putString(it.key, textState.text.toString().trim()) }
                    editing = null
                },
            )
        }
    }
}


@Composable
private fun TailscaleDeviceStatusCard(
    status: TailscaleStatus?,
    isRunning: Boolean,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tailscale_status),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        !isRunning -> stringResource(R.string.tailscale_vpn_disconnected)
                        status == null -> stringResource(R.string.tailscale_vpn_connecting)
                        else -> stringResource(R.string.tailscale_vpn_connected)
                    },
                    color = when {
                        !isRunning -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        status == null -> StatusColors.warning
                        else -> StatusColors.healthy
                    },
                    fontSize = 13.sp,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = isRunning,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.tailscale_refresh_devices),
                    modifier = Modifier.size(20.dp),
                    tint = if (isRunning) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun TailscaleDeviceRow(device: TailscaleDevice, isCurrent: Boolean) {
    val online = isCurrent || device.online
    val title = device.name.ifBlank { device.hostname.ifBlank { device.ips.firstOrNull().orEmpty() } }
    // 右侧徽章是唯一的在线状态展示，左侧副标题只放描述信息，避免两侧重复文案互相挤占。
    val summary = when {
        isCurrent -> device.ips.firstOrNull()?.let { ip ->
            stringResource(R.string.tailscale_current_device) + " · " + ip
        } ?: stringResource(R.string.tailscale_current_device)
        device.online -> device.ips.firstOrNull().orEmpty().ifBlank { device.os }
        device.lastSeen.isNotBlank() -> stringResource(R.string.tailscale_last_seen, formatLastSeen(device.lastSeen))
        else -> ""
    }
    val statusColor = if (online) StatusColors.healthy else StatusColors.neutral
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            text = stringResource(if (online) R.string.tailscale_device_online else R.string.tailscale_device_offline),
            color = statusColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(start = 8.dp)
                .squircleBackground(statusColor.copy(alpha = 0.12f), 3.dp)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

// lastSeen 是 Go 侧输出的 RFC3339 UTC 字符串，转本地时区按系统 locale 展示。
private fun formatLastSeen(iso: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()))
}.getOrDefault(iso)

private enum class Field(val key: String, val titleRes: Int) {
    AuthKey(StorageKeys.TAILSCALE_AUTH_KEY, R.string.tailscale_auth_key),
    ControlUrl(StorageKeys.TAILSCALE_CONTROL_URL, R.string.tailscale_control_url),
    Hostname(StorageKeys.TAILSCALE_HOSTNAME, R.string.tailscale_hostname),
    ExitNode(StorageKeys.TAILSCALE_EXIT_NODE, R.string.tailscale_exit_node),
}
