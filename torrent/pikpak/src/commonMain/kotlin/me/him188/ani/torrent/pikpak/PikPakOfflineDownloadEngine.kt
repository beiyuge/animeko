/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.SessionStore
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.createUrlFile
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getOrCreateDeepFolderId
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listOfflineTasks
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.upload
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.him188.ani.torrent.offline.OfflineDownloadAuthException
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadNaming
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import me.him188.ani.torrent.offline.OfflineDownloadProgress
import me.him188.ani.torrent.offline.ResolvedMedia
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.digest
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Credentials + enabled flag for the PikPak engine. Emitted by the app-level
 * SettingsRepository wrapper so the engine doesn't know about DataStore.
 *
 * [password] may be empty when the session store already holds a usable
 * refresh token. The SDK will try the refresh path first and only fall back
 * to signin-with-password if refresh fails; this lets us stop persisting the
 * plaintext password once we've bootstrapped a session.
 */
data class PikPakCredentials(
    val username: String,
    val password: String,
) {
    val isValid: Boolean get() = username.isNotEmpty()
}

/**
 * The PikPak implementation of [OfflineDownloadEngine], built on top of the
 * `io.github.nihildigit:pikpak-kotlin` SDK. The SDK owns auth, token refresh,
 * captcha, rate limiting and retry; this class owns the offline-task
 * orchestration policy (when to poll, season-pack child pick, failure cleanup).
 *
 * Pattern: per-credentials [PikPakClient] cached in [clientEntry]. Recreated
 * when credentials change, so a re-login after the user edits settings happens
 * naturally through the StateFlow pre-warm side-effect.
 */
