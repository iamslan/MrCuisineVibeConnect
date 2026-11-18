package app.toque.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.link.HomeLink
import app.toque.core.link.HomeLink.Pairing
import app.toque.core.machine.EventType
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeFilter
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Lien domotique : petit serveur HTTP local, en lecture seule, pour Home Assistant et consorts.
 * Contrat : docs/HOME_LINK_API.md. Désactivé par défaut (Réglages › Lien domotique).
 *
 * Aucune route ne commande la machine : le serveur ne connaît que l'état publié par
 * [app.toque.machine.MachineClient] et la bibliothèque de recettes. Tout sauf /api/info et
 * /api/pair exige un jeton obtenu par association (code affiché sur l'écran du robot).
 */
class HomeLinkServer(private val app: ToqueApp) {
    private val prefs: Prefs get() = app.prefs
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "toque-lien").apply { isDaemon = true } }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val streams = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()
    private val random = SecureRandom()
    val pairing = Pairing()

    @Volatile private var socket: ServerSocket? = null
    @Volatile var port = 0
        private set
    private var unsubSnapshot: (() -> Unit)? = null
    private var unsubEvents: (() -> Unit)? = null
    private var nsd: NsdManager.RegistrationListener? = null

    // État publié : calculé sur le fil principal à chaque échantillon, lu par les fils du serveur.
    @Volatile private var snapshot = Snapshot()
    @Volatile private var guidedCache: HomeLink.Guided? = null
    private var lastStable: Map<String, Any?>? = null
    private var lastPushMs = 0L
    private var pushPending = false
    private val version: String by lazy {
        try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    val running: Boolean get() = socket != null

    /** Identifiant stable de ce robot (créé au premier besoin). */
    val id: String
        get() {
            var v = prefs.linkId
            if (v.isEmpty()) {
                v = "toque-" + hex(ByteArray(4).also { random.nextBytes(it) })
                prefs.linkId = v
            }
            return v
        }

    // ------------------------------------------------------------------ marche / arrêt

    /** À appeler sur le fil principal. */
    fun start(): Boolean {
        if (running) return true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(prefs.linkPort))
            }
        } catch (e: IOException) {
            app.log.write("lien", "port ${prefs.linkPort} indisponible : ${e.message}")
            return false
        }
        socket = s
        port = s.localPort
        unsubSnapshot = app.machine.onSnapshot { onSnapshot(it) }
        unsubEvents = app.machine.onEvent { onEvent(it) }
        Thread({ acceptLoop(s) }, "toque-lien-accueil").apply { isDaemon = true }.start()
        announce()
        app.log.write("lien", "lien domotique ouvert sur le port $port (lecture seule)")
        return true
    }

    fun stop() {
        val s = socket ?: return
        socket = null
        unsubSnapshot?.invoke(); unsubSnapshot = null
        unsubEvents?.invoke(); unsubEvents = null
        pairing.close()
        try { s.close() } catch (_: IOException) {}
        for (q in streams) q.offer(CLOSE)
        nsd?.let { l ->
            try { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (_: Exception) {}
        }
        nsd = null
        app.log.write("lien", "lien domotique fermé")
    }

    /** Annonce mDNS (« _toque._tcp ») : Home Assistant trouve le robot tout seul. */
    private fun announce() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "Toque " + id.removePrefix("toque-")
                serviceType = HomeLink.SERVICE_TYPE
                port = this@HomeLinkServer.port
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                    app.log.write("lien", "annonce sur le réseau impossible (code $code) : l'adresse reste utilisable à la main")
                }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            (app.getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = l
        } catch (e: Exception) {
            app.log.write("lien", "annonce sur le réseau impossible : $e")
        }
    }

    /** Adresses où joindre le robot sur le réseau local (« 192.168.1.20:8730 »). */
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress + ":" + (if (port > 0) port else prefs.linkPort) }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ association

    /** Affiche un nouveau code d'association (6 chiffres, 5 minutes). */
    fun openPairing(): String {
        val code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
        pairing.open(code, System.currentTimeMillis())
        return code
    }

    val pairedCount: Int get() = prefs.linkTokens.size

    fun unpairAll() {
        prefs.linkTokens = emptySet()
        // Les flux ouverts avec les anciens jetons sont coupés.
        for (q in streams) q.offer(CLOSE)
        app.log.write("lien", "tous les appareils associés ont été retirés")
    }

    private fun authorized(r: HomeLink.Request): Boolean {
        val t = r.bearer
        return t.isNotEmpty() && sha256(t) in prefs.linkTokens
    }

    // ------------------------------------------------------------------ état et évènements (fil principal)

    private fun context(): HomeLink.Context = HomeLink.Context(
        id = id,
        version = version,
        mode = prefs.mode.key,
        bowlSensorReliable = !prefs.bowlSensorBad,
        guided = guidedCache,
    )

    private fun statusNow(): Map<String, Any?> = HomeLink.status(snapshot, context(), System.currentTimeMillis() / 1000)

    /** Recette guidée en cours d'après les réglages (tenus à jour par l'écran de cuisson). */
    private fun refreshGuided() {
        val rid = prefs.guidedRecipe
        if (rid.isEmpty()) {
            guidedCache = null
            return
        }
        val step = prefs.guidedStep
        val cached = guidedCache
        if (cached != null && cached.recipe.id == rid) {
            if (cached.stepIndex != step) guidedCache = HomeLink.Guided(cached.recipe, step)
            return
        }
        val r: Recipe? = try { app.recipes.getAdjusted(rid) } catch (_: Exception) { null }
        guidedCache = r?.let { HomeLink.Guided(it, step) }
    }

    private fun onSnapshot(s: Snapshot) {
        snapshot = s
        refreshGuided()
        if (streams.isEmpty()) {
            lastStable = null
            return
        }
        val status = statusNow()
        val stable = HomeLink.stable(status)
        val now = System.currentTimeMillis()
        val changed = stable != lastStable
        if (!changed && now - lastPushMs < HEARTBEAT_MS) return
        if (now - lastPushMs < MIN_PUSH_MS) {
            // Trop tôt : un seul envoi différé rattrapera le dernier état.
            if (!pushPending) {
                pushPending = true
                main.postDelayed({ pushPending = false; onSnapshot(snapshot) }, MIN_PUSH_MS)
            }
            return
        }
        lastStable = stable
        lastPushMs = now
        broadcast("status", status)
    }

    private fun onEvent(e: MachineEvent) {
        refreshGuided()
        val s = snapshot
        val now = System.currentTimeMillis() / 1000
        val kind = s.kind?.name?.lowercase()
        val label = s.jobLabel.ifEmpty { s.kind?.label ?: "" }
        val guided = HomeLink.guided(guidedCache, s)
        when (e.type) {
            EventType.JOB_STARTED -> broadcast("job_started", linkedMapOf("time" to now, "kind" to kind, "label" to label,
                "total" to (s.totalMs + 500) / 1000, "guided" to guided))
            EventType.JOB_PAUSED -> broadcast("job_paused", linkedMapOf("time" to now, "kind" to kind, "label" to label, "reason" to e.message))
            EventType.JOB_RESUMED -> broadcast("job_resumed", linkedMapOf("time" to now, "kind" to kind, "label" to label))
            EventType.JOB_ABORTED -> broadcast("job_aborted", linkedMapOf("time" to now, "kind" to kind, "label" to label, "reason" to e.message))
            EventType.JOB_FINISHED -> {
                broadcast("job_finished", linkedMapOf("time" to now, "kind" to kind, "label" to label, "guided" to guided))
                val before = guidedCache
                if (before != null) main.postDelayed({ afterGuidedStep(before) }, AFTER_STEP_MS)
            }
            EventType.LID_OPENED -> broadcast("lid_opened", linkedMapOf("time" to now))
            EventType.LID_CLOSED -> broadcast("lid_closed", linkedMapOf("time" to now))
            EventType.FAULT -> broadcast("fault", linkedMapOf("time" to now, "code" to s.faultCode.lowercase(), "message" to e.message))
            else -> Unit
        }
    }

    /**
     * Quelques secondes après la fin d'une étape de recette : si rien n'a redémarré tout seul
     * (étapes enchaînées), soit la recette est finie, soit le robot attend le cuisinier.
     */
    private fun afterGuidedStep(finished: HomeLink.Guided) {
        refreshGuided()
        val s = snapshot
        if (s.busy) return
        val now = System.currentTimeMillis() / 1000
        val current = guidedCache
        val last = finished.recipe.steps.size - 1
        val wasLast = finished.stepIndex >= last && (current == null || current.stepIndex >= last)
        if (current == null || wasLast) {
            broadcast("recipe_finished", linkedMapOf("time" to now, "recipe_id" to finished.recipe.id, "recipe_title" to finished.recipe.title))
        } else {
            broadcast("waiting_for_user", linkedMapOf("time" to now, "guided" to HomeLink.guided(current, s)))
        }
    }

    private fun broadcast(event: String, data: Map<String, Any?>) {
        if (streams.isEmpty()) return
        val message = "event: $event\r\ndata: ${Json.write(data)}\r\n\r\n"
        for (q in streams) {
            // Un client qui ne lit plus ne retient pas la mémoire du robot : on le coupe.
            if (q.size > MAX_QUEUE) q.offer(CLOSE) else q.offer(message)
        }
    }

    // ------------------------------------------------------------------ serveur

    private fun acceptLoop(server: ServerSocket) {
        while (socket === server) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (!slots.tryAcquire()) {
                try { client.close() } catch (_: IOException) {}
                continue
            }
            pool.execute {
                try {
                    handle(client)
                } catch (_: Exception) {
                } finally {
                    try { client.close() } catch (_: IOException) {}
                    slots.release()
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val out = client.getOutputStream()
        val r = try {
            HomeLink.readRequest(client.getInputStream())
        } catch (e: HomeLink.BadRequest) {
            return error(out, 400, "bad_request", e.message ?: "")
        } catch (_: SocketTimeoutException) {
            return
        }
        val get = r.method == "GET"
        when {
            get && r.path == "/api/info" -> json(out, 200, linkedMapOf(
                "id" to id, "name" to "Toque", "version" to version, "api" to HomeLink.API,
                "model" to HomeLink.MODEL, "pairing_open" to pairing.isOpen(System.currentTimeMillis()),
            ))
            r.method == "POST" && r.path == "/api/pair" -> pair(out, r)
            !r.path.startsWith("/api/") -> error(out, 404, "not_found", "")
            !authorized(r) -> error(out, 401, "unauthorized", "")
            !get -> error(out, 400, "bad_request", "lecture seule")
            r.path == "/api/status" -> json(out, 200, statusNow())
            r.path == "/api/events" -> events(client, out)
            r.path == "/api/categories" -> json(out, 200, linkedMapOf("categories" to app.recipes.index.categories))
            r.path == "/api/recipes" -> recipes(out, r)
            r.path.startsWith("/api/recipes/") -> recipe(out, r.path.removePrefix("/api/recipes/"))
            else -> error(out, 404, "not_found", "")
        }
    }

    private fun pair(out: OutputStream, r: HomeLink.Request) {
        val body = try { Json.parse(r.body).obj() } catch (_: Exception) { emptyMap() }
        val code = body.str("code").orEmpty()
        when (pairing.submit(code, System.currentTimeMillis())) {
            Pairing.Result.OK -> {
                val token = hex(ByteArray(32).also { random.nextBytes(it) })
                prefs.linkTokens = prefs.linkTokens + sha256(token)
                val name = body.str("name").orEmpty().take(40)
                app.log.write("lien", "appareil associé : " + name.ifEmpty { "(sans nom)" })
                main.post { onPaired?.invoke(name) }
                json(out, 200, linkedMapOf("token" to token, "id" to id, "name" to "Toque"))
            }
            Pairing.Result.BAD_CODE -> error(out, 403, "bad_code", "")
            Pairing.Result.CLOSED -> error(out, 403, "pairing_closed", "")
            Pairing.Result.TOO_MANY -> {
                app.log.write("lien", "association annulée : 5 codes faux")
                error(out, 429, "too_many_attempts", "")
            }
        }
    }

    /** Appelé sur le fil principal quand un appareil vient d'être associé (pour fermer l'écran du code). */
    @Volatile var onPaired: ((String) -> Unit)? = null

    private fun recipes(out: OutputStream, r: HomeLink.Request) {
        val limit = (r.query["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (r.query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val favorites = r.query["favorites"].let { it != null && it != "0" && !it.equals("false", ignoreCase = true) }
        val filter = RecipeFilter(category = r.query["category"].orEmpty(), favoritesOnly = favorites)
        val hits = app.recipes.index.search(r.query["q"].orEmpty(), filter, limit = 100_000)
        val page = hits.drop(offset).take(limit)
        json(out, 200, linkedMapOf(
            "total" to hits.size, "offset" to offset, "limit" to limit,
            "items" to page.map { HomeLink.recipeSummary(it.entry, app.recipes.imageFile(it.entry.image) != null) },
        ))
    }

    private fun recipe(out: OutputStream, rest: String) {
        val image = rest.endsWith("/image")
        val rid = if (image) rest.removeSuffix("/image") else rest
        if (rid.isEmpty() || rid.contains('/')) return error(out, 404, "not_found", "")
        val r = app.recipes.getAdjusted(rid) ?: return error(out, 404, "not_found", "")
        val file = app.recipes.imageFile(r.image)
        if (!image) return json(out, 200, HomeLink.recipeDetail(r, file != null))
        if (file == null) return error(out, 404, "not_found", "")
        val bytes = file.readBytes()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: max-age=86400\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** Flux d'évènements (SSE) : l'état d'abord, puis tout ce qui arrive, jusqu'à la déconnexion. */
    private fun events(client: Socket, out: OutputStream) {
        val q = LinkedBlockingQueue<String>()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        q.offer("event: status\r\ndata: ${Json.write(statusNow())}\r\n\r\n")
        streams += q
        // Le prochain échantillon repart d'un état « jamais envoyé » pour ce nouveau client.
        main.post { lastStable = null }
        try {
            client.soTimeout = 0
            while (socket != null) {
                val m = q.poll(KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
                if (m === CLOSE) break
                out.write((m ?: ": keep-alive\r\n\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: IOException) {
        } finally {
            streams -= q
        }
    }

    private fun json(out: OutputStream, code: Int, body: Map<String, Any?>) {
        val bytes = Json.write(body).toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun error(out: OutputStream, code: Int, error: String, message: String) =
        json(out, code, linkedMapOf("error" to error, "message" to message))

    private fun sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

    companion object {
        private const val MAX_CONNECTIONS = 12
        private const val READ_TIMEOUT_MS = 10_000
        private const val KEEP_ALIVE_MS = 15_000L
        private const val MIN_PUSH_MS = 1_000L
        private const val HEARTBEAT_MS = 30_000L
        private const val AFTER_STEP_MS = 3_000L
        private const val MAX_QUEUE = 200
        /** Sentinelle : ferme un flux d'évènements (comparée par identité). */
        private val CLOSE = String(charArrayOf('x'))
    }
}
