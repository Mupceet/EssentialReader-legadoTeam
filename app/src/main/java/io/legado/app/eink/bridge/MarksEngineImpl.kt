package io.legado.app.eink.bridge

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarksEngine
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.ReaderMarkingDetail
import io.legado.app.eink.contract.ReaderPageBookmarkContent
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.model.ReadBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import splitties.init.appCtx
import kotlin.coroutines.cancellation.CancellationException

/**
 * 书签/笔记统一端口实现（契约 v2 合并轮：阅读内选区状态机 + 目录页
 * 书签/笔记 Tab 同端口）。
 *
 * 本宿主存储为 bookmarks 单表，按 bookText 分区——空 = 位置书签
 * （书签 Tab，eink 快速书签不存摘录、显示字段仅章节名），非空 = 文字
 * 锚点书签（笔记 Tab 的划线/想法，与完整模式「选中文字书签」同一条
 * 记录，两模式互通）。
 *
 * 能力声明（对齐 md3 宿主全开）：
 *  - 笔记能力（supportsMarkings = true）：完整链路——阅读内划线/想法
 *    保存与状态转换、目录页笔记 Tab、跳转解析、Markdown 导出；
 *  - 书签能力（supportsBookmarks = true）：阅读内页面书签 toggle
 *    （下拉书签/顶栏书签钮/页角标）+ 目录页书签 Tab。单表分区的已知
 *    取舍：完整模式快速书签（带页摘录、bookText 非空）会归入笔记 Tab
 *    展示为划线卡（跳转/删除均可用；治本需 bookmarks 表加判别列）。
 *
 * 跳转校验为章节标题比对（宿主无源指纹机制）；契约的「本地重定位」
 * 评分机制本宿主未实现——标题不匹配时直接走 NeedConfirm（fallback =
 * 存储坐标），不发起网络。
 */
internal object MarksEngineImpl : MarksEngine {

    /** 位置书签判别：bookText 为空 = 快速书签（eink 快速书签不存摘录）。 */
    private fun Bookmark.isPageBookmark() = bookText.isEmpty()

    // —— 阅读内：笔记状态机与页面书签 ——

