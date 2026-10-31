package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import io.legado.app.data.appDb
import io.legado.app.eink.contract.ReaderChunkPaintOverride
import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.contract.ReaderUnderlineGeometry
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.isForBook
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import kotlin.math.roundToInt

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [ReaderPageSnapshot]：上游排版结构的字段
 * 漂移由本映射器消化，模块画布只有一份绘制实现。API35+ 的逐字字距半格
 * 补偿在映射期算进 x 坐标，模块不再感知。坐标口径：引擎视口 = 内容区
 * （系统栏避让与页眉带在视口外扣除，见 ReaderEngineImpl.updateViewSize），
 * 列坐标为内容区坐标，映射期统一补「避让 + 页眉带」偏移，快照按模块
 * 全屏画布的窗口坐标发射。
 *
 * 画笔规格只拷贝测量耦合参数（字号/字距/字体）——快照列坐标是引擎按
 * 这些参数测量的，模块必须按同值绘制才不错位；阴影等纯视觉效果不跨桥
 * （E-Ink 阅读不渲染，既定产品取舍），斜体例外：高亮规则的合成斜体
 * （skew，不改步进宽、无对齐风险）经逐段覆盖跨桥。本宿主 minSdk 21：
 * 可变字重 getter 为 API 26，映射期按 SDK 门控（21~25 无可变字重排版，
 * 恒 null 安全）。
 *
 * 高亮规则渲染（完整模式 ContentTextView.upHighlight 的 E-Ink 等价物）：
 *  - 规则命中样式经 [HighlightMatcher.resolve] 解析到列（完整模式同款
 *    纯区间数学，不写回引擎列状态）；
 *  - 颜色以**灰度**跨桥（BT.709 亮度换算、保留 alpha，见 [toEInkGrayArgb]）
 *    ——墨水屏无彩色，灰度是规则彩色的等价表达，模块按值绘制；
 *  - 规则的字体覆盖（字号差/自定义字体/字色/斜体/加粗）折为逐段画笔覆盖
 *    [ReaderChunkPaintOverride]——引擎按覆盖参数测宽（改度量规则经
 *    [ReadBook.upHighlightSpacing] 二次重排保证），模块按同值绘制才
 *    不会画不满/溢出测宽槽；
 *  - 暂不支持项（无契约等价物，明示降级）：规则自带字距
 *    （重排已把位置烘进列坐标，段内多字词在 API35+ 有半格漂移）、
 *    着重号/边框/阴影（纯视觉）、完整模式手动高亮 BookHighlight
 *    （E-Ink 划线走 bookmark 表另一链路）。
 *
 * 在引擎回调线程调用（onContentUpdated 内），产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口：规格取自引擎当前共享画笔，补偿按真实 SDK 版本。 */
    fun map(page: TextPage): ReaderPageSnapshot {
        // 书签行一次查询双消费：页角标（位置书签）+ 划线装饰锚点（文字书签）
        val rows = currentBookmarkRows()
        return mapWithSpecs(
            page = page,
            titleSpec = ChapterProvider.titlePaint.copyPaintSpec(),
            contentSpec = ChapterProvider.contentPaint.copyPaintSpec(),
            sdkInt = Build.VERSION.SDK_INT,
            imageLoader = ::defaultImageLoader,
            // 窗口坐标换算：引擎视口 = 内容区（避让边距 + 页眉带在视口外
            // 扣除，见 ReaderEngineImpl.updateViewSize），列坐标是内容区
            // 坐标，补上偏移后与模块全屏画布的窗口坐标约定对齐
            offsetX = ReaderEngineImpl.contentPadding.left.toFloat(),
            offsetY = ReaderEngineImpl.contentPadding.top +
                ReaderEngineImpl.headerDecorationExtentPx,
            bookmarkBadge = MarksEngineImpl.supportsBookmarks &&
                rows.any { row ->
                    row.bookText.isEmpty() &&
                        row.chapterIndex == page.chapterIndex &&
                        page.containPos(row.chapterPos)
                },
            markings = rows.asSequence()
                .filter { it.bookText.isNotEmpty() && it.chapterIndex == page.chapterIndex }
                .map { row ->
                    ReaderMarkingAnchor(
                        start = row.chapterPos,
                        text = row.bookText,
                        underlineMode = if (row.content.isEmpty()) 1 else 2,
                        markingId = row.time.toString(),
                    )
                }
                .toList(),
            ruleStyles = resolveRuleStyles(page),
        )
    }

    /**
     * 当前会话书的全部书签行（主线程同步单行索引查询，宿主 lastReadBookUrl
     * 同款口径；映射在排版回调线程调用，产物不可变跨线程安全）。
     */
    private fun currentBookmarkRows(): List<io.legado.app.data.entities.Bookmark> {
        val book = ReadBook.book ?: return emptyList()
        return runCatching {
            appDb.bookmarkDao.getByBook(book.name, book.author)
        }.getOrDefault(emptyList())
    }

    /**
     * 高亮规则管线驱动（E-Ink 对完整模式 ContentTextView.upHighlight 的
     * 章级职责）：规则匹配由 ReadBook.observeHighlightRuleLayout 在排版
     * 完成时自行驱动（引擎无关），这里只负责改度量规则（字号/字距覆盖、
     * 水平留白、胶囊底）的**二次重排**触发——[ReadBook.upHighlightSpacing]
     * 重排完成后经 upContent 回调重映，列槽位即按覆盖画笔测量的宽度。
     * 由引擎回调适配器在每次当前章内容更新时调用（不在 map 内做：翻页
     * 也走映射，重排触发放内容更新粒度即可，与完整模式 setContent 同频）。
     */
    fun driveHighlightRules() {
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter ?: return
        if (chapter.isTransient || !chapter.isForBook(book) || !chapter.isCompleted) return
        val matches = ReadBook.ruleMatchesOfChapter(chapter)
        if (matches.isEmpty()) return
        ReadBook.upHighlightSpacing(chapter, matches.toRuleRanges())
    }

    /**
     * 页级规则样式解析（完整模式 upHighlight 的页分支同构）：章内区间 →
     * 行/列样式矩阵，**不写回引擎列状态**（映射期消费即弃，避免桥层
     * 污染共享排版产物的可变状态）。返回 null = 本轮不携带规则样式：
     *  - 无规则命中 / 匹配在途（匹配完成回调重映）；
     *  - 改度量规则的重排在途或尚未烘进布局（重排完成回调重映——带样式
     *    映射会画不进/溢出基线测量的列槽）。
     */
    private fun resolveRuleStyles(page: TextPage): List<List<HighlightStyle?>>? {
        val book = ReadBook.book ?: return null
        val chapter = page.getTextChapter()
        if (page.isMsgPage || chapter.isTransient || !chapter.isForBook(book)) return null
        val pageBase = chapter.getReadLength(page.index)
        // 页覆盖区间（与完整模式同款：行字符 + 段落分隔符累计）
        val pageEnd = pageBase + page.lines.sumOf { it.charSize + if (it.isParagraphEnd) 1 else 0 }
        val matches = ReadBook.ruleMatchesOfChapter(chapter)
        if (matches.isEmpty()) return null
        val ranges = matches
            .filter { it.start < pageEnd && it.end > pageBase }
            .map { HighlightMatcher.Range(it.start, it.end, it.style, it.applyToTitle, it.applyToBody) }
        if (ranges.isEmpty()) return null
        // 重排状态守卫（完整模式 upHighlight 的 continue 分支）：改度量规则
        // 需要 spacing 烘进布局后才可带样式映射
        val needsSpacing = ranges.any {
            it.style.changesTextMetrics || it.style.resolvedHorizontalPadding != null ||
                (it.style.fill != 0 && it.style.resolvedFillShape == HighlightStyle.FillShape.PILL)
        }
        if (needsSpacing &&
            (chapter.highlightSpacingJob?.isActive == true || chapter.highlightSpacing.isEmpty)
        ) {
            return null
        }
        val lineSpecs = page.lines.map { line ->
            HighlightMatcher.LineSpec(
                charSize = line.charSize,
                columnCharLengths = line.columns.map { it.positionLength },
                isParagraphEnd = line.isParagraphEnd,
                isTitle = line.isTitle,
                paragraphIndentColumns = line.columns.map {
                    (it as? TextBaseColumn)?.isParagraphIndent == true
                },
            )
        }
        return HighlightMatcher.resolve(pageBase, lineSpecs, ranges)
    }

    /**
     * 锚点校验：划线的本质是「存储位置处的内容与存储原文吻合」——按页内
     * 行段（章内位置 → 行文本）逐字符核对锚点区间，页覆盖范围之外（跨页
     * 标记的本页外部分）放行，段间隙按段落分隔符 '\n' 核对。
     *
     * 作用：排除非划线锚点的行——完整模式快速书签的页摘录锚定在页中
     * 阅读位置（durChapterPos），与摘录起点不符，校验不过即不渲染（否则
     * 整页摘录会画成整页下划线）；同理正文变更后的陈旧标记不误划。完整
     * 模式选中书签的拼接若与渲染行字符有出入，校验保守拒绝——不产生
     * 错位下划线（列表/跳转不受影响）。
     */
    private fun validatedMarkings(
        markings: List<ReaderMarkingAnchor>,
        segmentStarts: List<Int>,
        segmentTexts: List<String>,
    ): List<ReaderMarkingAnchor> {
        if (markings.isEmpty() || segmentStarts.isEmpty()) return emptyList()
        val coverageStart = segmentStarts.first()
        val coverageEnd = segmentStarts.last() + segmentTexts.last().length
        return markings.filter { anchor ->
            if (anchor.text.isEmpty()) return@filter false
            var segmentIndex = 0
            var ok = true
            for ((offset, expected) in anchor.text.withIndex()) {
                val position = anchor.start + offset
                // 页覆盖范围之外（跨页标记的本页外部分）：无凭据即不判否
                if (position < coverageStart || position >= coverageEnd) continue
                while (
                    segmentIndex < segmentStarts.size - 1 &&
                    segmentStarts[segmentIndex + 1] <= position
                ) {
                    segmentIndex++
                }
                val indexInSegment = position - segmentStarts[segmentIndex]
                val actual = segmentTexts[segmentIndex].getOrNull(indexInSegment) ?: '\n'
                if (actual != expected) {
                    ok = false
                    break
                }
            }
            ok
        }
    }

    /** 页内标记锚点（映射期输入）：start 为章内位置，text 为划线原文。 */
    internal data class ReaderMarkingAnchor(
        val start: Int,
        val text: String,
        val underlineMode: Int,
        val markingId: String,
    )

    /** 行组装中间量（循环收集，锚点校验后统一产装饰）。 */
    private class PendingLine(
        val baseY: Float,
        val isTitle: Boolean,
        val chunks: List<String>,
        val x: FloatArray,
        val chapterPositions: IntArray,
        val top: Float,
        val bottom: Float,
        val startPos: Int,
        val textLen: Int,
        // 与 chunks 平行的规则装饰签名（高亮带 / 线形两路，null = 该段无）
        val bandSignatures: List<RuleRunSignature?>,
        val lineSignatures: List<RuleRunSignature?>,
        // 与 chunks 平行的逐段画笔覆盖（null = 沿用行规格；页内按签名共享实例）
        val chunkOverrides: List<ReaderChunkPaintOverride?>,
    )

    /**
     * 纯函数核心（单测直接喂规格与 SDK 版本）。
     *
     * [offsetX]/[offsetY]：引擎内容区坐标 → 模块窗口坐标的偏移（系统栏
     * 避让 + 页眉带，生产入口按 [ReaderEngineImpl.contentPadding] 与
     * headerDecorationExtentPx 计算；默认 0 = 内容区坐标直出，既有单测
     * 锚定基准）。
     */
    internal fun mapWithSpecs(
        page: TextPage,
        titleSpec: ReaderPaintSpec,
        contentSpec: ReaderPaintSpec,
        sdkInt: Int,
        imageLoader: (String) -> (Int, Int) -> Bitmap?,
        bookmarkBadge: Boolean = false,
        markings: List<ReaderMarkingAnchor> = emptyList(),
        /** 页级规则样式（行 × 列矩阵，与 page.lines/line.columns 平行；null = 无）。 */
        ruleStyles: List<List<HighlightStyle?>>? = null,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): ReaderPageSnapshot {
        val images = ArrayList<ReaderImageSlot>()
        // 行段（章内位置 → 行文本）与待组装行：循环后锚点校验再产装饰
        val segmentStarts = ArrayList<Int>()
        val segmentTexts = ArrayList<String>()
        val pendingLines = ArrayList<PendingLine>()
        // 覆盖签名 → 共享实例（页内等值段复用同一覆盖对象，模块画笔缓存按值命中）
        val overrideInterned = HashMap<ChunkOverrideKey, ReaderChunkPaintOverride>()
        for ((lineIndex, line) in page.lines.withIndex()) {
            val spec = if (line.isTitle) titleSpec else contentSpec
            // API 35+ drawText 会将 letterSpacing 应用在两侧，引擎画布同样补偿半格
            val halfSpacing =
                if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
            val chunks = ArrayList<String>()
            val xs = ArrayList<Float>()
            // 完整模式下划线 y = 行基线 + distance；模块 y = 行盒下沿 + offset，
            // 换算需基线到行盒下沿的落差（= 引擎字体度量 descent）
            val baselineDropPx = line.lineBottom - line.lineBase
            val bandSignatures = ArrayList<RuleRunSignature?>()
            val lineSignatures = ArrayList<RuleRunSignature?>()
            val chunkOverrideCandidates = ArrayList<ReaderChunkPaintOverride?>()
            // 各文本段首字符的章内位置：行 chapterPosition 起步，按行内元素
            // 字符占位累计（文本段按段长；图片/评论等替换字符各占 1 位）
            val positions = ArrayList<Int>()
            var position = line.chapterPosition
            for ((columnIndex, column) in line.columns.withIndex()) {
                when (column) {
                    is TextColumn -> {
                        chunks.add(column.charData)
                        xs.add(column.start + halfSpacing + offsetX)
                        positions.add(position)
                        position += column.charData.length
                        val ruleStyle = ruleStyles?.getOrNull(lineIndex)?.getOrNull(columnIndex)
                        bandSignatures.add(ruleStyle?.bandRunSignature())
                        lineSignatures.add(
                            ruleStyle?.lineRunSignature(dpPx = { it.dpToPx() }, baselineDropPx)
                        )
                        chunkOverrideCandidates.add(
                            ruleStyle.chunkOverrideOrNull(spec, overrideInterned)
                        )
                    }

                    is ImageColumn -> {
                        images.add(
                            ReaderImageSlot(
                                x0 = column.start + offsetX,
                                x1 = column.end + offsetX,
                                lineTop = line.lineTop + offsetY,
                                lineBottom = line.lineBottom + offsetY,
                                lineHeight = line.height,
                                fullLine = line.isImage,
                                loader = imageLoader(column.src),
                                source = column.src,
                                // 图片选项的 click 脚本原样透传（段评气泡等
                                // 交互元数据，点按经 dispatchImageAction 分派）
                                action = column.click,
                            )
                        )
                        position += 1
                    }

                    else -> {
                        // 评论列/HTML 列等：E-Ink 不渲染（同引擎画布 else 分支），
                        // 字符占位照计（评论列为单字符替换）
                        position += 1
                    }
                }
            }
            if (chunks.isNotEmpty()) {
                segmentStarts.add(line.chapterPosition)
                segmentTexts.add(chunks.joinToString(""))
                pendingLines.add(
                    PendingLine(
                        baseY = line.lineBase + offsetY,
                        isTitle = line.isTitle,
                        chunks = chunks,
                        x = xs.toFloatArray(),
                        chapterPositions = positions.toIntArray(),
                        top = line.lineTop + offsetY,
                        bottom = line.lineBottom + offsetY,
                        startPos = line.chapterPosition,
                        textLen = chunks.sumOf { it.length },
                        bandSignatures = bandSignatures,
                        lineSignatures = lineSignatures,
                        chunkOverrides = chunkOverrideCandidates,
                    )
                )
            }
        }
        // 锚点校验后的行装饰：区间 = 锚点区间与行文本的交（跨长按原文
        // 长度近似；含图片/替换字符的行两套索引有少量漂移，钳制后最多
        // 划短不划错行），实线 = 划线 / 虚线 = 想法
        val validated = validatedMarkings(markings, segmentStarts, segmentTexts)
        val lines = pendingLines.map { pending ->
            // 书签 run 在前：模块点按命中取首个覆盖 run，可操作的用户标记
            //（划线/想法）优先于纯视觉的规则装饰
            val bookmarkRuns = if (validated.isEmpty()) {
                emptyList()
            } else {
                validated.mapNotNull { anchor ->
                    val start = (anchor.start - pending.startPos).coerceAtLeast(0)
                    val end = (anchor.start + anchor.text.length - pending.startPos)
                        .coerceAtMost(pending.textLen)
                    if (end > start) {
                        ReaderDecorationRun(
                            start = start,
                            end = end,
                            underlineMode = anchor.underlineMode,
                            highlight = false,
                            markingId = anchor.markingId,
                        )
                    } else {
                        null
                    }
                }
            }
            ReaderPageLine(
                baseY = pending.baseY,
                isTitle = pending.isTitle,
                chunks = pending.chunks,
                x = pending.x,
                chapterPositions = pending.chapterPositions,
                top = pending.top,
                bottom = pending.bottom,
                decorations = bookmarkRuns +
                    mergeRuleRuns(pending.chunks, pending.bandSignatures) +
                    mergeRuleRuns(pending.chunks, pending.lineSignatures),
                chunkOverrides = if (pending.chunkOverrides.all { it == null }) {
                    emptyList()
                } else {
                    pending.chunkOverrides
                },
            )
        }
        return ReaderPageSnapshot(
            title = page.title,
            readProgress = page.readProgress,
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            lines = lines,
            images = images,
            bookmarkBadge = bookmarkBadge,
        )
    }

    /**
     * 位图解析闭包：本宿主 ImageColumn 无 book 字段（引擎绘制时读全局
     * ReadBook.book），映射期同样按映射时刻的全局书籍取图——换书瞬间
     * 旧页若仍绘制，取到新书的目录（与引擎画布行为一致）；尺寸 ≤ 0
     * 直接返回 null，异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(src: String): (Int, Int) -> Bitmap? =
        { w, h ->
            val book = ReadBook.book
            if (w <= 0 || h <= 0 || book == null) {
                null
            } else {
                runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull()
            }
        }
}

/**
 * 引擎画笔 → 渲染规格（只拷贝测量耦合参数；color 由模块主题自涂，
 * 阴影等纯视觉效果不跨桥）。fontVariationSettings 为 API 26，
 * minSdk 21 宿主需门控。
 */
internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
    textSizePx = textSize, // API 1
    letterSpacing = letterSpacing, // API 21
    typeface = typeface, // API 1
    fontVariationSettings =
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings else null, // API 26
)

/** 规则命中 → 区间模型（[ReadBook.highlightRangesOfChapter] 的规则子集）。 */
private fun List<io.legado.app.help.HighlightRuleMatcher.RuleMatch>.toRuleRanges() =
    map { HighlightMatcher.Range(it.start, it.end, it.style, it.applyToTitle, it.applyToBody) }

// ---- 高亮规则 → 模块渲染（以下为纯函数区，单测直接覆盖） ----

/**
 * 彩色 → 墨水屏灰度（BT.709 亮度，保留 alpha）：彩色规则色在灰阶屏上的
 * 等价表达。宿主完成换算，模块不做色彩推断。
 */
internal fun Int.toEInkGrayArgb(): Int {
    val alpha = this ushr 24
    val r = (this shr 16) and 0xFF
    val g = (this shr 8) and 0xFF
    val b = this and 0xFF
    val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (y shl 16) or (y shl 8) or y
}

/**
 * 合成斜体倾斜量：镜像 `HighlightDraw.obtainTextPaint` 的
 * `textSkewX = -0.25f`（完整模式口径——规则斜体一律 skew 合成，不换
 * 字体 italic 面）。刻意不改引擎文件抽公共常量：bridge 只读引擎行为，
 * 不为 bridge 需求动引擎绘制层；引擎侧若调整该值，这里须同步。
 */
