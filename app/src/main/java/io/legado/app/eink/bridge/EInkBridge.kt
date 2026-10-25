package io.legado.app.eink.bridge

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import io.legado.app.constant.PreferKey
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.PageTurnRippleMode
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

/**
 * E-Ink 自有偏好的专属 prefs 文件（模块 0.5.0 契约口径）：pullDownBookmark
 * 与 readerTapZones 落这里而非宿主默认 prefs 文件——模块侧约定自有键
 * 不与完整模式共享存储，且默认文件有被整体迁移/清空的历史风险。
 */
private const val EINK_PREFS_FILE = "eink_preferences"

/** 下拉添加书签（E-Ink 自有偏好，默认关）。 */
private const val KEY_PULL_DOWN_BOOKMARK = "einkReaderPullDownBookmark"

/** 点击区域九宫格整键（9 位编码原样存取，编码语义在模块侧）。 */
private const val KEY_READER_TAP_ZONES = "einkReaderTapZones"

/** 翻页水波纹档位自有键（"off"/"slow"/"standard"/"fast"，默认 off）。 */
private const val KEY_PAGE_TURN_RIPPLE_MODE = "einkPageTurnRippleMode"

/** 最近文件字体历史自有键（换行分隔 path 列表，编码语义在模块侧）。 */
private const val KEY_RECENT_FONTS = "einkRecentFontPaths"

/** 「我的 → 字体设置」反显记录：所选字体源 path。 */
private const val KEY_APP_FONT_SOURCE = "einkAppFontSourcePath"

/** 反显记录：安装后的私有副本路径（与当前生效副本相等时源记录有效）。 */
private const val KEY_APP_FONT_COPY = "einkAppFontCopyPath"

/** 段评气泡显示开关自有键（宿主引擎无总开关，E-Ink 侧自管，默认开）。 */
private const val KEY_SHOW_REVIEW_BUBBLES = "einkShowReviewBubbles"

/** 本宿主完整模式无界面字体缩放设置：E-Ink 自管键（0 = 未设置，跟随系统）。 */
private const val KEY_FONT_SCALE = "fontScale"

/**
 * E-Ink 引擎桥接层装配入口（本宿主 = 经典 legado 栈，无设置网关/
 * Koin，全部经 AppConfig / ReadBookConfig / SharedPreferences 扩展读写）。
 *
 * 由入口子类 EInkMainActivity 的 onInstallEngines 钩子调用（模块模板基类
 * EInkHostActivity 在 attachBaseContext 首行触发，早于任何 E-Ink
 * Composable 组合与端口读取）。
 */
object EInkBridge {

    /**
     * E-Ink tip 行高（dp）：随页眉/页脚字号设置（ReadTipConfig.tipTextSize，
     * 完整模式同键）同步伸缩——行高 = 字号 × 20/14，是模块推导口径
     * 「可用高度 × 14/20 = 字号」的反演（默认 12sp → 17dp，与历史常量
     * 一致，既有配置零漂移）。模块侧配置字号渲染仍经行需求钳制，极端
     * 字体行高超出时字形完整优先（字号微缩）。
     */
    internal fun tipRowDp(): Int = ReadTipConfig.tipTextSize * 20 / 14

    /** 模块页脚顶部的自动翻页进度条高度（dp，带宽预算内扣除）。 */
    internal const val TIP_PROGRESS_BAR_DP = 2

    /**
     * 当前在前的 E-Ink 宿主 Activity（EinkMainActivity onStart/onStop 挂载）。
     * 端口实现弹出宿主 UI 层（段评半屏 WebView 等宿主 DialogFragment）的事务
     * 宿主与生命周期作用域来源；无前台活动时（退后台/已销毁）相关分派
     * 静默放弃。
     */
    private var hostActivity: androidx.appcompat.app.AppCompatActivity? = null

    fun attachHostActivity(activity: androidx.appcompat.app.AppCompatActivity) {
        hostActivity = activity
    }

    fun detachHostActivity(activity: androidx.appcompat.app.AppCompatActivity) {
        if (hostActivity === activity) hostActivity = null
    }

    fun hostActivity(): androidx.appcompat.app.AppCompatActivity? = hostActivity

