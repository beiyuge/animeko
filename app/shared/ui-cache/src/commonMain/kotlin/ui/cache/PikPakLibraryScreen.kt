/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.cache

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.layout.AniWindowInsets
import me.him188.ani.app.ui.foundation.widgets.NsfwMask
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.torrent.offline.OfflineDownloadLibrary
import me.him188.ani.torrent.offline.OfflineDownloadLibraryEntry
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PikPakLibraryViewModel : AbstractViewModel(), KoinComponent {
    private val library: OfflineDownloadLibrary by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val subjectCollectionRepository: SubjectCollectionRepository by inject()

    val state = library.libraryState
    val enabled = settingsRepository.pikpakConfig.flow.map { it.enabled }
        .stateInBackground(false)
    private val coverRefreshGeneration = MutableStateFlow(0)
    private val subjectIds = state.map { libraryState ->
        libraryState.entries.mapNotNull { entry -> entry.subjectId.toIntOrNull() }.distinct()
    }.distinctUntilChanged()
    val subjectCovers = combine(subjectIds, coverRefreshGeneration) { ids, _ -> ids }
        .flatMapLatest(subjectCollectionRepository::subjectCollectionCoverInfoFlow)
        .map { covers ->
            covers.associate { cover ->
                cover.subjectId.toString() to PikPakSubjectCover(cover.imageLarge, cover.nsfwMode)
            }
        }
        .stateInBackground(emptyMap())

    init {
        backgroundScope.launch {
            enabled.filter { it }.first()
            library.sync()
        }
    }

    fun refresh() {
        coverRefreshGeneration.update { it + 1 }
        backgroundScope.launch { library.sync() }
    }

    fun deleteEpisode(subjectId: String, episodeId: String) {
        backgroundScope.launch { library.deleteEpisode(subjectId, episodeId) }
    }
}

private data class PikPakEpisodeRow(
    val subjectId: String,
    val subjectName: String,
    val episodeId: String,
    val episodeNumber: String,
    val episodeTitle: String,
    val resources: List<OfflineDownloadLibraryEntry>,
)

private data class PikPakSubjectCardModel(
    val subjectId: String,
    val subjectName: String,
    val cover: PikPakSubjectCover?,
    val episodes: List<PikPakEpisodeRow>,
)

data class PikPakSubjectCover(
    val url: String,
    val nsfwMode: NsfwMode,
)