internal const val SYNTHETIC_ITALIC_SKEW_X = -0.25f

/** 行内一段规则装饰的绘制签名（相邻同签名段合并为同一 run）。 */
internal data class RuleRunSignature(
    /** 契约 underlineMode：0 无 / 1..4 线形 / 6 删除线 / 7 荧光半高带。 */
    val underlineMode: Int,
    /** true = 整行盒高亮带（highlightColorArgb 涂色）。 */
    val highlight: Boolean,
    val highlightColorArgb: Int?,
    val underlineColorArgb: Int?,
    val underline: ReaderUnderlineGeometry?,
)

/**
 * 高亮带签名：矩形/圆角/胶囊底折整行盒带（highlight）；荧光/半高/基线
 * 带折荧光语义（mode 7 下半带）。胶囊底的胶囊形与水平外扩不跨桥
 * （模块按普通带绘制，明示降级）。
 */
internal fun HighlightStyle.bandRunSignature(): RuleRunSignature? {
    if (fill == 0) return null
    val gray = fill.toEInkGrayArgb()
    return when (resolvedFillShape) {
        HighlightStyle.FillShape.MARKER,
        HighlightStyle.FillShape.HALF,
        HighlightStyle.FillShape.BASELINE,
        -> RuleRunSignature(
            underlineMode = 7,
            highlight = false,
            highlightColorArgb = null,
            underlineColorArgb = gray,
            underline = null,
        )

        else -> RuleRunSignature(
            underlineMode = 0,
            highlight = true,
            highlightColorArgb = gray,
            underlineColorArgb = null,
            underline = null,
        )
    }
}

