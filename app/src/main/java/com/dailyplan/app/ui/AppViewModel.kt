package com.dailyplan.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplan.app.data.BatchOperationResult
import com.dailyplan.app.data.CategoryEntity
import com.dailyplan.app.data.OccurrenceEntity
import com.dailyplan.app.data.PlanExportOptions
import com.dailyplan.app.data.PlanImportPreview
import com.dailyplan.app.data.TaskEntity
import com.dailyplan.app.data.TaskRepository
import com.dailyplan.app.data.PlanTransferCodec
import com.dailyplan.app.BuildConfig
import com.dailyplan.app.update.GithubUpdateClient
import com.dailyplan.app.update.ReleaseManifestParser
import com.dailyplan.app.update.UpdateRelease
import com.dailyplan.app.sync.CloudSyncManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.LocalDate

data class PlanState(
    val tasks: List<TaskEntity> = emptyList(),
    val occurrences: List<OccurrenceEntity> = emptyList(),
    val overdueOnce: List<OccurrenceEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
)

data class TransferUiState(
    val isWorking: Boolean = false,
    val preview: PlanImportPreview? = null,
    val message: String? = null,
    val fileName: String = "",
)

data class OperationFeedback(val message: String, val undoAvailable: Boolean = false)

data class UpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val release: UpdateRelease? = null,
    val downloadedApkPath: String? = null,
    val lastCheckTime: Long = 0L,
    val automaticChecksEnabled: Boolean = true,
    val message: String? = null,
)

