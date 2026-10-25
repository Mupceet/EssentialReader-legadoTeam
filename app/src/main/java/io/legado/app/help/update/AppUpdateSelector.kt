package io.legado.app.help.update

internal fun selectUpdateRelease(
    releases: List<AppReleaseInfo>,
    appVariant: AppVariant,
    currentVersionName: String,
    supportedAbis: List<String>,
    currentVersionCode: Long = 0L
): AppReleaseInfo? {
    val prefersArm = supportedAbis.firstOrNull()?.isArmAbi() == true
    val candidates = releases.filter {
        it.appVariant == appVariant &&
            it.isNewerThan(currentVersionName, currentVersionCode) &&
            (prefersArm || it.isUniversalPackage())
    }
    val useVersionCode = currentVersionCode > 0L && candidates.any { it.versionCode > 0L }
    val comparableCandidates = if (useVersionCode) {
        candidates.filter { it.versionCode > 0L }
    } else {
        candidates
    }
    val latest = comparableCandidates.maxWithOrNull(Comparator { left, right ->
        val comparison = if (useVersionCode) {
            left.versionCode.compareTo(right.versionCode)
        } else {
            compareReleaseVersions(left.versionName, right.versionName)
        }
        comparison.takeIf { it != 0 }
            ?: left.createdAt.compareTo(right.createdAt)
    }) ?: return null
    val sameVersion = comparableCandidates
        .filter {
            if (useVersionCode) {
                it.versionCode == latest.versionCode
            } else {
                compareReleaseVersions(it.versionName, latest.versionName) == 0
            }
        }
        .sortedByDescending { it.createdAt }
    return if (prefersArm) {
        sameVersion.firstOrNull { !it.isUniversalPackage() } ?: sameVersion.firstOrNull()
    } else {
        sameVersion.firstOrNull { it.isUniversalPackage() }
    }
}

internal fun AppReleaseInfo.isNewerThan(
    currentVersionName: String,
    currentVersionCode: Long = 0L
): Boolean {
    return if (versionCode > 0L && currentVersionCode > 0L) {
        versionCode > currentVersionCode
    } else {
        compareReleaseVersions(versionName, currentVersionName) > 0
    }
}

internal fun compareReleaseVersions(left: String, right: String): Int {
    val leftParts = left.toVersionParts()
    val rightParts = right.toVersionParts()
    if (leftParts.isEmpty() || rightParts.isEmpty()) {
        return left.compareTo(right)
    }
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = leftParts.getOrElse(index) { 0L }
            .compareTo(rightParts.getOrElse(index) { 0L })
        if (comparison != 0) return comparison
    }
    return 0
}

private fun String.toVersionParts(): List<Long> {
    return comparableVersionPattern.find(this)?.value
        ?.let(::normalizeLegadoVersionName)
        ?.split('.')
        ?.mapNotNull { it.toLongOrNull() }
        .orEmpty()
}

private val comparableVersionPattern = Regex("""\d+(?:\.\d+)+""")

private fun String.isArmAbi(): Boolean {
    return equals("arm64-v8a", ignoreCase = true) ||
        equals("armeabi-v7a", ignoreCase = true)
}

private fun AppReleaseInfo.isUniversalPackage(): Boolean {
    return isUniversalPackageName(name)
}

internal fun isUniversalPackageName(fileName: String): Boolean {
    return fileName.contains("通用") ||
        fileName.contains("universal", ignoreCase = true) ||
        fileName.contains("_._")
}

// 本发行版（EssentialReader-legadoTeam）无上游的 CDN 分发网（cdn.mgz.la
// 等仅镜像上游官方资产）：更新下载经 UpdateAccelerator 探测改写
// （gh-proxy.com 前缀，可达才用、失败回退 GitHub 直连，墨本 md3 同构），
// 检查链路 manifest 优先（update-manifests 分支小文件，同样加速优先），
// 无独立镜像位。

internal fun isIgnoredAppUpdate(versionName: String, ignoredVersion: String?): Boolean =
    versionName == ignoredVersion
