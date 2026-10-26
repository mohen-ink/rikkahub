package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MohenReleaseTest {
    @Test
    fun `personal tags expose the app version`() {
        assertEquals("3.1.1-mohen.1", MohenRelease.versionFromTag("v3.1.1-mohen.1"))
        assertEquals("3.1.1-mohen.999", MohenRelease.versionFromTag("v3.1.1-mohen.999"))
    }

    @Test
    fun `unrelated and malformed tags are rejected`() {
        listOf(
            "nightly", "3.1.1", "v3.1.1", "mohen-v3.1.1.1", "3.1.1-mohen.1",
            "v3.1.1-mohen.0", "v3.1.1-mohen.1000", "v3.1.1-mohen.-1",
            "v03.1.1-mohen.1", "v3.1.1-mohen.01", "v3.1.1-mohen.1+build",
            "v999999999999.1.1-mohen.1",
        ).forEach { assertNull(it, MohenRelease.versionFromTag(it)) }
    }

    @Test
    fun `personal versions compare numerically and migrate upstream installs`() {
        assertTrue(Version("3.1.1-mohen.2") < Version("3.1.1-mohen.10"))
        assertTrue(MohenRelease.currentVersion("3.1.1") < Version("3.1.1-mohen.1"))
        assertTrue(Version("3.1.1-mohen.999") < Version("3.1.2-mohen.1"))
        assertEquals(Version("3.1.1-mohen.2"), MohenRelease.currentVersion("3.1.1-mohen.2"))
    }

    @Test
    fun `empty release lists have no update`() {
        assertNull(emptyList<GitHubRelease>().latestMohenUpdate())
    }

    @Test
    fun `unpublished drafts prereleases and upstream tags are ignored`() {
        assertNull(release().copy(draft = true).toUpdateInfo())
        assertNull(release().copy(prerelease = true).toUpdateInfo())
        assertNull(release().copy(publishedAt = null).toUpdateInfo())
        assertNull(release().copy(publishedAt = " ").toUpdateInfo())
        assertNull(release("3.1.1").toUpdateInfo())
        assertNull(release("nightly").toUpdateInfo())
        assertNull(release().copy(assets = emptyList()).toUpdateInfo())
    }

    @Test
    fun `highest version wins regardless of API or date ordering`() {
        val releases = listOf(
            release("v3.1.1-mohen.2").copy(publishedAt = "2026-10-26T00:00:00Z"),
            release("v3.1.1-mohen.10").copy(publishedAt = "2026-10-01T00:00:00Z"),
            release("v3.1.1-mohen.1"),
            release("v9.0.0-mohen.1").copy(prerelease = true),
        )
        assertEquals("3.1.1-mohen.10", releases.latestMohenUpdate()?.version)
        assertEquals("3.1.2-mohen.1", (releases + release("v3.1.2-mohen.1")).latestMohenUpdate()?.version)
    }

    @Test
    fun `only uploaded nonempty APKs from this release are offered`() {
        val release = release()
        val apk = release.assets.single()
        val update = release.copy(
            assets = listOf(
                apk.copy(name = "mapping.txt"),
                apk.copy(size = 0),
                apk.copy(state = "new"),
                apk.copy(downloadUrl = "https://github.com/other/repo/releases/download/v3.1.1-mohen.1/app.apk"),
                apk.copy(downloadUrl = "${MohenRelease.REPOSITORY_URL}/releases/download/v3.1.1-mohen.2/app.apk"),
                apk,
            )
        ).toUpdateInfo()
        assertEquals(listOf(UpdateDownload(apk.name, apk.downloadUrl, "1.0 MB")), update?.downloads)
    }

    @Test
    fun `GitHub JSON fields decode and unknown metadata is ignored`() {
        val release = Json { ignoreUnknownKeys = true }.decodeFromString<GitHubRelease>(
            """
            {
              "id": 123,
              "tag_name": "v3.1.1-mohen.1",
              "published_at": "2026-10-26T00:00:00Z",
              "body": null,
              "assets": [{
                "name": "RikkaHub-3.1.1-mohen.1-arm64-v8a.apk",
                "browser_download_url": "https://github.com/mohen-ink/rikkahub/releases/download/v3.1.1-mohen.1/app.apk",
                "size": 1048576,
                "state": "uploaded",
                "content_type": "application/vnd.android.package-archive"
              }]
            }
            """.trimIndent()
        )
        assertEquals("3.1.1-mohen.1", release.toUpdateInfo()?.version)
        assertEquals("", release.toUpdateInfo()?.changelog)
    }

    private fun release(tag: String = "v3.1.1-mohen.1") = GitHubRelease(
        tagName = tag,
        publishedAt = "2026-10-26T00:00:00Z",
        body = "Personal release",
        assets = listOf(
            GitHubReleaseAsset(
                name = "RikkaHub-${tag.removePrefix("v")}-arm64-v8a.apk",
                downloadUrl = "${MohenRelease.REPOSITORY_URL}/releases/download/$tag/app.apk",
                size = 1048576,
            )
        ),
    )
}