package io.legado.app.ui.book.read.page.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整页文字下划线让位分段契约（经典模式 TextLine.drawUnderline 与 E-Ink
 * ruleLines 发射共用）：覆盖段挖空、余下断开成段、整行被覆盖不发线、
 * 无覆盖与旧行为逐像素一致。MD3 宿主 addPageUnderline 同款语义移植。
 */
class TextLineUnderlineYieldTest {

    @Test
    fun `无覆盖返回单段与旧行为逐像素一致`() {
        val result = underlineYieldSegments(100f, 900f, emptyList())
        assertEquals(1, result.size)
        assertEquals(100f, result[0].start, 0f)
        assertEquals(900f, result[0].endInclusive, 0f)
    }

    @Test
    fun `中段覆盖挖空余下断开成段`() {
        val result = underlineYieldSegments(100f, 900f, listOf(300f..500f))
        assertEquals(2, result.size)
        assertEquals(100f, result[0].start, 0f)
        assertEquals(300f, result[0].endInclusive, 0f)
        assertEquals(500f, result[1].start, 0f)
        assertEquals(900f, result[1].endInclusive, 0f)
    }

    @Test
    fun `整行被覆盖返回空表不发线`() {
        assertTrue(underlineYieldSegments(100f, 900f, listOf(0f..2000f)).isEmpty())
        // 相邻覆盖区间拼满整行同样整行不发线
        assertTrue(underlineYieldSegments(100f, 900f, listOf(100f..500f, 500f..900f)).isEmpty())
    }

    @Test
    fun `越界与零宽覆盖区间裁剪不产生缺口`() {
        // 覆盖区间超出行界：裁剪到行内部分
        val clipped = underlineYieldSegments(100f, 900f, listOf(500f..2000f))
        assertEquals(1, clipped.size)
        assertEquals(100f, clipped[0].start, 0f)
        assertEquals(500f, clipped[0].endInclusive, 0f)
        // 零宽/行外区间（无可视线）不构成覆盖：照常单段
        assertEquals(1, underlineYieldSegments(100f, 900f, listOf(300f..300f)).size)
        assertEquals(1, underlineYieldSegments(100f, 900f, listOf(0f..50f)).size)
        assertEquals(1, underlineYieldSegments(100f, 900f, listOf(950f..1200f)).size)
    }

    @Test
    fun `无序覆盖区间排序后断开成段`() {
        // 覆盖区间无序传入（循环收集天然无序）：排序后 200..300 与 500..700
        val result = underlineYieldSegments(100f, 900f, listOf(500f..700f, 200f..300f))
        assertEquals(3, result.size)
        assertEquals(100f, result[0].start, 0f)
        assertEquals(200f, result[0].endInclusive, 0f)
        assertEquals(300f, result[1].start, 0f)
        assertEquals(500f, result[1].endInclusive, 0f)
        assertEquals(700f, result[2].start, 0f)
        assertEquals(900f, result[2].endInclusive, 0f)
    }

    @Test
    fun `重叠覆盖区间合并不产生零宽段`() {
        // 覆盖 200..400 与 300..600 合并为 200..600：余下 100..200 与 600..900
        val result = underlineYieldSegments(100f, 900f, listOf(200f..400f, 300f..600f))
        assertEquals(2, result.size)
        assertEquals(100f, result[0].start, 0f)
        assertEquals(200f, result[0].endInclusive, 0f)
        assertEquals(600f, result[1].start, 0f)
        assertEquals(900f, result[1].endInclusive, 0f)
    }

    @Test
    fun `行界退化不产生分段`() {
        assertTrue(underlineYieldSegments(500f, 500f, emptyList()).isEmpty())
        assertTrue(underlineYieldSegments(600f, 500f, emptyList()).isEmpty())
    }
}