@Composable
fun PikPakLibraryScreen(
    vm: PikPakLibraryViewModel,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val subjectCovers by vm.subjectCovers.collectAsStateWithLifecycle()
    PikPakLibraryScreen(
        state = state,
        subjectCovers = subjectCovers,
        onRefresh = vm::refresh,
        onPlay = onPlay,
        onDeleteEpisode = vm::deleteEpisode,
        modifier = modifier,
        windowInsets = windowInsets,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PikPakLibraryScreen(
    state: OfflineDownloadLibraryState,
    subjectCovers: Map<String, PikPakSubjectCover> = emptyMap(),
    onRefresh: () -> Unit,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    onDeleteEpisode: (subjectId: String, episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
) {
    val subjects = remember(state.entries, subjectCovers) {
        val episodes = state.entries.groupBy { it.subjectId to it.episodeId }.map { (_, entries) ->
            val latest = entries.maxBy(OfflineDownloadLibraryEntry::updatedAt)
            PikPakEpisodeRow(
                latest.subjectId,
                latest.subjectName.ifEmpty { "未知动漫" },
                latest.episodeId,
                latest.episodeNumber,
                latest.episodeTitle,
                entries,
            )
        }
        episodes.groupBy(PikPakEpisodeRow::subjectId).map { (subjectId, subjectEpisodes) ->
            PikPakSubjectCardModel(
                subjectId = subjectId,
                subjectName = subjectEpisodes.first().subjectName,
                cover = subjectCovers[subjectId],
                episodes = subjectEpisodes.sortedWith(
                    compareBy<PikPakEpisodeRow> { it.episodeNumber.toDoubleOrNull() ?: Double.MAX_VALUE }
                        .thenBy(PikPakEpisodeRow::episodeNumber),
                ),
            )
        }.sortedBy(PikPakSubjectCardModel::subjectName)
    }
    var deleteEpisode by remember { mutableStateOf<PikPakEpisodeRow?>(null) }

    Scaffold(
        modifier = modifier.testTag("PikPakLibraryScreen"),
        contentWindowInsets = windowInsets,
        topBar = {
            TopAppBar(
                title = { Text("PikPak") },
                actions = {
                    if (state.status == OfflineDownloadLibraryState.Status.Syncing) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onRefresh) { Icon(Icons.Rounded.Refresh, "同步 PikPak") }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(360.dp),
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp),
        ) {
            if (state.status == OfflineDownloadLibraryState.Status.Failed) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Column {
                            Text("PikPak 同步失败", style = MaterialTheme.typography.titleMedium)
                            Text(state.errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (subjects.isEmpty() && state.status != OfflineDownloadLibraryState.Status.Syncing) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Cloud, null)
                        Column {
                            Text("还没有缓存到 PikPak 的剧集", style = MaterialTheme.typography.titleMedium)
                            Text("在选集页使用“缓存至 PikPak”添加资源")
                        }
                    }
                }
            }
            items(subjects, key = PikPakSubjectCardModel::subjectId) { subject ->
                PikPakSubjectCard(
                    subject = subject,
                    remoteMissingEntryIds = state.remoteMissingEntryIds,
                    onPlay = onPlay,
                    onLongClickEpisode = { deleteEpisode = it },
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }

    deleteEpisode?.let { episode ->
        DeletePikPakDialog(
            title = "删除这集的 PikPak 缓存？",
            description = "将删除该集的独占资源；季度合集仍被其他剧集使用时会保留。",
            onDismiss = { deleteEpisode = null },
            onConfirm = {
                onDeleteEpisode(episode.subjectId, episode.episodeId)
                deleteEpisode = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PikPakSubjectCard(
    subject: PikPakSubjectCardModel,
    remoteMissingEntryIds: Set<String>,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    onLongClickEpisode: (PikPakEpisodeRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var nsfwMode by rememberSaveable(subject.subjectId, subject.cover?.nsfwMode) {
        mutableStateOf(subject.cover?.nsfwMode ?: NsfwMode.DISPLAY)
    }
    NsfwMask(
        mode = nsfwMode,
        onTemporarilyDisplay = { nsfwMode = NsfwMode.DISPLAY },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        PikPakSubjectCardContent(subject, remoteMissingEntryIds, onPlay, onLongClickEpisode)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PikPakSubjectCardContent(
    subject: PikPakSubjectCardModel,
    remoteMissingEntryIds: Set<String>,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    onLongClickEpisode: (PikPakEpisodeRow) -> Unit,
) {
    Card(Modifier.fillMaxWidth().height(164.dp)) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxHeight().width(108.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (subject.cover?.url.isNullOrBlank()) {
                    Icon(
                        Icons.Rounded.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(
                        subject.cover.url,
                        contentDescription = subject.subjectName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    subject.subjectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${subject.episodes.size} 集已缓存 · 长按剧集可删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    lazyRowItems(
                        subject.episodes,
                        key = { "${it.subjectId}:${it.episodeId}" },
                    ) { episode ->
                        val missing = episode.resources.all { entry ->
                            entry.entryId in remoteMissingEntryIds || runCatching {
                                DataStoreJson.decodeFromString(DefaultMedia.serializer(), entry.sourcePayload)
                            }.isFailure
                        }
                        Surface(
                            modifier = Modifier
                                .testTag("PikPakEpisode:${episode.subjectId}:${episode.episodeId}")
                                .width(148.dp).height(76.dp)
                                .combinedClickable(
                                    onClick = {
                                        val subjectId = episode.subjectId.toIntOrNull()
                                        val episodeId = episode.episodeId.toIntOrNull()
                                        if (subjectId != null && episodeId != null) onPlay(subjectId, episodeId)
                                    },
                                    onLongClick = { onLongClickEpisode(episode) },
                                ),
                            shape = MaterialTheme.shapes.small,
                            color = if (missing) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    buildString {
                                        append(
                                            if (episode.episodeNumber.isBlank()) {
                                                "未知集数"
                                            } else {
                                                "第${episode.episodeNumber}集"
                                            },
                                        )
                                        if (episode.episodeTitle.isNotBlank()) append(" · ${episode.episodeTitle}")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (missing) "资源失效" else "${episode.resources.size} 个资源",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (missing) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletePikPakDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = { TextButton(onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