/**
 * 线形装饰签名（下划线/删除线，两者并存取下划线——契约 run 单 mode
 * 字段，无法同 run 双线）。几何换算锚定完整模式 [io.legado.app.ui.book.read.page.HighlightDraw]：
 *  - y 口径：完整模式 = 基线 + distance，模块 = 行盒下沿 + offsetPx，
 *    [baselineDropPx]（行盒下沿 − 基线 = 引擎字体度量 descent）换算对齐；
 *  - 虚线 6dp/4dp、波浪波长 6dp、双线附加间距 1dp 同完整模式常量；
 *    波浪幅度 = max(线宽, 1dp)（完整模式同式）；
 *  - 点状线无契约形态，降级虚线（最接近的断线视觉）。
 */
internal fun HighlightStyle.lineRunSignature(
    dpPx: (Float) -> Float,
    baselineDropPx: Float,
): RuleRunSignature? {
    val underline = underline?.normalized()
    if (underline != null && underline.width > 0f) {
        val mode = when (underline.kind) {
            HighlightStyle.Kind.SOLID -> 1
            HighlightStyle.Kind.DASHED -> 2
            HighlightStyle.Kind.WAVY -> 3
            HighlightStyle.Kind.DOUBLE -> 4
            HighlightStyle.Kind.DOTTED -> 2
        }
        val widthPx = dpPx(underline.width)
        return RuleRunSignature(
            underlineMode = mode,
            highlight = false,
            highlightColorArgb = null,
            underlineColorArgb = underline.color.takeIf { it != 0 }?.toEInkGrayArgb(),
            underline = ReaderUnderlineGeometry(
                widthPx = widthPx,
                offsetPx = dpPx(underline.distance) - baselineDropPx,
                dashOnPx = dpPx(6f),
                dashOffPx = dpPx(4f),
                waveAmplitudePx = maxOf(widthPx, dpPx(1f)),
                waveLengthPx = dpPx(6f),
                doubleLineGapPx = dpPx(1f),
            ),
        )
    }
    if (strike == null) return null
    return RuleRunSignature(
        underlineMode = 6,
        highlight = false,
        highlightColorArgb = null,
        underlineColorArgb = strike.color.takeIf { it != 0 }?.toEInkGrayArgb(),
        underline = null,
    )
}