class PikPakOfflineDownloadEngine(
    scopedHttpClient: ScopedHttpClient,
    private val credentials: StateFlow<PikPakCredentials?>,
    private val scope: CoroutineScope,
    private val sessionStore: SessionStore,
    private val pollInterval: Duration = 2.seconds,
    private val resolveTimeout: Duration = 5.minutes,
    private val slotFolderName: String = "Animeko-Playing",
    private val metadataStorageDir: SystemPath? = null,
    /**
     * Supplies the user's desired slot-queue length at resolve time. Reading
     * the lambda per-resolve means the engine picks up setting changes without
     * needing a restart. Values `>= SLOT_QUEUE_UNLIMITED_SENTINEL` disable
     * eviction entirely.
     */
    private val slotQueueLength: () -> Int = { 1 },
) : OfflineDownloadEngine {

    companion object {
        /**
         * When [slotQueueLength] returns this (or more), the engine stops
         * evicting stale buckets — the user's PikPak drive becomes the limit.
         * Matches `PikPakConfig.SLOT_QUEUE_UNLIMITED` on the app side.
         */
        const val SLOT_QUEUE_UNLIMITED_SENTINEL: Int = 14
    }

    private val logger = logger<PikPakOfflineDownloadEngine>()

    override val id: String = "pikpak"
    override val displayName: String = "PikPak"

    override val isSupported: StateFlow<Boolean> = credentials
        .map { it != null && it.isValid }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = credentials.value?.isValid == true)

    private val _resolutionProgress = MutableStateFlow<OfflineDownloadProgress>(OfflineDownloadProgress.Idle)
    override val resolutionProgress: StateFlow<OfflineDownloadProgress> = _resolutionProgress

    // Borrow the underlying HttpClient for the lifetime of this engine. The
    // SDK needs a stable HttpClient to hand to its OkHttp/Darwin engine, and
    // ScopedHttpClient's borrowForever() is the documented escape hatch for
    // that exact scenario. The engine is a process-singleton (Koin `single`),
    // so no leak concern.
    @OptIn(UnsafeScopedHttpClientApi::class)
    private val sharedHttp: HttpClient = scopedHttpClient.borrowForever().client

    @Volatile
    private var clientEntry: Pair<PikPakCredentials, PikPakClient>? = null

    // Serialises [resolve] across concurrent callers. The engine is a single
    // per-process Koin instance and its slot lives on a shared PikPak folder
    // ("Animeko-Playing"), so two concurrent resolves would race on
    // listFiles → eviction → createBucket and could delete each other's
    // in-flight buckets. withTimeout stays on the outside so the resolveTimeout
    // budget covers queueing + work; a caller doesn't starve indefinitely
    // behind an earlier resolve.
    private val resolveMutex = Mutex()
    private val metadataMutex = Mutex()
    private val resolvedMediaCache = PikPakResolvedMediaCache()
    private var resolvedMediaCacheCredentials: PikPakCredentials? = null
    private val persistedMappings = mutableMapOf<String, PikPakFileMapping>()
    private val metadataJson = Json { prettyPrint = true }

    init {
        // Pre-warm the bearer token whenever valid credentials are available.
        // The user likely toggled PikPak on well before they actually hit play,
        // so doing the signin round-trip eagerly turns the first `resolve()`
        // call of a session into a task-submit + poll path (the bearer is
        // already cached).
        credentials
            .filter { it != null && it.isValid }
            .distinctUntilChanged()
            .onEach { creds ->
                scope.launch {
                    runCatchingCancellable { clientFor(creds!!).login() }
                        .onFailure { logger.warn(it) { "[pikpak] pre-warm signin failed (non-fatal)" } }
                }
            }
            .launchIn(scope)
    }

    override suspend fun resolve(
        uri: String,
        pickVideoFile: (candidateFilenames: List<String>) -> String?,
        naming: OfflineDownloadNaming?,
    ): ResolvedMedia =
        withTimeout(resolveTimeout) {
            resolveMutex.withLock {
            val creds = credentials.value
                ?: throw OfflineDownloadAuthException("PikPak not configured")
            if (!creds.isValid) {
                throw OfflineDownloadAuthException("PikPak credentials incomplete")
            }
            if (resolvedMediaCacheCredentials != creds) {
                resolvedMediaCache.clear()
                metadataMutex.withLock { persistedMappings.clear() }
                resolvedMediaCacheCredentials = creds
            }
            val client = clientFor(creds)
            // The SDK's endpoint functions don't auto-ensure a session; they
            // rely on the caller having populated one. The init-block pre-warm
            // is async and may not have finished by the time a user hits play,
            // so explicitly await login here. Cheap when already authed.
            _resolutionProgress.value = OfflineDownloadProgress.Authenticating
            client.login()

            val sourceKey = sourceKeyFor(uri)
            _resolutionProgress.value = OfflineDownloadProgress.CheckingCloudCache
            resolveCachedSource(client, sourceKey, pickVideoFile, naming, isCloudCacheHit = true)?.let { cached ->
                logger.info { "[pikpak] memory cache hit: bucket=$sourceKey file=${cached.providerFileId}" }
                return@withTimeout cached
            }

            // Server-side playback cache:
            //
            // The engine maintains one well-known folder in the user's PikPak
            // drive (default name "Animeko-Playing"). Each source has a stable
            // bucket, and the configured queue length controls how many source
            // buckets remain available for direct replay or episode switching.
            // Cleanup runs at the *start* of a later cache miss, not the end of
            // the current resolve, so the URL handed to the player remains valid.
            //
            // Benefits over fire-and-forget batchDelete:
            //   * State lives server-side. App crashes / restarts don't leak —
            //     the next resolve() drains the slot anyway.
            //   * No racing against libvlc's first GET (that was the root cause
            //     of every "VLC is unable to open MRL" we hit).
            //   * Season-pack siblings don't linger; the old pack folder is
            //     cascade-deleted before the new task is submitted.
            _resolutionProgress.value = OfflineDownloadProgress.CheckingCloudCache
            val slotId = client.getOrCreateDeepFolderId(parentId = "", path = slotFolderName)

            // Per-source sub-folder: every magnet / .torrent URL gets its own
            // namespace inside the slot. Its visible name is readable, while
            // its suffix retains the infohash (magnet) or URL hash (.torrent).
            // This is what lets us do a "cache hit"
            // safely — previously pickVideoFile alone was the hit predicate,
            // which mis-fired when an old cached episode and a new resolve's
            // target shared an episode number (e.g. slot held "Android ... 02"
            // and the user opened SPY×FAMILY E02; the filename's "02" pattern
            // matched and we handed back the wrong show).
            val topEntries = client.listFiles(parentId = slotId)
            val matchingBuckets = findSourceBuckets(topEntries, sourceKey)
            scheduleSourceBucketRenames(client, matchingBuckets, sourceKey, naming)

            // Slot-hit fast path: we only reuse when the bucket belongs to
            // *this exact source*. pickVideoFile still runs inside the bucket
            // to pick the right episode out of a cached season pack.
            if (matchingBuckets.isNotEmpty()) {
                val cached = matchingBuckets
                    .flatMap { collectSlotCandidates(client, it.id) }
                    .distinctBy(CachedPikPakFile::id)
                resolvedMediaCache.replace(sourceKey, cached)
                resolveCachedSource(client, sourceKey, pickVideoFile, naming, isCloudCacheHit = true)?.let { hit ->
                    logger.info { "[pikpak] slot hit: bucket=$sourceKey file=${hit.providerFileId}" }
                    return@withTimeout hit
                }
            }

            // Slot miss → evict to honor the user-configured queue length.
            val queueLen = slotQueueLength().coerceAtLeast(1)
            val toEvict = pickEvictions(topEntries, sourceKey, queueLen)
            if (toEvict.isNotEmpty()) {
                logger.info { "[pikpak] draining slot: keeping ${(queueLen - 1).coerceAtLeast(0)} newest, deleting ${toEvict.size}" }
                runCatchingCancellable { client.batchDelete(toEvict) }
                    .onFailure { logger.warn(it) { "[pikpak] slot drain failed (non-fatal)" } }
                val evictedSourceKeys = evictedSourceKeys(topEntries, toEvict)
                evictedSourceKeys.forEach(resolvedMediaCache::remove)
                scheduleLocalMappingCleanup(evictedSourceKeys)
            } else if (queueLen >= SLOT_QUEUE_UNLIMITED_SENTINEL) {
                logger.debug { "[pikpak] slot queue length = unlimited; skipping eviction" }
            }

            // Ensure this source's bucket exists, and submit the task *into
            // it*. PikPak lands everything from the task under parent_id, so
            // a season pack's pack-folder + children all sit inside the bucket.
            val bucketId = matchingBuckets.firstOrNull()?.id ?: run {
                _resolutionProgress.value = OfflineDownloadProgress.PreparingStorage
                client.getOrCreateDeepFolderId(
                    parentId = "",
                    path = "$slotFolderName/${readableBucketName(sourceKey, naming)}",
                )
            }

            // Track the latest root id we've seen — used for failure cleanup
            // only. On success, the task's result sits in the bucket for the
            // next resolve() to either hit or drain.
            var failureCleanupId: String? = null
            var cloudCacheHit = false

            try {
                // createUrlFile is also PikPak's only exact, provider-wide cache
                // probe for a magnet. The SDK has no infohash search endpoint;
                // filename search would risk matching the wrong show. An
                // InstantComplete result means PikPak already had the content,
                // while Queued is the point at which this becomes a real task.
                _resolutionProgress.value = OfflineDownloadProgress.CheckingCloudCache
                logger.info { "[pikpak] probe provider cache for ${uri.take(60)}... bucket=$sourceKey" }
                val bucketEntryIdsBeforeProbe = client.listFiles(parentId = bucketId)
                    .mapTo(mutableSetOf()) { it.id }
                val fileId = when (val result = client.createUrlFile(parentId = bucketId, url = uri)) {
                    is CreateUrlResult.Queued -> {
                        _resolutionProgress.value = OfflineDownloadProgress.Submitting
                        val task = result.task
                        logger.debug {
                            "[pikpak] submitted task id=${task.id} file_id=${task.fileId} file_name=${task.fileName}"
                        }
                        if (task.fileId.isNotEmpty()) failureCleanupId = task.fileId
                        _resolutionProgress.value = OfflineDownloadProgress.Waiting
                        awaitCompletion(client, task.id, task.fileId, onProgress = {
                            _resolutionProgress.value = OfflineDownloadProgress.Downloading(it)
                        }) { observed ->
                            failureCleanupId = observed
                        }
                    }
                    // PikPak recognised the URL's content as already cached on
                    // their side and dropped the file straight into our bucket
                    // without queuing a task. Prefer the entry that appeared
                    // after the atomic probe; the newest-entry fallback covers
                    // providers that reuse an existing id while refreshing it.
                    CreateUrlResult.InstantComplete -> {
                        cloudCacheHit = true
                        logger.info {
                            "[pikpak] instant-complete for bucket=$sourceKey; recovering landed file from bucket listing"
                        }
                        val landedEntries = client.listFiles(parentId = bucketId)
                        val landed = selectInstantCompleteEntry(landedEntries, bucketEntryIdsBeforeProbe)
                            ?: throw OfflineDownloadRejectedException(
                                "PikPak reported instant-complete but bucket $sourceKey is empty after submission",
                            )
                        landed.id
                    }
                }
                failureCleanupId = fileId

                _resolutionProgress.value = progressWhileSelectingFile(cloudCacheHit)
                val rootInfo = client.getFile(fileId)
                if (rootInfo.id.isNotEmpty()) failureCleanupId = rootInfo.id

                val candidates = if (rootInfo.kind == FileKind.FOLDER) {
                    // Season pack: keep every descendant file id in memory so
                    // switching episode only refreshes the selected child's URL.
                    collectPikPakFileCandidates(rootInfo.id) { parentId ->
                        client.listFiles(parentId = parentId)
                    }
                } else {
                    listOf(CachedPikPakFile(rootInfo.id, rootInfo.name))
                }
                resolvedMediaCache.replace(sourceKey, candidates)

                resolveCachedSource(client, sourceKey, pickVideoFile, naming, isCloudCacheHit = cloudCacheHit)
                    ?: throw OfflineDownloadRejectedException(
                        "PikPak resource $sourceKey contains no cached playable file " +
                                "(files: ${candidates.joinToString(limit = 10) { it.name }})",
                    )
                // No cleanup on success; next resolve drains the slot.
            } catch (e: Throwable) {
                // Failure path: best-effort cleanup inside the slot so a
                // hung-up task doesn't pile up on retries.
                scheduleCleanup(client, failureCleanupId)
                throw e
            }
            }
        }

    /**
     * Flattens everything currently in the slot into a `(id, name)` list of
     * video-file candidates. Handles both layouts the slot can be in:
     *   * A single file (from a past single-file torrent resolve)
     *   * A folder with children (from a past season-pack resolve)
     * PikPak usually flattens a torrent's directory structure, but some
     * provider-side cache hits preserve nested season/disc folders. Traverse
     * the full subtree so a partial candidate list cannot select a wrong episode.
     */
    private suspend fun collectSlotCandidates(
        client: PikPakClient,
        slotId: String,
    ): List<CachedPikPakFile> {
        return collectPikPakFileCandidates(slotId) { parentId ->
            client.listFiles(parentId = parentId)
        }
    }

    private suspend fun resolveCachedSource(
        client: PikPakClient,
        sourceKey: String,
        pickVideoFile: (candidateFilenames: List<String>) -> String?,
        naming: OfflineDownloadNaming?,
        isCloudCacheHit: Boolean,
    ): ResolvedMedia? = resolvedMediaCache.resolve(sourceKey, pickVideoFile) { cachedFile ->
        _resolutionProgress.value = progressWhileSelectingFile(isCloudCacheHit)
        var detail = try {
            client.getFile(cachedFile.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.debug { "[pikpak] cached file ${cachedFile.id} is unavailable: ${e.message}" }
            return@resolve null
        }
        if (detail.trashed || detail.id.isEmpty()) return@resolve null
        try {
            _resolutionProgress.value = if (isCloudCacheHit) {
                OfflineDownloadProgress.ResolvingCachedStreamUrl
            } else {
                OfflineDownloadProgress.ResolvingStreamUrl
            }
            buildResolvedMedia(detail).copy(isCloudCacheHit = isCloudCacheHit).also {
                scheduleReadableFileMetadata(client, sourceKey, detail, naming)
                _resolutionProgress.value = OfflineDownloadProgress.Ready
            }
        } catch (e: OfflineDownloadRejectedException) {
            logger.debug { "[pikpak] cached file ${cachedFile.id} is not playable: ${e.message}" }
            null
        }
    }

    private fun scheduleSourceBucketRenames(
        client: PikPakClient,
        buckets: List<FileStat>,
        sourceKey: String,
        naming: OfflineDownloadNaming?,
    ) {
        buckets.forEachIndexed { index, bucket ->
            val target = readableBucketName(sourceKey, naming, duplicateIndex = index)
            if (bucket.name == target) return@forEachIndexed
            scope.launch {
                runCatchingCancellable { client.rename(bucket.id, target) }
                    .onFailure { logger.warn(it) { "[pikpak] could not rename bucket ${bucket.id} to $target" } }
            }
        }
    }

    /**
     * Mapping and display-name maintenance must never delay playback. The background job writes the
     * mapping first and renames the video only after that succeeds, so an upload failure cannot lose
     * the provider's original filename.
     */
    private fun scheduleReadableFileMetadata(
        client: PikPakClient,
        sourceKey: String,
        detail: FileDetail,
        naming: OfflineDownloadNaming?,
    ) {
        val storageDir = metadataDirForCurrentAccount() ?: return
        if (naming == null || detail.parentId.isEmpty() || detail.id.isEmpty()) return
        scope.launch {
            try {
                metadataMutex.withLock {
                    val mappingName = mappingFileName(detail.id)
                    val localFile = storageDir.resolve(mappingName)
                    val existing = client.listFiles(parentId = detail.parentId)
                        .filter { it.isFile && it.name == mappingName }
                    var previous = loadLocalMapping(localFile, detail.id)
                    if (previous == null) {
                        for (entry in existing) {
                            previous = loadCloudMapping(client, entry, detail.id)
                            if (previous != null) break
                        }
                    }

                    // An existing cloud mapping may be the only remaining record of the original
                    // provider filename after app data was cleared. Do not overwrite it with the
                    // already-renamed visible name when there is no trusted local copy to merge.
                    if (previous == null && existing.isNotEmpty()) return@withLock

                    val mapping = updatedFileMapping(
                        sourceKey = sourceKey,
                        naming = naming,
                        providerFileId = detail.id,
                        observedFileName = detail.name,
                        previous = previous,
                    )
                    storageDir.createDirectories()
                    localFile.writeText(metadataJson.encodeToString(mapping))

                    persistMappingBeforeRename(
                        mapping = mapping,
                        observedFileName = detail.name,
                        persist = {
                            if (persistedMappings[detail.id] != mapping) {
                                if (existing.isNotEmpty()) {
                                    client.batchDelete(existing.map(FileStat::id))
                                }
                                client.upload(detail.parentId, localFile.path)
                                persistedMappings[detail.id] = mapping
                            }
                        },
                        rename = { targetName ->
                            client.rename(detail.id, targetName)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "[pikpak] could not persist mapping or rename file ${detail.id}" }
            }
        }
    }

    private fun loadLocalMapping(localFile: SystemPath, providerFileId: String): PikPakFileMapping? {
        if (!localFile.exists()) return null
        return runCatching {
            metadataJson.decodeFromString<PikPakFileMapping>(localFile.readText())
                .takeIf { it.providerFileId == providerFileId }
        }.onFailure {
            logger.warn(it) { "[pikpak] could not read local mapping for file $providerFileId" }
        }.getOrNull()
    }

    private suspend fun loadCloudMapping(
        client: PikPakClient,
        entry: FileStat,
        providerFileId: String,
    ): PikPakFileMapping? = try {
        val detail = client.getFile(entry.id)
        val url = detail.downloadUrl ?: detail.webContentLink.takeIf(String::isNotEmpty) ?: return null
        metadataJson.decodeFromString<PikPakFileMapping>(sharedHttp.get(url).bodyAsText())
            .takeIf { it.providerFileId == providerFileId }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn(e) { "[pikpak] could not read cloud mapping ${entry.id} for file $providerFileId" }
        null
    }

    private fun scheduleLocalMappingCleanup(sourceKeys: Collection<String>) {
        val storageDir = metadataDirForCurrentAccount() ?: return
        if (sourceKeys.isEmpty()) return
        scope.launch {
            metadataMutex.withLock {
                if (!storageDir.exists()) return@withLock
                for (path in storageDir.list()) {
                    val localFile = path.inSystem
                    val mapping = runCatching {
                        metadataJson.decodeFromString<PikPakFileMapping>(localFile.readText())
                    }.getOrNull() ?: continue
                    if (mapping.sourceKey !in sourceKeys) continue
                    runCatching { localFile.delete() }
                        .onFailure { logger.warn(it) { "[pikpak] could not delete local mapping $localFile" } }
                    persistedMappings.remove(mapping.providerFileId)
                }
            }
        }
    }

    private fun metadataDirForCurrentAccount(): SystemPath? {
        val account = resolvedMediaCacheCredentials?.username ?: return null
        return metadataStorageDir?.resolve("account-${sha256HexShort(account)}")
    }

    private fun scheduleCleanup(client: PikPakClient, fileId: String?) {
        if (fileId.isNullOrEmpty()) return
        scope.launch {
            runCatchingCancellable { client.batchDelete(listOf(fileId)) }
                .onFailure { logger.warn(it) { "[pikpak] cleanup batchDelete failed id=$fileId" } }
        }
    }

    private fun clientFor(creds: PikPakCredentials): PikPakClient {
        val current = clientEntry
        if (current != null && current.first == creds) return current.second
        val fresh = PikPakClient(
            account = creds.username,
            password = creds.password,
            sessionStore = sessionStore,
            httpClient = sharedHttp,
        )
        clientEntry = creds to fresh
        return fresh
    }

    /**
     * Poll the offline-task list until our task leaves the active phases. The
     * SDK intentionally exposes no built-in polling loop — the "task
     * disappeared from the RUNNING+ERROR+PENDING filter" heuristic below is
     * an app-layer policy that doesn't belong in the SDK.
     */
    private suspend fun awaitCompletion(
        client: PikPakClient,
        taskId: String,
        initialFileId: String,
        onProgress: (Float?) -> Unit = {},
        onFileIdObserved: (String) -> Unit = {},
    ): String {
        var fileId = initialFileId
        var attempt = 0
        // Include PENDING so a freshly queued task doesn't look "already gone"
        // and trip the "Task completed but no file_id" branch below.
        val activePhases = "PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR"
        while (true) {
            delay(pollInterval)
            attempt++
            val list = client.listOfflineTasks(phaseFilter = activePhases)
            val match = list.tasks.firstOrNull { it.id == taskId }
            if (match == null) {
                // Task left the PENDING/RUNNING/ERROR filter => completed.
                logger.info { "[pikpak] task $taskId completed after $attempt polls" }
                return fileId.ifEmpty {
                    throw OfflineDownloadRejectedException(
                        "Task completed but no file_id was observed; re-submit may be needed",
                    )
                }
            }
            if (match.fileId.isNotEmpty() && match.fileId != fileId) {
                fileId = match.fileId
                onFileIdObserved(fileId)
            }
            onProgress(match.progress.toProgressFraction())
            if (match.phase == "PHASE_TYPE_ERROR") {
                throw OfflineDownloadRejectedException(
                    "PikPak task failed: phase=${match.phase} message=${match.message}",
                )
            }
            logger.debug {
                "[pikpak] poll $attempt: phase=${match.phase} progress=${match.progress} file_id=$fileId"
            }
        }
    }

}

private fun Any?.toProgressFraction(): Float? {
    val raw = toString().trim().removeSuffix("%").toFloatOrNull() ?: return null
    return (if (raw > 1f) raw / 100f else raw).coerceIn(0f, 1f)
}

/**
 * Maps a PikPak [FileDetail] to the [ResolvedMedia] we hand to mediamp.
 * Prefers `links.octet-stream` over the legacy `web_content_link`; throws
 * [OfflineDownloadRejectedException] when neither is present (a file still
 * being processed server-side, or an access-denied record). Exposed
 * `internal` so commonTest can exercise URL selection without spinning up
 * the engine.
 */
internal fun buildResolvedMedia(file: FileDetail): ResolvedMedia {
    val streamUrl = file.downloadUrl
        ?: file.webContentLink.takeIf { it.isNotEmpty() }
        ?: throw OfflineDownloadRejectedException(
            "PikPak file has no playable link (file_id=${file.id})",
        )

    val expiresAt: Instant? = file.links.octetStream.expire
        .takeIf { it.isNotEmpty() }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    return ResolvedMedia(
        streamUrl = streamUrl,
        expiresAt = expiresAt,
        fileName = file.name.takeIf { it.isNotEmpty() },
        fileSize = file.size.toLongOrNull(),
        contentType = file.mimeType.takeIf { it.isNotEmpty() },
        providerFileId = file.id.takeIf { it.isNotEmpty() },
    )
}

/**
 * Deterministic cache key for a resolve source. Used as a sub-folder name
 * under the engine's working folder, so it (a) has to be a valid PikPak
 * filename and (b) must not collide across distinct sources — a collision
 * would let one source's slot reuse another's cached files.
 *
 * Rules:
 *  - Magnet: take the infohash after `xt=urn:btih:` and canonicalise it.
 *    Magnet URIs can carry the infohash as 40-char hex, 32-char RFC-4648
 *    base32, or 64-char hex (BEP-52 v2). All three are normalised to
 *    uppercase hex so two magnets pointing at the same torrent — even
 *    through different encodings — map to the same bucket.
 *  - HTTP `.torrent` URL (or a magnet with a missing/unrecognised infohash):
 *    SHA-256 of the URL, take the first 16 hex chars (64-bit). The `h-`
 *    prefix avoids clashing with hex-encoded infohashes. `String.hashCode()`
 *    used to live here; 32-bit is not wide enough to rule out a wrong-bucket
 *    hit at scale — SHA-256 eliminates the risk.
 *
 * Exposed `internal` so commonTest can exercise these rules without spinning
 * up the engine.
 */
internal fun sourceKeyFor(uri: String): String {
    val rawInfoHash = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        .find(uri)?.groupValues?.get(1)
    val canonical = rawInfoHash?.let { canonicalizeBtih(it) }
    if (canonical != null) return canonical
    return "h-" + sha256HexShort(uri)
}

/** Returns every durable bucket for this exact source, including legacy case variants. */
internal fun findSourceBuckets(topEntries: List<FileStat>, sourceKey: String): List<FileStat> =
    topEntries.filter {
        it.isFolder && (
                it.name.equals(sourceKey, ignoreCase = true) ||
                        bucketSourceKey(it.name)?.equals(sourceKey, ignoreCase = true) == true
                )
    }

private const val BUCKET_KEY_PREFIX = "【Animeko-"
private const val BUCKET_KEY_SUFFIX = "】"
private const val MAPPING_FILE_PREFIX = "Animeko映射-"

internal fun readableBucketName(
    sourceKey: String,
    naming: OfflineDownloadNaming?,
    duplicateIndex: Int = 0,
): String {
    val subject = sanitizePikPakName(naming?.subjectName, fallback = "动画资源", maxLength = 80)
    val duplicate = if (duplicateIndex == 0) "" else "-${duplicateIndex + 1}"
    return "$subject$duplicate$BUCKET_KEY_PREFIX$sourceKey$BUCKET_KEY_SUFFIX"
}

internal fun bucketSourceKey(name: String): String? {
    val markerStart = name.lastIndexOf(BUCKET_KEY_PREFIX)
    if (markerStart >= 0 && name.endsWith(BUCKET_KEY_SUFFIX)) {
        return name.substring(
            startIndex = markerStart + BUCKET_KEY_PREFIX.length,
            endIndex = name.length - BUCKET_KEY_SUFFIX.length,
        ).takeIf(String::isNotEmpty)
    }
    return name.takeIf { legacy ->
        ((legacy.length == 40 || legacy.length == 64) &&
                legacy.all { it.uppercaseChar() in HEX_ALPHABET }) ||
                legacy.matches(Regex("h-[0-9a-fA-F]{16}"))
    }
}

internal fun readableVideoFileName(
    originalName: String,
    providerFileId: String,
    naming: OfflineDownloadNaming,
): String {
    val episodeNumber = sanitizePikPakName(naming.episodeNumber, fallback = "", maxLength = 20)
    val episodeLabel = when {
        episodeNumber.isEmpty() -> "动画视频"
        episodeNumber.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) -> "第${episodeNumber}集"
        else -> "特别篇-$episodeNumber"
    }
    val title = sanitizePikPakName(naming.episodeTitle, fallback = "", maxLength = 80)
        .takeIf { it.isNotEmpty() && !it.equals(episodeNumber, ignoreCase = true) }
    val idSuffix = safeFileId(providerFileId, maxLength = 8)
        .takeIf(String::isNotEmpty)
        ?.let { "【$it】" }
        .orEmpty()
    val extension = originalName.substringAfterLast('.', "")
        .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        ?.let { ".$it" }
        .orEmpty()
    return buildList {
        add(episodeLabel)
        if (title != null) add(title)
    }.joinToString(" - ") + idSuffix + extension
}

private fun sanitizePikPakName(value: String?, fallback: String, maxLength: Int): String {
    val sanitized = value.orEmpty()
        .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(maxLength)
        .trim(' ', '.')
    return sanitized.ifEmpty { fallback }
}

internal fun mappingFileName(providerFileId: String): String =
    "$MAPPING_FILE_PREFIX${safeFileId(providerFileId, maxLength = 12)}.json"

private fun safeFileId(value: String, maxLength: Int): String =
    value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(maxLength)

private fun isAnimekoMetadataFile(name: String): Boolean =
    name.startsWith(MAPPING_FILE_PREFIX) && name.endsWith(".json", ignoreCase = true)

@Serializable
internal data class PikPakFileMapping(
    val version: Int = 1,
    val sourceKey: String,
    val subjectName: String?,
    val episodeTitle: String?,
    val episodeNumber: String?,
    val providerFileId: String,
    val originalFileName: String,
    val fileName: String,
)

internal fun updatedFileMapping(
    sourceKey: String,
    naming: OfflineDownloadNaming,
    providerFileId: String,
    observedFileName: String,
    previous: PikPakFileMapping?,
): PikPakFileMapping {
    val originalFileName = previous?.originalFileName ?: observedFileName
    return PikPakFileMapping(
        sourceKey = sourceKey,
        subjectName = naming.subjectName,
        episodeTitle = naming.episodeTitle,
        episodeNumber = naming.episodeNumber,
        providerFileId = providerFileId,
        originalFileName = originalFileName,
        fileName = readableVideoFileName(originalFileName, providerFileId, naming),
    )
}

internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

internal suspend fun persistMappingBeforeRename(
    mapping: PikPakFileMapping,
    observedFileName: String,
    persist: suspend () -> Unit,
    rename: suspend (targetName: String) -> Unit,
) {
    persist()
    if (observedFileName != mapping.fileName) {
        rename(mapping.fileName)
    }
}

/** Selects the file produced by an InstantComplete probe without confusing it with an older bucket entry. */
internal fun selectInstantCompleteEntry(entries: List<FileStat>, idsBeforeProbe: Set<String>): FileStat? =
    entries.filterNot { isAnimekoMetadataFile(it.name) }.let { contentEntries ->
        contentEntries.filterNot { it.id in idsBeforeProbe }.maxByOrNull { it.createdTime }
            ?: contentEntries.maxByOrNull { it.createdTime }
    }

/**
 * Cache-hit paths still validate the selected file id before refreshing its signed URL. Keep that
 * validation under the cache-checking phase so the UI cannot misleadingly report normal parsing
 * before it announces the cloud-cache hit.
 */
internal fun progressWhileSelectingFile(isCloudCacheHit: Boolean): OfflineDownloadProgress =
    if (isCloudCacheHit) {
        OfflineDownloadProgress.CheckingCloudCache
    } else {
        OfflineDownloadProgress.SelectingFile
    }

/** Collects every file below [rootId], guarding against duplicate ids and malformed folder cycles. */
internal suspend fun collectPikPakFileCandidates(
    rootId: String,
    listChildren: suspend (parentId: String) -> List<FileStat>,
): List<CachedPikPakFile> {
    val pendingFolders = ArrayDeque<String>()
    val visitedFolders = mutableSetOf<String>()
    val filesById = linkedMapOf<String, CachedPikPakFile>()
    pendingFolders.addLast(rootId)

    while (pendingFolders.isNotEmpty()) {
        val parentId = pendingFolders.removeFirst()
        if (!visitedFolders.add(parentId)) continue

        for (entry in listChildren(parentId)) {
            when {
                entry.isFolder && entry.id.isNotEmpty() -> pendingFolders.addLast(entry.id)
                entry.isFile && entry.id.isNotEmpty() && !isAnimekoMetadataFile(entry.name) -> {
                    filesById.putIfAbsent(entry.id, CachedPikPakFile(entry.id, entry.name))
                }
            }
        }
    }

    return filesById.values.toList()
}

private fun canonicalizeBtih(raw: String): String? {
    val upper = raw.uppercase()
    return when {
        // BEP-9 (SHA-1) infohash — already canonical.
        upper.length == 40 && upper.all { it in HEX_ALPHABET } -> upper
        // BEP-52 (SHA-256) infohash for v2 torrents.
        upper.length == 64 && upper.all { it in HEX_ALPHABET } -> upper
        // RFC-4648 base32, no padding — the other form BEP-9 allows.
        upper.length == 32 && upper.all { it in BASE32_ALPHABET } -> base32ToHexUpper(upper)
        else -> null
    }
}

private const val HEX_ALPHABET = "0123456789ABCDEF"
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32ToHexUpper(s: String): String {
    // 32 base32 chars × 5 bits = 160 bits = 20 bytes = 40 hex chars.
    val out = StringBuilder(s.length * 5 / 4)
    var buffer = 0
    var bitsInBuffer = 0
    for (c in s) {
        val v = BASE32_ALPHABET.indexOf(c)
        buffer = (buffer shl 5) or v
        bitsInBuffer += 5
        while (bitsInBuffer >= 4) {
            bitsInBuffer -= 4
            val nibble = (buffer shr bitsInBuffer) and 0xF
            out.append(HEX_ALPHABET[nibble])
        }
    }
    return out.toString()
}

private fun sha256HexShort(input: String): String {
    // 64-bit (16 hex chars) is overkill for a user's slot namespace but keeps
    // the key short enough to read in logs. Lowercase for easy visual
    // distinction from the uppercase-hex infohash bucket names above.
    val digest = input.encodeToByteString().digest(DigestAlgorithm.SHA256)
    val hexLower = "0123456789abcdef"
    val sb = StringBuilder(16)
    for (i in 0 until 8) {
        val b = digest[i].toInt() and 0xFF
        sb.append(hexLower[b ushr 4])
        sb.append(hexLower[b and 0x0F])
    }
    return sb.toString()
}

/**
 * Decides which top-level slot entries to [batchDelete][io.github.nihildigit.pikpak.batchDelete]
 * based on the user-configured queue length. Pure function — no network, no
 * mutation — so commonTest can cover the policy edges (single-slot, N-slot,
 * unlimited, entry ordering) without touching PikPakClient.
 *
 * Semantics:
 *  - `queueLength >= SLOT_QUEUE_UNLIMITED_SENTINEL` → return empty (no eviction).
 *  - Otherwise: keep the current-source bucket (never evicted, even if it's
 *    the oldest) plus the `(queueLength - 1)` newest of the remaining
 *    buckets; evict everything else.
 *  - Bucket age is read from [FileStat.createdTime], which PikPak returns as
 *    ISO-8601 strings — lexicographic sort matches chronological order.
 *
 * @return the `id`s of entries to delete. May be empty.
 */
internal fun pickEvictions(
    topEntries: List<FileStat>,
    currentSourceKey: String,
    queueLength: Int,
): List<String> {
    if (queueLength >= PikPakOfflineDownloadEngine.SLOT_QUEUE_UNLIMITED_SENTINEL) return emptyList()
    val others = topEntries.filter {
        !it.name.equals(currentSourceKey, ignoreCase = true) &&
                bucketSourceKey(it.name)?.equals(currentSourceKey, ignoreCase = true) != true
    }
    val keepCount = (queueLength - 1).coerceAtLeast(0)
    return others
        .sortedByDescending { it.createdTime }
        .drop(keepCount)
        .map { it.id }
}

internal fun evictedSourceKeys(topEntries: List<FileStat>, evictedIds: Collection<String>): List<String> =
    topEntries.asSequence()
        .filter { it.isFolder && it.id in evictedIds }
        .mapNotNull { bucketSourceKey(it.name) }
        .distinct()
        .toList()
