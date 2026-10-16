package pt.filipe.enquadra.nativa

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime

/** Settings, stored on the phone. Same names and defaults as the web version. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("enquadra", Context.MODE_PRIVATE)
    var gkey: String get() = p.getString("gkey", "")!!; set(v) = p.edit().putString("gkey", v).apply()
    var gmodel: String get() = p.getString("gmodel", "gemini-3.5-flash-lite")!!; set(v) = p.edit().putString("gmodel", v).apply()
    var persona: String get() = p.getString("persona", "criativo")!!; set(v) = p.edit().putString("persona", v).apply()
    var extra: String get() = p.getString("extra", "")!!; set(v) = p.edit().putString("extra", v).apply()
    var engine: String get() = p.getString("engine", "auto")!!; set(v) = p.edit().putString("engine", v).apply()
    var ratio: Float get() = p.getFloat("ratio", .8f); set(v) = p.edit().putFloat("ratio", v).apply()
    var grid: Boolean get() = p.getBoolean("grid", true); set(v) = p.edit().putBoolean("grid", v).apply()
    var level: Boolean get() = p.getBoolean("level", true); set(v) = p.edit().putBoolean("level", v).apply()
    var quota: Long get() = p.getLong("quota", 0); set(v) = p.edit().putLong("quota", v).apply()
    var lastPhoto: String get() = p.getString("lastPhoto", "")!!; set(v) = p.edit().putString("lastPhoto", v).apply()
}

class Persona(val label: String, val temp: Double, val range: DoubleArray, val cover: String, val text: String)

val PERSONAS = linkedMapOf(
    "criativo" to Persona("Criativo", .95, doubleArrayOf(.2, .75), "20-60%", """Style: creative and bold, like a street or fine-art photographer with a strong eye. Look past the obvious subject. Seek:
- details and textures most people miss; patterns and repetition, ideally with one element that breaks it;
- light as the subject: backlight, rim light, hard shadows, light and shadow shapes, glow, silhouettes;
- reflections in glass, water and puddles; layers with something in the foreground framing the scene;
- abstract geometry: lines, curves, diagonals, colour blocks, minimal compositions with a small subject in a large empty space.
Frame with intent and some tension: off-centre placement, a tight crop on a detail, strong diagonals, deliberate negative space. A deliberate cut is fine; an accidental one is not.
Choose looks with character when they fit the mood (cinema, pelicula, noir, pb, dusk, dourado, lofi) and borders that complete the idea. The tip should be evocative but still a concrete instruction."""),
    "equilibrado" to Persona("Equilibrado", .5, doubleArrayOf(.3, .65), "30-55%", "Style: balanced. A strong subject with good light, placed with a composition rule that suits the scene."),
    "classico" to Persona("Clássico", .3, doubleArrayOf(.35, .65), "30-55%", "Style: classic and clean. One clear, readable subject, placed on the rule of thirds or centred when symmetric. Calm, balanced framing with breathing room. Natural looks: prefer clean, porto, claro, quente; borders none, white or ficha."),
    "minimal" to Persona("Minimalista", .6, doubleArrayOf(.25, .6), "25-55%", "Style: minimalist. One simple element, lots of clean negative space, few colours, simple geometry. Avoid busy areas. Looks: suave, claro, pb; borders galeria, white or none."),
    "documental" to Persona("Documental", .5, doubleArrayOf(.35, .7), "35-65%", "Style: documentary. People, gestures and everyday moments first; otherwise the detail that tells the story of the place. Honest framing, little stylisation. Looks: clean, pelicula, pb; borders ficha, date or none.")
)
val PERSONA_DESC = mapOf("criativo" to "ousado, detalhes, luz, reflexos", "equilibrado" to "motivo forte, boa luz", "classico" to "regras, limpo, seguro",
    "minimal" to "espaço vazio, simples", "documental" to "pessoas e momentos")

class LocalLook(val scales: List<Double>, val styles: List<String>)
val LOCAL_LOOK = mapOf(
    "classico" to LocalLook(listOf(.45, .55, .65), listOf("tercos", "centro", "simetrico", "aurea")),
    "equilibrado" to LocalLook(listOf(.38, .5, .62), listOf("tercos", "aurea", "centro", "simetrico", "minimal", "diagonal")),
    "criativo" to LocalLook(listOf(.22, .32, .45, .6), listOf("diagonal", "minimal", "aurea", "tercos")),
    "minimal" to LocalLook(listOf(.25, .36, .5), listOf("minimal", "centro", "tercos")),
    "documental" to LocalLook(listOf(.45, .58, .72), listOf("tercos", "centro", "aurea"))
)
val FILTER_IDS = listOf("clean", "vivo", "quente", "claro", "suave", "pelicula", "dourado", "cinema", "porto", "dusk", "relic", "lofi", "pb", "noir")
val BORDER_IDS = listOf("none", "white", "ficha", "polaroid", "galeria", "pelicula", "desfocado", "paper", "date")

class GErr(val code: String, val msg: String = "") : Exception("$code $msg")

class Gemini(private val set: Settings) {
    companion object {
        const val TIMEOUT = 12000
        const val PAUSE = 120000L
        const val RETRY = 600000L
        val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash")
        val MODEL_LABEL = mapOf("gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite · rápido", "gemini-3.8-flash" to "Gemini 3.8 Flash · escolhe melhor, mais lento",
            "gemini-3.6-flash" to "Gemini 3.6 Flash")
    }
    // health: unknown | checking | up | down | quota | badkey | nokey | off
    var state = "unknown"; var model = ""; var why = ""; var at = 0L; var until = 0L
    private val paused = HashMap<String, Long>()
    private val noThink = HashSet<String>()
    var checking = false
    var onChange: (() -> Unit)? = null

    init { val q = set.quota; if (q > System.currentTimeMillis()) { state = "quota"; until = q } }

    fun persona() = PERSONAS[set.persona] ?: PERSONAS["criativo"]!!
    fun engine() = if (set.engine == "local") "local" else if (set.gkey.isNotEmpty()) "gemini" else "local"
    fun pausedNow(m: String) = System.currentTimeMillis() < (paused[m] ?: 0)

    /** lenses: (zoom, frame width as a fraction of the image width) for each real lens the photo can be taken with */
    fun prompt(w: Int, h: Int, ratio: Double, seen: List<String>, lenses: List<Pair<Double, Double>> = emptyList()): String {
        val lensField = if (lenses.isNotEmpty()) ",\"lens\":\"5 if the shot is the 5x size, otherwise 1\"" else ""
        val avoid = if (seen.isNotEmpty()) "\nThe user asked for another option: choose something different from ${seen.takeLast(4).joinToString(", ") { "\"$it\"" }}." else ""
        val p = persona()
        val extra = if (set.extra.isNotEmpty()) "\nThe photographer's own direction (follow it): ${set.extra.take(400)}" else ""
        return """You are an award-winning photographer with a strong personal eye, walking with someone who knows nothing about photography. The image is their live camera view (${if (w > h) "landscape" else "portrait"}, ${w}x${h}).
Your job is to find the single most beautiful PHOTOGRAPH hidden in this view. Not the most important or recognisable object: a photograph is made of light, shape, colour, texture and composition, not of "things". A dull sign, a parked car, a bin or a generic building is never the answer just because it is recognisable.
Think before answering, in this order:
1. Light: where is the most interesting light? Low warm sun, backlight, rim light, glow, hard shadows and their shapes, light and dark patterns, reflections in water or glass.
2. Graphics: strong shapes, lines that lead somewhere, repetition and rhythm, curves, contrasts of colour or tone, a small element against a large empty space, layers from near to far.
3. The photo: which part of the scene, cropped tight, becomes a clean, intentional picture with ONE clear point of interest, a simple background and nothing distracting or awkwardly cut at the edges?
Compose like a master: put the point of interest on a strong point (about one third in from two edges) or exactly in the middle when the scene is symmetric; let lines lead into the picture towards it; leave empty space in front of people, animals and vehicles in the direction they look or move; keep the horizon straight and high or low, never through the middle unless it is a reflection; never put a bright lamp, street light or bulb at the centre of interest (use what its light falls on, its glow or its reflection); the sun at sunset is fine.
${p.text}$extra
Then propose the shot: a rectangle with width:height = ${"%.3f".format(java.util.Locale.US, ratio)}. ${lensText(lenses, p.cover)}
Finally choose the look of the finished photo to match its mood (do not default to the same every time):
filter, one of: clean (natural), vivo (punchy colour), quente (warm), claro (bright and airy), suave (soft, faded), pelicula (film with grain), dourado (golden glow), cinema (teal and orange, vignette), porto (crisp, clean contrast), dusk (warm evening), relic (vintage), lofi (high contrast, saturated), pb (black and white), noir (dramatic black and white).
border, one of: none, white (thin white), ficha (white with camera specs), polaroid (instant photo with handwritten caption), galeria (museum mat), pelicula (film frame), desfocado (blurred square backdrop), paper (cream paper with caption), date (orange date stamp).$avoid
Answer with JSON only, with the fields in this order (write the idea first, then decide the rest from it):
{"idea":"the photo in one sentence in European Portuguese, about light, shape and mood, e.g. 'reflexo dourado do sol entre duas sombras compridas'","subject":"the point of interest in European Portuguese, max 4 words, lowercase","subject_box":[ymin,xmin,ymax,xmax],"shot_box":[ymin,xmin,ymax,xmax]$lensField,"rule":"tercos|aurea|centro|simetrico|diagonal|minimal|moldura","tip":"for someone who knows nothing about photography: where the main things sit in this photo, in simple European Portuguese, max 9 words, using only plain places (ao centro, em cima, em baixo, à esquerda, à direita, no canto de cima à direita...). Examples: 'Sol em cima à direita, mar em baixo', 'Pessoa ao centro, céu por cima', 'Barco pequeno à esquerda, muita água à volta'. Never use words like terços, simetria, diagonal, regra, composição, áurea, enquadra","filter":"one id from the list","border":"one id from the list"}
Coordinates are integers normalized to 0-1000."""
    }

    private fun fmtLens(z: Double) = if (kotlin.math.abs(z - Math.round(z)) < .05) Math.round(z).toString() else "%.1f".format(java.util.Locale.US, z)
    private fun lensText(lenses: List<Pair<Double, Double>>, cover: String): String {
        if (lenses.isEmpty()) return "It covers roughly $cover of the image width."
        val f5 = Math.round(lenses.first().second * 100)
        return "The shot can be any size from $f5% to about 90% of the image width ($cover is typical for this style). " +
            "A small, distant subject is best shot tight at exactly $f5% of the width: the phone then uses its 5x telephoto, the sharpest option for it. " +
            "Choose the size the photo needs; do not make it bigger just to include more."
    }

    /** Curator: Gemini sees n numbered candidate photographs of the scene and picks the most artistic one. */
    fun curatorPrompt(n: Int, seen: List<String>, teleNumbers: List<Int>): String {
        val p = persona()
        val extra = if (set.extra.isNotEmpty()) "\nThe photographer's own taste (follow it): ${set.extra.take(400)}" else ""
        val avoid = if (seen.isNotEmpty()) "\nThe user asked for another option: do not choose a photo like ${seen.takeLast(4).joinToString(", ") { "\"$it\"" }}." else ""
        val tele = if (teleNumbers.isNotEmpty()) "\nPhotos ${teleNumbers.joinToString(", ")} would be shot with the 5x telephoto (the sharpest, most compressed look)." else ""
        return """You are a fine-art photographer and picture editor with a strong personal eye. The image shows $n numbered photographs (1 to $n): different crops of the same scene the user is pointing the camera at. The numbers above each photo are labels, not part of the photos.
Choose the ONE that is the most beautiful, artistic photograph, as if you were choosing a print for a gallery wall. Judge each one as a finished picture, not as a picture of things:
- light and mood: warm low light, backlight, rim light, glow, shadows and their shapes, reflections, atmosphere;
- composition: one clear point of interest placed with intent, lines that lead into the frame, balance and breathing space, a clean background, nothing cut awkwardly at the edges, a straight horizon;
- graphic strength: shapes, patterns, rhythm, colour and tone contrast, simplicity;
- feeling or story.
A photo that only shows a recognisable object (a sign, a car, a building, a lamp) or a busy, cluttered view must lose to one with beautiful light, shape or atmosphere, even if nothing "important" is in it. Prefer the simpler, stronger picture.
${p.text}$extra$tele$avoid
Answer with JSON only, fields in this order (think first, then choose):
{"why":"one sentence in European Portuguese on what makes the winner a beautiful photograph","best":number,"second":number,"idea":"the winning photo in a few words, European Portuguese, about light, shape and mood","subject":"its point of interest, max 4 words, European Portuguese, lowercase","tip":"for someone who knows nothing about photography: where the main things sit in the winning photo, max 9 words, plain places only (ao centro, em cima, em baixo, à esquerda, à direita, no canto de cima à direita…), e.g. 'Sol em cima à direita, mar em baixo'. Never use terços, simetria, diagonal, regra, composição","filter":"one of clean, vivo, quente, claro, suave, pelicula, dourado, cinema, porto, dusk, relic, lofi, pb, noir — matching the mood","border":"one of none, white, ficha, polaroid, galeria, pelicula, desfocado, paper, date"}"""
    }

    /** Second look, after the zoom: Gemini sees the shot itself and makes the final composition adjustment. */
    fun refinePrompt(w: Int, h: Int, ratio: Double, subject: String): String {
        val extra = if (set.extra.isNotEmpty()) "\nThe photographer's own direction (follow it): ${set.extra.take(400)}" else ""
        return """You are a photo director. This image is the phone's zoomed-in view, about to be photographed (${w}x${h}). The subject is: "$subject".
${persona().text}$extra
Make the final composition adjustment yourself: choose the best final crop inside this image, a rectangle with width:height = ${"%.3f".format(java.util.Locale.US, ratio)}, at least 80% of the largest such rectangle (small adjustments only). Place the subject well, keep the horizon level-looking, remove distracting edges and anything cut awkwardly at the borders. If the current full view is already best, return it.
The app applies your crop itself, so the person must NOT move, tilt, rotate or re-aim the phone. Then give one short tip in European Portuguese (max 6 words) only about the moment to shoot, never about moving or tilting the phone, e.g. "Fica parado e dispara" or "Espera pela onda". Use "" if there is nothing to wait for.
Answer with JSON only: {"shot_box":[ymin,xmin,ymax,xmax],"tip":"..."}
Coordinates are integers normalized to 0-1000."""
    }

    /** Models to try: the one known to work first, then the chosen one, then the rest. */
    fun modelOrder(): List<String> = (listOf(if (state == "up" && model.isNotEmpty()) model else set.gmodel, set.gmodel) + MODELS).distinct()

    suspend fun ask(b64: String?, prompt: String, m: String, timeout: Int = TIMEOUT): String {
        try { return ask1(b64, prompt, m, timeout, !noThink.contains(m)) }
        catch (e: GErr) {
            if (e.code == "g_400" && Regex("think", RegexOption.IGNORE_CASE).containsMatchIn(e.msg) && !noThink.contains(m)) { noThink.add(m); return ask1(b64, prompt, m, timeout, false) }
            throw e
        }
    }

    private suspend fun ask1(b64: String?, prompt: String, m: String, timeout: Int, lowThink: Boolean): String = withContext(Dispatchers.IO) {
        val gen = JSONObject().put("responseMimeType", "application/json").put("temperature", persona().temp)
        // thinking makes Gemini 3 slow; framing a photo needs little of it
        // a photo needs a little thought (taste); the 8 s health pings need none
        if (lowThink) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", if (b64 != null || m.contains("3.8")) "low" else "minimal"))
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (b64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", gen).toString()
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(m, "UTF-8")}:generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = timeout; c.doOutput = true
            c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-goog-api-key", set.gkey)
            val code = try { c.outputStream.use { it.write(body.toByteArray()) }; c.responseCode }
            catch (e: SocketTimeoutException) { paused[m] = System.currentTimeMillis() + PAUSE; throw GErr("g_timeout") }
            catch (e: java.io.IOException) { throw GErr("g_net") }
            if (code !in 200..299) {
                if (code == 429 || code >= 500) paused[m] = System.currentTimeMillis() + PAUSE
                val msg = try { JSONObject(c.errorStream.bufferedReader().readText()).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { "" }
                throw GErr("g_$code", msg)
            }
            val txt = try { c.inputStream.bufferedReader().readText() } catch (e: SocketTimeoutException) { paused[m] = System.currentTimeMillis() + PAUSE; throw GErr("g_timeout") }
            val j = JSONObject(txt)
            val ps = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder(); for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text") ?: "")
            if (sb.isEmpty()) throw GErr("g_empty")
            sb.toString()
        } finally { c.disconnect() }
    }

    fun fatalKey(e: GErr): Boolean { val m = e.msg.lowercase(); return e.code == "g_401" || e.code == "g_403" || (e.code == "g_400" && (m.contains("api key") || m.contains("api_key"))) }

    fun setHealth(s: String, m: String = "", w: String? = null) { state = s; model = m; if (w != null) why = w; onChange?.invoke() }

    /** The free quota resets at midnight in California. */
    fun nextReset(): Long = ZonedDateTime.now(ZoneId.of("America/Los_Angeles")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()

    fun setQuota(w: String) {
        until = nextReset(); set.quota = until
        val h = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "PT")).format(java.util.Date(until))
        setHealth("quota", "", "Quota gratuita do Gemini esgotada. Volta às $h." + (if (w.isNotEmpty()) "\n$w" else ""))
    }

    /** A tiny request to each model in turn, 8 s each: the ✦ button never waits for a dead service. */
    suspend fun check() {
        if (engine() != "gemini") { setHealth(if (set.gkey.isNotEmpty()) "off" else "nokey", "", ""); return }
        if (state == "quota" && System.currentTimeMillis() < until) return
        if (checking) return; checking = true; at = System.currentTimeMillis()
        if (state != "up") setHealth("checking", model)
        val whys = ArrayList<String>(); var ok = ""; var bad = false; var quota = 0; var tried = 0
        for (m in modelOrder()) {
            val t0 = System.currentTimeMillis(); tried++
            try { parseJSON(ask(null, "Answer with JSON only: {\"ok\":true}", m, 8000)); ok = m; break }
            catch (e: GErr) { whys.add("$m: ${errCopy(e)} (${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s)"); if (e.code == "g_429") quota++; if (fatalKey(e)) { bad = true; break } }
            catch (e: Exception) { whys.add("$m: ${e.message}") }
        }
        checking = false
        if (ok.isNotEmpty()) { paused.clear(); set.quota = 0; setHealth("up", ok, "") }
        else if (quota > 0 && quota == tried) setQuota(whys.joinToString("\n"))
        else setHealth(if (bad) "badkey" else "down", "", whys.joinToString("\n"))
    }

    /** Look again only when it can have changed: after 10 minutes of failure, never during an exhausted quota. */
    suspend fun maybeRecheck() {
        if (engine() != "gemini") return
        if (state == "quota") { if (System.currentTimeMillis() < until) return; state = "unknown"; set.quota = 0 }
        if (state != "up" && state != "checking" && System.currentTimeMillis() - at > RETRY) check()
    }
}

