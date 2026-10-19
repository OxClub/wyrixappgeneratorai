package com.wyrix.appgenerator

import android.content.Context

/** Har generated project mein workflow, README aur .gitignore apne aap judte hain. */
object Templates {
    private fun asset(ctx: Context, name: String): String =
        ctx.assets.open("templates/$name").bufferedReader().use { it.readText() }

    fun extras(ctx: Context, plan: Plan): Map<String, String> {
        val wf = when (plan.type) {
            "android" -> "android.yml"
            "web", "fullstack", "node" -> "web.yml"
            "extension" -> "extension.yml"
            "python" -> "python.yml"
            else -> "other.yml"
        }
        val output = when (plan.type) {
            "android" -> "app-debug-apk (usme APK hogi)"
            "extension" -> "extension-zip"
            "web", "fullstack", "node" -> "build-output"
            else -> "output"
        }
        val readme = asset(ctx, "README.tpl")
            .replace("__NAME__", plan.name)
            .replace("__SUMMARY__", plan.summary)
            .replace("__TYPE__", plan.type)
            .replace("__OUTPUT__", output)
        return linkedMapOf(
            ".github/workflows/build.yml" to asset(ctx, wf),
            "README.md" to readme,
            ".gitignore" to asset(ctx, "gitignore.txt"),
        )
    }
}
