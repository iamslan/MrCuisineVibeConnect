package app.toque.core.link

import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * Lien domotique : ce que Toque montre du robot sur le réseau de la maison (Home Assistant…),
 * en lecture seule. Le contrat est décrit dans docs/HOME_LINK_API.md ; ici, tout ce qui se
 * calcule sans Android (état publié, association, lecture d'une requête HTTP), pour le tester.
 */
object HomeLink {
    const val API = 1
    const val DEFAULT_PORT = 8730
    const val SERVICE_TYPE = "_toque._tcp"
    const val MODEL = "Monsieur Cuisine connect"

    /** Recette guidée en cours : la recette et l'étape affichée (à partir de 0). */
    class Guided(val recipe: Recipe, val stepIndex: Int)

    /** Ce qui ne vient pas de la base moteur. */
    class Context(
        val id: String,
        val version: String,
        /** « hardware », « demo » ou « twin ». */
        val mode: String,
        /** Faux quand le réglage du robot a montré que le capteur du bol ne répond pas. */
        val bowlSensorReliable: Boolean = true,
        val guided: Guided? = null,
    )

    private fun seconds(ms: Long): Long = if (ms <= 0) 0 else (ms + 500) / 1000

    fun stepKind(st: Step): String = when {
        st.machine != null -> "machine"
        st.weigh != null -> "weigh"
        else -> "text"
    }

    fun guided(g: Guided?, s: Snapshot): Map<String, Any?>? {
        g ?: return null
        val steps = g.recipe.steps
        if (steps.isEmpty()) return null
        val i = g.stepIndex.coerceIn(0, steps.size - 1)
        val st = steps[i]
        return linkedMapOf(
            "recipe_id" to g.recipe.id,
            "recipe_title" to g.recipe.title,
            "step" to i + 1,
            "steps" to steps.size,
            "step_text" to st.text,
            "step_kind" to stepKind(st),
            "step_setting" to st.machine?.summary,
            "weigh_target" to st.weigh?.grams,
            // Recette ouverte et rien ne tourne : c'est au cuisinier de jouer.
            "waiting_for_user" to !s.busy,
        )
    }

    /** L'état du robot en un mot. */
    fun state(s: Snapshot, guided: Guided?): String = when {
        s.hasFault -> "fault"
        !s.hasSample || s.link == LinkState.OFFLINE || s.link == LinkState.LOST -> "offline"
        s.running -> "running"
        s.state == JobState.PAUSED -> "paused"
        guided != null && guided.recipe.steps.isNotEmpty() -> "waiting_for_user"
        else -> "idle"
    }

    private fun link(l: LinkState): String = when (l) {
        LinkState.OFFLINE, LinkState.LOST -> "offline"
        LinkState.ARMING -> "connecting"
        LinkState.READY -> "ready"
    }

    fun job(s: Snapshot, nowS: Long): Map<String, Any?>? {
        val state = s.state ?: return null
        val kind = s.kind ?: return null
        val heated = s.jobTempLevel > 0
        val remaining = seconds(s.remainingMs)
        return linkedMapOf(
            "kind" to kind.name.lowercase(),
            "label" to s.jobLabel.ifEmpty { kind.label },
            "state" to state.name.lowercase(),
            "pause_reason" to s.pauseReason.lowercase().ifEmpty { null },
            "elapsed" to seconds(s.elapsedMs),
            "total" to seconds(s.totalMs),
            "remaining" to remaining,
            "ends_at" to if (state == JobState.RUNNING) nowS + remaining else null,
            "phase" to s.phaseIndex + 1,
            "phases" to s.phaseCount,
            "speed" to s.jobSpeed,
            "target_temperature" to if (heated) app.toque.core.protocol.Mcc.celsiusForLevel(s.jobTempLevel) else null,
            "reverse" to s.jobReverse,
            "accessory" to s.accessory.lowercase(),
        )
    }

    /** Réponse de /api/status. [nowS] : heure Unix en secondes. */
    fun status(s: Snapshot, c: Context, nowS: Long): Map<String, Any?> {
        val job = job(s, nowS)
        val target = (job?.get("target_temperature") as? Int) ?: s.targetTempC.takeIf { s.heaterOn && it > 0 }
        return linkedMapOf(
            "id" to c.id,
            "version" to c.version,
            "time" to nowS,
            "link" to link(s.link),
            "mode" to c.mode,
            "state" to state(s, c.guided),
            "fault" to if (s.hasFault) linkedMapOf("code" to s.faultCode.lowercase(), "message" to s.faultMessage) else null,
            "lid_open" to (s.hasSample && s.lidOpen),
            "bowl_present" to if (c.bowlSensorReliable && s.hasSample) s.bowlDetected else null,
            "bowl_sensor_reliable" to c.bowlSensorReliable,
            "motor_on" to s.motorOn,
            "heater_on" to s.heaterOn,
            // La base ne mesure rien sous 37 °C (elle envoie 0) : température inconnue, « sous la plage ».
            "temperature" to if (s.hasSample && !s.tempBelowRange) s.actualTempC else null,
            "temperature_below_range" to s.tempBelowRange,
            "target_temperature" to target,
            "speed" to s.speedLevel,
            "rpm" to s.rpm,
            "weight" to if (s.hasSample) s.weightG else null,
            "weight_stable" to s.scaleStable,
            "firmware" to s.firmware.takeIf { it > 0 },
            "job" to job,
            "guided" to guided(c.guided, s),
        )
    }

    /**
     * Le même état sans ce qui change à chaque seconde (heure, temps écoulé et restant) : sert à
     * ne pousser l'état aux clients que lorsqu'il a vraiment changé. Le poids est arrondi à 5 g
     * et le régime ignoré, pour ne pas inonder le réseau pendant une pesée ou un mixage.
     */
    @Suppress("UNCHECKED_CAST")
    fun stable(status: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap(status)
        m.remove("time")
        m.remove("rpm")
        (m["weight"] as? Int)?.let { m["weight"] = Math.round(it / 5.0).toInt() * 5 }
        (m["job"] as? Map<String, Any?>)?.let { j ->
            val k = LinkedHashMap(j)
            k.remove("elapsed"); k.remove("remaining")
            // L'heure de fin glisse d'une seconde selon l'arrondi : on tolère 2 s.
            (k["ends_at"] as? Long)?.let { k["ends_at"] = it / 3 }
            m["job"] = k
        }
        return m
    }

    // ------------------------------------------------------------------ recettes

    fun recipeSummary(e: SearchIndex.Entry, hasImage: Boolean): Map<String, Any?> = linkedMapOf(
        "id" to e.id,
        "title" to e.title,
        "category" to e.category,
        "tags" to e.tags,
        "minutes" to e.totalMinutes,
        "rating" to e.rating,
        "rating_count" to e.ratingCount,
        "favorite" to e.favorite,
        "machine_steps" to e.machineSteps,
        "has_image" to hasImage,
    )

    fun recipeDetail(r: Recipe, hasImage: Boolean): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "title" to r.title,
        "category" to r.category,
        "tags" to r.tags,
        "servings" to r.servings,
        "prep_minutes" to r.prepMinutes,
        "minutes" to r.totalMinutes,
        "difficulty" to r.difficulty,
        "author" to r.author,
        "rating" to r.rating,
        "rating_count" to r.ratingCount,
        "favorite" to r.favorite,
        "notes" to r.notes,
        "has_image" to hasImage,
        "ingredients" to r.ingredients.map { g ->
            linkedMapOf(
                "group" to g.title,
                "items" to g.items.map { i ->
                    linkedMapOf("quantity" to i.qty.trim(), "unit" to i.unit.trim(), "name" to i.name.trim(), "note" to i.note.trim(), "text" to i.display)
                },
            )
        },
        "steps" to r.steps.map { st ->
            linkedMapOf("text" to st.text, "kind" to stepKind(st), "setting" to st.machine?.summary, "weigh_target" to st.weigh?.grams)
        },
    )

    // ------------------------------------------------------------------ association

    /**
     * Association d'un appareil : un code à 6 chiffres affiché sur le robot, valable 5 minutes,
     * annulé après 5 essais faux. Sans code à l'écran, rien ne peut s'associer.
     */
    class Pairing {
        enum class Result { OK, BAD_CODE, CLOSED, TOO_MANY }

        private var code: String? = null
        private var expiresMs = 0L
        private var wrong = 0

        @Synchronized fun open(code: String, nowMs: Long, validMs: Long = VALID_MS) {
            this.code = code
            expiresMs = nowMs + validMs
            wrong = 0
        }

        @Synchronized fun close() {
            code = null
        }

        @Synchronized fun isOpen(nowMs: Long): Boolean = code != null && nowMs < expiresMs

        /** Un code accepté ne sert qu'une fois. */
        @Synchronized fun submit(candidate: String, nowMs: Long): Result {
            val c = code
            if (c == null || nowMs >= expiresMs) {
                code = null
                return Result.CLOSED
            }
            // Comparaison en temps constant, pour ne rien laisser deviner chiffre par chiffre.
            val a = candidate.trim()
            var diff = a.length xor c.length
            for (i in 0 until minOf(a.length, c.length)) diff = diff or (a[i].code xor c[i].code)
            if (diff == 0) {
                code = null
                return Result.OK
            }
            wrong++
            if (wrong >= MAX_WRONG) {
                code = null
                return Result.TOO_MANY
            }
            return Result.BAD_CODE
        }

        companion object {
            const val VALID_MS = 5 * 60_000L
            const val MAX_WRONG = 5
        }
    }

    // ------------------------------------------------------------------ HTTP

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        /** Jeton « Authorization: Bearer … », sinon vide. */
        val bearer: String
            get() {
                val h = headers["authorization"]?.trim().orEmpty()
                return if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else ""
            }
    }

    class BadRequest(message: String) : Exception(message)

    /**
     * Lit une requête HTTP/1.1 (ligne de requête, en-têtes, corps éventuel). Volontairement
     * strict et borné : 8 Ko d'en-têtes, 4 Ko de corps, pas de corps découpé (chunked).
     */
    fun readRequest(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw BadRequest("requête incomplète")
            head.write(b)
            if (head.size() > MAX_HEAD) throw BadRequest("en-têtes trop longs")
            matched = if (b == END[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) throw BadRequest("ligne de requête")
        val method = first[0].uppercase()
        val target = first[1]
        if (!target.startsWith("/")) throw BadRequest("chemin")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val i = l.indexOf(':')
            if (i <= 0) throw BadRequest("en-tête")
            headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) throw BadRequest("corps découpé non pris en charge")
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw BadRequest("corps trop long")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw BadRequest("corps incomplet")
            read += n
        }
        val q = target.indexOf('?')
        val path = decode(if (q < 0) target else target.substring(0, q))
        val query = LinkedHashMap<String, String>()
        if (q >= 0) for (pair in target.substring(q + 1).split('&')) {
            if (pair.isEmpty()) continue
            val e = pair.indexOf('=')
            if (e < 0) query[decode(pair)] = "" else query[decode(pair.substring(0, e))] = decode(pair.substring(e + 1))
        }
        return Request(method, path, query, headers, String(body, Charsets.UTF_8))
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        throw BadRequest("encodage")
    }

    private const val END = "\r\n\r\n"
    private const val MAX_HEAD = 8 * 1024
    private const val MAX_BODY = 4 * 1024
}
