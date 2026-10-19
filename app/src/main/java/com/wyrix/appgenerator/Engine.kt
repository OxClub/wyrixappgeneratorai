package com.wyrix.appgenerator

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private const val PLAN_SYSTEM = """You are a principal software architect. Turn the user's request into a complete project blueprint.
Reply with ONE JSON object only (no markdown, no commentary) shaped exactly like this:
{"name":"kebab-case-name","type":"android|web|fullstack|extension|node|python|other","summary":"what is built, 3-6 sentences incl. main features","style_guide":"naming, folder structure, error handling, formatting rules (max 15 lines)","files":[{"path":"relative/path.ext","purpose":"what the file contains, with the public classes/functions it exposes and their signatures","deps":["paths of other project files it imports"]}]}
Rules:
- If the request is short or vague, decide good concrete features yourself.
- List EVERY file needed for the project to build and run: source, config, manifests, resources, package files, DB schema, env example.
- Size each file at 150-500 lines of real code. Small apps: 10-30 files. Large apps (e.g. stock/trading platform, social network): 60-150 files. Never more than 150 files.
- No binary files (images, fonts, jars). Do NOT list gradle-wrapper files, the .github folder or README.md: they are added automatically.
- android: Kotlin + Jetpack Compose, Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, composeOptions kotlinCompilerExtensionVersion 1.5.14, compileSdk 34, minSdk 26, JDK 17, Gradle 8.7, single module named app, android.useAndroidX=true in gradle.properties, use the system icon @android:drawable/sym_def_app_icon.
- fullstack: keep frontend and backend in separate top-level folders, each with its own package.json.
- extension: Manifest V3 with manifest.json at the project root.
- Use forward slashes in paths and the same names everywhere."""

private const val FILE_SYSTEM = """You are a senior engineer writing ONE file of a larger project.
Output ONLY the raw content of that file: no markdown fences, no explanations before or after.
The file must be complete and production-ready: no placeholders, no TODO stubs, no "rest of code here".
Follow the style guide. Use exactly the file paths, class names and function names from the blueprint and from the dependency files shown, so that imports match across files."""

private const val REVIEW_SYSTEM = """You are a strict code reviewer. You only see the beginning of each file.
Find concrete cross-file problems: imports of files/classes/functions that do not exist or are named differently, dependencies missing in package/gradle files, missing config files, wrong paths.
Reply with ONE JSON object only: {"issues":[{"path":"file that must be changed","problem":"precise description of the change needed"}]}
At most 10 issues. If nothing is clearly wrong return {"issues":[]}. Do not report style."""

internal fun extractJson(raw: String): JSONObject {
    val a = raw.indexOf('{')
    val b = raw.lastIndexOf('}')
    require(a >= 0 && b > a) { "Model ne JSON nahi diya" }
    return JSONObject(raw.substring(a, b + 1))
}

internal fun stripFences(s: String): String {
    var t = s.trim()
    if (t.startsWith("```")) {
        t = t.substringAfter("\n", "")
        val end = t.trimEnd()
        t = if (end.endsWith("```")) end.removeSuffix("```") else end
    }
    return t.trimEnd() + "\n"
}

class Engine(private val router: ModelRouter) {

    suspend fun makePlan(prompt: String): Plan {
        var lastErr = ""
        for (attempt in 0 until 3) {
            val user = if (lastErr.isEmpty()) prompt
            else "$prompt\n\n(Your previous reply was invalid: $lastErr. Reply with valid JSON only.)"
            val raw = router.complete(Tier.PLANNER, PLAN_SYSTEM, user, 8000)
            try {
                return Plan.fromJson(extractJson(raw))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErr = e.message ?: "invalid json"
            }
        }
        throw IllegalStateException("Plan nahi ban paya: $lastErr")
    }

