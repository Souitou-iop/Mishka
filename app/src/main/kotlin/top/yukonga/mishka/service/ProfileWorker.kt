package top.yukonga.mishka.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import top.yukonga.mishka.R
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.util.describe
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配置后台更新前台服务。
 *
 * 接收更新请求，在后台执行配置下载、Provider 下载、验证，
 * 显示进度通知和结果通知。
 */
class ProfileWorker : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)

    // onCreate 起前台失败后已 stopSelf，之后到达的 start 不能再接活
    @Volatile
    private var foregroundFailed = false

    // 与 UI 侧共享同一 store：内存值是权威值，自建实例读不到刚落的设置
    private val overrideStore: OverrideJsonStore by inject()

    private val updateScheduler: ProfileUpdateScheduler by inject()

    private val serviceController: ProxyServiceController by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_PROFILE_WORKER,
                NotificationHelper.buildProfileWorkerNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            foregroundFailed = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundFailed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_SCHEDULE_UPDATES -> launchTracked(startId) { updateScheduler.reconcileNow() }

            ACTION_UPDATE_PROFILE -> {
                val uuid = intent.data?.host
                if (uuid != null) launchTracked(startId) { runUpdate(uuid) } else stopIfIdle(startId)
            }

            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * 跑一件任务，完成后以**本次** startId 请求停止。
     *
     * `stopSelfResult` 只在没有更新的 start 到达时才真停，那条更新的请求会由它自己的任务
     * 再试一次——「等 10s 再 drain 队列」做不到这点：`poll()` 返回 null 与 `stopSelf()` 之间
     * 到达的请求入队后无人 join，onDestroy 的 `scope.cancel()` 直接把它掐掉，更新静默失败。
     */
    private fun launchTracked(startId: Int, block: suspend () -> Unit) {
        inFlight.incrementAndGet()
        scope.launch {
            try {
                block()
            } finally {
                if (inFlight.decrementAndGet() == 0) stopSelfResult(startId)
            }
        }
    }

    private fun stopIfIdle(startId: Int) {
        if (inFlight.get() == 0) stopSelfResult(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUpdate(uuid: String) {
        val database = getAppDatabase(this)
        val importedDao = database.importedDao()
        val imported = importedDao.queryByUUID(uuid) ?: return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val statusId = NotificationHelper.profileProgressId(uuid)

        val storage = PlatformStorage(this)
        val fileManager = AndroidProfileFileManager(this)
        val repo = SubscriptionRepositoryImpl(
            importedDao = importedDao,
            pendingDao = database.pendingDao(),
            selectionDao = database.selectionDao(),
            storage = storage,
            fileManager = fileManager,
            scope = scope,
            database = database,
        )
        val proxyResolver = SubscriptionProxyResolver(storage, overrideStore)
        val processor = ProfileProcessor(
            repo = repo,
            fileManager = fileManager,
            defaultProfileName = getString(R.string.subscription_default_name),
            proxyResolver = proxyResolver,
        )

        try {
            notificationManager.notify(
                statusId,
                NotificationHelper.buildProfileUpdatingNotification(this, imported.name),
            )

            processor.update(uuid)

            // 本 Service 自身是前台服务，app 因此处于前台状态，这里发给 Tun/Root Service 的
            // startService 不受后台启动限制
            serviceController.restartAfterProfileUpdate(uuid)

            NotificationHelper.notifyProfileUpdateSuccess(this, imported.name)
            Log.i(TAG, "Profile ${imported.name} updated successfully")
            // 下次闹钟不在这里布置：update 写回 imported 表会让 ProfileUpdateScheduler 的
            // Flow 重新对账，按刚刷新的 config.yaml mtime 续期
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile ${imported.name}", e)
            NotificationHelper.notifyProfileUpdateFailed(
                this, imported.name, e.describe().ifBlank { getString(R.string.notification_unknown_error) }
            )
        } finally {
            notificationManager.cancel(statusId)
        }
    }

    companion object {
        private const val TAG = "ProfileWorker"
        const val ACTION_SCHEDULE_UPDATES = "top.yukonga.mishka.action.SCHEDULE_UPDATES"
        const val ACTION_UPDATE_PROFILE = "top.yukonga.mishka.action.UPDATE_PROFILE"
    }
}