data class AccountUiState(
    val configured: Boolean = false,
    val email: String? = null,
    val isWorking: Boolean = false,
    val syncMessage: String = "",
    val message: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TaskRepository(application)
    private val accountPreferences = application.getSharedPreferences("cloud_account", Application.MODE_PRIVATE)
    private var firebaseAuth: FirebaseAuth? = null
    private var cloudSyncManager: CloudSyncManager? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var blockedAccountMessage: String? = null
    private val mutableAccountState = MutableStateFlow(AccountUiState(configured = BuildConfig.FIREBASE_CONFIGURED))
    val accountState: StateFlow<AccountUiState> = mutableAccountState.asStateFlow()
    private val mutableState = MutableStateFlow(PlanState())
    val state: StateFlow<PlanState> = mutableState
    private val updateClient = GithubUpdateClient(application)
    private val updatePreferences = application.getSharedPreferences("update_settings", Application.MODE_PRIVATE)
    private val mutableUpdateState = MutableStateFlow(
        UpdateUiState(
            lastCheckTime = updatePreferences.getLong(KEY_LAST_CHECK, 0L),
            automaticChecksEnabled = updatePreferences.getBoolean(KEY_AUTO_CHECK, true),
        ),
    )
    val updateState: StateFlow<UpdateUiState> = mutableUpdateState
    private val mutableTransferState = MutableStateFlow(TransferUiState())
    val transferState: StateFlow<TransferUiState> = mutableTransferState.asStateFlow()
    private val mutableOperationMessage = MutableStateFlow<OperationFeedback?>(null)
    val operationMessage: StateFlow<OperationFeedback?> = mutableOperationMessage.asStateFlow()
    private var observationJob: Job? = null
    private var pendingBatchUndo: (suspend () -> Unit)? = null
    private var rangeStart = LocalDate.now().minusDays(90)
    private var rangeEnd = LocalDate.now().plusMonths(13)

    init {
        configureCloudAccount(application)
        reload()
        checkForUpdate(manual = false)
    }

    private fun configureCloudAccount(application: Application) {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            mutableAccountState.value = AccountUiState(
                configured = false,
                message = "需要先在 app 文件夹放入 Firebase 提供的 google-services.json。",
            )
            return
        }
        runCatching {
            FirebaseApp.initializeApp(application)
            FirebaseAuth.getInstance()
        }.onSuccess { auth ->
            firebaseAuth = auth
            cloudSyncManager = CloudSyncManager(repository)
            val listener = FirebaseAuth.AuthStateListener { currentAuth ->
                val user = currentAuth.currentUser
                if (user == null) {
                    cloudSyncManager?.stop()
                    mutableAccountState.value = AccountUiState(
                        configured = true,
                        message = blockedAccountMessage,
                    )
                    blockedAccountMessage = null
                } else {
                    val ownerUid = accountPreferences.getString(KEY_CLOUD_UID, null)
                    if (ownerUid != null && ownerUid != user.uid) {
                        blockedAccountMessage = "此设备的本机数据已绑定到另一个账号。为避免把任务写入错误账号，请先使用原账号；更换账号前需要先完成数据迁移。"
                        mutableAccountState.value = AccountUiState(configured = true, message = blockedAccountMessage)
                        currentAuth.signOut()
                    } else {
                        if (ownerUid == null) accountPreferences.edit().putString(KEY_CLOUD_UID, user.uid).apply()
                        mutableAccountState.value = AccountUiState(
                            configured = true,
                            email = user.email,
                            syncMessage = "正在同步…",
                        )
                        cloudSyncManager?.start(user.uid) { status ->
                            mutableAccountState.value = mutableAccountState.value.copy(syncMessage = status)
                        }
                    }
                }
            }
            authStateListener = listener
            auth.addAuthStateListener(listener)
        }.onFailure { error ->
            mutableAccountState.value = AccountUiState(
                configured = true,
                message = "Firebase 初始化失败：${error.message ?: "请检查 Firebase 配置文件"}",
            )
        }
    }

    fun signIn(email: String, password: String, createAccount: Boolean) {
        val auth = firebaseAuth ?: run {
            mutableAccountState.value = mutableAccountState.value.copy(message = "Firebase 尚未配置。")
            return
        }
        if (mutableAccountState.value.isWorking) return
        mutableAccountState.value = mutableAccountState.value.copy(isWorking = true, message = null)
        viewModelScope.launch {
            runCatching {
                if (createAccount) auth.createUserWithEmailAndPassword(email.trim(), password).await()
                else auth.signInWithEmailAndPassword(email.trim(), password).await()
            }.onFailure { error ->
                mutableAccountState.value = mutableAccountState.value.copy(message = authErrorMessage(error))
            }
            mutableAccountState.value = mutableAccountState.value.copy(isWorking = false)
        }
    }

    fun resetPassword(email: String) {
        val auth = firebaseAuth ?: return
        if (email.isBlank()) {
            mutableAccountState.value = mutableAccountState.value.copy(message = "请先填写邮箱地址。")
            return
        }
        viewModelScope.launch {
            runCatching { auth.sendPasswordResetEmail(email.trim()).await() }
                .onSuccess { mutableAccountState.value = mutableAccountState.value.copy(message = "密码重置邮件已发送。") }
                .onFailure { mutableAccountState.value = mutableAccountState.value.copy(message = authErrorMessage(it)) }
        }
    }

    fun signOut() {
        val auth = firebaseAuth ?: return
        viewModelScope.launch {
            mutableAccountState.value = mutableAccountState.value.copy(isWorking = true, message = null)
            val pendingWritesFinished = withTimeoutOrNull(5_000) {
                FirebaseFirestore.getInstance().waitForPendingWrites().await()
                true
            } ?: false
            if (!pendingWritesFinished) {
                mutableAccountState.value = mutableAccountState.value.copy(
                    isWorking = false,
                    message = "仍有数据等待上传。请连接网络并等同步完成后再退出登录。",
                )
                return@launch
            }
            cloudSyncManager?.stop()
            auth.signOut()
            mutableAccountState.value = mutableAccountState.value.copy(isWorking = false)
        }
    }

    private fun authErrorMessage(error: Throwable): String = when ((error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode) {
        "ERROR_INVALID_EMAIL" -> "邮箱格式不正确。"
        "ERROR_EMAIL_ALREADY_IN_USE" -> "这个邮箱已经注册过了。"
        "ERROR_WEAK_PASSWORD" -> "密码至少需要 6 位。"
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> "邮箱或密码不正确。"
        "ERROR_TOO_MANY_REQUESTS" -> "尝试次数过多，请稍后再试。"
        else -> error.message ?: "登录失败，请检查网络后重试。"
    }

    override fun onCleared() {
        authStateListener?.let { firebaseAuth?.removeAuthStateListener(it) }
        cloudSyncManager?.close()
        super.onCleared()
    }

    fun checkForUpdate(manual: Boolean = true) {
        val current = mutableUpdateState.value
        if (current.isChecking) return
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!current.automaticChecksEnabled) return
            val lastAutomaticCheck = updatePreferences.getLong(KEY_LAST_AUTO_CHECK, 0L)
            if (!ReleaseManifestParser.shouldCheckAutomatically(lastAutomaticCheck, now)) return
            updatePreferences.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply()
        }
        updatePreferences.edit().putLong(KEY_LAST_CHECK, now).apply()
        mutableUpdateState.value = current.copy(isChecking = true, lastCheckTime = now, message = null)
        viewModelScope.launch {
            try {
                val latest = updateClient.latestRelease()
                val newer = ReleaseManifestParser.isNewer(latest.versionCode, BuildConfig.VERSION_CODE.toLong())
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    isChecking = false,
                    release = latest.takeIf { newer },
                    downloadedApkPath = null,
                    downloadProgress = 0,
                    message = if (newer) null else "当前已是最新版本",
                )
            } catch (error: Exception) {
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    isChecking = false,
                    release = null,
                    message = "检查失败：${error.message ?: "网络暂不可用"}",
                )
            }
        }
    }

    fun setAutomaticChecksEnabled(enabled: Boolean) {
        updatePreferences.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        mutableUpdateState.value = mutableUpdateState.value.copy(automaticChecksEnabled = enabled)
        if (enabled) checkForUpdate(manual = false)
    }

    fun downloadUpdate() {
        val release = mutableUpdateState.value.release ?: return
        if (mutableUpdateState.value.isDownloading) return
        mutableUpdateState.value = mutableUpdateState.value.copy(
            isDownloading = true,
            downloadProgress = 0,
            downloadedApkPath = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                val apk = updateClient.downloadAndVerify(release) { progress ->
                    mutableUpdateState.value = mutableUpdateState.value.copy(downloadProgress = progress)
                }
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    isDownloading = false,
                    downloadProgress = 100,
                    downloadedApkPath = apk.absolutePath,
                    message = "安装包已校验，可以安装",
                )
            } catch (error: Exception) {
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    isDownloading = false,
                    downloadedApkPath = null,
                    message = error.message ?: "下载失败，请重试",
                )
            }
        }
    }

    fun dismissUpdate() {
        mutableUpdateState.value = mutableUpdateState.value.copy(release = null, message = null)
    }

    fun loadAround(day: LocalDate) {
        rangeStart = minOf(day.withDayOfMonth(1), LocalDate.now().minusDays(90))
        rangeEnd = maxOf(day.withDayOfMonth(day.lengthOfMonth()), LocalDate.now().plusMonths(13))
        reload()
    }

    fun loadHistory() = viewModelScope.launch {
        rangeStart = repository.earliestTaskDate() ?: LocalDate.now().minusDays(365)
        rangeEnd = LocalDate.now()
        reload()
    }

    fun loadHome() {
        rangeStart = LocalDate.now().minusDays(90)
        rangeEnd = LocalDate.now().plusMonths(13)
        reload()
    }

    fun refresh() = reload()

    private fun reload() {
        val from = rangeStart
        val to = rangeEnd
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            repository.ensureOccurrences(from, to)
            repository.ensureOverdueOnce()
            combine(
                repository.observeTasks(),
                repository.observeOccurrences(from, to),
                repository.observeOverdueOnce(),
                repository.observeCategories(),
            ) { tasks, occurrences, overdue, categories ->
                PlanState(tasks, occurrences, overdue, categories)
            }
                .collect { mutableState.value = it }
        }
    }

    fun save(task: TaskEntity) = viewModelScope.launch { repository.save(task); reload() }
    fun toggle(row: OccurrenceEntity) = viewModelScope.launch { repository.toggle(row) }
    fun setCompletion(row: OccurrenceEntity, complete: Boolean) = viewModelScope.launch { repository.setCompletion(row, complete) }
    fun pause(task: TaskEntity, paused: Boolean) = viewModelScope.launch { repository.setPaused(task, paused); reload() }
    fun delete(task: TaskEntity) = viewModelScope.launch { repository.delete(task) }

    fun addCategory(name: String, color: Long) = runOperation("分类") { repository.addCategory(name, color) }

    suspend fun createCategoryNow(name: String, color: Long): CategoryEntity = repository.addCategory(name, color)

    fun renameCategory(category: CategoryEntity, name: String, color: Long) =
        runOperation("分类") { repository.renameCategory(category, name, color) }

    fun deleteCategory(category: CategoryEntity) = viewModelScope.launch {
        pendingBatchUndo = null
        runCatching { repository.deleteCategory(category.id) }
            .onSuccess { count -> mutableOperationMessage.value = OperationFeedback("已删除分类，$count 项任务已转入未分类。") }
            .onFailure { mutableOperationMessage.value = OperationFeedback(it.message ?: "删除分类失败") }
    }

    fun moveCategory(categoryId: Long, offset: Int) = runOperation("分类排序") { repository.moveCategory(categoryId, offset) }

    fun bulkCategory(ids: Set<Long>, categoryId: Long?) {
        val previousCategories = mutableState.value.tasks
            .filter { it.id in ids }
            .associate { it.id to it.categoryId }
        runBatch(
            "修改分类",
            action = { repository.bulkSetCategory(ids, categoryId) },
            undo = { repository.restoreTaskCategories(previousCategories) },
        )
    }

    fun bulkDate(ids: Set<Long>, date: LocalDate) =
        runBatch("修改日期", action = { repository.bulkChangeStartDate(ids, date) })

    fun bulkPause(ids: Set<Long>, paused: Boolean) =
        runBatch(if (paused) "暂停" else "恢复", action = { repository.bulkSetPaused(ids, paused) })

    fun bulkDelete(ids: Set<Long>) = runBatch("删除", action = { repository.bulkDelete(ids) })

    fun clearOperationMessage() { mutableOperationMessage.value = null }

    fun undoLastBatch() {
        val undo = pendingBatchUndo ?: return
        pendingBatchUndo = null
        viewModelScope.launch {
            runCatching { undo() }
                .onSuccess {
                    mutableOperationMessage.value = OperationFeedback("分类修改已撤销。")
                    reload()
                }
                .onFailure { mutableOperationMessage.value = OperationFeedback("撤销失败：${it.message ?: "请稍后重试"}") }
        }
    }

    fun importFile(uri: Uri) {
        mutableTransferState.value = TransferUiState(isWorking = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val name = displayName(uri)
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PLAN_FILE_BYTES) { "文件超过 24 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法读取所选文件")
                val parsed = PlanTransferCodec.parse(bytes, name)
                repository.previewImport(parsed) to name
            }.onSuccess { (preview, name) ->
                mutableTransferState.value = TransferUiState(preview = preview, fileName = name)
            }.onFailure { error ->
                mutableTransferState.value = TransferUiState(message = error.message ?: "读取计划文件失败")
            }
        }
    }

    fun confirmImport(skipErrorRows: Boolean, importDuplicatesAsNew: Boolean) {
        val preview = mutableTransferState.value.preview ?: return
        mutableTransferState.value = mutableTransferState.value.copy(isWorking = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.importPlans(preview, skipErrorRows, importDuplicatesAsNew) }
                .onSuccess { result ->
                    mutableTransferState.value = TransferUiState(
                        message = "导入完成：新增 ${result.created} 项，跳过重复 ${result.skippedDuplicates} 项，跳过错误 ${result.skippedErrors} 行，提醒未同步 ${result.reminderFailures} 项。",
                    )
                    reload()
                }
                .onFailure { error -> mutableTransferState.value = mutableTransferState.value.copy(isWorking = false, message = error.message ?: "导入失败，现有任务未更改") }
        }
    }

    fun dismissImportPreview() {
        mutableTransferState.value = TransferUiState()
    }

    fun exportFile(uri: Uri, fileName: String, options: PlanExportOptions) {
        mutableTransferState.value = mutableTransferState.value.copy(isWorking = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val records = repository.exportPlans(options)
                val bytes = if (fileName.endsWith(".xlsx", true)) PlanTransferCodec.exportXlsx(records) else PlanTransferCodec.exportCsv(records)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("无法写入目标文件")
                records.size
            }.onSuccess { count ->
                mutableTransferState.value = TransferUiState(message = "已导出 $count 项计划。")
            }.onFailure { error ->
                mutableTransferState.value = TransferUiState(message = error.message ?: "导出失败")
            }
        }
    }

    fun clearTransferMessage() {
        mutableTransferState.value = mutableTransferState.value.copy(message = null)
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty().ifBlank { "plans.csv" }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "plans.csv" } ?: "plans.csv"
    }

    private fun runBatch(
        label: String,
        action: suspend () -> BatchOperationResult,
        undo: (suspend () -> Unit)? = null,
    ) = viewModelScope.launch {
        runCatching { action() }
            .onSuccess { result ->
                pendingBatchUndo = undo.takeIf { result.succeeded > 0 }
                mutableOperationMessage.value = OperationFeedback(
                    "$label：成功 ${result.succeeded} 项，跳过 ${result.skipped} 项。${result.detail}",
                    undoAvailable = pendingBatchUndo != null,
                )
                reload()
            }
            .onFailure {
                pendingBatchUndo = null
                mutableOperationMessage.value = OperationFeedback("${label}失败：${it.message ?: "请稍后重试"}")
            }
    }

    private fun runOperation(label: String, action: suspend () -> Unit) = viewModelScope.launch {
        pendingBatchUndo = null
        runCatching { action() }
            .onSuccess { mutableOperationMessage.value = OperationFeedback("${label}已更新。"); reload() }
            .onFailure { mutableOperationMessage.value = OperationFeedback(it.message ?: "${label}操作失败") }
    }

    companion object {
        private const val MAX_PLAN_FILE_BYTES = 24 * 1024 * 1024
        private const val KEY_AUTO_CHECK = "automatic_checks_enabled"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val KEY_LAST_AUTO_CHECK = "last_automatic_check_time"
        private const val KEY_CLOUD_UID = "cloud_owner_uid"
    }
}