    override suspend fun createMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        return try {
            // 同锚点 upsert（锚点 = 章节 + 选区起点；限文字书签不吞位置书签）
            // ——契约 v2 下 createMarking 仅「新选区」入口可达，upsert 为
            // 防御性去重（模块保证不会把状态转换路由到本方法）
            val existing = appDb.bookmarkDao.getByBook(book.name, book.author)
                .firstOrNull {
                    !it.isPageBookmark() &&
                        it.chapterIndex == commit.chapterIndex &&
                        it.chapterPos == commit.start
                }
            if (existing != null) {
                existing.bookText = commit.selectedText
                existing.content = commit.note
                appDb.bookmarkDao.update(existing)
            } else {
                appDb.bookmarkDao.insert(
                    Bookmark(
                        bookName = book.name,
                        bookAuthor = book.author,
                        chapterIndex = commit.chapterIndex,
                        chapterPos = commit.start,
                        chapterName = ReadBook.curTextChapter?.title ?: "",
                        bookText = commit.selectedText,
                        content = commit.note,
                    )
                )
            }
            // 契约：落库后触发当前章重排（保持页内位置）——新快照携带划线
            // 装饰推送，模块的待确认预览随 pageVersion 推进正常收尾
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun updateMarkingNote(markingId: String, note: String): Boolean? {
        val marking = findBookmarkRow(markingId) ?: return null
        return try {
            marking.content = note
            appDb.bookmarkDao.update(marking)
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteMarking(markingId: String): Boolean {
        val marking = findBookmarkRow(markingId) ?: return false
        return try {
            appDb.bookmarkDao.delete(marking)
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun findMarking(markingId: String): ReaderMarkingDetail? {
        val marking = findBookmarkRow(markingId) ?: return null
        // 契约 v2：类型恒由 note 派生（note 空白 = 划线；非空白 = 想法）
        return ReaderMarkingDetail(
            selectedText = marking.bookText,
            note = marking.content,
        )
    }

    override suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean? {
        val book = ReadBook.book ?: return null
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex) ?: return null
        val samePage = appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter {
                it.isPageBookmark() &&
                    it.chapterIndex == ReadBook.durChapterIndex &&
                    page.containPos(it.chapterPos)
            }
        return try {
            if (samePage.isNotEmpty()) {
                // 同页多条时删最近一条（契约快速书签语义）
                appDb.bookmarkDao.delete(samePage.maxBy { it.time })
                ReaderEngineImpl.relayout()
                false
            } else {
                appDb.bookmarkDao.insert(
                    book.createBookMark().apply {
                        chapterIndex = ReadBook.durChapterIndex
                        chapterPos = ReadBook.durChapterPos
                        chapterName = content.chapterName
                        // 不存页摘录：空 bookText = 位置书签（不进笔记 Tab，
                        // 与「选中文字书签 = 划线」的分区判别一致——单表
                        // 宿主以 bookText 空否分区，存摘录会误入笔记 Tab）
                        bookText = ""
                    }
                )
                ReaderEngineImpl.relayout()
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // —— 目录页：列表 / 跳转 / 导出 ——

    override fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>> =
        observeRows(bookUrl) { rows ->
            rows.filter { it.isPageBookmark() }
                .map { row ->
                    BookmarkUiModel(
                        id = row.time,
                        chapterIndex = row.chapterIndex,
                        chapterName = row.chapterName,
                        bookText = row.bookText,
                        content = row.content,
                    )
                }
        }

    override fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>> =
        observeRows(bookUrl) { rows ->
            rows.filter { !it.isPageBookmark() }
                .map { row ->
                    MarkingUiModel(
                        id = row.time.toString(),
                        chapterIndex = row.chapterIndex,
                        chapterName = row.chapterName,
                        chapterPos = row.chapterPos,
                        selectedText = row.bookText,
                        note = row.content,
                        thought = row.content.isNotEmpty(),
                        createdAt = row.time,
                    )
                }
        }

    /** 按 bookUrl 解析书籍后订阅书签流（按章节、章内位置升序分区映射）。 */
    private fun <T> observeRows(
        bookUrl: String,
        transform: (List<Bookmark>) -> List<T>,
    ): Flow<List<T>> {
        val book = runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull()
            ?: return flowOf(emptyList())
        return appDb.bookmarkDao.flowByBook(book.name, book.author)
            .map { rows ->
                transform(rows.sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }, { it.time })))
            }
    }

    override suspend fun deleteBookmark(bookmarkId: Long): Boolean {
        // 宿主 DAO 无按主键单查：全量快照过滤（书签量级可接受）
        val row = appDb.bookmarkDao.flowAll().first()
            .firstOrNull { it.time == bookmarkId }
            ?: return false
        return try {
            appDb.bookmarkDao.delete(row)
            if (ReadBook.book != null) ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution {
        val row = sessionRow(bookmarkId) ?: return JumpResolution.Failed("书签不存在")
        return resolveJump(row)
    }

    override suspend fun resolveMarkingJump(markingId: String): JumpResolution {
        val id = markingId.toLongOrNull()
            ?: return JumpResolution.Failed("标记不存在")
        val row = sessionRow(id) ?: return JumpResolution.Failed("标记不存在")
        return resolveJump(row)
    }

    /** 章节标题比对校验（Match→Located；不匹配/越界→NeedConfirm 回落存储坐标）。 */
    private suspend fun resolveJump(row: Bookmark): JumpResolution {
        val book = ReadBook.book ?: return JumpResolution.Failed("无阅读会话")
        if (row.chapterIndex >= book.totalChapterNum) {
            return JumpResolution.NeedConfirm(
                "书签指向第${row.chapterIndex + 1}章，当前目录共${book.totalChapterNum}章（换源或目录变化），仍跳转？",
                JumpResolution.Located(row.chapterIndex, row.chapterPos),
            )
        }
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, row.chapterIndex)
        return if (chapter?.title == row.chapterName) {
            JumpResolution.Located(row.chapterIndex, row.chapterPos)
        } else {
            JumpResolution.NeedConfirm(
                "书签章节「${row.chapterName}」与当前目录不一致（换源或目录更新），仍按原位置跳转？",
                JumpResolution.Located(row.chapterIndex, row.chapterPos),
            )
        }
    }

    override suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean {
        val book = runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull() ?: return false
        val markings = appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter { !it.isPageBookmark() }
            .sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }))
        if (markings.isEmpty()) return false
        val markdown = buildString {
            appendLine("# 《${book.name}》笔记")
            appendLine()
            appendLine("- 作者：${book.author}")
            appendLine("- 导出时间：${io.legado.app.constant.AppConst.timeFormat.format(java.util.Date())}")
            var lastChapter = Int.MIN_VALUE
            for (marking in markings) {
                if (marking.chapterIndex != lastChapter) {
                    lastChapter = marking.chapterIndex
                    appendLine()
                    appendLine("## ${marking.chapterName}")
                    appendLine()
                }
                marking.bookText.lines().forEach { appendLine("> $it") }
                if (marking.content.isNotEmpty()) {
                    appendLine()
                    appendLine(marking.content)
                }
                appendLine()
            }
        }
        return try {
            appCtx.contentResolver.openOutputStream(Uri.parse(uri))?.use { output ->
                output.write(markdown.toByteArray())
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /** 当前会话书内按主键取行（markingId = time.toString()）。 */
    internal suspend fun findBookmarkRow(markingId: String): Bookmark? {
        val book: Book = ReadBook.book ?: return null
        val id = markingId.toLongOrNull() ?: return null
        return appDb.bookmarkDao.getByBook(book.name, book.author)
            .firstOrNull { it.time == id }
    }

    /** 当前会话书内按主键取行（目录页跳转解析按会话书限定）。 */
    private suspend fun sessionRow(bookmarkId: Long): Bookmark? {
        val book: Book = ReadBook.book ?: return null
        return appDb.bookmarkDao.getByBook(book.name, book.author)
            .firstOrNull { it.time == bookmarkId }
    }
}
