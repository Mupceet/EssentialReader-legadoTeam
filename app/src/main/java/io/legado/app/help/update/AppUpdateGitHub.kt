package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import kotlin.time.Duration.Companion.milliseconds

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    /**
     * 更新源：本发行版自有仓库（EssentialReader 家族 · LegadoTeam 上游）。
     * 仅发布正式版（releases 列表过滤 pre-release）；beta 通道不发布
     * （tags/beta 恒 404，checkBeta 走「获取失败」语义）。发版资产命名
     * 约定见 AppReleaseInfo 解析：文件名不得含 "release" 字样（否则被
     * 推断为 beta 变体）、tag 取 3.yyMMddHH 版本、文件名带 vc<versionCode>
     * 段保证版本比较稳定。
     *
     * 检查链路（墨本 md3 同构）：manifest 优先——发版流水线把 release
     * JSON 投影到 update-manifests 分支的 official.json（GithubRelease
     * 同构），raw.githubusercontent 经 [UpdateAccelerator] 加速取小文件，
     * 失败回退直连再回退 GitHub API；API 兜底恒直连（公共代理出口 IP
     * 共享 GitHub 按 IP 的 API 限流额度，改写会放大 403/429）。下载
     * 直链经 [UpdateAccelerator.resolveDownloadUrl] 探测改写。
     */
    private const val repoPath = "Mupceet/EssentialReader-legadoTeam"

    private val releasesUrl =
        "https://api.github.com/repos/$repoPath/releases?per_page=30"

    private val betaTagUrl =
        "https://api.github.com/repos/$repoPath/releases/tags/beta"

    private val updateManifestBaseUrl =
        "https://raw.githubusercontent.com/$repoPath/update-manifests"

    private const val manifestTimeoutMillis = 2500L

    private val checkVariant: AppVariant
        get() = AppConst.appInfo.appVariant.takeUnless { it == AppVariant.UNKNOWN }
            ?: AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        // manifest 命中时 API 兜底失败不拖垮检查（国内 API 直连不可达是
        // 常态）；两路皆空才报获取失败
        val manifest = listOfNotNull(getManifestRelease())
        val apiReleases = runCatching { getApiReleases() }.getOrDefault(emptyList())
        val releases = manifest + apiReleases
        if (releases.isEmpty()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return releases
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }
    }

    /** manifest 单文件通道：加速 → 直连各一次，失败交回 API 兜底。 */
    private suspend fun getManifestRelease(): GithubRelease? {
        val manifestUrl = "$updateManifestBaseUrl/official.json"
        return fetchManifestRelease(UpdateAccelerator.accelerate(manifestUrl))
            ?: fetchManifestRelease(manifestUrl)
    }

    private suspend fun fetchManifestRelease(url: String): GithubRelease? {
        return try {
            withTimeoutOrNull(manifestTimeoutMillis.milliseconds) {
                val response = okHttpClient.newCallResponse { url(url) }
                response.use {
                    if (!it.isSuccessful) return@withTimeoutOrNull null
                    val release = GSON.fromJsonObject<GithubRelease>(it.body.text()).getOrNull()
                        ?: return@withTimeoutOrNull null
                    // 官方通道契约：非 pre-release 且至少一个有效 APK 资产
                    release.takeIf {
                        !it.isPreRelease && it.assets.orEmpty().any { asset -> asset.isValid }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getApiReleases(): List<GithubRelease> {
        val res = okHttpClient.newCallResponse {
            url(releasesUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        val releases = GSON.fromJsonArray<GithubRelease>(body).getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
        }
        return releases.filterNot { it.isPreRelease }
    }

    private suspend fun getBetaRelease(): List<AppReleaseInfo> {
        val res = okHttpClient.newCallResponse {
            url(betaTagUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException(
                "${appCtx.getString(R.string.check_beta_update_failed)}(${res.code})"
            )
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException(appCtx.getString(R.string.check_beta_update_failed))
        }
        return runCatching {
            GSON.fromJson(body, GithubRelease::class.java).gitReleaseToAppReleaseInfo()
        }.getOrElse {
            throw NoStackTraceException(
                appCtx.getString(R.string.check_beta_update_failed) + " " + it.localizedMessage
            )
        }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            selectUpdateRelease(
                releases = getLatestRelease(),
                appVariant = checkVariant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            )
                ?.let { return@async it.toUpdateInfo() }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val variant = AppConst.betaUpdateVariant
                ?: throw NoStackTraceException(appCtx.getString(R.string.beta_update_unsupported))
            val releases = getBetaRelease()
            selectUpdateRelease(
                releases = releases,
                appVariant = variant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                currentVersionCode = AppConst.appInfo.versionCode
            )
                ?.let { return@async it.toUpdateInfo(isBeta = true) }
            if (releases.any {
                    it.appVariant == variant && it.isNewerThan(
                        AppConst.appInfo.versionName,
                        AppConst.appInfo.versionCode
                    )
                }) {
                throw NoStackTraceException(appCtx.getString(R.string.beta_update_no_compatible_apk))
            }
            throw NoStackTraceException(appCtx.getString(R.string.latest_beta_version))
        }.timeout(10000)
    }
}

internal suspend fun AppReleaseInfo.toUpdateInfo(isBeta: Boolean = false): AppUpdate.UpdateInfo {
    // 下载直链：加速源可达则前缀改写（Range 0-0 探测），不可达回退
    // GitHub 原文；本发行版无上游 CDN 分发网，镜像位恒空
    val primaryUrl = UpdateAccelerator.resolveDownloadUrl(downloadUrl)
    return AppUpdate.UpdateInfo(
        tagName = versionName,
        updateLog = note,
        downloadUrl = primaryUrl,
        fileName = name,
        mirrorDownloadUrl = null,
        alternateMirrorDownloadUrl = null,
        size = size,
        createdAt = createdAt,
        isBeta = isBeta
    )
}
