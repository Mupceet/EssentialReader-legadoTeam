package io.legado.app.eink.bridge

import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.UNDERLINE_MODE_DASHED
import io.legado.app.help.config.UNDERLINE_MODE_DOTTED
import io.legado.app.help.config.UNDERLINE_MODE_DOUBLE
import io.legado.app.help.config.UNDERLINE_MODE_DOUBLE_DASHED
import io.legado.app.help.config.UNDERLINE_MODE_SOLID
import io.legado.app.help.config.UNDERLINE_MODE_WAVY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮规则 → 模块渲染的纯函数契约：灰度换算（BT.709 保 alpha）、装饰
 * 签名折叠（带/线两路、点线降级、几何口径）、逐段画笔覆盖发射判定、
 * 相邻同签名段合并。
 */
class ReaderPageSnapshotMapperTest {

    // ---- 灰度换算 ----

    @Test
    fun `灰度换算 BT709 保 alpha`() {
        // 0.2126*255 ≈ 54 (0x36)
        assertEquals(0xFF363636.toInt(), 0xFFFF0000.toInt().toEInkGrayArgb())
        // 0.7152*255 ≈ 182 (0xB6)
        assertEquals(0xFFB6B6B6.toInt(), 0xFF00FF00.toInt().toEInkGrayArgb())
        // 0.0722*255 ≈ 18 (0x12)
        assertEquals(0xFF121212.toInt(), 0xFF0000FF.toInt().toEInkGrayArgb())
        assertEquals(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt().toEInkGrayArgb())
        assertEquals(0xFF000000.toInt(), 0xFF000000.toInt().toEInkGrayArgb())
        // alpha 原样保留
        assertEquals(0x80363636.toInt(), 0x80FF0000.toInt().toEInkGrayArgb())
    }

    // ---- 逐段画笔覆盖发射 ----

    @Test
    fun `空样式与字距样式不发射覆盖`() {
        assertNull(ruleChunkOverride(HighlightStyle(), null, null, 20f))
        // 字距无契约字段（明示降级）：不发射，位置已由重排烘进列坐标
        assertNull(ruleChunkOverride(HighlightStyle(letterSpacing = 0.2f), null, null, 20f))
    }

    @Test
    fun `加粗走合成 fakeBold 跨桥`() {
        // 完整模式同口径：fake bold 只描边加宽不改步进宽，无对齐风险
        val override = ruleChunkOverride(HighlightStyle(bold = true), null, null, 20f)!!
        assertTrue(override.fakeBold)
        assertNull(override.textSizePx)
        assertNull(override.typeface)
        assertNull(override.colorArgb)
        assertNull(override.skewX)
    }

    @Test
    fun `斜体字色字号差发射覆盖`() {
        val override = ruleChunkOverride(
            HighlightStyle(textColor = 0xFF0000FF.toInt(), italic = true, bold = true),
            textSizePx = 24f,
            typeface = null,
            baseTextSizePx = 20f,
        )!!
        assertEquals(24f, override.textSizePx)
        assertEquals(0xFF121212.toInt(), override.colorArgb)
        assertEquals(SYNTHETIC_ITALIC_SKEW_X, override.skewX)
        assertTrue(override.fakeBold)
        assertNull(override.typeface)
        // 倾斜量镜像完整模式 HighlightDraw.obtainTextPaint 的 textSkewX
        assertEquals(-0.25f, SYNTHETIC_ITALIC_SKEW_X, 0f)
    }

    @Test
    fun `字号与基线同值不发射覆盖`() {
        assertNull(
            ruleChunkOverride(HighlightStyle(fontSize = 20f), textSizePx = 20f, typeface = null, baseTextSizePx = 20f)
        )
        // 字号缺省（null）仅字色发射时 textSize 为 null
        val override = ruleChunkOverride(HighlightStyle(textColor = 0xFF00FF00.toInt()), null, null, 20f)!!
        assertNull(override.textSizePx)
        assertEquals(0xFFB6B6B6.toInt(), override.colorArgb)
    }

    // ---- 装饰签名 ----

    @Test
    fun `fill 形状折叠为整行盒带或荧光半高带`() {
        assertNull(HighlightStyle().bandRunSignature())
        val rect = HighlightStyle(fill = 0xFFFF0000.toInt()).bandRunSignature()!!
        assertTrue(rect.highlight)
        assertEquals(0, rect.underlineMode)
        assertEquals(0xFF363636.toInt(), rect.highlightColorArgb)
        assertNull(rect.underlineColorArgb)
        for (shape in listOf(
            HighlightStyle.FillShape.MARKER,
            HighlightStyle.FillShape.HALF,
            HighlightStyle.FillShape.BASELINE,
        )) {
            val marker = HighlightStyle(fill = 0xFF00FF00.toInt(), fillShape = shape).bandRunSignature()!!
            assertTrue("!$shape 应折荧光半高带", !marker.highlight)
            assertEquals(7, marker.underlineMode)
            assertEquals(0xFFB6B6B6.toInt(), marker.underlineColorArgb)
            assertNull(marker.highlightColorArgb)
        }
    }

