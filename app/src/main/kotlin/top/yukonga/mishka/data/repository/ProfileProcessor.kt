package top.yukonga.mishka.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.bridge.CoreFetchProgress
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.data.bridge.MishkaCoreError
import top.yukonga.mishka.data.database.decodeOverrideIds
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.platform.ProfileFileManager
import java.io.File
import java.net.URI

/**
 * 导入进度的语义步骤。本地化文案在 UI 层按 [ImportStep] 映射，
 * data 层只发结构化进度，不依赖字符串资源。
 */
enum class ImportStep { Downloading, Prefetching, Validating, Other }

data class ImportProgress(
    val step: ImportStep,
    /** FetchProviders 阶段正在预取的 provider 名，用于「更新 {name} ({i}/{n})」文案。 */
    val providerName: String = "",
    /** [ImportStep.Other] 时携带 mihomo 原始 action 字符串。 */
    val rawLabel: String = "",
    val current: Int = 0,
    val total: Int = 0,
)

class ConfigValidationException(message: String) : Exception(message)

/**
 * snapshot → fetchAndValid → commit-swap 三阶段。
 * processLock 串行整个流程，[SubscriptionRepository.profileLock] 守护 DB snapshot。
 * commit 阶段必须 NonCancellable：目录 rename 换入与 DB 更新之间被打断会让两者不一致。
 */
