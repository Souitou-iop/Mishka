package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.diagnostics.ConfigDiagnosticsBuilder
import top.yukonga.mishka.domain.model.ConfigPreview
import top.yukonga.mishka.domain.model.ConfigValidationResult
import top.yukonga.mishka.domain.model.RuntimeOverridePreview
import top.yukonga.mishka.domain.model.TailscalePreview
import top.yukonga.mishka.domain.model.TransformOrigin
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 配置诊断：只读展示最终生效配置的 transform 链、Tailscale 注入与 runtime override 摘要，
 * 并可触发一次不启动代理的完整性校验。
 *
 * 数据全部来自 [ConfigDiagnosticsBuilder]，后者只暴露聚合计数与布尔——
 * **本页面绝不接收或渲染 auth key / secret / external-controller / 订阅正文**，
 * 因此整页内容可安全截图贴出。敏感项（Tailscale 密钥、secret）只渲染「已配置 / 未配置」状态。
 */
@Composable
fun DiagnosticsScreen(
    builder: ConfigDiagnosticsBuilder,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<ConfigPreview?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }

    // 拉取只读快照。buildPreview 本身不下发任何请求、不启动 Service，
    // 只在 IO 上读 DB / storage / override 文件。
    LaunchedEffect(Unit) {
        preview = runCatching { builder.buildPreview() }.getOrNull()
        isLoaded = true
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_diagnostics),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                when {
                                    isValidating -> Unit
                                    else -> {
                                        isValidating = true
                                        scope.launch {
                                            preview = runCatching { builder.validate() }.getOrNull()
                                            isValidating = false
                                        }
                                    }
                                }
                            },
                            enabled = !isValidating,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.diagnostics_validate),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                item { SmallTitle(text = stringResource(R.string.diagnostics_active_profile)) }
                groupedCardItems(
                    keyPrefix = "diagnostics_profile",
                    items = buildList {
                        val current = preview
                        if (current == null) {
                            add(CardItem("loading") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_active_profile),
                                    value = if (!isLoaded) {
                                        stringResource(R.string.diagnostics_loading)
                                    } else {
                                        stringResource(R.string.diagnostics_unavailable)
                                    },
                                )
                            })
                        } else if (!current.activeProfilePresent) {
                            add(CardItem("noProfile") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_active_profile),
                                    value = stringResource(R.string.diagnostics_no_active_profile),
                                    valueColor = StatusColors.warning,
                                )
                            })
                        } else {
                            add(CardItem("name") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_profile_name),
                                    value = current.activeProfileName.ifBlank { current.activeProfileId.orEmpty() },
                                )
                            })
                            add(CardItem("type") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_profile_type),
                                    value = current.profileType?.name.orEmpty(),
                                )
                            })
                            add(CardItem("age") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_age_encrypted),
                                    value = yesNo(current.ageEncrypted),
                                    valueColor = if (current.ageEncrypted) StatusColors.healthy else null,
                                )
                            })
                        }
                    },
                )

                val current = preview
                if (current != null) {
                    item { SmallTitle(text = stringResource(R.string.diagnostics_validation)) }
                    item {
                        ValidationCard(
                            validation = current.validation,
                            isValidating = isValidating,
                            onValidate = {
                                if (!isValidating) {
                                    isValidating = true
                                    scope.launch {
                                        preview = runCatching { builder.validate() }.getOrNull()
                                        isValidating = false
                                    }
                                }
                            },
                        )
                    }

                    item { SmallTitle(text = stringResource(R.string.diagnostics_transform)) }
                    if (current.transformSteps.isEmpty()) {
                        item {
                            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.diagnostics_transform_none),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                            }
                        }
                    } else {
                        groupedCardItems(
                            keyPrefix = "diagnostics_transform",
                            items = current.transformSteps.mapIndexed { index, step ->
                                CardItem("step_$index") {
                                    ConfigInfoRow(
                                        label = "${index + 1}. ${step.name}",
                                        value = stringResource(
                                            if (step.origin == TransformOrigin.Tailscale) {
                                                R.string.diagnostics_origin_tailscale
                                            } else {
                                                R.string.diagnostics_origin_subscription
                                            },
                                        ) + " · " + step.format.name,
                                    )
                                }
                            },
                        )
                    }

                    item { SmallTitle(text = stringResource(R.string.diagnostics_tailscale)) }
                    groupedCardItems(
                        keyPrefix = "diagnostics_tailscale",
                        items = tailscaleRows(current.tailscale ?: TailscalePreview()),
                    )

                    item { SmallTitle(text = stringResource(R.string.diagnostics_runtime)) }
                    groupedCardItems(
                        keyPrefix = "diagnostics_runtime",
                        items = runtimeRows(current.runtime),
                    )

                    item { SmallTitle(text = stringResource(R.string.diagnostics_summary)) }
                    groupedCardItems(
                        keyPrefix = "diagnostics_summary",
                        items = buildList {
                            val summary = current.diagnostics
                            add(CardItem("overrideCount") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_override_count),
                                    value = "${summary.selectedOverrideCount} / ${summary.overrideCount}",
                                )
                            })
                            add(CardItem("rootMode") {
                                ConfigInfoRow(
                                    label = stringResource(R.string.diagnostics_root_mode),
                                    value = yesNo(summary.rootModeSelected),
                                )
                            })
                        },
                    )
                }

                item {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}