    @Test
    fun `下划线几何锚定完整模式口径`() {
        // dp 注入恒等映射：断言与完整模式常量同值（虚线 6/4dp、波长 6dp、
        // 双线附加 1dp、幅度 = max(线宽, 1dp)、offset = distance − descent）
        val sig = HighlightStyle(
            underline = HighlightStyle.Underline(
                kind = HighlightStyle.Kind.WAVY,
                width = 2f,
                distance = 3f,
            ),
        ).lineRunSignature(dpPx = { it }, baselineDropPx = 9f)!!
        assertEquals(3, sig.underlineMode)
        val geometry = sig.underline!!
        assertEquals(2f, geometry.widthPx, 0f)
        assertEquals(-6f, geometry.offsetPx, 0f)
        assertEquals(6f, geometry.dashOnPx, 0f)
        assertEquals(4f, geometry.dashOffPx, 0f)
        assertEquals(6f, geometry.waveLengthPx, 0f)
        assertEquals(2f, geometry.waveAmplitudePx, 0f)
        assertEquals(1f, geometry.doubleLineGapPx, 0f)
        assertNull(sig.underlineColorArgb)
    }

    @Test
    fun `点状线降级虚线 线宽为零回落删除线`() {
        assertEquals(
            2,
            HighlightStyle(underline = HighlightStyle.Underline(kind = HighlightStyle.Kind.DOTTED))
                .lineRunSignature({ it }, 0f)!!.underlineMode,
        )
        // 宽度 0 的下划线不产生线形装饰（完整模式同款判据），删除线接管
        val strike = HighlightStyle(
            underline = HighlightStyle.Underline(width = 0f),
            strike = HighlightStyle.Deco(color = 0xFF0000FF.toInt()),
        ).lineRunSignature({ it }, 0f)!!
        assertEquals(6, strike.underlineMode)
        assertEquals(0xFF121212.toInt(), strike.underlineColorArgb)
        // 删除线无色：null = 模块主题文字色
        assertNull(
            HighlightStyle(strike = HighlightStyle.Deco()).lineRunSignature({ it }, 0f)!!.underlineColorArgb
        )
    }

    // ---- run 合并 ----

