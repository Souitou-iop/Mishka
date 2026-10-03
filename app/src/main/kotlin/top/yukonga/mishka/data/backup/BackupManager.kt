package top.yukonga.mishka.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.PendingEntity
import top.yukonga.mishka.data.database.decodeOverrideIds
import top.yukonga.mishka.data.database.encodeOverrideIds
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.service.ProfileFileOps
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

@Serializable
data class BackupProfile(
    val uuid: String,
    val name: String,
    val type: String,
    val source: String,
    val userAgent: String = "",
    val ageSecretKey: String = "",
    val overrideIds: List<String> = emptyList(),
    val overrideSortPreference: List<String> = emptyList(),
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val createdAt: Long,
)

@Serializable
data class BackupSelection(
    val uuid: String,
    val proxy: String,
    val selected: String,
)

@Serializable
data class BackupSnapshot(
    val version: Int,
    val createdAt: Long,
    val imported: List<BackupProfile> = emptyList(),
    val pending: List<BackupProfile> = emptyList(),
    val selections: List<BackupSelection> = emptyList(),
    val stringPrefs: Map<String, String> = emptyMap(),
    val stringSetPrefs: Map<String, List<String>> = emptyMap(),
    val bootStartEnabled: Boolean = false,
    // 明文随备份（敏感级同三表里的 ageSecretKey）：Keystore 密钥硬件绑定，密文跨设备不可解，
    // 搬密文等于没搬；恢复走 putSecret 回写 SecretStore
    val tailscaleAuthKey: String = "",
)

/**
 * WebDAV 备份/恢复的打包与落地。
 *
 * zip 布局：`backup.json`（版本 + 三表 JSON + prefs + Tailscale auth key）+ files/imported、files/pending
 * 两棵目录树 + `files/override.user.json`。DB 走 JSON 导出重放而非拷贝
 * db 文件——绕开 WAL 一致性问题，且跨 schema 版本可由字段默认值兜底。
 *
 * 备份与恢复都持 [ProfileProcessor.withProcessLock] 进程级锁：备份期间不能有导入管线
 * commit 改写 imported/，恢复期间不能有任何管线在跑。恢复要求代理已停止（调用方校验），
 * 完成后必须重启进程——OverrideJsonStore / Repository Flow 等内存热状态不随磁盘恢复而刷新。
 */