/**
 * 相邻同签名段合并为装饰 run（行内拼接文本 UTF-16 区间）。规则装饰的
 * markingId 恒空串——契约口径「空串 = 宿主高亮规则等非用户标记来源，
 * 点按视为未命中」，不进标记浮条/删除链路。
 */
internal fun mergeRuleRuns(
    chunks: List<String>,
    signatures: List<RuleRunSignature?>,
): List<ReaderDecorationRun> {
    val runs = ArrayList<ReaderDecorationRun>()
    var runStart = -1
    var runEnd = -1
    var runSignature: RuleRunSignature? = null
    var offset = 0
    for (index in chunks.indices) {
        val signature = signatures.getOrNull(index)
        if (signature != runSignature) {
            if (runSignature != null) {
                runs.add(runSignature.toRun(runStart, runEnd))
            }
            runStart = offset
            runSignature = signature
        }
        offset += chunks[index].length
        runEnd = offset
    }
    if (runSignature != null) {
        runs.add(runSignature.toRun(runStart, runEnd))
    }
    return runs
}

private fun RuleRunSignature.toRun(start: Int, end: Int) = ReaderDecorationRun(
    start = start,
    end = end,
    underlineMode = underlineMode,
    highlight = highlight,
    markingId = "",
    underline = underline,
    highlightColorArgb = highlightColorArgb,
    underlineColorArgb = underlineColorArgb,
)