    @Test
    fun `相邻同签名段合并为规则 run 且 markingId 为空`() {
        val band = HighlightStyle(fill = 0xFFFF0000.toInt()).bandRunSignature()
        val runs = mergeRuleRuns(
            chunks = listOf("对", "话", "：", "你", "好"),
            signatures = listOf(band, band, null, band, band),
        )
        assertEquals(2, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].end)
        assertEquals(3, runs[1].start)
        assertEquals(5, runs[1].end)
        assertTrue(runs.all { it.markingId.isEmpty() })
        assertTrue(runs.all { it.highlight })
    }

    @Test
    fun `不同灰度不并入同一 run`() {
        val red = HighlightStyle(fill = 0xFFFF0000.toInt()).bandRunSignature()
        val green = HighlightStyle(fill = 0xFF00FF00.toInt()).bandRunSignature()
        val runs = mergeRuleRuns(listOf("甲", "乙"), listOf(red, green))
        assertEquals(2, runs.size)
        assertEquals(0xFF363636.toInt(), runs[0].highlightColorArgb)
        assertEquals(0xFFB6B6B6.toInt(), runs[1].highlightColorArgb)
    }

    @Test
    fun `全空签名不产生 run`() {
        assertTrue(mergeRuleRuns(listOf("文", "本"), listOf(null, null)).isEmpty())
    }

    // ---- 页级横线（整页文字下划线 → ruleLines）----

    /** 发射输入基构型：实线 3px 粗、基线下 12px、显式黑。 */
    private fun underlineSpec(
        mode: Int,
        widthPx: Float = 3f,
        distancePx: Float = 12f,
        titleEnabled: Boolean = true,
        bodyEnabled: Boolean = true,
        colorArgb: Int? = 0xFF111111.toInt(),
    ) = WholePageUnderline(
        mode = mode,
        widthPx = widthPx,
        distancePx = distancePx,
        titleEnabled = titleEnabled,
        bodyEnabled = bodyEnabled,
        colorArgb = colorArgb,
        bookIsImage = false,
        dashOnPx = 18f,
        dashOffPx = 12f,
        doubleLineExtraPx = 3f,
    )

    /** 正文行基构型：缩进 20px、基线 40px。 */
    private fun emitLine(
        spec: WholePageUnderline,
        isTitle: Boolean = false,
        isImageLine: Boolean = false,
        isHtmlLine: Boolean = false,
        lineStartX: Float = 100f,
        lineEndX: Float = 900f,
        indentWidth: Float = 20f,
        lineBaseY: Float = 40f,
        grayArgb: Int = 0xFF000000.toInt(),
    ) = wholePageUnderlineRuleLines(
        isTitle = isTitle,
        isImageLine = isImageLine,
        isHtmlLine = isHtmlLine,
        lineStartX = lineStartX,
        lineEndX = lineEndX,
        indentWidth = indentWidth,
        lineBaseY = lineBaseY,
        spec = spec,
        grayArgb = grayArgb,
    )

    @Test
    fun `实线下划线发射零高矩形并含缩进锚定基线加距离`() {
        val lines = emitLine(underlineSpec(UNDERLINE_MODE_SOLID), grayArgb = 0xFF121212.toInt())
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(120f, line.left, 0f) // lineStart + indentWidth
        assertEquals(900f, line.right, 0f)
        assertEquals(52f, line.top, 0f) // lineBase + distance
        assertEquals("整页下划线为零高矩形（top == bottom = 线 y）", line.top, line.bottom, 0f)
        assertEquals(3f, line.widthPx, 0f)
        assertFalse(line.dashed)
        assertEquals(0xFF121212.toInt(), line.colorArgb)
    }

    @Test
    fun `虚线族按值发射_点状降级虚线_波浪降级实线`() {
        // 虚线：dashed = true，墨长/空长原样透传
        val dashed = emitLine(underlineSpec(UNDERLINE_MODE_DASHED))[0]
        assertTrue(dashed.dashed)
        assertEquals(18f, dashed.dashOnPx, 0f)
        assertEquals(12f, dashed.dashOffPx, 0f)
        // 点状：无契约形态，降级虚线（最接近的断线视觉）
        assertTrue(emitLine(underlineSpec(UNDERLINE_MODE_DOTTED))[0].dashed)
        // 波浪：花色形状无契约形态，降级实线（连续线语义）
        assertFalse(emitLine(underlineSpec(UNDERLINE_MODE_WAVY))[0].dashed)
    }

    @Test
    fun `双线系发射两条且分离为线粗加附加量`() {
        // 双实线：y ∓ (3 + 3) / 2 = 52 ∓ 3
        val doubleLines = emitLine(underlineSpec(UNDERLINE_MODE_DOUBLE))
        assertEquals(2, doubleLines.size)
        assertEquals(49f, doubleLines[0].top, 0f)
        assertEquals(55f, doubleLines[1].top, 0f)
        assertTrue(doubleLines.all { !it.dashed })
        // 双虚线：两条均虚线
        val doubleDashed = emitLine(underlineSpec(UNDERLINE_MODE_DOUBLE_DASHED))
        assertEquals(2, doubleDashed.size)
        assertTrue(doubleDashed.all { it.dashed })
        assertEquals(49f, doubleDashed[0].top, 0f)
    }

    @Test
    fun `标题正文分控与图片书_html_图片行门控不发射`() {
        // 标题行在 body 关闭（仅 bodyEnabled=false）时仍发射（标题分控独立开）
        assertEquals(
            1,
            emitLine(underlineSpec(UNDERLINE_MODE_SOLID, bodyEnabled = false), isTitle = true).size,
        )
        // 标题分控关闭：标题行不发射，正文行发射
        assertTrue(emitLine(underlineSpec(UNDERLINE_MODE_SOLID, titleEnabled = false), isTitle = true).isEmpty())
        assertEquals(1, emitLine(underlineSpec(UNDERLINE_MODE_SOLID, titleEnabled = false)).size)
        // 引擎行级门控同款：图片书/图片行/HTML 行不发射
        assertTrue(
            emitLine(
                underlineSpec(UNDERLINE_MODE_SOLID).copy(bookIsImage = true),
            ).isEmpty()
        )
        assertTrue(emitLine(underlineSpec(UNDERLINE_MODE_SOLID), isImageLine = true).isEmpty())
        assertTrue(emitLine(underlineSpec(UNDERLINE_MODE_SOLID), isHtmlLine = true).isEmpty())
    }

    @Test
    fun `空列行与零宽行不发射`() {
        // x1 <= x0（lineEnd 不大于缩进起点）跳过（引擎同款守卫）
        assertTrue(
            emitLine(underlineSpec(UNDERLINE_MODE_SOLID), lineStartX = 100f, lineEndX = 110f, indentWidth = 20f).isEmpty()
        )
    }
}
