package io.legado.app.eink.bridge

import io.legado.app.help.HighlightStyle
import org.junit.Assert.assertEquals
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
    fun `空样式与纯视觉样式不发射覆盖`() {
        assertNull(ruleChunkOverride(HighlightStyle(), null, null, 20f))
        // 加粗/字距无契约字段（明示降级）：不发射，避免真实 BOLD 字体偏离槽宽测量
        assertNull(ruleChunkOverride(HighlightStyle(bold = true), null, null, 20f))
        assertNull(ruleChunkOverride(HighlightStyle(letterSpacing = 0.2f), null, null, 20f))
    }

    @Test
    fun `斜体字色字号差发射覆盖`() {
        val override = ruleChunkOverride(
            HighlightStyle(textColor = 0xFF0000FF.toInt(), italic = true),
            textSizePx = 24f,
            typeface = null,
            baseTextSizePx = 20f,
        )!!
        assertEquals(24f, override.textSizePx)
        assertEquals(0xFF121212.toInt(), override.colorArgb)
        assertEquals(SYNTHETIC_ITALIC_SKEW_X, override.skewX)
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
}
