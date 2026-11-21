package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.utils.cnCompare
import kotlin.math.max

/**
 * 书架排序唯一实现（完整模式书架与纯净模式书架两渲染面共用，排序语义
 * 单源）：0/未知 = 最近阅读（durChapterTime 降序）、1 = 更新时间降序、
 * 2 = 书名（cnCompare 中文排序）、3 = 手动（order 升序）、4 = 综合
 * （max(更新, 阅读) 时间降序）。
 *
 * 排序档按分组持久化：调用方经 `AppConfig.getBookSortByGroupId(groupId)`
 * 解析后传入（组 bookSort < 0 跟随全局 bookshelfSort，否则组覆盖；系统
 * 筛选器与全部视图无组行，取全局默认）。
 */
internal fun <T> List<T>.sortedByBookshelfSort(
    sort: Int,
    latestChapterTime: (T) -> Long,
    durChapterTime: (T) -> Long,
    name: (T) -> String,
    order: (T) -> Int,
): List<T> = when (sort) {
    1 -> sortedByDescending(latestChapterTime)
    2 -> sortedWith { first, second -> name(first).cnCompare(name(second)) }
    3 -> sortedBy(order)
    4 -> sortedByDescending { max(latestChapterTime(it), durChapterTime(it)) }
    else -> sortedByDescending(durChapterTime)
}

/** [Book] 投影排序（BookshelfFragment2.sortBooks 与 E-Ink 书架引擎共用）。 */
@JvmName("sortedBookByBookshelfSort")
internal fun List<Book>.sortedByBookshelfSort(sort: Int): List<Book> =
    sortedByBookshelfSort(
        sort,
        latestChapterTime = { it.latestChapterTime },
        durChapterTime = { it.durChapterTime },
        name = { it.name },
        order = { it.order },
    )

/** [BookshelfBook] 投影排序（书架分组条目预览封面共用）。 */
internal fun List<BookshelfBook>.sortedByBookshelfSort(sort: Int): List<BookshelfBook> =
    sortedByBookshelfSort(
        sort,
        latestChapterTime = { it.latestChapterTime },
        durChapterTime = { it.durChapterTime },
        name = { it.name },
        order = { it.order },
    )
