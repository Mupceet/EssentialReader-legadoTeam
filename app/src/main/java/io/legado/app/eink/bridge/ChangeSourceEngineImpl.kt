package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ChangeSourceBookUiModel
import io.legado.app.eink.contract.ChangeSourceEngine
import io.legado.app.eink.contract.ChangeSourceResultUiModel
import io.legado.app.eink.contract.SearchResultHandle
import io.legado.app.eink.contract.SourceHandle
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 书源句柄（包装 BookSource）。 */
internal class SourceHandleImpl(val source: BookSource) : SourceHandle {
    override val url: String get() = source.bookSourceUrl
}

/** 搜索结果句柄（包装 SearchBook）。 */
internal class SearchResultHandleImpl(val searchBook: SearchBook) : SearchResultHandle

/**
 * 换源端口实现。
 *
 * 本上游差异：searchBookAwait filter 为三参 (name, author, kind)（kind
 * 不参与匹配，占位忽略）；migrateTo 为两参（无 replaceEnableDefault/
 * chineseConverterType 形参，走宿主默认值）；getChapterListAwait 返回
 * Result；历史换源记录经 searchBookDao.changeSourceByGroup 读写。
 *
 * 缓存维护（对齐宿主 ChangeBookSourceViewModel 语义，非只读消费）：
 *  - 读：cachedSourceBooks = 宿主换源 initData 同一条查询；
 *  - 写：searchSourceBook 逐源落库（REPLACE）= 宿主 searchSuccess 的
 *    边搜边存，与完整模式换源/搜索页共享同一份记录；
 *  - 重建：新搜索会话（enabledSources 标记，模块每会话起始调用一次）
 *    先删该书当前展示口径的旧记录 = 宿主 startSearch 的先删后搜。
 */
internal object ChangeSourceEngineImpl : ChangeSourceEngine {

    private val _bookChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val bookChanged: SharedFlow<String> = _bookChanged.asSharedFlow()

    override suspend fun currentReadingBook(
        bookUrl: String,
    ): Pair<BookHandle, ChangeSourceBookUiModel>? {
        val book = ReadBook.book?.takeIf { it.bookUrl == bookUrl }
            ?: appDb.bookDao.getBook(bookUrl)
            ?: return null
        return BookHandleImpl(book) to ChangeSourceBookUiModel(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            origin = book.origin,
            // 常驻首项「当前源」行展示：书源显示名快照与已缓存最新章节
            //（originName 留空会使该行回落显示 origin URL）
            originName = book.originName,
            latestChapterTitle = book.latestChapterTitle,
        )
    }

    override fun enabledSources(): List<SourceHandle> =
        appDb.bookSourceDao.allEnabledPart
            .mapNotNull { it.getBookSource() }
            .filter { !it.bookSourceUrl.isBlank() }
            .map { SourceHandleImpl(it) }
            .also {
                // 模块换源 VM 于每次搜索会话起始调用本方法（随后才并发逐源
                // searchSourceBook）——标记下一次 searchSourceBook 为新会话
                // 首源，承接宿主 startSearch 的「先删当前展示记录」步骤
                pendingSessionReset.set(true)
            }

    /**
     * 新搜索会话的重建步骤（对齐宿主 ChangeBookSourceViewModel.startSearch）：
     * 删掉该书当前展示口径的历史搜索记录——与 [cachedSourceBooks] 同一条
     * changeSourceByGroup 查询（宿主删的就是它的展示列表），随后逐源
     * 落库的新记录自然重建。会话标记经 [sessionStartMutex] 原子消费：
     * 删除完成后才放行并发搜索，保证所有落库都晚于删除。
     */
    private val sessionStartMutex = Mutex()
    private val pendingSessionReset = AtomicBoolean(false)