/** 覆盖签名（typeface 恒为工厂缓存实例，按引用等值即参数等值）。 */
private data class ChunkOverrideKey(
    val textSizePx: Float?,
    val typeface: Typeface?,
    val colorArgb: Int?,
    val skewX: Float?,
    val fakeBold: Boolean,
)

/**
 * 元素样式 → 逐段画笔覆盖（映射期 Android 换算外壳）：字号 sp→px、
 * 规则字体经引擎工厂 [ChapterProvider.getHighlightTypeface] 解析并按
 * 基线字体样式折算（完整模式 obtainTextPaint 同式）。等值段复用共享
 * 实例（模块画笔缓存按值命中）。
 */
private fun HighlightStyle?.chunkOverrideOrNull(
    spec: ReaderPaintSpec,
    interned: MutableMap<ChunkOverrideKey, ReaderChunkPaintOverride>,
): ReaderChunkPaintOverride? {
    if (this == null) return null
    val textSizePx = resolvedFontSize?.spToPx()
    val typeface = resolvedFontPath.takeIf { it.isNotEmpty() }
        ?.let { ChapterProvider.getHighlightTypeface(it) }
        ?.let { Typeface.create(it, spec.typeface?.style ?: Typeface.NORMAL) }
    val override = ruleChunkOverride(
        style = this,
        textSizePx = textSizePx,
        typeface = typeface,
        baseTextSizePx = spec.textSizePx,
    ) ?: return null
    val key = ChunkOverrideKey(
        override.textSizePx, override.typeface, override.colorArgb, override.skewX, override.fakeBold
    )
    return interned.getOrPut(key) { override }
}

