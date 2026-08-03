/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.platform.getAniUserAgent
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.Arch
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform
import kotlin.time.Instant

data class UpdateCheckResult(
    val fork: NewVersion?,
    val upstream: UpstreamUpdateNotice?,
)

/** Checks installable fork releases and informational upstream releases on GitHub. */
class UpdateChecker(
    private val clientFactory: () -> HttpClient = {
        HttpClient(getPlatformKtorEngine()) { expectSuccess = true }
    },
) {
    suspend fun checkUpdates(
        releaseClass: ReleaseClass,
        currentVersion: String = currentAniBuildConfig.versionName,
        platform: Platform = currentPlatform(),
        lastNotifiedUpstreamTag: String = "",
    ): UpdateCheckResult = clientFactory().use { client ->
        try {
            val forkReleases = client.getReleases(FORK_REPOSITORY)
            val upstreamReleases = try {
                client.getReleases(UPSTREAM_REPOSITORY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Failed to check informational upstream releases" }
                emptyList()
            }
            UpdateCheckResult(
                fork = selectForkUpdate(forkReleases, currentVersion, releaseClass, platform),
                upstream = selectUpstreamNotice(
                    upstreamReleases,
                    currentVersion,
                    releaseClass,
                    lastNotifiedUpstreamTag,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            logger.error(e) { "Failed to check GitHub releases" }
            throw e
        }
    }

    /** Kept for callers that only need the installable fork update. */
    suspend fun checkLatestVersion(
        releaseClass: ReleaseClass,
        currentVersion: String = currentAniBuildConfig.versionName,
    ): NewVersion? = checkUpdates(releaseClass, currentVersion).fork

    private suspend fun HttpClient.getReleases(repository: String): List<GitHubRelease> {
        val body = get("https://api.github.com/repos/$repository/releases?per_page=40") {
            header(HttpHeaders.UserAgent, getAniUserAgent())
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.bodyAsText()
        return json.decodeFromString(body)
    }

    private companion object {
        private const val FORK_REPOSITORY = "beiyuge/animeko"
        private const val UPSTREAM_REPOSITORY = "open-ani/animeko"
        private val logger = logger<UpdateChecker>()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun selectForkUpdate(
    releases: List<GitHubRelease>,
    currentVersion: String,
    releaseClass: ReleaseClass,
    platform: Platform,
): NewVersion? {
    val current = AnimekoReleaseVersion.parse(currentVersion) ?: return null
    val latest = releases.asSequence()
        .filterNot { it.draft }
        .mapNotNull { release ->
            val version = AnimekoReleaseVersion.parse(release.tagName) ?: return@mapNotNull null
            if (!version.isXuanyu || !version.releaseClass.moreStableThan(releaseClass)) return@mapNotNull null
            release to version
        }
        .filter { (_, version) -> version > current }
        .maxByOrNull { it.second }
        ?: return null
    val (release, version) = latest
    val urls = selectDownloadAssets(release.assets, platform)
        .ifEmpty { listOf(release.htmlUrl) }
    return NewVersion(
        name = release.tagName.removePrefix("v"),
        changelogs = listOf(
            Changelog(
                version = release.tagName.removePrefix("v"),
                publishedAt = formatGitHubTime(release.publishedAt),
                changes = release.body,
            ),
        ),
        downloadUrlAlternatives = urls,
        publishedAt = formatGitHubTime(release.publishedAt),
        detailsUrl = release.htmlUrl,
    ).also {
        check(version > current)
    }
}

internal fun selectUpstreamNotice(
    releases: List<GitHubRelease>,
    currentVersion: String,
    releaseClass: ReleaseClass,
    lastNotifiedTag: String,
): UpstreamUpdateNotice? {
    val currentBase = AnimekoReleaseVersion.parse(currentVersion)?.withoutFork() ?: return null
    val notified = AnimekoReleaseVersion.parse(lastNotifiedTag)?.withoutFork()
    val latest = releases.asSequence()
        .filterNot { it.draft }
        .mapNotNull { release ->
            val version = AnimekoReleaseVersion.parse(release.tagName)?.withoutFork()
                ?: return@mapNotNull null
            if (version.isXuanyu || !version.releaseClass.moreStableThan(releaseClass)) return@mapNotNull null
            release to version
        }
        .filter { (_, version) -> version > currentBase && (notified == null || version > notified) }
        .maxByOrNull { it.second }
        ?: return null
    return UpstreamUpdateNotice(
        tag = latest.first.tagName,
        version = latest.first.tagName.removePrefix("v"),
        detailsUrl = latest.first.htmlUrl,
    )
}

internal fun selectDownloadAssets(assets: List<GitHubAsset>, platform: Platform): List<String> {
    fun score(name: String): Int {
        val value = name.lowercase()
        return when (platform) {
            is Platform.Android -> {
                if (!value.endsWith(".apk")) return -1
                when {
                    value.contains(platform.arch.displayName.lowercase()) -> 100
                    value.contains("universal") -> 50
                    else -> -1
                }
            }

            is Platform.Windows -> when {
                !value.contains("windows") || !value.contains("x86_64") -> -1
                value.endsWith(".msi") -> 100
                value.endsWith(".exe") -> 90
                value.endsWith(".zip") -> 70
                else -> -1
            }

            is Platform.MacOS -> {
                val archMatches = when (platform.arch) {
                    Arch.AARCH64, Arch.ARMV8A -> value.contains("aarch64") || value.contains("arm64")
                    Arch.X86_64 -> value.contains("x86_64") || value.contains("intel")
                    Arch.ARMV7A -> false
                }
                when {
                    !value.contains("macos") || !archMatches -> -1
                    value.endsWith(".dmg") -> 100
                    value.endsWith(".zip") -> 70
                    else -> -1
                }
            }

            is Platform.Linux -> when {
                !value.contains("linux") || !value.contains("x86_64") -> -1
                value.endsWith(".appimage") -> 100
                value.endsWith(".deb") -> 80
                value.endsWith(".zip") -> 60
                else -> -1
            }

            Platform.Ios -> -1
        }
    }
    return assets.mapNotNull { asset ->
        score(asset.name).takeIf { it >= 0 }?.let { it to asset.browserDownloadUrl }
    }.sortedByDescending { it.first }.map { it.second }
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

internal data class AnimekoReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val releaseClass: ReleaseClass,
    val preReleaseNumber: Int,
    val isXuanyu: Boolean,
    val forkRevision: Int,
) : Comparable<AnimekoReleaseVersion> {
    override fun compareTo(other: AnimekoReleaseVersion): Int = compareValuesBy(
        this,
        other,
        AnimekoReleaseVersion::major,
        AnimekoReleaseVersion::minor,
        AnimekoReleaseVersion::patch,
        { it.releaseClass.ordinal },
        AnimekoReleaseVersion::preReleaseNumber,
        { if (it.isXuanyu) 1 else 0 },
        AnimekoReleaseVersion::forkRevision,
    )

    fun withoutFork(): AnimekoReleaseVersion = copy(isXuanyu = false, forkRevision = 0)

    companion object {
        private val pattern = Regex(
            """^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+)?)?(?:\.xuanyu(?:\.(\d+))?)?(?:\.android)?$""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String): AnimekoReleaseVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            val stage = when (match.groupValues[4].lowercase()) {
                "alpha" -> ReleaseClass.ALPHA
                "beta" -> ReleaseClass.BETA
                "rc" -> ReleaseClass.RC
                else -> ReleaseClass.STABLE
            }
            val hasFork = raw.contains(".xuanyu", ignoreCase = true)
            return AnimekoReleaseVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                releaseClass = stage,
                preReleaseNumber = match.groupValues[5].toIntOrNull() ?: 0,
                isXuanyu = hasFork,
                forkRevision = match.groupValues[6].toIntOrNull() ?: if (hasFork) 1 else 0,
            )
        }
    }
}

private fun formatGitHubTime(value: String): String = runCatching {
    TimeFormatter().format(Instant.parse(value).toEpochMilliseconds())
}.getOrElse { value }
