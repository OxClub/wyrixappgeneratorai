package com.wyrix.appgenerator

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

enum class Tier { PLANNER, CODER, FAST }

data class ModelCfg(val provider: String, val model: String, val tier: Tier)

data class Provider(val id: String, val label: String, val baseUrl: String, val kind: Kind) {
    enum class Kind { OPENAI, ANTHROPIC, GEMINI }
}

object Providers {
    private val oa = Provider.Kind.OPENAI
    val all = listOf(
        Provider("openai", "OpenAI (ChatGPT)", "https://api.openai.com/v1", oa),
        Provider("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", Provider.Kind.ANTHROPIC),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", Provider.Kind.GEMINI),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", oa),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", oa),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", oa),
        Provider("kimi", "Kimi (Moonshot)", "https://api.moonshot.ai/v1", oa),
        Provider("openrouter", "OpenRouter (kai models)", "https://openrouter.ai/api/v1", oa),
    )

    fun byId(id: String): Provider? = all.firstOrNull { it.id == id }
}

/** Har line: tier|provider|model. Upar wala pehle try hota hai, limit khatam to agla. Settings mein badal sakte hain. */
object DefaultModels {
    const val TEXT = """
# tier|provider|model
planner|anthropic|claude-sonnet-5-5
planner|openai|gpt-4o
planner|gemini|gemini-2.5-pro
planner|deepseek|deepseek-reasoner
coder|deepseek|deepseek-chat
coder|kimi|kimi-k2-0905-preview
coder|gemini|gemini-2.5-flash
coder|openai|gpt-4o-mini
coder|anthropic|claude-haiku-5-5
fast|groq|llama-3.3-70b-versatile
fast|nvidia|meta/llama-3.1-70b-instruct
fast|openrouter|deepseek/deepseek-chat
"""
}

data class PlanFile(val path: String, val purpose: String, val deps: List<String>)

data class Plan(
    val name: String,
    val type: String,
    val summary: String,
    val styleGuide: String,
    val files: List<PlanFile>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("type", type)
        .put("summary", summary)
        .put("style_guide", styleGuide)
        .put("files", JSONArray().also { arr ->
            files.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("path", f.path)
                        .put("purpose", f.purpose)
                        .put("deps", JSONArray(f.deps))
                )
            }
        })

    companion object {
        fun fromJson(j: JSONObject): Plan {
            val arr = j.getJSONArray("files")
            val list = ArrayList<PlanFile>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = safePath(o.optString("path")) ?: continue
                if (!seen.add(p)) continue
                val deps = ArrayList<String>()
                val d = o.optJSONArray("deps")
                if (d != null) {
                    for (k in 0 until d.length()) {
                        safePath(d.optString(k))?.let { deps.add(it) }
                    }
                }
                list.add(PlanFile(p, o.optString("purpose"), deps))
            }
            require(list.isNotEmpty()) { "Plan mein koi file nahi mili" }
            val name = j.optString("name", "project").ifBlank { "project" }
            return Plan(
                name = name,
                type = j.optString("type", "other").lowercase(),
                summary = j.optString("summary"),
                styleGuide = j.optString("style_guide"),
                files = sortByDeps(list),
            )
        }
    }
}

/** ../ ya absolute path wali files ko reject karta hai. */
fun safePath(raw: String?): String? {
    var p = (raw ?: "").trim().replace('\\', '/')
    while (p.startsWith("./") || p.startsWith("/")) {
        p = p.removePrefix("./").removePrefix("/")
    }
    if (p.isEmpty()) return null
    if (p.split('/').any { it == ".." || it.isEmpty() }) return null
    return p
}

private fun sortByDeps(files: List<PlanFile>): List<PlanFile> {
    val byPath = files.associateBy { it.path }
    val out = ArrayList<PlanFile>()
    val visited = HashSet<String>()
    fun visit(f: PlanFile) {
        if (!visited.add(f.path)) return
        f.deps.forEach { d -> byPath[d]?.let { visit(it) } }
        out.add(f)
    }
    files.forEach { visit(it) }
    return out
}

enum class Stage { IDLE, PLANNING, PLAN_READY, GENERATING, REVIEWING, DONE, ERROR }

data class UiState(
    val stage: Stage = Stage.IDLE,
    val prompt: String = "",
    val plan: Plan? = null,
    val files: Map<String, String> = emptyMap(),
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val log: List<String> = emptyList(),
    val message: String = "",
    val hasSaved: Boolean = false,
    val exportUri: Uri? = null,
)