fun parseJSON(t: String): JSONObject {
    val a = t.indexOf('{'); val b = t.lastIndexOf('}')
    if (a < 0 || b < a) throw GErr("bad_json")
    return try { JSONObject(t.substring(a, b + 1)) } catch (e: Exception) { throw GErr("bad_json") }
}

fun fromAI(res: JSONObject): Plan {
    val b = res.optJSONArray("subject_box") ?: res.optJSONArray("box_2d") ?: res.optJSONArray("box")
    if (b == null || b.length() < 4) throw GErr("bad_json")
    fun n(v: Double) = cl(v / 1000, 0.0, 1.0)
    var y0 = n(b.optDouble(0, 0.0)); var x0 = n(b.optDouble(1, 0.0)); var y1 = n(b.optDouble(2, 0.0)); var x1 = n(b.optDouble(3, 0.0))
    if (x1 < x0) { val t = x0; x0 = x1; x1 = t }; if (y1 < y0) { val t = y0; y0 = y1; y1 = t }
    val subj = Subj(res.optString("subject", "motivo").lowercase().take(36), null, 1.0,
        doubleArrayOf(x0, y0, maxOf(.01, x1 - x0), maxOf(.01, y1 - y0)), doubleArrayOf((x0 + x1) / 2, y0 + (y1 - y0) * .4))
    var shot: Shot? = null
    val sb = res.optJSONArray("shot_box")
    if (sb != null && sb.length() >= 4) {
        val a = n(sb.optDouble(0, 0.0)); val c = n(sb.optDouble(1, 0.0)); val d = n(sb.optDouble(2, 0.0)); val e = n(sb.optDouble(3, 0.0))
        shot = Shot(minOf(c, e), minOf(a, d), kotlin.math.abs(e - c), kotlin.math.abs(d - a))
        if (shot.w < .05 || shot.h < .05) shot = null
    }
    val rule = res.optString("rule").let { if (it in STYLE_IDS) it else "tercos" }
    val filter = res.optString("filter").takeIf { it in FILTER_IDS }
    val border = res.optString("border").takeIf { it in BORDER_IDS }
    val lens = res.optDouble("lens", Double.NaN).takeIf { !it.isNaN() && it > 0 }
    return Plan(subj, shot, rule, res.optString("tip", "").take(70), filter, border, subj.name, lens).also { it.idea = res.optString("idea", "").take(120) }
}

fun errCopy(e: Throwable): String {
    val c = (e as? GErr)?.code ?: ""; val m = ((e as? GErr)?.msg ?: "").lowercase()
    return when {
        c == "g_net" -> "Sem ligação ao Gemini."
        c == "g_timeout" -> "O Gemini demorou demasiado."
        c == "g_paused" -> "Gemini em pausa (estava sobrecarregado)."
        c == "g_400" -> if (m.contains("api key") || m.contains("api_key")) "Gemini: a chave não é válida." else "Gemini: pedido recusado."
        c == "g_401" || c == "g_403" -> "Gemini: a chave não tem acesso."
        c == "g_404" -> "Gemini: modelo não encontrado. Muda-o nas Definições."
        c == "g_429" -> "Gemini: limite gratuito atingido. Espera um pouco."
        c.startsWith("g_5") -> "Gemini indisponível de momento."
        c == "g_empty" || c == "bad_json" -> "A IA respondeu num formato inesperado."
        else -> "Não consegui analisar a cena."
    }
}
