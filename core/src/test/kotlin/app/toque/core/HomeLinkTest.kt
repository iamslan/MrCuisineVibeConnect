package app.toque.core

import app.toque.core.json.Json
import app.toque.core.link.HomeLink
import app.toque.core.link.HomeLink.Pairing
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.Weigh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Lien domotique : état publié, association, lecture des requêtes (voir docs/HOME_LINK_API.md). */
class HomeLinkTest {
    private val ctx = HomeLink.Context("toque-1a2b3c4d", "0.6.0", "hardware")
    private val ready = Snapshot(link = LinkState.READY, hasSample = true, bowlDetected = true, weightG = 512, scaleStable = true, firmware = 4922)
    private val recipe = Recipe(
        id = "web-1", title = "Soupe",
        ingredients = listOf(IngredientGroup("", listOf(Ingredient("300", "g", "carottes", "épluchées"), Ingredient(name = "sel")))),
        steps = listOf(
            Step("Peser les carottes.", weigh = Weigh("carottes", 300)),
            Step("Mixer.", machine = StepMachine(seconds = 30, speed = 8)),
            Step("Servir."),
        ),
    )

    private fun running(remainingMs: Long = 145_000) = ready.copy(
        jobActive = true, jobKind = JobKind.MANUAL.name, jobState = JobState.RUNNING.name, jobLabel = "Cuisson manuelle",
        elapsedMs = 35_000, totalMs = 180_000, remainingMs = remainingMs, phaseCount = 1,
        jobSpeed = 2, jobTempLevel = 5, motorOn = true, heaterOn = true, speedLevel = 2, actualTempC = 41, targetTempC = 67,
    )

    @Test fun idleRobot() {
        val m = HomeLink.status(ready, ctx, 1000)
        assertEquals("idle", m["state"])
        assertEquals("ready", m["link"])
        assertEquals("hardware", m["mode"])
        assertNull(m["job"])
        assertNull(m["guided"])
        assertNull(m["fault"])
        assertEquals(true, m["bowl_present"])
        assertEquals(512, m["weight"])
        assertEquals(4922, m["firmware"])
        // La base envoie 0 sous 37 °C : pas de température, mais « sous la plage ».
        assertNull(m["temperature"])
        assertEquals(true, m["temperature_below_range"])
        // Le tout s'écrit en JSON et se relit.
        assertEquals("idle", (Json.parse(Json.write(m)) as Map<*, *>)["state"])
    }

    @Test fun offlineAndFault() {
        assertEquals("offline", HomeLink.status(Snapshot(), ctx, 0)["state"])
        assertEquals("offline", HomeLink.status(ready.copy(link = LinkState.LOST), ctx, 0)["state"])
        assertEquals("connecting", HomeLink.status(Snapshot(link = LinkState.ARMING), ctx, 0)["link"])
        val f = HomeLink.status(ready.copy(faultCode = "BOARD_ERROR", faultMessage = "Surchauffe"), ctx, 0)
        assertEquals("fault", f["state"])
        assertEquals(mapOf("code" to "board_error", "message" to "Surchauffe"), f["fault"])
    }

    @Test fun runningProgram() {
        val m = HomeLink.status(running(), ctx, 1000)
        assertEquals("running", m["state"])
        assertEquals(41, m["temperature"])
        assertEquals(false, m["temperature_below_range"])
        @Suppress("UNCHECKED_CAST") val j = m["job"] as Map<String, Any?>
        assertEquals("manual", j["kind"])
        assertEquals("running", j["state"])
        assertEquals(35L, j["elapsed"])
        assertEquals(180L, j["total"])
        assertEquals(145L, j["remaining"])
        assertEquals(1145L, j["ends_at"])
        assertEquals(60, j["target_temperature"])
        assertEquals(60, m["target_temperature"])
        assertEquals("blade", j["accessory"])
        assertNull(j["pause_reason"])
        // En pause : plus d'heure de fin, et la raison de la pause.
        val p = HomeLink.status(running().copy(jobState = JobState.PAUSED.name, pauseReason = "LID"), ctx, 1000)
        assertEquals("paused", p["state"])
        @Suppress("UNCHECKED_CAST") val pj = p["job"] as Map<String, Any?>
        assertNull(pj["ends_at"])
        assertEquals("lid", pj["pause_reason"])
    }

    @Test fun guidedRecipe() {
        val waiting = HomeLink.Context("id", "v", "hardware", guided = HomeLink.Guided(recipe, 0))
        val m = HomeLink.status(ready, waiting, 0)
        assertEquals("waiting_for_user", m["state"])
        @Suppress("UNCHECKED_CAST") val g = m["guided"] as Map<String, Any?>
        assertEquals("web-1", g["recipe_id"])
        assertEquals(1, g["step"])
        assertEquals(3, g["steps"])
        assertEquals("weigh", g["step_kind"])
        assertEquals(300, g["weigh_target"])
        assertNull(g["step_setting"])
        assertEquals(true, g["waiting_for_user"])
        // Étape machine en cours : le robot travaille, on n'attend pas le cuisinier.
        val cooking = HomeLink.Context("id", "v", "hardware", guided = HomeLink.Guided(recipe, 1))
        val r = HomeLink.status(running(), cooking, 0)
        assertEquals("running", r["state"])
        @Suppress("UNCHECKED_CAST") val rg = r["guided"] as Map<String, Any?>
        assertEquals("machine", rg["step_kind"])
        assertTrue((rg["step_setting"] as String).isNotEmpty())
        assertEquals(false, rg["waiting_for_user"])
        // Étape hors limites (recette modifiée entre-temps) : ramenée à la dernière.
        val last = HomeLink.guided(HomeLink.Guided(recipe, 99), ready)!!
        assertEquals(3, last["step"])
        assertEquals("text", last["step_kind"])
    }

