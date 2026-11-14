package top.yukonga.mishka.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.store.OverrideProfileStore
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.util.describe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * WebDAV 自动同步：本机改动防抖推送新快照 + 前台时检测远端新快照（只提示，不自动恢复）。
 *
 * 推送源：订阅表（[SubscriptionRepository.subscriptions]）、Override 文件列表、
 * override.user.json、prefs 中非 [BackupManager.EXCLUDED_PREF_KEYS] 的键。
 * 代理组选择（selections）不触发推送——切一次节点就是一次几十 MB 快照上传。
 *
 * 冲突策略：本地未同步的改动永远赢（force 上传，版本号 = max(远端, 本地) + 1，历史快照
 * 由保留策略兜底）；远端更新时只置位 [WebDavSyncState.remoteUpdateVersion] 提示，恢复与否
 * 由用户决定。
 *
 * 请求经 [SubscriptionProxyResolver] 走 mixed-port 出网：Mishka 自身流量绕过 TUN，直连在
 * 境外链路或本机 DNS 被 fake-ip 污染时不可达（实测根因）。凭据未配置时整体静默停用。
 */
class WebDavAutoSyncManager(
    private val storage: PlatformStorage,
    private val backupManager: BackupManager,
    private val proxyResolver: SubscriptionProxyResolver,
    subscriptions: SubscriptionRepository,
    overrideStore: OverrideJsonStore,
    overrideProfileStore: OverrideProfileStore,
    private val appScope: CoroutineScope,
) {
    data class WebDavSyncState(
        /** 远端存在的更新快照版本；null = 已是最新或未检测到。 */
        val remoteUpdateVersion: Long? = null,
        val lastPushVersion: Long? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(WebDavSyncState())
    val state: StateFlow<WebDavSyncState> = _state.asStateFlow()

    private val pushMutex = Mutex()
    private val pushScheduled = AtomicBoolean(false)

    @Volatile private var pendingPush = false
    @Volatile private var remoteMaxSeen: Long = 0
    private var lastForegroundCheck: TimeMark? = null

    init {
        // StateFlow 初值不算改动：进程启动本身不应触发推送，只有启动后的真实变化才算
        appScope.launch { subscriptions.subscriptions.drop(1).collect { markDirty() } }
        appScope.launch { overrideStore.state.drop(1).collect { markDirty() } }
        appScope.launch { overrideProfileStore.profiles.drop(1).collect { markDirty() } }
        storage.registerChangeListener { key ->
            when {
                // 推送/恢复成功都会写版本号，借它重求值远端更新旗标（清除提示）
                key == StorageKeys.WEBDAV_SYNC_VERSION -> reevaluateRemoteFlag()
                key in BackupManager.EXCLUDED_PREF_KEYS -> Unit
                else -> markDirty()
            }
        }
    }

    /** 前台钩子：节流检测远端；有未落盘的本地改动时顺带重试推送。 */
    fun onAppForeground() {
        val now = TimeSource.Monotonic.markNow()
        val last = lastForegroundCheck
        if (last == null || last.elapsedNow() >= FOREGROUND_CHECK_MIN_INTERVAL) {
            lastForegroundCheck = now
            appScope.launch { checkRemote() }
        }
        if (pendingPush) {
            appScope.launch { attemptPush() }
        }
    }

    private fun markDirty() {
        if (ProxyServiceBridge.isRestoreWindowHeld()) return
        pendingPush = true
        // 只保留一个防抖任务；正在执行的推送不受影响，结束后 pendingPush 仍在，等前台或下次改动
        if (pushScheduled.compareAndSet(false, true)) {
            appScope.launch {
                try {
                    delay(PUSH_DEBOUNCE)
                    attemptPush()
                } finally {
                    pushScheduled.set(false)
                }
            }
        }
    }

    private suspend fun attemptPush() {
        if (!pendingPush) return
        // 已有推送在跑则直接返回：不排队，pendingPush 保持 true，由前台钩子或下次改动收尾
        if (!pushMutex.tryLock()) return
        try {
            if (ProxyServiceBridge.isRestoreWindowHeld()) return
            val client = buildClient() ?: run {
                pendingPush = false
                return
            }
            val snapshot = backupManager.withTransferFile { file ->
                backupManager.writeBackupTo(file)
                // 本地未同步改动永远赢：远端更新也 force 推上去，历史快照可回溯
                client.uploadNextSnapshot(file, syncVersion(), force = true)
            }
            storage.putString(StorageKeys.WEBDAV_SYNC_VERSION, snapshot.version.toString())
            client.pruneSnapshots()
            _state.update { s ->
                s.copy(
                    lastPushVersion = snapshot.version,
                    lastError = null,
                    remoteUpdateVersion = null,
                )
            }
            pendingPush = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 不定时重试：失败后等下一次改动或前台钩子，避免离线设备空转
            _state.update { it.copy(lastError = e.describe()) }
        } finally {
            pushMutex.unlock()
        }
    }

    private suspend fun checkRemote() {
        if (!hasCredentials()) return
        if (ProxyServiceBridge.isRestoreWindowHeld()) return
        val client = buildClient() ?: return
        try {
            val remoteMax = client.listSnapshots().maxOfOrNull { snapshot -> snapshot.version } ?: 0L
            remoteMaxSeen = remoteMax
            reevaluateRemoteFlag()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 检测失败静默：网络恢复后下次前台再试，不打扰用户
        }
    }

    private fun reevaluateRemoteFlag() {
        val remoteMax = remoteMaxSeen
        _state.update { s ->
            s.copy(remoteUpdateVersion = remoteMax.takeIf { version -> version > syncVersion() })
        }
    }

    private suspend fun buildClient(): WebDavClient? {
        if (!hasCredentials()) return null
        val url = storage.getString(StorageKeys.WEBDAV_URL, "").trim()
        val user = storage.getString(StorageKeys.WEBDAV_USERNAME, "")
        val pass = storage.getString(StorageKeys.WEBDAV_PASSWORD, "")
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) return null
        // 代理未运行解析为 null → 直连；解析按 bridge state 实时求值，不缓存
        val proxyUrl = runCatching { proxyResolver.resolve(requireUserToggle = false) }.getOrNull()
        return WebDavClient(url, user, pass, proxyUrl)
    }

    private fun hasCredentials(): Boolean =
        storage.getString(StorageKeys.WEBDAV_URL, "").isNotBlank() &&
                storage.getString(StorageKeys.WEBDAV_USERNAME, "").isNotBlank() &&
                storage.getString(StorageKeys.WEBDAV_PASSWORD, "").isNotBlank()

    private fun syncVersion(): Long =
        storage.getString(StorageKeys.WEBDAV_SYNC_VERSION, "0").toLongOrNull()?.coerceAtLeast(0) ?: 0

    private companion object {
        val PUSH_DEBOUNCE = 30_000L.milliseconds
        val FOREGROUND_CHECK_MIN_INTERVAL = 60_000L.milliseconds
    }
}
