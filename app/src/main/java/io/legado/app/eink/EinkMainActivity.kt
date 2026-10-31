package io.legado.app.eink

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.constant.PreferKey
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.eink.bridge.EinkAppFonts
import io.legado.app.eink.bridge.PageTurnEffectEngineImpl
import io.legado.app.eink.contract.EInkHostActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity

/**
 * E-Ink 版本单 Activity 入口（模块模板基类 [EInkHostActivity] 的宿主子类）。
 *
 * 进入条件：本宿主以 themeMode == "4"（纯净模式/墨水屏 Compose 模式）
 * 分流——MainActivity.onActivityCreated 冷启动分流至此；退出时把
 * themeMode 写回 "0"（跟随系统）并回到 MainActivity。
 *
 * 生命周期编排（引擎装配时机、字体缩放、启动清理、直达最近阅读、
 * 跟随系统深浅色主题、按键分发）全部由基类承担，宿主差异只剩两个钩子
 * （引擎装配与退出出口）加可选 UI 字体钩子（[uiFontFamily]，E-Ink
 * 自管应用字体）。
 */
class EInkMainActivity : EInkHostActivity() {

    override fun onInstallEngines() = EInkBridge.install()

    /**
     * 字体文件夹选择接管（无系统 DocumentsUI 的低版本/定制固件）：
     * 宿主完整模式同款 HandleFileContract（应用内文件浏览器，DIR 模式，
     * 带 SD/Fonts 快捷项）——与 FontSelectDialog.selectFontDir 同链。
     * 结果归一化为 content uri 字符串或文件路径（selectFontDir 同口径），
     * ReaderFontFolder 枚举与 loadFontTypeface 加载均支持，模块原样落库
     * 即可。
     */
    private var pickFontFolderCallback: ((String?) -> Unit)? = null

    private val pickFontFolder = registerForActivityResult(
        io.legado.app.ui.file.HandleFileContract()
    ) { result ->
        // 双形态归一化（对齐 FontSelectDialog.selectFontDir）：content uri
        // 原样字符串；file://（FilePickerDialog 应用内选择器与手动输入的
        // 返回形态）取解码路径——契约约定回传「content uri 或文件路径」，
        // file:// 字符串直落库会使 File(folder) 路径解析失效（枚举恒空）
        val folder = result.uri?.let { uri ->
            if (uri.isContentScheme()) uri.toString() else uri.path
        }
        pickFontFolderCallback?.invoke(folder)
        pickFontFolderCallback = null
    }

    override fun onPickFontFolder(onResult: (String?) -> Unit): Boolean {
        pickFontFolderCallback = onResult
        pickFontFolder.launch { }
        return true
    }

    /**
     * 挂载为端口弹层的宿主 Activity（段评半屏 WebView 等宿主 DialogFragment
     * 的事务宿主与生命周期作用域；见 EInkBridge.attachHostActivity）。
     * 基类 onCreate 为 final 编排，onStart/onStop 是可用的生命周期挂点。
     */
    override fun onStart() {
        super.onStart()
        EInkBridge.attachHostActivity(this)
    }

    override fun onStop() {
        EInkBridge.detachHostActivity(this)
        super.onStop()
    }

    // ============ 旋转获取链路验证读数（调试） ============
    // 入口 Manifest 声明了 orientation|screenSize configChanges（旋转不重建），
    // 且入口包装冻结 resources.configuration——旋转正确性取决于 Display 实时
    // 查询。这两个钩子在任何设备（含不支持水波纹的）上可触发，logcat 过滤
    // TAG「PageTurnRipple」查看；R8 release 剥离 Log，仅 debug/noR8 构建输出。
    override fun onResume() {
        super.onResume()
        PageTurnEffectEngineImpl.debugLogRotation("onResume")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PageTurnEffectEngineImpl.debugLogRotation("onConfigurationChanged")
    }

    /**
     * E-Ink 界面 UI 字体（E-Ink 自管应用字体，[EinkAppFonts] 私有副本）：
     * 本宿主完整模式无应用字体设置，字体仅作用于 E-Ink 界面（与 md3
     * 宿主「完整模式外观字体共享」形态的差异点）。
     *
     * 按契约 EInkHostActivity.uiFontFamily KDoc 口径消费：单 Normal 声明
     * 的 FontList（FontFamily(Font(file))）——Bold 请求经平台
     * Typeface.create 合成假粗；FontFamily(typeface) 直包会使排版系统
     * 12 个 Bold 样式静默降为 Regular。副本路径就位/变更时重组替换；
     * 「字体设置」页选择/清除经状态流实时生效。
     */
    @Composable
    override fun uiFontFamily(): FontFamily? {
        val path by EinkAppFonts.activeFontPath.collectAsState()
        return path?.let { remembered ->
            remember(remembered) {
                FontFamily(Font(java.io.File(remembered)))
            }
        }
    }

    override fun onExitToFullMode(context: Context) {
        // 完整模式（View UI）——导入导出等管理功能在完整模式中完成，
        // 再次启用需把主题模式切回 "4"（宿主主题设置的分流键）
        // （E-Ink 几何经 extent 工厂实时计算，引擎侧无残留状态需清理）
        context.putPrefString(PreferKey.themeMode, "0")
        AppConfig.themeMode = "0"
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }
}
