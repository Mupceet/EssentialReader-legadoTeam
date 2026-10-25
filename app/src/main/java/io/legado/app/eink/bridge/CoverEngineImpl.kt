package io.legado.app.eink.bridge

import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.eink.contract.CoverEngine
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面字节端口实现：接入宿主完整模式同一条 Glide 封面管线——
 * downloadOnly 下载原始字节，与完整模式**共享同一份磁盘缓存**
 * （LegadoGlideModule 的 1GB InternalCache）：
 *
 *  - 命中（完整模式或上次 E-Ink 加载已落盘）零网络，冷启动即秒出；
 *  - 未命中经宿主自定义 OkHttpStreamFetcher 抓取，书源防盗链头由
 *    sourceOriginOption 携带（与宿主 loadBitmap(path, sourceOrigin)
 *    同机制——头部走 Options 不进缓存键），结果写入共享缓存，
 *    两模式互惠；
 *  - 缓存键 = URL（宿主管线口径）。
 *
 * 进程级失败冷却（5 分钟）：坏源封面不在每次书架渲染时反复全量请求
 * （冷却内存态，进程重启后允许重试一次）。
 *
 * 失败一律返回 null（模块回退占位封面），CancellationException 透传。
 */
internal object CoverEngineImpl : CoverEngine {

    private val failedAt = ConcurrentHashMap<String, Long>()

    /** 失败冷却窗口（ms）。 */
    private const val FAILURE_COOLDOWN_MS = 5 * 60_000L

    override suspend fun fetchCoverBytes(url: String, sourceOrigin: String?): ByteArray? {
        val lastFail = failedAt[url]
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAILURE_COOLDOWN_MS) {
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val request = Glide.with(appCtx).downloadOnly().load(url)
                val origin = sourceOrigin?.takeIf { it.isNotBlank() }
                if (origin != null) {
                    request.apply(
                        RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin)
                    )
                }
                val file = request.submit().get()
                failedAt.remove(url)
                file.readBytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[url] = System.currentTimeMillis()
            null
        }
    }
}