class ProfileProcessor(
    private val repo: SubscriptionRepositoryImpl,
    private val fileManager: ProfileFileManager,
    /** 自动命名兜底链的最后一环，由调用方按 locale 取值——data 层拿不到资源 */
    private val defaultProfileName: String,
    private val proxyResolver: SubscriptionProxyResolver,
    private val transformWriter: ProfileTransformWriter,
) {

    /**
     * 启动清理：processing/ 残留 + 孤儿订阅目录（删除是先删 DB 行再删目录两步，中间进程
     * 死亡会留下无人认领的 imported/{uuid}/）。必须与 [runProcess] 持同一把进程级锁，
     * 否则会擦掉后台 ProfileWorker 正在进行的更新写到 processing/ 的内容。
     */
    suspend fun cleanupResidual() = withContext(Dispatchers.IO) {
        processLock.withLock {
            // 顺序有意义：cleanupProcessing 会把 commit.old.{uuid} 还原回 imported/，
            // 反扫必须看到还原后的结果
            fileManager.cleanupProcessing()
            val orphans = fileManager.deleteOrphanDirs(repo.knownUuids())
            if (orphans.isNotEmpty()) Log.i(TAG, "Removed orphan profile dirs: $orphans")
        }
    }

    suspend fun apply(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = false, onProgress)
    }

    suspend fun update(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = true, onProgress)
    }

    private suspend fun runProcess(
        uuid: String,
        isUpdate: Boolean,
        onProgress: (ImportProgress) -> Unit,
        // 三阶段全是阻塞磁盘操作（递归拷贝 / 递归删除 / rename），占 Default 会与 Compose
        // 重组和 appScope 的 Room Flow 合并抢同一批 CPU 线程
    ) = withContext(Dispatchers.IO) {
        processLock.withLock {
            val (snapshot, workDir) = repo.withProfileLock {
                if (isUpdate) {
                    val imported = repo.queryImported(uuid)
                        ?: throw IllegalArgumentException("Profile $uuid not found")
                    val snap = PendingSnapshot(
                        imported.uuid, imported.name, imported.type, imported.source,
                        imported.userAgent, imported.ageSecretKey, imported.interval,
                        imported.overrideIds, imported.overrideSortPreference,
                    )
                    val dir = fileManager.prepareProcessing(uuid)
                    // File 类型需要保留旧 config.yaml 作基准；Url 类型会被 force=true 覆盖下载
                    fileManager.readImportedFile(uuid, "config.yaml")?.let {
                        fileManager.writeProcessingConfig(dir, it)
                    }
                    snap to dir
                } else {
                    val pending = repo.queryPending(uuid)
                        ?: throw IllegalArgumentException("No pending profile for $uuid")
                    pending.enforceFieldValid()
                    val dir = fileManager.prepareProcessing(uuid)
                    PendingSnapshot(
                        pending.uuid, pending.name, pending.type, pending.source,
                        pending.userAgent, pending.ageSecretKey, pending.interval,
                        pending.overrideIds, pending.overrideSortPreference,
                    ) to dir
                }
            }

            try {
                val proxyUrl = if (snapshot.type == ProfileType.Url) proxyResolver.resolve() else null

                val result = try {
                    MishkaCoreBridge.fetchAndValid(
                        workDir = workDir,
                        url = if (snapshot.type == ProfileType.Url) snapshot.source else "",
                        force = snapshot.type == ProfileType.Url,
                        httpProxy = proxyUrl,
                        userAgent = snapshot.userAgent,
                        ageSecretKey = snapshot.ageSecretKey,
                        onProgress = { p -> onProgress(mapProgress(p)) },
                    )
                } catch (e: MishkaCoreError) {
                    // "validate config:" 前缀区分 Parse 失败 vs fetch / unmarshal 失败，UI 文案不同
                    val msg = e.message ?: throw e
                    if (msg.startsWith("validate config:")) {
                        throw ConfigValidationException(msg.removePrefix("validate config:").trim())
                    }
                    throw e
                }

                val transformPath = transformWriter.write(snapshot.toSubscription(), PROCESSING_TRANSFORM)
                // fetchAndValid 已校验原始配置，只有实际变换才需要再 Parse。
                if (transformPath != null) {
                    try {
                        MishkaCoreBridge.validateTransform(
                            File(workDir),
                            File(transformPath),
                            snapshot.ageSecretKey,
                        )
                    } catch (e: MishkaCoreError) {
                        throw ConfigValidationException(e.message.orEmpty().removePrefix("validate config:").trim())
                    } finally {
                        File(transformPath).delete()
                    }
                }
                // 阻塞脚本校验返回时可能已取消，不能带着取消状态进入不可取消的提交区。
                currentCoroutineContext().ensureActive()

                // commit 从这里到块尾不可取消：目录 rename 换入与 DB 更新之间被打断就是两者不一致。
                // 块内还要再取 profileLock，等锁期间同样取消不了；processLock → profileLock
                // 这个嵌套顺序全仓只此一处，别在别处反着取
                withContext(NonCancellable) {
                    repo.withProfileLock {
                        if (isUpdate) {
                            val current = repo.queryImported(uuid)
                                ?: throw IllegalArgumentException("Imported profile $uuid disappeared during update")
                            check(current.uuid == snapshot.uuid)
                            check(snapshot.hasSameTransformInputs(current.ageSecretKey, current.overrideIds, current.overrideSortPreference)) {
                                "Profile transform inputs changed during processing"
                            }
                            fileManager.commitProcessingToImported(uuid)
                            repo.updateImported(
                                uuid = uuid,
                                upload = result.upload,
                                download = result.download,
                                total = result.total,
                                expire = result.expire,
                            )
                        } else {
                            val currentPending = repo.queryPending(uuid)
                                ?: throw IllegalArgumentException("Pending profile $uuid disappeared during commit")
                            check(currentPending.uuid == snapshot.uuid)
                            check(snapshot.hasSameTransformInputs(currentPending.ageSecretKey, currentPending.overrideIds, currentPending.overrideSortPreference)) {
                                "Profile transform inputs changed during processing"
                            }
                            fileManager.commitProcessingToImported(uuid)
                            repo.commitPending(
                                uuid = uuid,
                                upload = result.upload,
                                download = result.download,
                                total = result.total,
                                expire = result.expire,
                                fallbackName = autoProfileName(snapshot, result.fileName),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) { fileManager.cleanupProcessing() }
                throw t
            }
        }
    }

    /**
     * 名字留空时的兜底链：Content-Disposition > URL host > [defaultProfileName]。是否采用由
     * commitPending 按 commit 时刻的 pending.name 判空决定——fetch 期间锁已释放，用户可能已另填名字，
     * 用户输入永远优先。
     */
    private fun autoProfileName(snapshot: PendingSnapshot, dispositionName: String): String {
        if (snapshot.type != ProfileType.Url) return ""
        return dispositionName
            .ifBlank { runCatching { URI(snapshot.source).host.orEmpty() }.getOrDefault("") }
            .ifBlank { defaultProfileName }
    }

    private fun mapProgress(p: CoreFetchProgress): ImportProgress = when (p.action) {
        "FetchConfiguration" -> ImportProgress(ImportStep.Downloading)
        "FetchProviders" -> ImportProgress(
            step = ImportStep.Prefetching,
            providerName = p.args.firstOrNull().orEmpty(),
            current = p.progress,
            total = p.max,
        )

        "Verifying" -> ImportProgress(ImportStep.Validating)
        else -> ImportProgress(ImportStep.Other, rawLabel = p.action)
    }

    companion object {
        /**
         * 进程级处理锁。processing/ 是进程内单例沙箱目录，但前台 SubscriptionViewModel 与后台
         * ProfileWorker 各自构造独立的 ProfileProcessor 实例。锁若是实例级，两个实例的 update/apply
         * 会并发清空并复用同一个 processing/：一个订阅下载的 config 被提交进另一个订阅的
         * imported/{uuid}/，造成「界面显示订阅 A、实际运行订阅 B」。锁必须进程级共享，真正串行化
         * 对 processing/ 的「清空 → 下载 → 提交」全过程。
         */
        private val processLock = Mutex()

        private const val TAG = "ProfileProcessor"
        private const val PROCESSING_TRANSFORM = "processing/profile.validate.transform.json"

        /**
         * 以进程级锁独占执行 [block]。WebDAV 备份/恢复用：备份读取 imported/ 期间不能有
         * commit 改写目录，恢复覆盖 imported/ + DB 期间不能有任何导入管线在跑。
         */
        suspend fun <T> withProcessLock(block: suspend () -> T): T = processLock.withLock { block() }
    }
}

// 与 PendingEntity 解耦：update 路径下 Pending DB 记录不存在，但仍需带字段做 commit
internal data class PendingSnapshot(
    val uuid: String,
    val name: String,
    val type: ProfileType,
    val source: String,
    val userAgent: String,
    val ageSecretKey: String,
    val interval: Long,
    val overrideIds: String = "",
    val overrideSortPreference: String = "",
) {
    fun toSubscription(): Subscription = Subscription(
        id = uuid,
        name = name,
        type = type,
        url = source,
        userAgent = userAgent,
        ageSecretKey = ageSecretKey,
        interval = interval,
        overrideIds = overrideIds.decodeOverrideIds(),
        overrideSortPreference = overrideSortPreference.decodeOverrideIds(),
    )

    fun hasSameTransformInputs(ageSecretKey: String, overrideIds: String, overrideSortPreference: String): Boolean =
        this.ageSecretKey == ageSecretKey &&
            this.overrideIds.decodeOverrideIds() == overrideIds.decodeOverrideIds() &&
            this.overrideSortPreference.decodeOverrideIds() == overrideSortPreference.decodeOverrideIds()
}
