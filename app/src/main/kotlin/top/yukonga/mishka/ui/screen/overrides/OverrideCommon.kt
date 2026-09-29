package top.yukonga.mishka.ui.screen.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ClearOverrideErrorOnExit(viewModel: OverrideProfileViewModel) {
    DisposableEffect(viewModel) { onDispose { viewModel.clearError() } }
}

@Composable
internal fun OverrideBackButton(onBack: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onBack) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.common_back),
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.graphicsLayer {
                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
            },
        )
    }
}

@Composable
internal fun overrideSummary(profile: OverrideProfile): String = stringResource(
    if (profile.isRemote) R.string.override_source_remote else R.string.override_source_local,
) + " · " + stringResource(
    if (profile.format == OverrideFormat.Yaml) {
        R.string.override_format_yaml
    } else {
        R.string.override_format_javascript
    },
)
