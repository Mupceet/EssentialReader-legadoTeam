package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.eink.contract.BookSourceEngine
import io.legado.app.eink.contract.BookSourceEntryUiModel
import io.legado.app.help.CacheManager
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 书源管理端口实现。
 *
 * 读路径直连 `book_sources_part` 数据库视图的 Room flow（与完整模式书源
 * 管理列表同源），写操作复用宿主既有 DAO 事务路径；删除走
 * [SourceHelp.deleteBookSourceParts]（事务内级联删除源变量缓存并同步
 * 选中源配置，不可恢复——模块侧经用户确认后才调用）。
 *
 * 登录状态按 [CacheManager] `userInfo_<key>` 的存在性判定（与
 * BaseSource.getLoginInfoMap 同键）：登录信息不是 Room 表、无失效通知，
 * 由模块在进入页面/从登录界面返回（ON_RESUME）时拉取快照合并。
 */
internal object BookSourceEngineImpl : BookSourceEngine {

    override fun sourceEntries(): Flow<List<BookSourceEntryUiModel>> =
        appDb.bookSourceDao.flowAll().map { parts -> parts.map { it.toUiModel() } }

    private fun BookSourcePart.toUiModel(): BookSourceEntryUiModel = BookSourceEntryUiModel(
        url = bookSourceUrl,
        name = bookSourceName,
        group = bookSourceGroup,
        enabled = enabled,
        hasLoginUrl = hasLoginUrl,
        customOrder = customOrder,
        weight = weight,
        lastUpdateTime = lastUpdateTime,
        respondTime = respondTime,
    )

    override suspend fun loginStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        // 只查配置了登录地址的源（book_sources_part 视图内联 hasLoginUrl 条件）
        appDb.bookSourceDao.allPart
            .filter { it.hasLoginUrl }
            .associate {
                it.bookSourceUrl to (CacheManager.get("userInfo_${it.bookSourceUrl}") != null)
            }
    }

    override suspend fun setEnabled(urls: List<String>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            // dao 批量启停为按 url 逐条 UPDATE 的事务包装，最小 Part 只需 url
            appDb.bookSourceDao.enable(enabled, urls.map { BookSourcePart(bookSourceUrl = it) })
        }

    override suspend fun setExploreEnabled(urls: List<String>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            appDb.bookSourceDao.enableExplore(
                enabled,
                urls.map { BookSourcePart(bookSourceUrl = it) },
            )
        }

    override suspend fun moveToEdge(urls: List<String>, toTop: Boolean) =
        withContext(Dispatchers.IO) {
            // 按当前 manual 序（customOrder 升序）选中块整体移到全表最前/最后，
            // 块内保持原相对顺序；随后全表重编 customOrder（0..n-1）——恒为新序，
            // 无序号越界/重复隐患（契约的边界偏移优化路径退化为等价的全表重排）。
            // 不存在的 url 静默跳过（partition 丢弃）。
            val selected = urls.toHashSet()
            val all = appDb.bookSourceDao.all
            if (all.isEmpty() || selected.isEmpty()) return@withContext
            val (sel, rest) = all.partition { it.bookSourceUrl in selected }
            val ordered = if (toTop) sel + rest else rest + sel
            ordered.forEachIndexed { index, bookSource -> bookSource.customOrder = index }
            appDb.bookSourceDao.updateSources(*ordered.toTypedArray())
        }

    override suspend fun deleteSources(urls: List<String>) = withContext(Dispatchers.IO) {
        SourceHelp.deleteBookSourceParts(urls.map { BookSourcePart(bookSourceUrl = it) })
    }

    override fun openSourceLogin(sourceKey: String): Boolean {
        // 登录界面（表单/WebView/验证码）由宿主完整模式同款
        // SourceLoginActivity 承载（type=key 约定与 AutoTask 登录入口同链），
        // 覆盖在 E-Ink 界面之上；登录完成返回后模块经 ON_RESUME 重拉登录态
        val activity = EInkBridge.hostActivity() ?: return false
        activity.startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", sourceKey)
        }
        return true
    }
}
