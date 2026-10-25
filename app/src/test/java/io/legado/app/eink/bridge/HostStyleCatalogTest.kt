package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主排版目录契约：页眉/页脚字号声明（宿主单键 ReadTipConfig.tipTextSize
 * 的值域/默认，模块「页眉页脚字号」统一拖动条据此呈现）。
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
}