    fun install() {
        EInkEngineRegistry.install(
            globalSettings = GlobalSettingsImpl,
            bookshelfEngine = BookshelfEngineImpl,
            searchEngine = SearchEngineImpl,
            tocEngine = TocEngineImpl,
            bookDetailEngine = BookDetailEngineImpl,
            changeSourceEngine = ChangeSourceEngineImpl,
            coverEngine = CoverEngineImpl,
            readerEngine = ReaderEngineImpl,
            appUpdateEngine = AppUpdateEngineImpl,
            marksEngine = MarksEngineImpl,
            bookshelfGroupEngine = BookshelfGroupEngineImpl,
            backupSyncEngine = BackupSyncEngineImpl,
            pageTurnEffectEngine = PageTurnEffectEngineImpl,
        )
        // 封面开关为快照状态缓存：每次进入 E-Ink 与宿主设置对齐，
        // 防止完整模式（或上一会话）修改后的陈旧值
        GlobalSettingsImpl.syncUseDefaultCover()
        // tip 带预留按当前可见性同步（引擎排版避让页眉/页脚带）
        ReaderEngineImpl.syncTipReserves()
        // 应用字体快照与偏好记录对齐（副本可能被系统清理）
        EinkAppFonts.reload()
    }

    /**
     * 清零引擎侧 E-Ink 装饰预留（回完整模式前/入口销毁时调用——
     * ChapterProvider 为进程级单例，残留预留会使完整模式排版让位）。
     */
    fun resetEngineDecorations() {
        ChapterProvider.einkHeaderReserveDp = 0
        ChapterProvider.einkFooterReserveDp = 0
    }
}

/**
 * E-Ink 应用字体（界面字体）状态：本宿主完整模式无应用字体概念，
 * E-Ink 自管——源字体复制入私有目录，状态流发**已验证可加载的副本路径**
 * （Typeface 预载校验），入口 uiFontFamily 钩子按 md3 参照形态
 * `FontFamily(Font(file))` 消费（单 Normal 声明的 FontList——Bold 请求
 * 经平台 Typeface.create 合成假粗，直包 Typeface 会使 Bold 静默降级）。
 * 仅作用于 E-Ink 界面（与 md3「完整模式外观字体共享」形态的差异点）。
 */
internal object EinkAppFonts {

    /** 应用字体私有副本目录（filesDir/eink_fonts/）。 */
    internal val fontDir get() = java.io.File(appCtx.filesDir, "eink_fonts")

    private val state = MutableStateFlow<String?>(null)

    /** 当前生效的副本路径（null = 未设置/校验失败，平台默认）。 */
    val activeFontPath: kotlinx.coroutines.flow.StateFlow<String?> = state

    /** 按偏好记录重载副本（install 与每次 set/clear 后调用）。 */
    fun reload() {
        val copyPath = einkSharedPreferences().getString(KEY_APP_FONT_COPY, null)
        // 预载校验：非法字体文件（复制损坏/非字体）回落平台默认
        state.value = copyPath?.takeIf { path ->
            runCatching { android.graphics.Typeface.createFromFile(path) }.isSuccess
        }
    }

    /** 记录的当前副本路径（文件仍存在时）。 */
    fun activeCopyPath(): String? =
        einkSharedPreferences().getString(KEY_APP_FONT_COPY, null)
            ?.takeIf { java.io.File(it).exists() }

    /** 安装新副本：复制入私有目录、清理旧副本、写回记录并重载。 */
    fun install(sourcePath: String): Boolean {
        val uri = android.net.Uri.parse(sourcePath)
        val input = runCatching { appCtx.contentResolver.openInputStream(uri) }
            .getOrNull() ?: return false
        fontDir.mkdirs()
        val target = java.io.File(fontDir, "app_font_${System.currentTimeMillis()}")
        val ok = runCatching {
            input.use { src ->
                target.outputStream().use { src.copyTo(it) }
            }
        }.isSuccess
        if (!ok) {
            target.delete()
            return false
        }
        // 清理旧副本（仅保留新副本），再写记录
        fontDir.listFiles()?.forEach { if (it != target) it.delete() }
        einkSharedPreferences().edit()
            .putString(KEY_APP_FONT_SOURCE, sourcePath)
            .putString(KEY_APP_FONT_COPY, target.absolutePath)
            .apply()
        reload()
        return true
    }

    /** 清除副本与记录并重载（回平台默认）。 */
    fun clear() {
        fontDir.listFiles()?.forEach { it.delete() }
        einkSharedPreferences().edit()
            .remove(KEY_APP_FONT_SOURCE)
            .remove(KEY_APP_FONT_COPY)
            .apply()
        reload()
    }
}

// E-Ink 设置项的异步落盘作用域（本宿主 putPref 为同步 SP.apply，作用域
// 仅保留 fire-and-forget 形状以对齐端口契约的写入语义档位）。
internal val einkSettingsWriteScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private fun einkSharedPreferences() =
    appCtx.getSharedPreferences(EINK_PREFS_FILE, Context.MODE_PRIVATE)