    private fun blueprint(prompt: String, plan: Plan): StringBuilder {
        val sb = StringBuilder()
        sb.appendLine("USER REQUEST: $prompt")
        sb.appendLine()
        sb.appendLine("PROJECT: ${plan.name} (${plan.type})")
        sb.appendLine(plan.summary)
        sb.appendLine()
        sb.appendLine("STYLE GUIDE:")
        sb.appendLine(plan.styleGuide)
        sb.appendLine()
        sb.appendLine("ALL FILES IN THE PROJECT:")
        plan.files.forEach { sb.appendLine("- ${it.path}: ${it.purpose}") }
        sb.appendLine()
        return sb
    }

    suspend fun generateFile(
        prompt: String,
        plan: Plan,
        pf: PlanFile,
        done: Map<String, String>,
        issue: String?,
    ): String {
        val sb = blueprint(prompt, plan)
        var budget = 24000
        val depTexts = pf.deps.mapNotNull { d -> done[d]?.let { d to it } }
        if (depTexts.isNotEmpty()) {
            sb.appendLine("DEPENDENCY FILES ALREADY WRITTEN (use their exact names):")
            for ((d, c) in depTexts) {
                if (budget <= 0) break
                val part = c.take(minOf(4000, budget))
                budget -= part.length
                sb.appendLine("=== $d ===")
                sb.appendLine(part)
            }
            sb.appendLine()
        }
        sb.appendLine("NOW WRITE THIS FILE: ${pf.path}")
        sb.appendLine("Purpose: ${pf.purpose}")
        if (issue != null) {
            sb.appendLine("Your previous attempt had a problem: $issue. Write the complete file again, without that problem.")
        }
        return stripFences(router.complete(Tier.CODER, FILE_SYSTEM, sb.toString(), 8000))
    }

    /** Phone par compile nahi ho sakta, isliye sirf sasti local checks. */
    fun localIssue(path: String, content: String): String? {
        val t = content.trim()
        if (t.length < 5) return "file khali hai"
        val bad = Regex("(?i)(rest of (the )?code|code (goes )?here|implement (this )?here|\\.\\.\\. ?existing)")
        if (bad.containsMatchIn(t)) return "placeholder text mila"
        val ext = path.substringAfterLast('.', "").lowercase()
        val name = path.substringAfterLast('/')
        if (ext == "json" && !name.startsWith("tsconfig")) {
            try {
                if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            } catch (e: Exception) {
                return "JSON invalid"
            }
        }
        val braceExts = setOf("kt", "java", "js", "ts", "jsx", "tsx", "css", "go", "rs", "c", "cpp", "cs", "swift", "php")
        if (ext in braceExts) {
            val diff = t.count { it == '{' } - t.count { it == '}' }
            if (diff != 0) return "braces balance nahi hain (file adhoori ho sakti hai)"
        }
        return null
    }

    suspend fun review(plan: Plan, files: Map<String, String>): List<Pair<String, String>> {
        val per = (90000 / files.size.coerceAtLeast(1)).coerceIn(150, 1200)
        val sb = StringBuilder()
        sb.appendLine("PROJECT: ${plan.name} (${plan.type})")
        sb.appendLine(plan.summary)
        sb.appendLine()
        for ((p, c) in files) {
            sb.appendLine("=== $p ===")
            sb.appendLine(c.take(per))
        }
        val raw = router.complete(Tier.PLANNER, REVIEW_SYSTEM, sb.toString(), 3000)
        val arr = extractJson(raw).optJSONArray("issues") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = safePath(o.optString("path")) ?: continue
            out.add(p to o.optString("problem"))
        }
        return out
    }

    suspend fun fixFile(prompt: String, plan: Plan, path: String, current: String, problem: String): String {
        val sb = blueprint(prompt, plan)
        sb.appendLine("CURRENT CONTENT OF $path:")
        sb.appendLine(current.take(30000))
        sb.appendLine()
        sb.appendLine("PROBLEM TO FIX: $problem")
        sb.appendLine("Rewrite the entire file $path with this problem fixed. Output only the raw file content.")
        return stripFences(router.complete(Tier.CODER, FILE_SYSTEM, sb.toString(), 8000))
    }
}
