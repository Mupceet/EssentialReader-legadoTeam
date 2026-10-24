package io.legado.app.eink.bridge

import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.contract.BookshelfCoverRefUiModel
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import splitties.init.appCtx

/**
 * 书架分组端口实现（契约 v2 双模式：无筛选=文件夹视图 / 精选筛选=平铺
 * 视图 + 分组/筛选器管理）。位掩码运算全部封闭在本层，不进契约。
 *
 * 本宿主差异（相对 md3 参照实现）：
 *  - 无设置网关/DataStore：选中筛选/范围经进程内状态流 + eink 自有
 *    prefs 键持久化（md3 宿主借宿主设置域的 einkShelfFilter/saveTabPosition
 *    键，本宿主完整模式 Tab 位与组 id 不同域，不共用）；
 *  - 无分组计数/未归组/单书掩码等专用 Flow 查询：计数、未归组判定与
 *    成员回显在 (组表流 × 全书流) 上内存计算（书量级可接受，Room 失效
 *    追踪复用两张基表）；
 *  - 无私密分组（宿主无 isPrivate 概念）：私密排除逻辑不适用；
 *  - 无 hideEmptyGroups 设置键：筛选器菜单空计数恒不展示（「无筛选」
 *    恒在首位，计数 = 全书架口径），文件夹视图空组条目保留显示；
 *  - 无书架排序键接入：组内/未归组书按 flowAll 自然序（durChapterTime
 *    desc）下发，不读宿主 bookshelfSort。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine {

    /** E-Ink 书架筛选/范围选中态的自有 prefs 键（eink_preferences 专属文件）。 */
    private const val KEY_SHELF_FILTER = "einkShelfFilter"
    private const val KEY_SHELF_GROUP = "einkShelfGroup"

    /**
     * 系统筛选器命名（id → 本地化资源；不读宿主库内自定义名，同 md3
     * 参照实现的取舍）。IdRoot(-100) 不是筛选器（根目录入口），排除。
     */
    private val SYSTEM_FILTER_NAMES: Map<Long, Int> = mapOf(
        BookGroup.IdAll to R.string.all,
        BookGroup.IdLocal to R.string.local,
        BookGroup.IdAudio to R.string.audio,
        BookGroup.IdNetNone to R.string.net_no_group,
        BookGroup.IdLocalNone to R.string.local_no_group,
        BookGroup.IdVideo to R.string.video,
        BookGroup.IdError to R.string.update_book_fail,
    )

    private fun filterNameOf(id: Long): String =
        SYSTEM_FILTER_NAMES[id]?.let { appCtx.getString(it) } ?: ""

    /** 组表全量流（含系统行）。 */
    private val groupsFlow: Flow<List<BookGroup>> = appDb.bookGroupDao.flowAll()

    /** 书架全量书流（内存计算的基表；notShelf 过滤在消费点做）。 */
    private val shelfBooksFlow: Flow<List<Book>> =
        appDb.bookDao.flowAll().map { books -> books.filterNot { it.isNotShelf } }

    /** 用户组位掩码（组 id 均为单 bit，求和即掩码；同 BookDao SQL 口径）。 */
    private fun userGroupMask(groups: List<BookGroup>): Long =
        groups.filter { it.groupId > 0 }.sumOf { it.groupId }

    /** 系统筛选器行（按 order）。 */
    private fun systemFilterRows(groups: List<BookGroup>): List<BookGroup> =
        groups.filter { it.groupId < 0 && it.groupId != BookGroup.IdRoot }.sortedBy { it.order }

    /** 书是否符合系统筛选器（语义对齐 BookDao.flowByGroup 的 SQL 分支）。 */
    private fun Book.matchesFilter(filterId: Long, groupMask: Long): Boolean = when (filterId) {
        BookGroup.IdAll -> true
        BookGroup.IdLocal -> type and BookType.local > 0
        BookGroup.IdAudio -> type and BookType.audio > 0
        BookGroup.IdVideo -> type and BookType.video > 0
        BookGroup.IdError -> type and BookType.updateError > 0
        BookGroup.IdNetNone ->
            type and BookType.audio == 0 &&
                type and BookType.local == 0 &&
                type and BookType.video == 0 &&
                group and groupMask == 0L
        BookGroup.IdLocalNone ->
            type and BookType.local > 0 && group and groupMask == 0L
        else -> false
    }

    // —— 筛选 ——

    private val selectedFilterState = MutableStateFlow(
        einkPrefLong(KEY_SHELF_FILTER, BookshelfGroupIds.NONE)
    )

    override fun observeFilters(): Flow<List<BookshelfGroupUiModel>> =
        combine(groupsFlow, shelfBooksFlow) { groups, books ->
            val mask = userGroupMask(groups)
            val counts = systemFilterRows(groups).associate { row ->
                row.groupId to books.count { it.matchesFilter(row.groupId, mask) }
            }
            buildFilterUiModels(
                noneCount = counts[BookGroup.IdAll] ?: 0,
                counts = counts,
                noneName = appCtx.getString(R.string.eink_shelf_filter_none),
                nameOf = ::filterNameOf,
            )
        }.distinctUntilChanged()

    override fun observeManageableFilters(): Flow<List<BookshelfGroupUiModel>> =
        combine(groupsFlow, shelfBooksFlow) { groups, books ->
            val mask = userGroupMask(groups)
            systemFilterRows(groups).map { row ->
                BookshelfGroupUiModel(
                    groupId = row.groupId,
                    name = filterNameOf(row.groupId),
                    bookCount = books.count { it.matchesFilter(row.groupId, mask) },
                    shown = row.show,
                )
            }
        }.distinctUntilChanged()

    override val selectedFilter: Flow<Long> =
        selectedFilterState.map { raw ->
            // 读路径收敛：NONE 与系统筛选器 id 直通，脏值回落 NONE
            if (raw == BookshelfGroupIds.NONE || raw in SYSTEM_FILTER_NAMES) {
                raw
            } else {
                BookshelfGroupIds.NONE
            }
        }.distinctUntilChanged()

    override suspend fun setSelectedFilter(filterId: Long) {
        require(
            filterId == BookshelfGroupIds.NONE || filterId in SYSTEM_FILTER_NAMES
        ) { "非系统筛选器: $filterId" }
        selectedFilterState.value = filterId
        einkPrefPutLong(KEY_SHELF_FILTER, filterId)
    }

    // —— 范围 ——

    private val selectedGroupState = MutableStateFlow(
        einkPrefLong(KEY_SHELF_GROUP, BookshelfGroupIds.ALL)
    )

    override val selectedGroup: Flow<Long> =
        selectedGroupState.map { raw ->
            if (raw == BookshelfGroupIds.ALL || raw > 0) raw else BookshelfGroupIds.ALL
        }.distinctUntilChanged()

    override suspend fun setSelectedGroup(groupId: Long) {
        require(groupId == BookshelfGroupIds.ALL || groupId > 0) {
            "范围仅接受全部或用户组: $groupId"
        }
        selectedGroupState.value = groupId
        einkPrefPutLong(KEY_SHELF_GROUP, groupId)
    }

    override fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>> =
        combine(groupsFlow, shelfBooksFlow) { groups, books ->
            groups.filter { it.groupId > 0 }.sortedBy { it.order }.map { group ->
                BookshelfGroupUiModel(
                    groupId = group.groupId,
                    name = group.groupName,
                    bookCount = books.count { it.group and group.groupId > 0 },
                    shown = group.show,
                )
            }
        }.distinctUntilChanged()

    // —— 无筛选（文件夹）视图 ——

    override fun observeShelfWithGroups(): Flow<List<BookshelfShelfEntryUiModel>> =
        combine(groupsFlow, shelfBooksFlow) { groups, books ->
            val visibleGroups = groups
                .filter { it.groupId > 0 && it.show }
                .sortedBy { it.order }
            val visibleMask = visibleGroups.sumOf { it.groupId }
            // 未归组书 = 不被任何可见组收纳（隐藏组的书平铺回顶层）；
            // flowAll 自然序（durChapterTime desc）
            val ungrouped = books.filter { it.group and visibleMask == 0L }
            buildShelfWithGroupsEntries(visibleGroups, books, ungrouped)
        }.distinctUntilChanged()

    // —— 筛选（平铺）视图 ——

    override fun observeFilteredShelf(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>> {
        require(filterId != BookshelfGroupIds.NONE) { "无筛选态走文件夹视图: observeShelfWithGroups" }
        return appDb.bookDao.flowByGroup(filterId)
            .map { books ->
                books.map { BookshelfShelfEntryUiModel.Book(it.toBookshelfItemUiModel()) }
            }
    }

    // —— 组视图 ——

    override fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>> =
        appDb.bookDao.flowByGroup(groupId)
            .map { books -> books.map { it.toBookshelfItemUiModel() } }

    // —— 管理（用户组与系统筛选器的排序/显隐一次性整表提交） ——

    override suspend fun createGroup(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = null)
        val newId = appDb.bookGroupDao.getUnusedId()
        check(newId != 0L) { "分组数量已达上限" }
        appDb.bookGroupDao.insert(
            BookGroup(groupId = newId, groupName = trimmed)
        )
        return newId
    }

    override suspend fun renameGroup(groupId: Long, name: String) {
        require(groupId > 0) { "系统筛选器不支持重命名" }
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = groupId)
        val current = appDb.bookGroupDao.getByID(groupId) ?: error("分组不存在: $groupId")
        current.groupName = trimmed
        appDb.bookGroupDao.update(current)
    }

    override suspend fun applyGroupAdjustments(orderedGroupIds: List<Long>, shownGroupIds: Set<Long>) {
        val groups = appDb.bookGroupDao.flowAll().first()
        require(
            validateClassSubmission(groups, orderedGroupIds) { it.groupId > 0 }
        ) { "分组列表已变化，请重试" }
        require(shownGroupIds.all { it in orderedGroupIds }) { "显隐集含未知分组" }
        appDb.bookGroupDao.update(
            *weaveClassAdjustmentUpdate(groups, orderedGroupIds, shownGroupIds) { it.groupId > 0 }
                .toTypedArray()
        )
    }

    override suspend fun applyFilterAdjustments(orderedFilterIds: List<Long>, shownFilterIds: Set<Long>) {
        val groups = appDb.bookGroupDao.flowAll().first()
        val isFilter = { g: BookGroup -> g.groupId < 0 && g.groupId != BookGroup.IdRoot }
        require(validateClassSubmission(groups, orderedFilterIds, isFilter)) {
            "筛选器列表已变化，请重试"
        }
        require(shownFilterIds.all { it in orderedFilterIds }) { "显隐集含未知筛选器" }
        appDb.bookGroupDao.update(
            *weaveClassAdjustmentUpdate(groups, orderedFilterIds, shownFilterIds, isFilter)
                .toTypedArray()
        )
    }

    override suspend fun dissolveGroup(groupId: Long) {
        require(groupId > 0) { "系统筛选器不支持解散" }
        val group = appDb.bookGroupDao.getByID(groupId) ?: error("分组不存在: $groupId")
        // 书籍保留仅清位（对齐宿主完整模式解散分组语义）
        val memberBooks = appDb.bookDao.getBooksByGroup(groupId)
        memberBooks.forEach { book ->
            book.group = book.group and groupId.inv()
        }
        if (memberBooks.isNotEmpty()) appDb.bookDao.update(*memberBooks.toTypedArray())
        appDb.bookGroupDao.delete(group)
    }

    override suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean) {
        require(groupId > 0) { "系统筛选器不支持归书" }
        val book = appDb.bookDao.getBook(bookUrl) ?: error("书籍不存在: $bookUrl")
        book.group = if (member) {
            book.group or groupId
        } else {
            book.group and groupId.inv()
        }
        appDb.bookDao.update(book)
    }

    override fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>> =
        combine(
            appDb.bookDao.flowAll().map { books ->
                books.firstOrNull { it.bookUrl == bookUrl }?.group ?: 0L
            }.distinctUntilChanged(),
            groupsFlow,
        ) { mask, groups ->
            groups.filter { it.groupId > 0 && mask and it.groupId > 0 }
                .map { it.groupId }
                .toSet()
        }.distinctUntilChanged()

    // —— 私有装配 ——

    private suspend fun requireUserGroupNameFree(name: String, excludeGroupId: Long?) {
        val dup = appDb.bookGroupDao.getByName(name)
        require(dup == null || dup.groupId == excludeGroupId) { "已有同名分组: $name" }
    }

    /** 文件夹视图混合条目装配：可见组条目置顶 + 未归组书跟随。 */
    private fun buildShelfWithGroupsEntries(
        visibleGroups: List<BookGroup>,
        allBooks: List<Book>,
        ungroupedBooks: List<Book>,
    ): List<BookshelfShelfEntryUiModel> {
        val entries = visibleGroups.map { group ->
            val members = allBooks.filter { it.group and group.groupId > 0 }
            BookshelfShelfEntryUiModel.Group(
                groupId = group.groupId,
                name = group.groupName,
                bookCount = members.size,
                covers = members
                    .sortedByDescending { it.durChapterTime }
                    .take(GROUP_COVER_COUNT)
                    .map { book ->
                        BookshelfCoverRefUiModel(
                            url = book.getDisplayCover(),
                            name = book.name,
                            author = book.author,
                            origin = book.origin,
                        )
                    },
            )
        }
        return entries + ungroupedBooks.map { BookshelfShelfEntryUiModel.Book(it.toBookshelfItemUiModel()) }
    }

    /** 拼贴封面数量（模块文件夹条目象限上限）。 */
    private const val GROUP_COVER_COUNT = 7
}

