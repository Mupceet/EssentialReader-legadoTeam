package io.legado.app.eink.bridge

import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.lib.webdav.WebDavFile

/**
 * 云端备份启动检查判定核心（纯函数）。复刻宿主完整模式
 * MainActivity.backupSync 语义：「自动检测新备份」设置门控；云端最新
 * 备份 mtime 与本地 lastBackup 标记差 > 1 分钟才算「新」（宿主
 * android.text.format.DateUtils.MINUTE_IN_MILLIS = 60000ms 同值，框架
 * 类型不进纯函数故以字面常量表达）。「发现即标记」的写入由调用方
 * （[BackupSyncEngineImpl]）承接。
 */
internal object BackupSyncCheckPolicy {

    /** 新备份判定阈值（ms），宿主 backupSync 同值。 */
    private const val NEWER_THRESHOLD_MS = 60_000L

    /** 宿主备份文件名格式：backup<yyyy-MM-dd>-<设备名>.zip（设备段可缺省）。 */
    private val backupFileNameRegex = Regex("""^backup(\d{4}-\d{2}-\d{2})(?:-(.*))?\.zip$""")

    /**
     * 是否应提示恢复。
     *
     * @param autoCheckEnabled 宿主「自动检测新备份」设置（默认开）。
     * @param latest 云端最新备份；null（无备份/拉取失败）恒不提示。
     * @param lastBackupMark 本地 lastBackup 标记（上次备份/恢复/发现时间戳）。
     * @return 命中时原样返回 [latest]，否则 null。
     */
    fun shouldPrompt(
        autoCheckEnabled: Boolean,
        latest: WebDavFile?,
        lastBackupMark: Long,
    ): WebDavFile? {
        if (!autoCheckEnabled) return null
        latest ?: return null
        return if (latest.lastModify - lastBackupMark > NEWER_THRESHOLD_MS) latest else null
    }

    /**
     * 备份文件名 → 弹层快照：日期段不合法或整体不匹配时 dateText 回退
     * 原文件名，设备段缺失为空串（弹层展示「未命名」）。
     */
    fun parseSnapshot(backup: WebDavFile): CloudBackupNewer {
        val match = backupFileNameRegex.matchEntire(backup.displayName)
        return CloudBackupNewer(
            fileName = backup.displayName,
            deviceName = match?.groupValues?.getOrNull(2).orEmpty(),
            dateText = match?.groupValues?.getOrNull(1).takeUnless { it.isNullOrBlank() }
                ?: backup.displayName,
        )
    }
}
