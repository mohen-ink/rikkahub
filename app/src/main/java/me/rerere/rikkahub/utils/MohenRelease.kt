package me.rerere.rikkahub.utils

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object MohenRelease {
    const val REPOSITORY = "mohen-ink/rikkahub"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val LICENSE_URL = "$REPOSITORY_URL/blob/personal/LICENSE"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases"

    private val tagPattern = Regex(
        """v((0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-mohen\.([1-9][0-9]{0,2}))"""
    )
    private val baseVersionPattern = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")

    fun versionFromTag(tag: String): String? {
        val match = tagPattern.matchEntire(tag) ?: return null
        if (match.groupValues.subList(2, 5).any { it.toIntOrNull() == null }) return null
        return match.groupValues[1]
    }

    fun currentVersion(version: String): Version {
        return Version(if (baseVersionPattern.matches(version)) "$version-mohen.0" else version)
    }
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    fun toUpdateInfo(): UpdateInfo? {
        if (draft || prerelease || publishedAt.isNullOrBlank()) return null
        val version = MohenRelease.versionFromTag(tagName) ?: return null
        val downloadPrefix = "${MohenRelease.REPOSITORY_URL}/releases/download/$tagName/"
        val downloads = assets.filter {
            it.state == "uploaded" && it.size > 0 && it.name.endsWith(".apk", ignoreCase = true) &&
                it.downloadUrl.startsWith(downloadPrefix)
        }.map {
            UpdateDownload(
                name = it.name,
                url = it.downloadUrl,
                size = String.format(Locale.ROOT, "%.1f MB", it.size / 1048576.0),
            )
        }
        if (downloads.isEmpty()) return null
        return UpdateInfo(
            version = version,
            publishedAt = publishedAt,
            changelog = body.orEmpty(),
            downloads = downloads,
        )
    }
}

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long,
    val state: String = "uploaded",
)

internal fun List<GitHubRelease>.latestMohenUpdate(): UpdateInfo? {
    return mapNotNull { it.toUpdateInfo() }.maxByOrNull { Version(it.version) }
}
