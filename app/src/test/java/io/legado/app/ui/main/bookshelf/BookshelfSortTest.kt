package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架排序唯一实现契约（完整模式书架与纯净模式书架两渲染面共用）：
 * 0/未知 = 最近阅读降序、1 = 更新时间降序、2 = 书名、3 = 手动 order
 * 升序、4 = 综合 max(更新, 阅读) 降序。
 */
class BookshelfSortTest {

    /** 基构型：三本书的时间/序错位布置，各排序档区分度明确。 */
    private fun books() = listOf(
        Book(name = "甲", order = 3, durChapterTime = 100L, latestChapterTime = 900L),
        Book(name = "乙", order = 1, durChapterTime = 300L, latestChapterTime = 200L),
        Book(name = "丙", order = 2, durChapterTime = 200L, latestChapterTime = 500L),
    )

    private fun names(books: List<Book>) = books.map { it.name }

    @Test
    fun `默认与未知档按最近阅读降序`() {
        assertEquals(listOf("乙", "丙", "甲"), names(books().sortedByBookshelfSort(0)))
        assertEquals(listOf("乙", "丙", "甲"), names(books().sortedByBookshelfSort(99)))
    }

    @Test
    fun `更新时间档按最新章节时间降序`() {
        assertEquals(listOf("甲", "丙", "乙"), names(books().sortedByBookshelfSort(1)))
    }

    @Test
    fun `手动档按 order 升序`() {
        assertEquals(listOf("乙", "丙", "甲"), names(books().sortedByBookshelfSort(3)))
    }

    @Test
    fun `综合档按更新与阅读时间较大值降序`() {
        // max: 甲 900、乙 300、丙 500
        assertEquals(listOf("甲", "丙", "乙"), names(books().sortedByBookshelfSort(4)))
    }

    @Test
    fun `书名档为稳定排序不改变同键相对次序`() {
        val tied = listOf(
            Book(name = "同", order = 1, durChapterTime = 1L, latestChapterTime = 1L),
            Book(name = "同", order = 2, durChapterTime = 1L, latestChapterTime = 1L),
        )
        assertEquals(listOf(1, 2), tied.sortedByBookshelfSort(2).map { it.order })
    }

    @Test
    fun `空表与单表不重排`() {
        assertEquals(0, emptyList<Book>().sortedByBookshelfSort(1).size)
        assertEquals(listOf("甲"), names(books().take(1).sortedByBookshelfSort(1)))
    }
}
