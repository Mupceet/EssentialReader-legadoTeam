package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主排版目录契约：页眉/页脚字号声明（宿主单键 ReadTipConfig.tipTextSize
 * 的值域/默认，模块「页眉页脚字号」统一拖动条据此呈现）；分隔线 0.8.2 起
 * 恢复协商（模块按快照样式画线，写宿主 showHeaderLine/showFooterLine
 * 共享键）；整页下划线族/正文斜体/标题分段刻意不声明（宿主能力面与
 * 契约模型不对应或无配置键，见 HostStyleCatalog 头注）。
 */
class HostStyleCatalogTest {

    @Test
    fun `页眉页脚字号声明为可调档且值域默认同宿主 tipTextSize`() {
        val catalog = HostStyleCatalog.create()
        listOf(Ids.HEADER_SIZE, Ids.FOOTER_SIZE).forEach { id ->
            val param = catalog.find(id) as? ReaderStyleParam.Stepped
            assertTrue("$id 应声明为 Stepped 可调档", param != null)
            param!!
            assertTrue("$id 应可用", param.available)
            assertTrue("$id 调整带高应触发重排", param.affectsLayout)
            assertEquals("$id 下界应同 ReadTipConfig.minTextSize", 5f, param.min, 0f)
            assertEquals("$id 上界应同 ReadTipConfig.maxTextSize", 50f, param.max, 0f)
            assertEquals("$id 默认值应同宿主默认", 12f, param.default, 0f)
        }
    }

    @Test
    fun `分隔线声明为开关档且默认同宿主共享键`() {
        val catalog = HostStyleCatalog.create()
        listOf(Ids.HEADER_DIVIDER, Ids.FOOTER_DIVIDER).forEach { id ->
            val param = catalog.find(id) as? ReaderStyleParam.Toggle
            assertTrue("$id 应声明为 Toggle 开关档", param != null)
            param!!
            assertTrue("$id 应可用", param.available)
            // 分隔线计入 tip 带高（tipBandExtentPx），变更走重排路径
            assertTrue("$id 变更带高应触发重排", param.affectsLayout)
        }
        assertEquals(
            "页眉分隔线默认应同宿主 showHeaderLine",
            false,
            (catalog.find(Ids.HEADER_DIVIDER) as ReaderStyleParam.Toggle).default,
        )
        assertEquals(
            "页脚分隔线默认应同宿主 showFooterLine",
            true,
            (catalog.find(Ids.FOOTER_DIVIDER) as ReaderStyleParam.Toggle).default,
        )
    }

    @Test
    fun `整页下划线族_正文斜体_标题分段不声明`() {
        val catalog = HostStyleCatalog.create()
        val undeclared = listOf(
            Ids.BODY_UNDERLINE, Ids.BODY_UNDERLINE_WIDTH, Ids.BODY_UNDERLINE_OFFSET,
            Ids.BODY_UNDERLINE_EXTEND, Ids.BODY_UNDERLINE_DASHED, Ids.BODY_UNDERLINE_DASH_ON,
            Ids.BODY_UNDERLINE_DASH_OFF, Ids.BODY_ITALIC, Ids.FOOTER_FONT,
            Ids.TITLE_SEGMENT_MODE, Ids.TITLE_SEGMENT_DISTANCE, Ids.TITLE_SEGMENT_SCALE,
            Ids.TITLE_SEGMENT_SPACING,
        )
        undeclared.forEach { id ->
            assertEquals("$id 不应进目录（宿主能力面见 HostStyleCatalog 头注）", null, catalog.find(id))
        }
    }
}
