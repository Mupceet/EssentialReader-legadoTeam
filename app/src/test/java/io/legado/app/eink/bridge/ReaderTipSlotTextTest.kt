package io.legado.app.eink.bridge

import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.help.config.ReaderInfoValues
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页眉/页脚槽位文本解析契约（ReaderTipSlotTexts 宿主义务，0.8.2 起
 * headerFooterTipTexts 端口）：视觉型类型文本化降级——电量图形给 `NN%`、
 * 时间+图形电量给 `HH:mm NN%`、箭头给纯章节标题；文本/模板文字原样保留。
 */
class ReaderTipSlotTextTest {

    /** 数值基构型：时间 08:05、电量 85%、页 2/10、章节 3/100。 */
    private fun values(
        chapterTitle: String = "第三章 试炼",
        time: String = "08:05",
        battery: Int = 85,
    ) = ReaderInfoValues(
        bookName = "测试书名",
        chapterTitle = chapterTitle,
        time = time,
        battery = battery,
        page = "2",
        totalPages = "10",
        readProgress = "12.3%",
        chapter = "3",
        totalChapters = "100",
    )

    @Test
    fun `电量图形文本化为百分比`() {
        assertEquals("85%", renderTipSlotText(ReaderInfoTemplate.BATTERY_ICON, values()))
        // 带数值档图标（BatteryIcon showLevel）同样降级 NN%
        assertEquals("85%", renderTipSlotText(ReaderInfoTemplate.BATTERY_NUMBER_ICON, values()))
    }

    @Test
    fun `时间加图形电量合成 HHmm NNpct`() {
        assertEquals(
            "08:05 85%",
            renderTipSlotText("${ReaderInfoTemplate.TIME} ${ReaderInfoTemplate.BATTERY_ICON}", values()),
        )
    }

    @Test
    fun `章节标题给纯标题不添箭头装饰`() {
        assertEquals("第三章 试炼", renderTipSlotText(ReaderInfoTemplate.CHAPTER_TITLE, values()))
    }

    @Test
    fun `文本与混合模板逐段拼接`() {
        assertEquals(
            "第2/10页",
            renderTipSlotText("第${ReaderInfoTemplate.PAGE}/${ReaderInfoTemplate.TOTAL_PAGES}页", values()),
        )
        assertEquals(
            "测试书名 08:05",
            renderTipSlotText("${ReaderInfoTemplate.BOOK_NAME} ${ReaderInfoTemplate.TIME}", values()),
        )
    }

    @Test
    fun `空模板解析为空串_槽不绘制`() {
        assertEquals("", renderTipSlotText("", values()))
        // 未知 token 原样保留（解析器兜底口径，不抛异常）
        assertEquals("{未知}", renderTipSlotText("{未知}", values()))
    }

    @Test
    fun `电量钳制到 0_100`() {
        assertEquals("0%", renderTipSlotText(ReaderInfoTemplate.BATTERY_ICON, values(battery = -5)))
        assertEquals("100%", renderTipSlotText(ReaderInfoTemplate.BATTERY_ICON, values(battery = 130)))
    }
}
