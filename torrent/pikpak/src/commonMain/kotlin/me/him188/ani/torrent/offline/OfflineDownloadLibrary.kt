/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.offline

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

interface OfflineDownloadLibrary {
    val libraryState: StateFlow<OfflineDownloadLibraryState>

    suspend fun sync()

    suspend fun resolveCachedEpisode(subjectId: String, episodeId: String): ResolvedMedia?

    suspend fun recordResolvedResource(entry: OfflineDownloadLibraryEntry)

    suspend fun rematch(subjectId: String, episodeId: String, preferredEntryId: String)

    suspend fun deleteEpisode(subjectId: String, episodeId: String)

    suspend fun deleteUnmatched(providerFileId: String)
}

enum class OfflineEpisodeAvailability {
    Cached,
    MiddleGap,
    TrailingGap,
    NotAired,
    NoPikPakCache,
    RemoteMissing,
}

/** Pure availability classifier used by episode UI after its forced AniAPI refresh. */
fun classifyOfflineEpisodeAvailability(
    targetEpisode: Int,
    latestAiredEpisode: Int,
    cachedEpisodes: Set<Int>,
    remoteMissingEpisodes: Set<Int> = emptySet(),
): OfflineEpisodeAvailability = when {
    targetEpisode in remoteMissingEpisodes -> OfflineEpisodeAvailability.RemoteMissing
    targetEpisode in cachedEpisodes -> OfflineEpisodeAvailability.Cached
    targetEpisode > latestAiredEpisode -> OfflineEpisodeAvailability.NotAired
    cachedEpisodes.isEmpty() -> OfflineEpisodeAvailability.NoPikPakCache
    cachedEpisodes.any { it > targetEpisode } -> OfflineEpisodeAvailability.MiddleGap
    else -> OfflineEpisodeAvailability.TrailingGap
}

@Serializable
data class OfflineDownloadLibraryManifest(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val entries: List<OfflineDownloadLibraryEntry> = emptyList(),
)

@Serializable
data class OfflineDownloadLibraryEntry(
    val entryId: String,
    val subjectId: String,
    val subjectName: String,
    val episodeId: String,
    val episodeNumber: String,
    val episodeTitle: String,
    val sourceKey: String,
    val sourcePayload: String,
    val resourceRootId: String,
    val providerFileId: String,
    val providerFileName: String,
    val fileSize: Long? = null,
    val sharedResource: Boolean = false,
    val preferred: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

data class OfflineDownloadUnmatchedResource(
    val providerFileId: String,
    val providerFileName: String,
    val isFolder: Boolean,
)

data class OfflineDownloadLibraryState(
    val status: Status = Status.Idle,
    val manifest: OfflineDownloadLibraryManifest = OfflineDownloadLibraryManifest(),
    val unmatchedResources: List<OfflineDownloadUnmatchedResource> = emptyList(),
    val remoteMissingEntryIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
) {
    val entries: List<OfflineDownloadLibraryEntry>
        get() = manifest.entries.filterNot(OfflineDownloadLibraryEntry::isDeleted)

    sealed interface Status {
        data object Idle : Status
        data object Syncing : Status
        data object Ready : Status
        data object Failed : Status
    }
}

internal fun mergeOfflineDownloadLibraryManifests(
    local: OfflineDownloadLibraryManifest,
    remote: OfflineDownloadLibraryManifest,
    now: Long,
): OfflineDownloadLibraryManifest {
    val mergedEntries = (local.entries + remote.entries)
        .groupBy(OfflineDownloadLibraryEntry::entryId)
        .map { (_, versions) ->
            val deletionVersions = versions.filter(OfflineDownloadLibraryEntry::isDeleted)
            (deletionVersions.ifEmpty { versions }).maxBy(OfflineDownloadLibraryEntry::updatedAt)
        }
        .sortedWith(compareBy(OfflineDownloadLibraryEntry::subjectName, OfflineDownloadLibraryEntry::episodeNumber))
    return OfflineDownloadLibraryManifest(
        schemaVersion = maxOf(local.schemaVersion, remote.schemaVersion),
        revision = maxOf(local.revision, remote.revision) + 1,
        updatedAt = now,
        entries = mergedEntries,
    )
}