class BackupManager(
    private val context: Context,
    private val storage: PlatformStorage,
    private val database: AppDatabase,
    private val repository: SubscriptionRepositoryImpl,
    private val bootStartManager: BootStartManager,
) {
    private val importedDao get() = database.importedDao()
    private val pendingDao get() = database.pendingDao()
    private val selectionDao get() = database.selectionDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mihomoDir: File
        get() = File(context.filesDir, "mihomo")

    /** 打包到 SAF 文档。 */
    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        // "wt" 截断写：目标文档已存在且比新内容长时，默认模式会残留旧尾部导致 zip 损坏
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("Cannot open $uri for writing")
        out.use { writeBackup(it) }
    }

    /** 打包到普通文件（WebDAV 上传的中转）。 */
    suspend fun writeBackupTo(file: File) = withContext(Dispatchers.IO) {
        file.outputStream().use { writeBackup(it) }
    }

    /**
     * 逐条目流式打包，整包不进内存。
     *
     * 备份内容可达数十 MB（provider 缓存），先攒成 ByteArray 再交出去等于把压缩包与它的
     * 来源同时按在堆上，恢复侧再叠一份全量解压结果——恰好是最容易 OOM 的组合。
     */
    private suspend fun writeBackup(out: OutputStream) = ProfileProcessor.withProcessLock {
        withContext(Dispatchers.IO) {
            // 一次快照分两类装：dumpAll 每次加锁复制整张表，两次调用之间还可能被写入撕开
            val prefs = storage.dumpAll().filterKeys { it !in EXCLUDED_PREF_KEYS }
            val snapshot = BackupSnapshot(
                version = BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                imported = importedDao.queryAll().map { it.toBackup() },
                pending = pendingDao.queryAll().map { it.toBackup() },
                selections = selectionDao.queryAll().map { BackupSelection(it.uuid, it.proxy, it.selected) },
                stringPrefs = prefs
                    .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
                    .toMap(),
                stringSetPrefs = prefs
                    .mapNotNull { (k, v) ->
                        @Suppress("UNCHECKED_CAST")
                        (v as? Set<String>)?.let { k to it.toList() }
                    }
                    .toMap(),
                bootStartEnabled = bootStartManager.isEnabled(),
                tailscaleAuthKey = storage.getSecret(StorageKeys.TAILSCALE_AUTH_KEY),
            )

            ZipOutputStream(out.buffered()).use { zip ->
                zip.putEntry(ENTRY_SNAPSHOT, json.encodeToString(snapshot).toByteArray())
                zipDirIfExists(zip, File(mihomoDir, "imported"), "$ENTRY_FILES_PREFIX/imported")
                zipDirIfExists(zip, File(mihomoDir, "pending"), "$ENTRY_FILES_PREFIX/pending")
                zipDirIfExists(zip, File(mihomoDir, OVERRIDES_DIR), "$ENTRY_FILES_PREFIX/$OVERRIDES_DIR")
                val override = File(mihomoDir, OVERRIDE_FILE)
                if (override.isFile) zip.putFile("$ENTRY_FILES_PREFIX/$OVERRIDE_FILE", override)
            }
        }
    }

    /**
     * 中转文件：WebDAV 收发都需要一个能带长度、可重复读的 body，用完即删。
     */
    suspend fun <T> withTransferFile(block: suspend (File) -> T): T {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("mishka-backup-transfer-", ".zip", context.cacheDir)
        }
        return try {
            block(file)
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** 从 SAF 文档恢复。 */
    suspend fun importFrom(uri: Uri) = withRestoreMaintenance {
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BackupException("Cannot open $uri for reading")
            input.use { restoreBackup(it) }
        }
    }

    /** 从普通文件恢复（WebDAV 下载的中转）。 */
    suspend fun restoreBackupFrom(file: File) = withRestoreMaintenance {
        withContext(Dispatchers.IO) {
            file.inputStream().use { restoreBackup(it) }
        }
    }

    /**
     * Restore is guarded here rather than at individual UI/CLI call sites so every entry point
     * shares the same stopped-state and proxy-start exclusion window.
     */
    private suspend fun <T> withRestoreMaintenance(block: suspend () -> T): T {
        if (!ProxyServiceBridge.tryAcquireRestoreWindow()) {
            throw BackupException("backup restore requires a stopped proxy")
        }
        return try {
            block()
        } finally {
            ProxyServiceBridge.releaseRestoreWindow()
        }
    }

    /**
     * 恢复：覆盖式。imported/ 与 pending/ 目录整体替换，三表清空重放，prefs 逐 key 写入
     * （本机多出的 key 保留）。调用方保证代理已停止；成功返回后必须重启进程。
     *
     * 归档同样是流式读：条目边读边落进 staging，不在内存里聚一份全量解压结果。
     */
    private suspend fun restoreBackup(input: InputStream) = ProfileProcessor.withProcessLock {
        withContext(Dispatchers.IO) {
            // 校验（含版本过新）排在解包之后：staging 是临时目录，正式目录直到换入阶段才被触碰，
            // 失败路径只是把 staging 删掉
            val snapshot = extractToStaging(input)

            // 文件与 DB 一起换：旧文件必须一直保留到 DB 提交成功，否则 DB 失败会留下旧 DB + 新文件。
            // NonCancellable 覆盖 swap → DB 事务 → 回滚窗口，避免取消打断恢复临界区。
            withContext(NonCancellable) {
                repository.withProfileLock {
                    withSwappedFiles {
                        replaceDatabase(snapshot)
                    }
                }
            }

            // prefs 恢复（黑名单 key 不写入，本机运行时状态不被备份污染）
            snapshot.stringPrefs.forEach { (k, v) ->
                if (k !in EXCLUDED_PREF_KEYS) storage.putString(k, v)
            }
            snapshot.stringSetPrefs.forEach { (k, v) ->
                if (k !in EXCLUDED_PREF_KEYS) storage.putStringSet(k, v.toSet())
            }

            // auth key 必须走 putSecret 回写 SecretStore，不能 putString 进明文 prefs。
            // Keystore 不可用（极少数被裁 ROM）时只跳过本项并记日志：此时 prefs 已写一半、
            // 文件已换入，抛错只会把恢复停在更糟的中间态，用户事后手动粘贴即可
            if (snapshot.tailscaleAuthKey.isNotEmpty()) {
                runCatching { storage.putSecret(StorageKeys.TAILSCALE_AUTH_KEY, snapshot.tailscaleAuthKey) }
                    .onFailure { Log.w(TAG, "auth key in backup not restored: keystore unavailable", it) }
            }

            // active 指向已不存在的订阅时清空，避免启动校验单点报「配置缺失」死循环
            val activeUuid = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "")
            if (activeUuid.isNotEmpty() && importedDao.queryByUUID(activeUuid) == null) {
                storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, "")
                storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, "")
            }

            // Wi-Fi 策略的组件位由重启后 MainActivity 按恢复出的 pref reconcile，此处不处理
            bootStartManager.setEnabled(snapshot.bootStartEnabled)
        }
    }

    private fun ImportedEntity.toBackup() = BackupProfile(
        uuid, name, type.name, source, userAgent, ageSecretKey,
        overrideIds.decodeOverrideIds(), overrideSortPreference.decodeOverrideIds(),
        interval, upload, download, total, expire, createdAt,
    )

    private fun PendingEntity.toBackup() = BackupProfile(
        uuid, name, type.name, source, userAgent, ageSecretKey,
        overrideIds.decodeOverrideIds(), overrideSortPreference.decodeOverrideIds(),
        interval, upload, download, total, expire, createdAt,
    )

    private fun BackupProfile.profileType(): ProfileType? =
        runCatching { ProfileType.valueOf(type) }.getOrNull()

    private fun BackupProfile.toImportedEntity(): ImportedEntity? = profileType()?.let {
        ImportedEntity(
            uuid, name, it, source, userAgent, ageSecretKey,
            overrideIds.encodeOverrideIds(), overrideSortPreference.encodeOverrideIds(),
            interval, upload, download, total, expire, createdAt,
        )
    }

    private fun BackupProfile.toPendingEntity(): PendingEntity? = profileType()?.let {
        PendingEntity(
            uuid, name, it, source, userAgent, ageSecretKey,
            overrideIds.encodeOverrideIds(), overrideSortPreference.encodeOverrideIds(),
            interval, upload, download, total, expire, createdAt,
        )
    }

    private fun ZipOutputStream.putEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    /** 内容先全写进 staging（此时正式目录一动未动），再 rename 换入；失败从 old/ 回滚。 */
    /**
     * 三表清空重放。**必须整体一个事务**：逐条 insert 各自一个隐式事务，中途抛（备份里
     * 重复 uuid 会撞上 ImportedDao 的 ABORT）就留下半张表，而此时文件已经换完了。
     * 未知 ProfileType（未来版本新增）跳过该条而非让整次恢复失败。
     */
    private suspend fun replaceDatabase(snapshot: BackupSnapshot) {
        database.withWriteTransaction {
            importedDao.clearAll()
            pendingDao.clearAll()
            selectionDao.clearAll()
            snapshot.imported.forEach { p -> p.toImportedEntity()?.let { importedDao.insert(it) } }
            snapshot.pending.forEach { p -> p.toPendingEntity()?.let { pendingDao.insert(it) } }
            snapshot.selections.forEach {
                selectionDao.insert(SelectionEntity(it.uuid, it.proxy, it.selected))
            }
        }
    }

    /**
     * 单遍读归档：文件条目落进 staging，backup.json 留在内存（KB 级）并在读完后校验。
     * 任何一步失败都只留下一个待删的 staging，正式目录未被触碰。
     */
    private fun extractToStaging(input: InputStream): BackupSnapshot {
        val staging = File(mihomoDir, RESTORE_STAGING)
        staging.deleteRecursively()
        val stagingRoot = staging.also { it.mkdirs() }.canonicalFile.toPath()
        try {
            var snapshotBytes: ByteArray? = null
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_SNAPSHOT && !entry.isDirectory -> snapshotBytes = zis.readBytes()
                        name.startsWith("$ENTRY_FILES_PREFIX/") -> {
                            val relative = name.removePrefix("$ENTRY_FILES_PREFIX/")
                            validateProfileEntryPath(relative)
                            if (!entry.isDirectory) {
                                val target = File(staging, relative)
                                // zip-slip 防御：规范化后必须仍在 staging 内；Path.startsWith 按路径段比较。
                                if (!target.canonicalFile.toPath().startsWith(stagingRoot)) {
                                    throw BackupException("Illegal entry path: $name")
                                }
                                // 旧归档（过滤上线前产出）可能携带源设备的 Tailscale 节点身份，
                                // 落盘 = 恢复成别人的 node key，恢复侧一律丢弃
                                if (!isTailscaleStateEntry(relative)) {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zis.copyTo(it) }
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            val bytes = snapshotBytes ?: throw BackupException("backup.json not found in archive")
            val snapshot = runCatching { json.decodeFromString<BackupSnapshot>(bytes.decodeToString()) }
                .getOrElse { throw BackupException("Invalid backup.json: ${it.message}") }
            if (snapshot.version > BACKUP_VERSION) {
                throw BackupException("Backup version ${snapshot.version} is newer than supported $BACKUP_VERSION")
            }
            validateSnapshotUuids(snapshot)
            return snapshot
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * 在 DB 事务期间保留 old/，只有 block 成功返回后才清理；失败则按实际完成的 rename 逆序回滚。
     * 这样 replaceDatabase 抛错时，旧 DB 仍对应旧文件。
     */
    private suspend fun <T> withSwappedFiles(block: suspend () -> T): T {
        val staging = File(mihomoDir, RESTORE_STAGING)
        val old = File(mihomoDir, RESTORE_OLD)
        val swapped = mutableListOf<SwappedTarget>()
        var preserveRollback = false
        old.deleteRecursively()
        try {
            if (!old.exists() && !old.mkdirs()) {
                throw BackupException("Cannot create restore rollback directory")
            }
            RESTORE_TARGETS.forEach { name ->
                val current = File(mihomoDir, name)
                val saved = File(old, name)
                val incoming = File(staging, name)
                val currentMoved = current.exists()
                if (currentMoved && !current.renameTo(saved)) {
                    throw BackupException("Cannot move aside $name")
                }

                val incomingExists = incoming.exists()
                val target = SwappedTarget(name = name, currentMoved = currentMoved)
                swapped += target
                if (incomingExists && !incoming.renameTo(current)) {
                    throw BackupException("Cannot swap in $name")
                }
                target.incomingMoved = incomingExists
            }
            return block()
        } catch (error: Throwable) {
            val rollbackFailure = runCatching {
                swapped.asReversed().forEach { rollbackSwap(it, old) }
            }.exceptionOrNull()
            if (rollbackFailure != null) {
                preserveRollback = true
                error.addSuppressed(rollbackFailure)
            }
            throw error
        } finally {
            staging.deleteRecursively()
            if (!preserveRollback) old.deleteRecursively()
        }
    }

    private data class SwappedTarget(
        val name: String,
        val currentMoved: Boolean,
        var incomingMoved: Boolean = false,
    )

    private fun rollbackSwap(target: SwappedTarget, old: File) {
        val current = File(mihomoDir, target.name)
        if (target.incomingMoved && current.exists() && !current.deleteRecursively()) {
            throw BackupException("Cannot remove restored ${target.name}")
        }
        if (target.currentMoved) {
            val saved = File(old, target.name)
            if (!saved.exists() || !saved.renameTo(current)) {
                throw BackupException("Cannot restore old ${target.name}")
            }
        }
    }

    private fun validateSnapshotUuids(snapshot: BackupSnapshot) {
        (snapshot.imported.asSequence() + snapshot.pending.asSequence())
            .map { it.uuid }
            .plus(snapshot.selections.asSequence().map { it.uuid })
            .forEach(::requireValidProfileUuid)
    }

    private fun requireValidProfileUuid(uuid: String) {
        if (!ProfileFileOps.isValidProfileUuid(uuid)) {
            throw BackupException("Invalid profile UUID")
        }
    }

    private fun validateProfileEntryPath(relative: String) {
        val segments = relative.split('/')
        if (segments.firstOrNull() in PROFILE_DIRECTORY_NAMES) {
            val uuid = segments.getOrNull(1)
            if (uuid == null || !ProfileFileOps.isValidProfileUuid(uuid)) {
                throw BackupException("Invalid profile directory")
            }
        }
    }

    private fun zipDirIfExists(zip: ZipOutputStream, dir: File, entryPrefix: String) {
        if (!dir.isDirectory) return
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                // 订阅目录里的 GeoIP 是共享 geodata/ 的符号链接（symlink 失败时退化为实体拷贝），
                // 体积大且启动/校验路径的 ensureGeodataLinks 会自动重建，不进备份；
                // 符号链接一律跳过，防止 readBytes 追随链接把目标内容实体化进 zip
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                if (file.name in ProfileFileOps.GEODATA_FILES) return@forEach
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                if (isTailscaleStateEntry(relative)) return@forEach
                zip.putFile("$entryPrefix/$relative", file)
            }
    }

    companion object {
        const val BACKUP_VERSION = 1

        private const val TAG = "BackupManager"

        private const val ENTRY_SNAPSHOT = "backup.json"
        private const val ENTRY_FILES_PREFIX = "files"
        private const val OVERRIDE_FILE = "override.user.json"
        private const val OVERRIDES_DIR = "overrides"

        // 与正式目录同分区，rename 才原子
        private const val RESTORE_STAGING = ".restore"
        private const val RESTORE_OLD = ".restore-old"

        // mihomo Tailscale 出站的 state-dir 默认解析到工作目录 tailscale/（tsnet 节点密钥所在）
        private const val TAILSCALE_STATE_DIR = "tailscale"

        /**
         * Tailscale 节点身份不进备份、也不从备份恢复：node key 是设备在 tailnet 的唯一身份，
         * 恢复进别的设备 = 多台机器共用一个节点（控制台报 Duplicate node key）。按目录段过滤，
         * 与具体 state 文件名解耦。恢复是整树替换，归档里没有 tailscale/ 即清掉本机旧身份，
         * 恢复后需重新认证一次——节点身份本来只该属于一台设备。
         */
        internal fun isTailscaleStateEntry(relativePath: String): Boolean =
            relativePath.split('/').contains(TAILSCALE_STATE_DIR)

        // WebDAV 收发的中转文件：每次调用使用独立临时文件，避免并发请求互相覆盖。
        private val RESTORE_TARGETS = listOf("imported", "pending", OVERRIDES_DIR, OVERRIDE_FILE)
        private val PROFILE_DIRECTORY_NAMES = setOf("imported", "pending")

        /**
         * 不进备份也不从备份恢复的 key，三类语义：
         * ① 本机/本次运行的设备态（root 探测、进程 PID、boot session、Wi-Fi 策略运行时
         *    中间态、一次性迁移标记）——跨设备恢复会把别人的 PID / boot count 带进来；
         * ② WebDAV 凭据自身（凭据是「连到这份备份」的前提，写进备份既无意义又多一份泄露面）；
         * ③ 每设备自定义态（Tailscale 主机名）——设备名就该各设备自己定，随备份恢复会
         *    覆盖目标设备的自定名；留空时 Tailscale 侧回落到 Android 设备名。
         *
         * **新增任何运行时态 key 都要补进本名单**，否则它会随备份跨设备漂移。
         */
        private val EXCLUDED_PREF_KEYS = setOf(
            StorageKeys.SERVICE_WAS_RUNNING,
            StorageKeys.HAS_ROOT,
            StorageKeys.ROOT_MIHOMO_PID,
            StorageKeys.ROOT_MIHOMO_SECRET,
            StorageKeys.ROOT_START_TIME,
            StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID,
            StorageKeys.ROOT_BOOT_COUNT,
            StorageKeys.ROOT_SUBMODE_ACTIVE,
            StorageKeys.ROOT_TETHER_MODE_ACTIVE,
            StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE,
            StorageKeys.WIFI_POLICY_MATCHED,
            StorageKeys.WIFI_POLICY_MATCHED_ACTION,
            StorageKeys.WIFI_POLICY_PENDING_RESTART,
            StorageKeys.WIFI_POLICY_RUNTIME_MODE,
            StorageKeys.MIGRATION_ROOT_RECLAIM_DONE,
            StorageKeys.WEBDAV_URL,
            StorageKeys.WEBDAV_USERNAME,
            StorageKeys.WEBDAV_PASSWORD,
            StorageKeys.WEBDAV_SYNC_VERSION,
            // 敏感凭据经 SecretStore 落地、不进 prefs dump，随备份走 BackupSnapshot.tailscaleAuthKey
            // 专用字段；此条只兜住旧版明文残留不被 dumpAll 卷进 stringPrefs
            StorageKeys.TAILSCALE_AUTH_KEY,
            StorageKeys.TAILSCALE_HOSTNAME,
        )
    }
}
