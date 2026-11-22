package io.legado.app.eink.bridge

import io.legado.app.constant.PreferKey
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.eink.contract.WebDavSyncConfig
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import splitties.init.appCtx

/**
 * 云端备份端口实现：转发宿主完整模式同一条链（MainActivity.backupSync
 * 判定 + AppWebDav.lastBackUp/restoreWebDav + Backup.backupLocked），
 * E-Ink 侧不新增备份语义。开关（autoCheckNewBackup）、比较与标记
 * （LocalConfig.lastBackup，与完整模式共享同一闸，同进程先发现者标记、
 * 后者静默）、恢复管线（BackupRestoreMutex 串行，AppWebDav.restoreWebDav
 * 内置）全部沿用宿主。
 *
 * WebDAV 管理（supportsWebDavManagement）：五字段直读写宿主偏好键
 * （与完整模式「备份与恢复」设置同一存储）；保存后即时 [AppWebDav.upConfig]
 * 重建运行时授权（对齐宿主设置页 onSharedPreferenceChanged →
 * upWebDavConfig 同链，本宿主无设置域流须显式触发）；配置流经 SP
 * 键监听重发（完整模式侧改动同步反映）。[latestBackup] 仅作展示快照，
 * 不写 lastBackup 标记（与 checkNewBackupOnStart 的「发现即标记」
 * 刻意区分）。
 *
 * 宿主「测试」语义注：[AppWebDav.upConfig] 吞异常、成败以
 * [AppWebDav.isOk] 表达；账号密码错误时宿主链路 toast 提示并**清除
 * 已存密码键**（宿主自身设置页同款行为）——本端口经 [rebuildAuthorization]
 * 在重建后回写密码，**保存与验证解耦**：保存/测试不销毁用户输入，
 * 验证成败经测试返回值与宿主 toast 反馈。
 */
object BackupSyncEngineImpl : BackupSyncEngine {

    /** 五个 WebDAV 配置键：配置流与保存路径的公共集合。 */
    private val CONFIG_KEYS = arrayOf(
        PreferKey.webDavUrl,
        PreferKey.webDavAccount,
        PreferKey.webDavPassword,
        PreferKey.webDavDir,
        PreferKey.webDavDeviceName,
    )

    private val configState = MutableStateFlow(readConfig())

    /**
     * 宿主默认 prefs 文件的键监听：五键任一变化（宿主设置页或本端口
     * 写入）重发配置快照。宿主无 DataStore 迁移接管（plain SP），默认
     * 文件可安全监听。
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in CONFIG_KEYS) configState.value = readConfig()
        }

    init {
        appCtx.defaultSharedPreferences
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun readConfig(): WebDavSyncConfig {
        val url = appCtx.getPrefString(PreferKey.webDavUrl)?.trim().orEmpty()
        return WebDavSyncConfig(
            // 空地址运行时回落坚果云默认（AppWebDav.rootWebDavUrl 同语义），
            // 此处原样上报空串，由模块保存前归一化
            serverUrl = url,
            account = appCtx.getPrefString(PreferKey.webDavAccount).orEmpty(),
            password = appCtx.getPrefString(PreferKey.webDavPassword).orEmpty(),
            dir = appCtx.getPrefString(PreferKey.webDavDir).orEmpty(),
            deviceName = appCtx.getPrefString(PreferKey.webDavDeviceName).orEmpty(),
        )
    }

    override suspend fun checkNewBackupOnStart(): CloudBackupNewer? {
        // 拉取失败（未配置 WebDAV/网络不可用）静默，对齐宿主 catch→return
        val latest = runCatching { AppWebDav.lastBackUp().getOrNull() }
            .getOrNull() ?: return null
        val hit = BackupSyncCheckPolicy.shouldPrompt(
            autoCheckEnabled = AppConfig.autoCheckNewBackup,
            latest = latest,
            lastBackupMark = LocalConfig.lastBackup,
        ) ?: return null
        // 发现即标记：用户取消后同一备份不再提示，失败重试由弹层承担
        LocalConfig.lastBackup = latest.lastModify
        return BackupSyncCheckPolicy.parseSnapshot(hit)
    }

    override suspend fun restoreBackup(fileName: String) {
        AppWebDav.restoreWebDav(fileName)
        // 宿主完整模式同位：恢复成功后把标记推到该备份的 mtime
        //（fileName 原样回传，mtime 经列表反查；反查失败按当前时刻，
        // 宁可晚提示一次不重复弹已恢复的备份）
        val mark = AppWebDav.lastBackUp().getOrNull()
            ?.takeIf { it.displayName == fileName }?.lastModify
            ?: System.currentTimeMillis()
        LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, mark)
    }

    override val supportsWebDavManagement: Boolean = true

    override val webDavConfig: Flow<WebDavSyncConfig> = configState

    override suspend fun saveWebDavConfig(config: WebDavSyncConfig) {
        appCtx.putPrefString(PreferKey.webDavUrl, config.serverUrl)
        appCtx.putPrefString(PreferKey.webDavAccount, config.account)
        appCtx.putPrefString(PreferKey.webDavPassword, config.password)
        appCtx.putPrefString(PreferKey.webDavDir, config.dir)
        appCtx.putPrefString(PreferKey.webDavDeviceName, config.deviceName)
        // 宿主设置页同链：保存后即时重建运行时授权（成功时顺带目录初始化）
        rebuildAuthorization(config.password)
    }

    override suspend fun testWebDav(): Boolean {
        // 成败细节由宿主链路反馈（checkAuthorization 内置 toast）；返回
        // isOk，模块不重复提示。测试同样不销毁已存密码（保存与验证解耦）
        val saved = appCtx.getPrefString(PreferKey.webDavPassword).orEmpty()
        return runCatching { rebuildAuthorization(saved) }.isSuccess && AppWebDav.isOk
    }

    /**
     * 重建运行时授权（[AppWebDav.upConfig]：成功时顺带目录初始化），并保持
     * 已存密码不被授权失败链清除。宿主既有语义为校验（401）失败即
     * `removePref(webDavPassword)`——会把刚保存的密码一并销毁，保存与
     * 验证未解耦（纯净模式实测表现为输入账号密码保存后密码字段被清空、
     * 重输循环无法落盘）。本端口原则：保存/测试忠实持久化用户输入，
     * 验证成败经 [testWebDav] 返回值与宿主链路 toast 反馈，不销毁数据。
     */
    private suspend fun rebuildAuthorization(password: String) {
        AppWebDav.upConfig()
        if (password.isNotEmpty() &&
            appCtx.getPrefString(PreferKey.webDavPassword) != password
        ) {
            appCtx.putPrefString(PreferKey.webDavPassword, password)
        }
    }

    override suspend fun backupNow() {
        // 宿主手动备份同链（生成备份 zip + WebDAV 上传 + 按保留数清理）；
        // 失败抛异常（backupLocked 的 check 语义），模块展示 message
        Backup.backupLocked(appCtx, AppConfig.backupPath, uploadWebDav = true)
    }

    override suspend fun latestBackup(): CloudBackupNewer? {
        return runCatching { AppWebDav.lastBackUp().getOrNull() }
            .getOrNull()
            ?.let(BackupSyncCheckPolicy::parseSnapshot)
    }
}