/** E-Ink 自有偏好读取（专属 prefs 文件，SP 同步语义）。 */
internal fun einkPrefBoolean(key: String, default: Boolean): Boolean =
    einkSharedPreferences().getBoolean(key, default)

internal fun einkPrefInt(key: String, default: Int): Int =
    einkSharedPreferences().getInt(key, default)

/** E-Ink 自有偏好读取（专属 prefs 文件，SP 同步语义）。 */
internal fun einkPrefLong(key: String, default: Long): Long =
    einkSharedPreferences().getLong(key, default)

/** E-Ink 自有偏好写入（SP.apply 异步落盘，内存即时可见）。 */
internal fun einkPrefPutBoolean(key: String, value: Boolean) {
    einkSharedPreferences().edit().putBoolean(key, value).apply()
}

internal fun einkPrefPutInt(key: String, value: Int) {
    einkSharedPreferences().edit().putInt(key, value).apply()
}

/** E-Ink 自有偏好写入（SP.apply 异步落盘，内存即时可见）。 */
internal fun einkPrefPutLong(key: String, value: Long) {
    einkSharedPreferences().edit().putLong(key, value).apply()
}

/**
 * 全局设置视图（本宿主实现：AppConfig/ReadBookConfig/SP 扩展）。
 *
 * 写入语义：putPrefBoolean/putPrefInt 均为 SP.apply 异步落盘——写后立即
 * 读 getter 不保证可见新值，UI 侧按 fire-and-forget 档做乐观更新
 * （useDefaultCover 的快照状态除外）。
 */
private object GlobalSettingsImpl : GlobalSettings {

    /** 封面开关快照状态（install 时与宿主设置对齐，防跨模式往返陈旧值）。 */
    private val useDefaultCoverState = mutableStateOf(false)

    fun syncUseDefaultCover() {
        useDefaultCoverState.value = AppConfig.useDefaultCover
    }

    override val threadCount: Int
        get() = AppConfig.threadCount

    override var autoRefreshBook: Boolean
        get() = AppConfig.autoRefreshBook
        set(value) {
            appCtx.putPrefBoolean(PreferKey.autoRefresh, value)
        }

