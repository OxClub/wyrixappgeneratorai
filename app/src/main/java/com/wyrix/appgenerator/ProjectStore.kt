package com.wyrix.appgenerator

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Har file banate hi disk par save hoti hai, taaki app band ho to wahin se resume ho sake. */
class ProjectStore(ctx: Context) {
    private val root = File(ctx.filesDir, "project")
    private val filesDir = File(root, "files")

    fun exists(): Boolean = File(root, "meta.json").exists()

    fun clear() {
        root.deleteRecursively()
    }

    fun saveMeta(prompt: String, plan: Plan) {
        root.mkdirs()
        File(root, "meta.json").writeText(
            JSONObject().put("prompt", prompt).put("plan", plan.toJson()).toString()
        )
    }

    fun saveFile(path: String, content: String) {
        val f = File(filesDir, path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun load(): Triple<String, Plan, LinkedHashMap<String, String>>? {
        return try {
            val meta = File(root, "meta.json")
            if (!meta.exists()) return null
            val j = JSONObject(meta.readText())
            val plan = Plan.fromJson(j.getJSONObject("plan"))
            val files = LinkedHashMap<String, String>()
            for (pf in plan.files) {
                val f = File(filesDir, pf.path)
                if (f.exists()) files[pf.path] = f.readText()
            }
            Triple(j.getString("prompt"), plan, files)
        } catch (e: Exception) {
            null
        }
    }
}
