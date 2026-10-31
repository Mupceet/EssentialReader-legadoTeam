package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/**
 * 宿主排版参数协商目录：按本宿主 ReadBookConfig/ReadTipConfig 引擎真实
 * 能力逐参数声明（值域/默认值与宿主默认一致），模块据此呈现设置行。
 *
 * 本宿主能力边界（不可用参数直接不进目录，模块隐藏对应设置项）：
 *  - 标题/页眉无独立字体（引擎单字体 + titleBold/textBold 独立字重键）；
 *  - 标题行距无配置键；
 *  - 页眉/页脚字号共用宿主单键 ReadTipConfig.tipTextSize（页眉页脚同
 *    字号，完整模式同键同值域；模块统一拖动条写两侧）；
 *  - 标题字号为「正文 + 增量」模型（引擎 titlePaint = textSize+titleSize），
 *    契约 TITLE_SIZE 的绝对值在 applyStyle/currentStyle 映射期换算；
 *  - 正文/标题字重均仅 0 常规/1 粗/2 细三预设（引擎 getPaints）——
 *    Presets 档声明（无自定义滑条；若有自定义值进入 applyStyle 就近
 *    量化兜底）。标题字重的「跟随正文」态（titleBold=-1）按引擎语义
 *    在快照期折算等效档，编辑写入即独立生效。
 *
 * affectsLayout 判定：本宿主 visibleHeight = viewHeight - 上下边距（含
 * E-Ink 装饰预留），页眉/页脚显隐与分割线不改变分页几何（绘制于边距
 * 带内），标 affectsLayout = false；字号（页眉/页脚带高随字号伸缩，
 * 见 ReaderEngineImpl.syncTipReserves）/字体/字重/标题参数与四边距均
 * 影响排版。
 */
internal object HostStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(Ids.BODY_SIZE, 8f, 40f, 20f),
            stepped(Ids.BODY_LETTER_SPACING, 0f, 0.5f, 0.1f),
            stepped(Ids.BODY_INDENT, 0f, 4f, 2f),
            stepped(Ids.BODY_LINE_SPACING, 0f, 30f, 12f),
            stepped(Ids.BODY_PARAGRAPH_SPACING, 0f, 10f, 2f),
            ReaderStyleParam.Font(Ids.BODY_FONT, available = true, affectsLayout = true),
            // 正文字重：引擎仅三预设（textBold 0 常规/1 粗/2 细）——预设档
            // 声明，无自定义滑条（allowCustom = false 隐藏「自定义」按钮）
            ReaderStyleParam.Presets(
                id = Ids.BODY_WEIGHT,
                available = true,
                affectsLayout = true,
                default = 0,
            ),
            // 标题字重：引擎独立 titleBold 键（-1 跟随正文 / 0 常规 / 1 粗 /
            // 2 细）——预设档声明；默认粗对齐引擎默认（跟随正文态的等效档）
            ReaderStyleParam.Presets(
                id = Ids.TITLE_WEIGHT,
                available = true,
                affectsLayout = true,
                default = 1,
            ),
            ReaderStyleParam.Choice(
                id = Ids.TITLE_MODE,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "居左"),
                    ReaderStyleParam.Choice.Option(1, "居中"),
                    ReaderStyleParam.Choice.Option(2, "隐藏"),
                ),
                default = 0,
            ),
            stepped(Ids.TITLE_SIZE, 8f, 60f, 20f),
            stepped(Ids.TITLE_TOP_SPACING, 0f, 48f, 0f),
            stepped(Ids.TITLE_BOTTOM_SPACING, 0f, 48f, 0f),
            ReaderStyleParam.Choice(
                id = Ids.HEADER_VISIBILITY,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "随状态栏"),
                    ReaderStyleParam.Choice.Option(1, "显示"),
                    ReaderStyleParam.Choice.Option(2, "隐藏"),
                ),
                default = 0,
            ),
            ReaderStyleParam.Toggle(
                id = Ids.FOOTER_VISIBILITY,
                available = true,
                affectsLayout = false,
                default = true,
            ),
            // 页眉/页脚字号：宿主单键 ReadTipConfig.tipTextSize（值域/默认
            // 同宿主 minTextSize..maxTextSize，页眉页脚同字号——模块统一
            // 拖动条写两侧）；带高随字号伸缩（syncTipExtents）影响分页
            stepped(Ids.HEADER_SIZE, 5f, 50f, 12f),
            stepped(Ids.FOOTER_SIZE, 5f, 50f, 12f),
            // 页眉/页脚分隔线不声明（模块 UI 整行隐藏）：E-Ink 模式不渲染
            // 分隔线（模块 tip 绘制按 style.headerDivider/footerDivider ==
            // true 才画，snapshotStyle 恒发 false），宿主 showHeaderLine/
            // showFooterLine 键保留给完整模式，两模式互不干扰
            stepped(Ids.BODY_PADDING_TOP, 0f, 48f, 6f),
            stepped(Ids.BODY_PADDING_BOTTOM, 0f, 48f, 6f),
            stepped(Ids.BODY_PADDING_LEFT, 0f, 64f, 16f),
            stepped(Ids.BODY_PADDING_RIGHT, 0f, 64f, 16f),
            stepped(Ids.HEADER_PADDING_TOP, 0f, 48f, 0f),
            stepped(Ids.HEADER_PADDING_BOTTOM, 0f, 48f, 0f),
            paintOnly(Ids.HEADER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(Ids.HEADER_PADDING_RIGHT, 0f, 48f, 16f),
            stepped(Ids.FOOTER_PADDING_TOP, 0f, 48f, 6f),
            stepped(Ids.FOOTER_PADDING_BOTTOM, 0f, 48f, 6f),
            paintOnly(Ids.FOOTER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(Ids.FOOTER_PADDING_RIGHT, 0f, 48f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)
}