    @Test fun unreliableBowlSensorIsNotPublished() {
        val c = HomeLink.Context("id", "v", "hardware", bowlSensorReliable = false)
        val m = HomeLink.status(ready, c, 0)
        assertNull(m["bowl_present"])
        assertEquals(false, m["bowl_sensor_reliable"])
    }

    @Test fun onlyRealChangesArePushed() {
        fun stable(s: Snapshot, now: Long) = HomeLink.stable(HomeLink.status(s, ctx, now))
        // Une seconde de plus : le temps restant baisse, l'heure de fin ne bouge pas.
        assertEquals(stable(running(145_000), 999), stable(running(144_000).copy(elapsedMs = 36_000), 1000))
        // Le régime et quelques grammes ne comptent pas.
        assertEquals(stable(ready, 0), stable(ready.copy(rpm = 40, weightG = 511), 50))
        // Un vrai changement compte.
        assertNotEquals(stable(ready, 0), stable(ready.copy(lidOpen = true), 0))
        assertNotEquals(stable(ready, 0), stable(ready.copy(weightG = 600), 0))
        assertNotEquals(stable(running(), 0), stable(running().copy(jobSpeed = 3), 0))
    }

    @Test fun recipeDetailCarriesShoppingLines() {
        val d = HomeLink.recipeDetail(recipe, hasImage = false)
        @Suppress("UNCHECKED_CAST") val items = ((d["ingredients"] as List<Map<String, Any?>>)[0]["items"]) as List<Map<String, Any?>>
        assertEquals("300 g carottes, épluchées", items[0]["text"])
        assertEquals("300", items[0]["quantity"])
        assertEquals("sel", items[1]["text"])
        assertEquals("", items[1]["quantity"])
        @Suppress("UNCHECKED_CAST") val steps = d["steps"] as List<Map<String, Any?>>
        assertEquals(listOf("weigh", "machine", "text"), steps.map { it["kind"] })
        assertEquals(300, steps[0]["weigh_target"])
    }

    // ------------------------------------------------------------------ association

    @Test fun pairingAcceptsTheCodeOnce() {
        val p = Pairing()
        assertEquals(Pairing.Result.CLOSED, p.submit("123456", 0))
        p.open("482913", nowMs = 1000)
        assertTrue(p.isOpen(2000))
        assertEquals(Pairing.Result.BAD_CODE, p.submit("482914", 2000))
        assertEquals(Pairing.Result.BAD_CODE, p.submit("48291", 2000))
        assertEquals(Pairing.Result.OK, p.submit(" 482913 ", 3000))
        // Le code a servi : il ne peut pas associer un second appareil.
        assertFalse(p.isOpen(3000))
        assertEquals(Pairing.Result.CLOSED, p.submit("482913", 3000))
    }

    @Test fun pairingExpiresAndGivesUpAfterFiveWrongCodes() {
        val p = Pairing()
        p.open("111111", nowMs = 0)
        assertEquals(Pairing.Result.CLOSED, p.submit("111111", Pairing.VALID_MS))
        p.open("222222", nowMs = 0)
        repeat(4) { assertEquals(Pairing.Result.BAD_CODE, p.submit("000000", 10)) }
        assertEquals(Pairing.Result.TOO_MANY, p.submit("000000", 10))
        // Même le bon code est refusé ensuite : il faut en afficher un nouveau.
        assertEquals(Pairing.Result.CLOSED, p.submit("222222", 10))
    }

    // ------------------------------------------------------------------ HTTP

    private fun request(text: String) = HomeLink.readRequest(text.replace("\n", "\r\n").byteInputStream(Charsets.UTF_8))

    @Test fun readsGetWithQueryAndToken() {
        val r = request("GET /api/recipes?q=soupe%20de+potiron&limit=20&favorites HTTP/1.1\nHost: robot\nAuthorization: Bearer abc123\n\n")
        assertEquals("GET", r.method)
        assertEquals("/api/recipes", r.path)
        assertEquals("soupe de potiron", r.query["q"])
        assertEquals("20", r.query["limit"])
        assertEquals("", r.query["favorites"])
        assertEquals("abc123", r.bearer)
        assertEquals("", r.body)
    }

    @Test fun readsPostBody() {
        val body = """{"code":"482913","name":"Maison"}"""
        val r = request("POST /api/pair HTTP/1.1\nContent-Type: application/json\nContent-Length: ${body.length}\n\n$body")
        assertEquals("POST", r.method)
        assertEquals(body, r.body)
        assertEquals("", r.bearer)
    }

    @Test fun refusesMalformedOrOversizedRequests() {
        fun bad(text: String) {
            try {
                request(text)
                fail("requête acceptée : $text")
            } catch (_: HomeLink.BadRequest) {
            }
        }
        bad("GET /api/status")
        bad("BONJOUR\n\n")
        bad("GET http://ailleurs/ HTTP/1.1\n\n")
        bad("POST /api/pair HTTP/1.1\nContent-Length: 999999\n\n")
        bad("POST /api/pair HTTP/1.1\nContent-Length: 10\n\nabc")
        bad("POST /api/pair HTTP/1.1\nTransfer-Encoding: chunked\n\n")
        bad("GET /api/status HTTP/1.1\nX: " + "a".repeat(9000) + "\n\n")
    }
}
