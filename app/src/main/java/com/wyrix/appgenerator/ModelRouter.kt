package com.wyrix.appgenerator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFail(val code: Int, msg: String) : Exception(msg)
class AllModelsFailed(msg: String) : Exception(msg)

/**
 * Ek request ke liye models ko order mein try karta hai.
 * Limit (429) ya error aaye to model kuch der cooldown mein jata hai aur agla model kaam sambhal leta hai.
 */
class ModelRouter(private val store: SecureStore) {
    var onLog: (String) -> Unit = {}

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun id(m: ModelCfg) = "${m.provider}/${m.model}"

    private fun parseModels(): List<ModelCfg> =
        store.models().lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val p = line.split("|").map { it.trim() }
            if (p.size < 3) return@mapNotNull null
            val tier = try {
                Tier.valueOf(p[0].uppercase())
            } catch (e: Exception) {
                return@mapNotNull null
            }
            ModelCfg(p[1].lowercase(), p[2], tier)
        }

    suspend fun complete(tier: Tier, system: String, user: String, maxTokens: Int = 8000): String {
        val usable = parseModels().filter { store.key(it.provider).isNotBlank() }
        if (usable.isEmpty()) {
            throw AllModelsFailed("Koi API key set nahi hai. Settings mein kam se kam ek key daalein.")
        }
        // Pehle maange gaye tier ke models, phir baaki sab last resort ke taur par.
        val order = usable.filter { it.tier == tier } + usable.filter { it.tier != tier }
        var lastErr = ""

        for (round in 0 until 4) {
            val now = System.currentTimeMillis()
            val live = order.filter { id(it) !in dead }
            if (live.isEmpty()) break
            val ready = live.filter { (cooldownUntil[id(it)] ?: 0L) <= now }
            if (ready.isEmpty()) {
                val wait = (live.minOf { cooldownUntil[id(it)] ?: 0L } - now).coerceIn(1000L, 60000L)
                onLog("⏳ Sab models cooldown mein, ${wait / 1000}s ruk rahe hain")
                delay(wait)
                continue
            }
            for (m in ready) {
                try {
                    onLog("→ ${m.provider}/${m.model}")
                    return call(m, system, user, maxTokens)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpFail) {
                    lastErr = "${id(m)}: HTTP ${e.code} ${e.message}"
                    val wait = when (e.code) {
                        401, 402, 403, 404 -> {
                            dead.add(id(m))
                            0L
                        }
                        400 -> 600_000L
                        429 -> 60_000L
                        else -> 30_000L
                    }
                    if (wait > 0) cooldownUntil[id(m)] = System.currentTimeMillis() + wait
                    onLog("✗ ${id(m)} fail (${e.code}), agla model")
                } catch (e: Exception) {
                    lastErr = "${id(m)}: ${e.message}"
                    cooldownUntil[id(m)] = System.currentTimeMillis() + 30_000L
                    onLog("✗ ${id(m)} error, agla model")
                }
            }
        }
        throw AllModelsFailed("Sab models fail ho gaye. Aakhri error: $lastErr")
    }

    private suspend fun call(m: ModelCfg, system: String, user: String, maxTokens: Int): String {
        val p = Providers.byId(m.provider) ?: throw HttpFail(0, "unknown provider ${m.provider}")
        val key = store.key(m.provider)

        val req: Request = when (p.kind) {
            Provider.Kind.OPENAI -> {
                val body = JSONObject()
                    .put("model", m.model)
                    .put("max_tokens", maxTokens)
                    .put(
                        "messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user))
                    )
                Request.Builder().url("${p.baseUrl}/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody(json)).build()
            }

            Provider.Kind.ANTHROPIC -> {
                val body = JSONObject()
                    .put("model", m.model)
                    .put("max_tokens", maxTokens)
                    .put("system", system)
                    .put(
                        "messages", JSONArray()
                            .put(JSONObject().put("role", "user").put("content", user))
                    )
                Request.Builder().url("${p.baseUrl}/messages")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody(json)).build()
            }

            Provider.Kind.GEMINI -> {
                val body = JSONObject()
                    .put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                    )
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", user)))
                        )
                    )
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
                Request.Builder().url("${p.baseUrl}/models/${m.model}:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(json)).build()
            }
        }

        val resp = client.newCall(req).await()
        val text = resp.use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpFail(r.code, b.take(200))
            b
        }
        val out = try {
            extractText(p.kind, text)
        } catch (e: Exception) {
            throw HttpFail(0, "response samajh nahi aaya: ${e.message}")
        }
        if (out.isBlank()) throw HttpFail(0, "khali response")
        return out
    }

    private fun extractText(kind: Provider.Kind, body: String): String {
        val j = JSONObject(body)
        return when (kind) {
            Provider.Kind.OPENAI ->
                j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")

            Provider.Kind.ANTHROPIC -> {
                val a = j.getJSONArray("content")
                buildString {
                    for (i in 0 until a.length()) {
                        val b = a.getJSONObject(i)
                        if (b.optString("type") == "text") append(b.optString("text"))
                    }
                }
            }

            Provider.Kind.GEMINI -> {
                val parts = j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                buildString {
                    for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
                }
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