@Composable
private fun ValidationCard(
    validation: ConfigValidationResult,
    isValidating: Boolean,
    onValidate: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.diagnostics_validation_state),
                    style = MiuixTheme.textStyles.body1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        isValidating -> stringResource(R.string.diagnostics_validating)
                        validation is ConfigValidationResult.Running -> stringResource(R.string.diagnostics_validating)
                        validation is ConfigValidationResult.Valid -> stringResource(R.string.diagnostics_valid)
                        validation is ConfigValidationResult.Invalid -> stringResource(R.string.diagnostics_invalid)
                        else -> stringResource(R.string.diagnostics_not_run)
                    },
                    style = MiuixTheme.textStyles.body1,
                    color = when {
                        isValidating -> StatusColors.warning
                        validation is ConfigValidationResult.Valid -> StatusColors.healthy
                        validation is ConfigValidationResult.Invalid -> StatusColors.danger
                        else -> MiuixTheme.colorScheme.onSurfaceContainerVariant
                    },
                )
            }
            if (validation is ConfigValidationResult.Invalid && validation.message.isNotBlank()) {
                Text(
                    text = validation.message,
                    style = MiuixTheme.textStyles.body2,
                    color = StatusColors.danger,
                )
            }
            TextButton(
                text = stringResource(R.string.diagnostics_validate),
                onClick = onValidate,
                enabled = !isValidating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConfigInfoRow(
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = valueColor ?: MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    if (value) stringResource(R.string.diagnostics_yes) else stringResource(R.string.diagnostics_no)

private fun tailscaleRows(tailscale: TailscalePreview): List<CardItem> = buildList {
    add(CardItem("enabled") {
        ConfigInfoRow(
            label = "tailscale.enabled",
            value = if (tailscale.enabled) "true" else "false",
            valueColor = if (tailscale.enabled) StatusColors.healthy else null,
        )
    })
    add(CardItem("injected") {
        ConfigInfoRow(
            label = "tailscale.injected",
            value = if (tailscale.injected) "true" else "false",
        )
    })
    if (!tailscale.enabled) return@buildList
    // 密钥只暴露「是否已配置」，绝不读取或渲染 auth key 本身
    add(CardItem("authKey") {
        ConfigInfoRow(
            label = "tailscale.auth-key",
            value = if (tailscale.authKeyConfigured) "configured" else "not configured",
            valueColor = if (tailscale.authKeyConfigured) StatusColors.healthy else StatusColors.warning,
        )
    })
    if (tailscale.controlUrl.isNotBlank()) {
        add(CardItem("controlUrl") {
            ConfigInfoRow(label = "tailscale.control-url", value = tailscale.controlUrl)
        })
    }
    if (tailscale.hostname.isNotBlank()) {
        add(CardItem("hostname") {
            ConfigInfoRow(label = "tailscale.hostname", value = tailscale.hostname)
        })
    }
    if (tailscale.exitNode.isNotBlank()) {
        add(CardItem("exitNode") {
            ConfigInfoRow(label = "tailscale.exit-node", value = tailscale.exitNode)
        })
    }
    add(CardItem("acceptRoutes") {
        ConfigInfoRow(label = "tailscale.accept-routes", value = if (tailscale.acceptRoutes) "true" else "false")
    })
    add(CardItem("udp") {
        ConfigInfoRow(label = "tailscale.udp", value = if (tailscale.udp) "true" else "false")
    })
    add(CardItem("ephemeral") {
        ConfigInfoRow(label = "tailscale.ephemeral", value = if (tailscale.ephemeral) "true" else "false")
    })
}

private fun runtimeRows(runtime: RuntimeOverridePreview): List<CardItem> = buildList {
    add(CardItem("tunMode") {
        ConfigInfoRow(label = "runtime.tun-mode", value = runtime.tunMode)
    })
    runtime.mixedPort?.let { port ->
        add(CardItem("mixedPort") {
            ConfigInfoRow(label = "runtime.mixed-port", value = port.toString())
        })
    }
    runtime.mode?.let { mode ->
        add(CardItem("mode") {
            ConfigInfoRow(label = "runtime.mode", value = mode)
        })
    }
    runtime.allowLan?.let { value ->
        add(CardItem("allowLan") {
            ConfigInfoRow(label = "runtime.allow-lan", value = value.toString())
        })
    }
    runtime.ipv6?.let { value ->
        add(CardItem("ipv6") {
            ConfigInfoRow(label = "runtime.ipv6", value = value.toString())
        })
    }
    runtime.tcpConcurrent?.let { value ->
        add(CardItem("tcpConcurrent") {
            ConfigInfoRow(label = "runtime.tcp-concurrent", value = value.toString())
        })
    }
    runtime.findProcessMode?.let { value ->
        add(CardItem("findProcessMode") {
            ConfigInfoRow(label = "runtime.find-process-mode", value = value)
        })
    }
    runtime.tunEnabled?.let { value ->
        add(CardItem("tunEnabled") {
            ConfigInfoRow(label = "runtime.tun.enable", value = value.toString())
        })
    }
    runtime.tunMtu?.let { value ->
        add(CardItem("tunMtu") {
            ConfigInfoRow(label = "runtime.tun.mtu", value = value.toString())
        })
    }
    runtime.tproxyPort?.let { value ->
        add(CardItem("tproxyPort") {
            ConfigInfoRow(label = "runtime.tproxy-port", value = value.toString())
        })
    }
    runtime.wifiRuntimeMode?.let { value ->
        add(CardItem("wifiRuntimeMode") {
            ConfigInfoRow(label = "runtime.wifi-runtime-mode", value = value)
        })
    }
}