/**
 * 逐段画笔覆盖发射判定（纯函数）：字号差/规则字体/灰度字色/合成斜体/
 * 合成加粗，与行规格无差异返回 null。加粗走 fake bold（完整模式
 * [io.legado.app.ui.book.read.page.HighlightDraw] 同口径——绘制期描边
 * 加宽、不改步进宽，跨桥无对齐风险；不发射真实 BOLD 字体，避免字形
 * 宽度偏离引擎槽宽测量）。字距覆盖不发射（契约无字段，KDoc 明示降级：
 * 位置已由重排烘进列坐标）。
 */
internal fun ruleChunkOverride(
    style: HighlightStyle,
    textSizePx: Float?,
    typeface: Typeface?,
    baseTextSizePx: Float,
): ReaderChunkPaintOverride? {
    val textSize = textSizePx?.takeIf { it != baseTextSizePx }
    val color = style.textColor.takeIf { it != 0 }?.toEInkGrayArgb()
    val skewX = SYNTHETIC_ITALIC_SKEW_X.takeIf { style.italic }
    val fakeBold = style.bold
    if (textSize == null && typeface == null && color == null && skewX == null && !fakeBold) {
        return null
    }
    return ReaderChunkPaintOverride(
        textSizePx = textSize,
        typeface = typeface,
        fontVariationSettings = null,
        colorArgb = color,
        skewX = skewX,
        fakeBold = fakeBold,
    )
}
