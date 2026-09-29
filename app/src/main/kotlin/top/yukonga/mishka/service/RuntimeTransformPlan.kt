package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.data.store.ProfileTransformWriter

internal data class RuntimeTransformPlan(
    val transformPath: String? = null,
    val mixedPort: Int? = null,
    val ageSecretKey: String = "",
)

/** 从 imported DB 读取运行时快照；订阅 StateFlow 的异步回填不能用于立即重启。 */
internal suspend fun prepareRuntimeTransform(
    context: Context,
    subscriptionId: String?,
    repository: SubscriptionRepositoryImpl,
    writer: ProfileTransformWriter,
): RuntimeTransformPlan {
    if (subscriptionId == null) return RuntimeTransformPlan()
    val subscription = repository.loadRuntimeSubscription(subscriptionId)
        ?: throw IllegalArgumentException("Profile $subscriptionId not found")
    val path = writer.write(subscription, ProfileTransformWriter.RUNTIME_PATH)
    // 脚本会在子进程里执行；有变换时这里让默认端口先作为兜底，显式端口由子进程恢复。
    val port = if (path == null) {
        ConfigGenerator.readSubscriptionMixedPort(context, subscriptionId)
    } else null
    return RuntimeTransformPlan(path, port, subscription.ageSecretKey)
}