    private suspend fun beginSessionIfNeeded(name: String, author: String, checkAuthor: Boolean) {
        sessionStartMutex.withLock {
            if (pendingSessionReset.getAndSet(false)) {
                val matchAuthor =
                    if (checkAuthor) author.replace(AppPattern.authorRegex, "") else ""
                val stale = appDb.searchBookDao.changeSourceByGroup(name, matchAuthor, "")
                if (stale.isNotEmpty()) {
                    appDb.searchBookDao.delete(*stale.toTypedArray())
                }
            }
        }
    }

    /**
     * 该书名的历史换源搜索记录（宿主换源 initData 同语义）：只含当前
     * 启用书源（DAO 内联过滤）；不校验作者时作者不参与匹配。
     */
    override suspend fun cachedSourceBooks(
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel> {
        val matchAuthor = if (checkAuthor) author.replace(AppPattern.authorRegex, "") else ""
        return appDb.searchBookDao.changeSourceByGroup(name, matchAuthor, "")
            .map { searchBook ->
                ChangeSourceResultUiModel(
                    handle = SearchResultHandleImpl(searchBook),
                    bookUrl = searchBook.bookUrl,
                    name = searchBook.name,
                    author = searchBook.author,
                    origin = searchBook.origin,
                    originName = searchBook.originName,
                    latestChapter = searchBook.latestChapterTitle,
                    deduplicationKey = searchBook.primaryStr(),
                )
            }
    }

    override suspend fun searchSourceBook(
        source: SourceHandle,
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel> {
        beginSessionIfNeeded(name, author, checkAuthor)
        val bookSource = (source as SourceHandleImpl).source
        val strippedAuthor = author.replace(AppPattern.authorRegex, "")
        val searchBooks = WebBook.searchBookAwait(
            bookSource,
            name,
            filter = { fName, fAuthor, _ ->
                fName == name && (!checkAuthor || fAuthor.contains(strippedAuthor))
            }
        ).onEach { searchBook ->
            searchBook.releaseHtmlData()
        }
        // 逐源结果落库（REPLACE）——对齐宿主 searchSuccess 的边搜边存，
        // 作为下次进入换源页的历史缓存，与完整模式换源/搜索页共享同一份
        if (searchBooks.isNotEmpty()) {
            appDb.searchBookDao.insert(*searchBooks.toTypedArray())
        }
        return searchBooks.map { searchBook ->
            ChangeSourceResultUiModel(
                handle = SearchResultHandleImpl(searchBook),
                bookUrl = searchBook.bookUrl,
                name = searchBook.name,
                author = searchBook.author,
                origin = searchBook.origin,
                originName = searchBook.originName,
                latestChapter = searchBook.latestChapterTitle,
                deduplicationKey = searchBook.primaryStr(),
            )
        }
    }

    override suspend fun changeBookSource(
        bookHandle: BookHandle,
        result: ChangeSourceResultUiModel,
    ): Result<BookHandle> {
        val oldBook = (bookHandle as BookHandleImpl).book
        val searchBook = (result.handle as SearchResultHandleImpl).searchBook
        return try {
            val source = appDb.bookSourceDao.getBookSource(searchBook.origin)
                ?: throw IllegalStateException("书源不存在")
            val newBook = searchBook.toBook()
            if (newBook.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, newBook)
            }
            val toc = WebBook.getChapterListAwait(source, newBook).getOrThrow()

            oldBook.migrateTo(newBook, toc)
            newBook.removeType(BookType.updateError)
            oldBook.delete()
            appDb.bookDao.insert(newBook)
            appDb.bookChapterDao.insert(*toc.toTypedArray())

            // 重载引擎会话；阅读页返回时会采用引擎当前书籍
            ReadBook.resetData(newBook)
            ReadBook.loadContent(resetPageOffset = true)
            // 通知栈下方的详情等界面按新 bookUrl 跟随刷新
            _bookChanged.tryEmit(newBook.bookUrl)
            Result.success(BookHandleImpl(newBook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("换源失败\n${e.localizedMessage}", e)
            Result.failure(e)
        }
    }
}