    override var defaultToRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.defaultToRead)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.defaultToRead, value)
        }

    /** 音量键翻页（实时档：按键时逐次读 SP，无一致性窗口）。 */
    override var volumeKeyPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.volumeKeyPage, value)
        }

    /** 快照状态档：组合内读取订阅变化，切换后开关行与封面立即重组。 */
    override var useDefaultCover: Boolean
        get() = useDefaultCoverState.value
        set(value) {
            useDefaultCoverState.value = value
            einkSettingsWriteScope.launch {
                appCtx.putPrefBoolean(PreferKey.useDefaultCover, value)
                AppConfig.useDefaultCover = value
            }
        }

    /**
     * 下拉添加书签（E-Ink 自有偏好，默认关）：专属 prefs 文件同步读写，
     * 读取在阅读 VM 构造时一次、写入即时生效且同步落盘。
     */
    override var pullDownBookmark: Boolean
        get() = einkSharedPreferences().getBoolean(KEY_PULL_DOWN_BOOKMARK, false)
        set(value) {
            einkSharedPreferences().edit().putBoolean(KEY_PULL_DOWN_BOOKMARK, value).apply()
        }

    /**
     * 阅读页隐藏系统状态栏（转发宿主阅读设置 hideStatusBar，与完整模式
     * 同键共享存储）。宿主该键为启动期一次性装载的内存快照——写入时
     * 双写内存值与 prefs，页眉可见性实时对齐。
     */
    override var hideStatusBar: Boolean
        get() = ReadBookConfig.hideStatusBar
        set(value) {
            ReadBookConfig.hideStatusBar = value
            appCtx.putPrefBoolean(PreferKey.hideStatusBar, value)
        }

    /**
     * 段评气泡显示开关（E-Ink 自有偏好，默认开）：宿主引擎有完整段评
     * 管线（书源 ruleReview / 图片选项 reviewCount → ImageColumn 携
     * click 脚本），但无总开关设置——完整模式段评列随书源配置恒渲染，
     * E-Ink 侧以本键自管（模块「显示段评气泡」开关读写此键）。
     */
    override var showReviewBubbles: Boolean
        get() = einkSharedPreferences().getBoolean(KEY_SHOW_REVIEW_BUBBLES, true)
        set(value) {
            einkSharedPreferences().edit()
                .putBoolean(KEY_SHOW_REVIEW_BUBBLES, value).apply()
        }

    override val supportsReviewBubbles: Boolean
        get() = true

    /**
     * 点击区域九宫格（E-Ink 自有偏好，不转发完整模式 clickAction* 键）：
     * 整键 9 位编码原样存取于专属 prefs 文件，编码/解码语义在模块侧。
     */
    override var readerTapZonesEncoding: String?
        get() = einkSharedPreferences().getString(KEY_READER_TAP_ZONES, null)
        set(value) {
            einkSharedPreferences().edit()
                .putString(KEY_READER_TAP_ZONES, value).apply()
        }

    /**
     * 翻页水波纹档位（E-Ink 自有偏好，默认关）：已注册
     * [PageTurnEffectEngineImpl]（掌阅 EPDC 固件反射实现）——非掌阅
     * 设备 supported=false，档位行自动隐藏，存储位保留。
     */
    override var pageTurnRippleMode: PageTurnRippleMode
        get() = PageTurnRippleMode.entries.firstOrNull {
            it.name.lowercase() ==
                einkSharedPreferences().getString(KEY_PAGE_TURN_RIPPLE_MODE, "off")
        } ?: PageTurnRippleMode.OFF
        set(value) {
            einkSharedPreferences().edit()
                .putString(KEY_PAGE_TURN_RIPPLE_MODE, value.name.lowercase()).apply()
        }

    /**
     * 最近文件字体历史（E-Ink 自有偏好）：换行分隔 path 列表原样存取，
     * 编码语义在模块侧。
     */
    override var recentFontPathsEncoding: String
        get() = einkSharedPreferences().getString(KEY_RECENT_FONTS, "") ?: ""
        set(value) {
            einkSharedPreferences().edit()
                .putString(KEY_RECENT_FONTS, value).apply()
        }

    /**
     * 繁简转换（转发宿主 chineseConverterType 键，与完整模式内容处理管线
     * 共享存储）。
     */
    override var chineseConverterType: Int
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    override val supportsChineseConverter: Boolean
        get() = true

    override val useAntiAlias: Boolean
        get() = AppConfig.useAntiAlias

    override val preDownloadChapterCount: Int
        get() = AppConfig.preDownloadNum

    override val changeSourceCheckAuthor: Boolean
        get() = AppConfig.changeSourceCheckAuthor

    /**
     * 应用内字体缩放（attach 期档）：本宿主完整模式无对应设置，E-Ink 自管
     * 键；0 视为未设置（null 语义——0/10 不在 0.8~1.6 区间，行为等价）。
     */
    override var fontScaleSetting: Int?
        get() = appCtx.getPrefInt(KEY_FONT_SCALE).takeIf { it != 0 }
        set(value) {
            appCtx.putPrefInt(KEY_FONT_SCALE, value ?: 0)
        }

    /**
     * 云端进度同步总开关（转发宿主「同步阅读进度」键，与完整模式「备份与
     * 恢复」共享存储）。E-Ink 一键全开语义由 [ReaderProgressSyncer] 落地：
     * 主开关开即完整双向同步（Plus 子键仅完整模式生效）。
     */
    override var syncReadingProgress: Boolean
        get() = AppConfig.syncBookProgress
        set(value) {
            appCtx.putPrefBoolean(PreferKey.syncBookProgress, value)
        }

    // —— 应用界面字体（E-Ink 自管，仅作用于 E-Ink 界面——本宿主完整
    // 模式无应用字体概念；md3 宿主为「完整模式外观字体共享」形态）——

    /**
     * 安装应用字体：源 path（SAF content/file URI）复制入私有目录后
     * 生效。复制串行执行（einkSettingsWriteScope 单协程顺序）；源不可
     * 读等失败静默丢弃（当前字体保持不变，对齐 md3 的降级口径）。
     */
    override fun setAppFont(sourcePath: String) {
        einkSettingsWriteScope.launch {
            EinkAppFonts.install(sourcePath)
        }
    }

    override fun clearAppFont() {
        einkSettingsWriteScope.launch {
            EinkAppFonts.clear()
        }
    }

    override val hasCustomAppFont: Boolean
        get() = EinkAppFonts.activeCopyPath() != null

    /**
     * 反显匹配：E-Ink 侧选择时记录的 源 path + 副本路径，仅当记录副本
     * 仍是当前生效副本时返回源 path。
     */
    override val currentAppFontListPath: String?
        get() = EinkAppFonts.activeCopyPath()?.let { active ->
            einkSharedPreferences().getString(KEY_APP_FONT_SOURCE, null)
                ?.takeIf { _ -> java.io.File(active).exists() }
        }
}
