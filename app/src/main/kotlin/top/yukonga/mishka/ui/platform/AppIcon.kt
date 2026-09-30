package top.yukonga.mishka.ui.platform

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.util.AppIconCache
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val normalizedPackageName = packageName.trim()
    val hasPackageName = normalizedPackageName.isNotEmpty()

    val cached = remember(normalizedPackageName) {
        normalizedPackageName.takeIf { hasPackageName }?.let(AppIconCache::getFromCache)
    }
    var bitmap by remember(normalizedPackageName) { mutableStateOf(cached) }

    if (cached == null && hasPackageName) {
        LaunchedEffect(normalizedPackageName) {
            try {
                bitmap = AppIconCache.loadIcon(context, normalizedPackageName, sizePx)
            } catch (_: Exception) {
                // 加载失败，保持 null
            }
        }
    }

    // asImageBitmap 每次调用新分配包装对象，裸调会让 Image 的 bitmap 参数恒判为变了
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(modifier = modifier.size(size)) {
        // 缓存命中即已就位，无须淡入
        if (cached != null) {
            IconOrPlaceholder(image, size)
        } else {
            Crossfade(
                targetState = image,
                animationSpec = tween(durationMillis = 150),
                label = "AppIconFade",
            ) { icon -> IconOrPlaceholder(icon, size) }
        }
    }
}

@Composable
private fun IconOrPlaceholder(image: ImageBitmap?, size: Dp) {
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 8.dp),
        )
    }
}
