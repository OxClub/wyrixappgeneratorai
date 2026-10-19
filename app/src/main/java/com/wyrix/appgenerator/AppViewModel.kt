package com.wyrix.appgenerator

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val store = SecureStore(ctx)
    private val router = ModelRouter(store)
    private val engine = Engine(router)
    private val disk = ProjectStore(ctx)

    private val _state = MutableStateFlow(UiState(hasSaved = disk.exists()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        router.onLog = { log(it) }
    }

    private fun log(msg: String) {
        _state.update { it.copy(log = (it.log + msg).takeLast(60)) }
    }

    private fun fail(e: Throwable) {
        KeepAliveService.stop(ctx)
        _state.update { it.copy(stage = Stage.ERROR, message = e.message ?: e.toString()) }
    }

    /** Step 1: prompt -> plan */
    fun startPlan(prompt: String) {
        job?.cancel()
        disk.clear()
        _state.value = UiState(stage = Stage.PLANNING, prompt = prompt)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val plan = engine.makePlan(prompt)
                disk.saveMeta(prompt, plan)
                _state.update {
                    it.copy(stage = Stage.PLAN_READY, plan = plan, total = plan.files.size, done = 0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Step 2: plan approve -> file-by-file generation, review, extras */
    fun startGenerate() {
        val plan = _state.value.plan ?: return
        val prompt = _state.value.prompt
        job?.cancel()
        KeepAliveService.start(ctx)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(stage = Stage.GENERATING, message = "", total = plan.files.size) }
                val files = LinkedHashMap(_state.value.files)

                for (pf in plan.files) {
                    if (files.containsKey(pf.path)) continue
                    _state.update { it.copy(current = pf.path, done = files.size) }
                    var content = engine.generateFile(prompt, plan, pf, files, null)
                    val issue = engine.localIssue(pf.path, content)
                    if (issue != null) {
                        log("⚠ ${pf.path}: $issue, dobara likh rahe hain")
                        content = engine.generateFile(prompt, plan, pf, files, issue)
                    }
                    files[pf.path] = content
                    disk.saveFile(pf.path, content)
                    _state.update { it.copy(files = LinkedHashMap(files), done = files.size) }
                }

                // Reviewer AI: cross-file galtiyan dhundhe aur theek kare
                _state.update { it.copy(stage = Stage.REVIEWING, current = "Reviewer AI check kar raha hai") }
                val issues = try {
                    engine.review(plan, files)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Review skip: ${e.message}")
                    emptyList()
                }
                for ((path, problem) in issues.take(10)) {
                    val current = files[path] ?: continue
                    log("🔧 fix: $path")
                    try {
                        val fixed = engine.fixFile(prompt, plan, path, current, problem)
                        if (fixed.isNotBlank()) {
                            files[path] = fixed
                            disk.saveFile(path, fixed)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Fix skip $path: ${e.message}")
                    }
                }

                // Workflow, README, .gitignore (planner ki files overwrite nahi hoti)
                for ((p, c) in Templates.extras(ctx, plan)) {
                    if (!files.containsKey(p)) files[p] = c
                }

                _state.update {
                    it.copy(
                        stage = Stage.DONE,
                        files = LinkedHashMap(files),
                        done = plan.files.size,
                        current = "",
                        message = "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                return@launch
            }
            KeepAliveService.stop(ctx)
        }
    }

    fun cancel() {
        job?.cancel()
        KeepAliveService.stop(ctx)
        val plan = _state.value.plan
        _state.update {
            it.copy(
                stage = if (plan != null) Stage.PLAN_READY else Stage.IDLE,
                hasSaved = disk.exists(),
                done = it.files.size,
            )
        }
    }

    fun backToPrompt() {
        _state.update { UiState(stage = Stage.IDLE, prompt = it.prompt, hasSaved = disk.exists()) }
    }

    fun reset() {
        job?.cancel()
        KeepAliveService.stop(ctx)
        disk.clear()
        _state.value = UiState()
    }

    fun resume() {
        val loaded = disk.load() ?: run {
            _state.update { it.copy(hasSaved = false) }
            return
        }
        val (prompt, plan, files) = loaded
        _state.value = UiState(
            stage = Stage.PLAN_READY,
            prompt = prompt,
            plan = plan,
            files = files,
            done = files.size,
            total = plan.files.size,
            hasSaved = true,
        )
    }

    fun retry() {
        if (_state.value.plan != null) startGenerate() else startPlan(_state.value.prompt)
    }

    /** ZIP Downloads folder mein save hoti hai. */
    fun exportZip() {
        val plan = _state.value.plan ?: return
        val files = _state.value.files
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = plan.name.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "project" }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$name.zip")
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ZIP file nahi ban payi")
                val os = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("ZIP file khul nahi payi")
                os.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        for ((path, content) in files) {
                            zip.putNextEntry(ZipEntry("$name/$path"))
                            zip.write(content.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                }
                _state.update { it.copy(exportUri = uri, message = "Downloads mein save hua: $name.zip") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Export fail hua: ${e.message}") }
            }
        }
    }
}
