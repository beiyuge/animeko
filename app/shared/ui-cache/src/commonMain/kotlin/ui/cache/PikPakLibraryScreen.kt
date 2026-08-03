/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.cache

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.layout.AniWindowInsets
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.torrent.offline.OfflineDownloadLibrary
import me.him188.ani.torrent.offline.OfflineDownloadLibraryEntry
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import me.him188.ani.torrent.offline.OfflineDownloadUnmatchedResource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PikPakLibraryViewModel : AbstractViewModel(), KoinComponent {
    private val library: OfflineDownloadLibrary by inject()
    private val settingsRepository: SettingsRepository by inject()

    val state = library.libraryState
    val enabled = settingsRepository.pikpakConfig.flow.map { it.enabled }
        .stateInBackground(false)

    init {
        backgroundScope.launch {
            enabled.filter { it }.first()
            library.sync()
        }
    }

    fun refresh() {
        backgroundScope.launch { library.sync() }
    }

    fun deleteEpisode(subjectId: String, episodeId: String) {
        backgroundScope.launch { library.deleteEpisode(subjectId, episodeId) }
    }

    fun deleteUnmatched(providerFileId: String) {
        backgroundScope.launch { library.deleteUnmatched(providerFileId) }
    }

    fun prepareUnmatchedRematch(providerFileId: String, subjectId: Int, episodeId: Int) {
        library.prepareUnmatchedRematch(providerFileId, subjectId.toString(), episodeId.toString())
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

@Composable
fun PikPakLibraryScreen(
    vm: PikPakLibraryViewModel,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    onRematchUnmatched: (subjectId: Int, episodeId: Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    PikPakLibraryScreen(
        state = state,
        onRefresh = vm::refresh,
        onPlay = onPlay,
        onDeleteEpisode = vm::deleteEpisode,
        onDeleteUnmatched = vm::deleteUnmatched,
        onRematchUnmatched = { providerFileId, subjectId, episodeId ->
            vm.prepareUnmatchedRematch(providerFileId, subjectId, episodeId)
            onRematchUnmatched(subjectId, episodeId)
        },
        modifier = modifier,
        windowInsets = windowInsets,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PikPakLibraryScreen(
    state: OfflineDownloadLibraryState,
    onRefresh: () -> Unit,
    onPlay: (subjectId: Int, episodeId: Int) -> Unit,
    onDeleteEpisode: (subjectId: String, episodeId: String) -> Unit,
    onDeleteUnmatched: (providerFileId: String) -> Unit,
    onRematchUnmatched: (providerFileId: String, subjectId: Int, episodeId: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
) {
    val rows = remember(state.entries) {
        state.entries.groupBy { it.subjectId to it.episodeId }.map { (_, entries) ->
            val latest = entries.maxBy(OfflineDownloadLibraryEntry::updatedAt)
            PikPakEpisodeRow(
                latest.subjectId,
                latest.subjectName.ifEmpty { "未知动漫" },
                latest.episodeId,
                latest.episodeNumber,
                latest.episodeTitle,
                entries,
            )
        }.sortedWith(compareBy(PikPakEpisodeRow::subjectName, PikPakEpisodeRow::episodeNumber))
    }
    var deleteEpisode by remember { mutableStateOf<PikPakEpisodeRow?>(null) }
    var deleteUnmatched by remember { mutableStateOf<OfflineDownloadUnmatchedResource?>(null) }
    var rematchUnmatched by remember { mutableStateOf<OfflineDownloadUnmatchedResource?>(null) }

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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.status == OfflineDownloadLibraryState.Status.Failed) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.ErrorOutline, null) },
                        headlineContent = { Text("PikPak 同步失败") },
                        supportingContent = { Text(state.errorMessage.orEmpty()) },
                    )
                }
            }
            if (rows.isEmpty() && state.unmatchedResources.isEmpty() &&
                state.status != OfflineDownloadLibraryState.Status.Syncing
            ) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Cloud, null) },
                        headlineContent = { Text("还没有缓存到 PikPak 的剧集") },
                        supportingContent = { Text("在选集页使用“缓存至 PikPak”添加资源") },
                    )
                }
            }
            rows.groupBy(PikPakEpisodeRow::subjectId).forEach { (_, episodes) ->
                item {
                    Text(
                        episodes.first().subjectName,
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(episodes, key = { "${it.subjectId}:${it.episodeId}" }) { episode ->
                    ListItem(
                        modifier = Modifier.testTag("PikPakEpisode:${episode.subjectId}:${episode.episodeId}")
                            .combinedClickable(
                            onClick = {
                                val subjectId = episode.subjectId.toIntOrNull()
                                val episodeId = episode.episodeId.toIntOrNull()
                                if (subjectId != null && episodeId != null) onPlay(subjectId, episodeId)
                            },
                            onLongClick = { deleteEpisode = episode },
                        ),
                        leadingContent = { Icon(Icons.Rounded.VideoLibrary, null) },
                        headlineContent = {
                            Text(
                                buildString {
                                    append(if (episode.episodeNumber.isBlank()) "未知集数" else "第${episode.episodeNumber}集")
                                    if (episode.episodeTitle.isNotBlank()) append(" · ${episode.episodeTitle}")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            val missing = episode.resources.all { entry ->
                                entry.entryId in state.remoteMissingEntryIds || runCatching {
                                    DataStoreJson.decodeFromString(DefaultMedia.serializer(), entry.sourcePayload)
                                }.isFailure
                            }
                            Text(
                                if (missing) "资源已失效 · 长按可删除"
                                else "可播放 · ${episode.resources.size} 个资源",
                                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
            if (state.unmatchedResources.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("未匹配资源", style = MaterialTheme.typography.titleMedium)
                        Text("点击可重新匹配，长按可删除", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(state.unmatchedResources, key = OfflineDownloadUnmatchedResource::providerFileId) { resource ->
                    ListItem(
                        modifier = Modifier.testTag("PikPakUnmatched:${resource.providerFileId}")
                            .combinedClickable(
                            onClick = { rematchUnmatched = resource },
                            onLongClick = { deleteUnmatched = resource },
                        ),
                        leadingContent = { Icon(Icons.Rounded.Cloud, null) },
                        headlineContent = { Text(resource.providerFileName, maxLines = 1) },
                        supportingContent = { Text("未匹配") },
                    )
                }
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
    deleteUnmatched?.let { resource ->
        DeletePikPakDialog(
            title = "删除未匹配资源？",
            description = resource.providerFileName,
            onDismiss = { deleteUnmatched = null },
            onConfirm = {
                onDeleteUnmatched(resource.providerFileId)
                deleteUnmatched = null
            },
        )
    }
    rematchUnmatched?.let { resource ->
        RematchUnmatchedDialog(
            resourceName = resource.providerFileName,
            onDismiss = { rematchUnmatched = null },
            onConfirm = { subjectId, episodeId ->
                onRematchUnmatched(resource.providerFileId, subjectId, episodeId)
                rematchUnmatched = null
            },
        )
    }
}

@Composable
private fun RematchUnmatchedDialog(
    resourceName: String,
    onDismiss: () -> Unit,
    onConfirm: (subjectId: Int, episodeId: Int) -> Unit,
) {
    var subjectId by remember { mutableStateOf("") }
    var episodeId by remember { mutableStateOf("") }
    val parsedSubjectId = subjectId.toIntOrNull()
    val parsedEpisodeId = episodeId.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新匹配资源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(resourceName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    subjectId,
                    { subjectId = it.filter(Char::isDigit) },
                    modifier = Modifier.testTag("PikPakRematchSubjectId"),
                    label = { Text("动漫 ID") },
                )
                OutlinedTextField(
                    episodeId,
                    { episodeId = it.filter(Char::isDigit) },
                    modifier = Modifier.testTag("PikPakRematchEpisodeId"),
                    label = { Text("剧集 ID") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsedSubjectId!!, parsedEpisodeId!!) },
                enabled = parsedSubjectId != null && parsedEpisodeId != null,
            ) { Text("选择资源") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
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