/**
 * 筛选器菜单装配（「无筛选」恒在首位、计数 = 全书架口径；本宿主无
 * hideEmptyGroups 设置键，空计数系统筛选器恒不展示）。
 */
internal fun buildFilterUiModels(
    noneCount: Int,
    counts: Map<Long, Int>,
    noneName: String,
    nameOf: (Long) -> String,
): List<BookshelfGroupUiModel> = buildList {
    add(
        BookshelfGroupUiModel(
            groupId = BookshelfGroupIds.NONE,
            name = noneName,
            bookCount = noneCount,
        )
    )
    counts.forEach { (filterId, count) ->
        if (filterId == BookGroup.IdAll) return@forEach
        if (count == 0) return@forEach
        add(
            BookshelfGroupUiModel(
                groupId = filterId,
                name = nameOf(filterId),
                bookCount = count,
            )
        )
    }
}

/** 提交集合与当前类别集合一致（同元素集；size 隐含）才允许落库。 */
internal fun validateClassSubmission(
    allGroups: List<BookGroup>,
    orderedIds: List<Long>,
    isTarget: (BookGroup) -> Boolean,
): Boolean {
    val current = allGroups.filter(isTarget).map { it.groupId }
    return orderedIds.size == current.size && orderedIds.toSet() == current.toSet()
}

/**
 * 织入重排 + 显隐落位（用户组/系统筛选器两用）：非目标行原位原序不动，
 * 目标行按提交序依次占据目标槽位、show 取显隐集；全表 order = index
 *（与完整模式分组管理拖拽落序同构）。
 */
internal fun weaveClassAdjustmentUpdate(
    allGroups: List<BookGroup>,
    orderedIds: List<Long>,
    shownIds: Set<Long>,
    isTarget: (BookGroup) -> Boolean,
): List<BookGroup> {
    val byId = allGroups.associateBy { it.groupId }
    val queue = ArrayDeque(orderedIds)
    return allGroups.map { group ->
        if (isTarget(group)) byId.getValue(queue.removeFirst()) else group
    }.mapIndexed { index, group ->
        val shown = if (isTarget(group)) group.groupId in shownIds else group.show
        group.copy(order = index, show = shown)
    }
}
