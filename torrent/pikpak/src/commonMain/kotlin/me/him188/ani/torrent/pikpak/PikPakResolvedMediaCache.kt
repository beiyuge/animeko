/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import me.him188.ani.torrent.offline.ResolvedMedia

internal data class CachedPikPakFile(
    val id: String,
    val name: String,
)

/**
 * Process-local index of files already discovered in Animeko's PikPak working folder.
 *
 * The caller must refresh the selected file by id before playback. A null refresh
 * means the remote file was removed or is no longer playable and evicts that entry.
 */
internal class PikPakResolvedMediaCache {
    private val sources = mutableMapOf<String, List<CachedPikPakFile>>()

    fun replace(sourceKey: String, candidates: List<CachedPikPakFile>) {
        if (candidates.isEmpty()) {
            sources.remove(sourceKey)
        } else {
            sources[sourceKey] = candidates
        }
    }

    fun clear() {
        sources.clear()
    }

    fun remove(sourceKey: String) {
        sources.remove(sourceKey)
    }

    suspend fun resolve(
        sourceKey: String,
        pickVideoFile: (candidateFilenames: List<String>) -> String?,
        refresh: suspend (CachedPikPakFile) -> ResolvedMedia?,
    ): ResolvedMedia? {
        val candidates = sources[sourceKey] ?: return null
        // Always run the episode picker, even for a single indexed candidate.
        // A season pack can temporarily expose only one candidate when the
        // provider is still materialising or when its directory is nested; a
        // cardinality shortcut would then silently play that unrelated episode.
        val selectedName = pickVideoFile(candidates.map { it.name }) ?: return null
        val selected = candidates.firstOrNull { it.name == selectedName } ?: return null
        return refresh(selected).also { resolved ->
            if (resolved == null) {
                replace(sourceKey, candidates.filterNot { it.id == selected.id })
            }
        }
    }
}
