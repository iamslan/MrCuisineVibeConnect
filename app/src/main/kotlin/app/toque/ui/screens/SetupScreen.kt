package app.toque.ui.screens

import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.RobotSetup
import app.toque.core.machine.RobotSetup.BowlSensor
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Réglage du robot, proposé la première fois que Toque pilote la vraie machine (et relançable
 * depuis Réglages › Outils). Les robots d'une même gamme ne répondent pas tous pareil : le capteur
 * du bol est signalé à l'envers sur certaines bases, le bouton rotatif n'envoie pas partout les
 * mêmes touches, et Android se présente partout comme « spm188a ». Plutôt que de deviner la série,
 * on fait faire quatre gestes et on retient ce que le robot répond (voir [RobotSetup]).
 *
 * Rien n'est enregistré avant le résumé : quitter l'écran en cours de route ne change aucun réglage.
 */
class SetupScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglage du robot")
    override val keepAwake: Boolean get() = true

    private enum class Step { INTRO, BOWL_IN, BOWL_OUT, KNOB_RIGHT, KNOB_LEFT, KNOB_PRESS, SUMMARY }

    private var step = Step.INTRO
    private lateinit var box: LinearLayout
    private var live: TextView? = null
    private var confirm: View? = null

    /** Bit « bol » brut de la base aux deux mesures ; null : étape passée. */
    private var bitWithBowl: Boolean? = null
    private var bitWithoutBowl: Boolean? = null
    private var knob = RobotSetup.Knob()
    private var knobSkipped = false

    override fun build(): View {
        box = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        box.setPadding(Ui.u(60), Ui.u(18), Ui.u(60), Ui.u(24))
        render()
        return box
    }

    override fun onShow() {
        if (step == Step.SUMMARY) {
            // L'écran est réutilisé : un nouveau passage repart du début.
            step = Step.INTRO
            bitWithBowl = null; bitWithoutBowl = null; knob = RobotSetup.Knob(); knobSkipped = false
        }
        render()
    }

    override fun onHide() {
        a.knobListener = null
    }

    override fun onSnapshot(s: Snapshot) {
        val t = live ?: return
        if (step == Step.INTRO) {
            // La version de la base moteur n'est connue qu'une fois la liaison établie.
            t.text = identity(s)
            return
        }
        if (step != Step.BOWL_IN && step != Step.BOWL_OUT) return
        val bit = rawBowlBit(s)
        t.text = if (bit == null) tr("Base moteur non connectée : cette étape ne peut pas être faite maintenant.")
        else tr("Signal du capteur en ce moment : %s", if (bit) "1" else "0")
        confirm?.let { Ui.setButtonEnabled(it, bit != null) }
    }

    /** Bit « bol » tel que la base l'envoie, sans l'inversion éventuellement déjà réglée. */
    private fun rawBowlBit(s: Snapshot): Boolean? {
        if (!s.hasSample || s.rxHex.isEmpty()) return null
        return try {
            Mcc.decodeRx(Mcc.parseHex(s.rxHex)).bowlNtcDetected
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ étapes

    private fun go(next: Step) {
        step = next
        render()
    }

    private fun render() {
        box.removeAllViews()
        live = null
        confirm = null
        a.knobListener = null
        when (step) {
            Step.INTRO -> intro()
            Step.BOWL_IN -> bowl(
                tr("Capteur du bol · 1 sur 2"),
                tr("Posez le bol sur la base"),
                tr("Enclenchez-le bien, comme pour cuisiner. Le couvercle peut rester ouvert."),
                tr("Le bol est en place"),
            ) { bit -> bitWithBowl = bit; go(Step.BOWL_OUT) }
            Step.BOWL_OUT -> bowl(
                tr("Capteur du bol · 2 sur 2"),
                tr("Retirez le bol de la base"),
                tr("Soulevez-le complètement et posez-le à côté du robot."),
                tr("Le bol est retiré"),
            ) { bit -> bitWithoutBowl = bit; go(Step.KNOB_RIGHT) }
            Step.KNOB_RIGHT -> knobStep(tr("Bouton rotatif · 1 sur 3"), tr("Tournez le bouton d'un cran vers la droite"))
            Step.KNOB_LEFT -> knobStep(tr("Bouton rotatif · 2 sur 3"), tr("Tournez le bouton d'un cran vers la gauche"))
            Step.KNOB_PRESS -> knobStep(tr("Bouton rotatif · 3 sur 3"), tr("Appuyez une fois sur le bouton"))
            Step.SUMMARY -> summary()
        }
        onSnapshot(a.snapshot)
    }

    private fun heading(small: String, big: String, body: String) {
        if (small.isNotEmpty()) box.addView(Ui.text(c, small.uppercase(), 15f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.08f },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        box.addView(Ui.text(c, big, 34f, Ui.TEXT, Ui.LIGHT).apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, t = 10))
        if (body.isNotEmpty()) box.addView(Ui.text(c, body, 19f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
            Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
    }

    private fun buttons(vararg views: View) {
        box.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val row = Ui.horizontal(c, Gravity.CENTER)
        for ((i, v) in views.withIndex()) row.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP, l = if (i > 0) 14 else 0))
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
    }

    private fun intro() {
        heading(
            "", tr("Réglons Toque sur votre robot"),
            tr("Tous les Monsieur Cuisine connect ne répondent pas de la même façon. En quatre gestes, Toque vérifie le capteur du bol et le bouton rotatif de celui-ci. Bol vide, une minute suffit."),
        )
        val id = Ui.text(c, identity(a.snapshot), 16f, Ui.TEXT3).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        box.addView(id, Ui.lp(Ui.MATCH, Ui.WRAP, t = 22))
        live = id
        buttons(
            Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 64) {
                // On ne repose pas la question à chaque allumage : l'assistant reste dans Réglages › Outils.
                a.prefs.setupDone = true
                a.toast(tr("À relancer quand vous voulez : Réglages › Outils › Réglage du robot"))
                a.back()
            },
            // Le capteur du bol ne se vérifie que sur la vraie base (la base simulée n'a pas de bol à retirer).
            Ui.button(c, tr("Commencer"), Ui.Style.PRIMARY, height = 64) {
                go(if (a.prefs.mode == Prefs.Mode.HARDWARE) Step.BOWL_IN else Step.KNOB_RIGHT)
            },
        )
    }

    private fun bowl(small: String, big: String, body: String, done: String, onDone: (Boolean) -> Unit) {
        heading(small, big, body)
        val t = Ui.text(c, "", 18f, Ui.TEXT3).apply { gravity = Gravity.CENTER }
        box.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP, t = 22))
        live = t
        val ok = Ui.button(c, done, Ui.Style.PRIMARY, height = 64) {
            val bit = rawBowlBit(a.snapshot)
            if (bit == null) a.toast(tr("Base moteur non connectée"), Ui.WARN) else onDone(bit)
        }
        confirm = ok
        buttons(
            Ui.button(c, tr("Passer cette étape"), Ui.Style.GHOST, height = 64) {
                bitWithBowl = null; bitWithoutBowl = null
                go(Step.KNOB_RIGHT)
            },
            ok,
        )
    }

    private fun knobStep(small: String, big: String) {
        heading(small, big, tr("Toque retient le signal envoyé par le bouton."))
        val t = Ui.text(c, "…", 56f, Ui.TEXT, Ui.THIN)
        box.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        a.knobListener = { e -> onKnobKey(e, t) }
        buttons(
            Ui.button(c, tr("Mon robot n'a pas ce bouton"), Ui.Style.GHOST, height = 64) {
                knobSkipped = true
                go(Step.SUMMARY)
            },
        )
    }

    private fun onKnobKey(e: KeyEvent, shown: TextView) {
        if (e.repeatCount > 0) return
        val next = knob.accept(e.keyCode)
        // Même objet : touche déjà prise par un geste précédent (crans restants du même tour).
        if (next === knob) return
        knob = next
        shown.text = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        shown.setTextColor(Ui.OK)
        val after = when (step) {
            Step.KNOB_RIGHT -> Step.KNOB_LEFT
            Step.KNOB_LEFT -> Step.KNOB_PRESS
            else -> Step.SUMMARY
        }
        // Laisse voir le signal reçu, et passer les crans restants du même tour.
        a.knobListener = { _ -> }
        val from = step
        box.postDelayed({ if (a.current === this && step == from) go(after) }, 700)
    }

    // ------------------------------------------------------------------ résumé

    private fun summary() {
        val lines = ArrayList<String>()
        val inBowl = bitWithBowl
        val outBowl = bitWithoutBowl
        var bowlNote = ""
        if (inBowl == null || outBowl == null) {
            lines += tr("Capteur du bol : étape passée, réglage inchangé.")
        } else when (RobotSetup.bowlSensor(inBowl, outBowl)) {
            BowlSensor.NORMAL -> {
                a.prefs.bowlSensorBad = false
                a.prefs.invertBowl = false
                a.prefs.requireBowl = true
                lines += tr("Capteur du bol : fonctionne, dans le sens habituel.")
                bowlNote = tr("Toque refusera désormais de démarrer sans le bol (Réglages › Sécurité pour changer).")
            }
            BowlSensor.INVERTED -> {
                a.prefs.bowlSensorBad = false
                a.prefs.invertBowl = true
                a.prefs.requireBowl = true
                lines += tr("Capteur du bol : fonctionne, signalé à l'envers sur ce robot. Toque en tient compte.")
                bowlNote = tr("Toque refusera désormais de démarrer sans le bol (Réglages › Sécurité pour changer).")
            }
            BowlSensor.UNUSABLE -> {
                a.prefs.bowlSensorBad = true
                a.prefs.requireBowl = false
                lines += tr("Capteur du bol : le signal n'a pas changé en retirant le bol. Toque ne s'y fiera pas.")
            }
        }
        if (knob.complete) {
            a.prefs.knobRight = knob.right
            a.prefs.knobLeft = knob.left
            a.prefs.knobPress = knob.press
            lines += tr("Bouton rotatif : reconnu (droite %s, gauche %s, appui %s).", keyName(knob.right), keyName(knob.left), keyName(knob.press))
        } else {
            lines += if (knobSkipped) tr("Bouton rotatif : étape passée, réglage inchangé.") else tr("Bouton rotatif : réglage inchangé.")
        }
        a.prefs.setupDone = true
        a.machine.reconfigure { a.refusal(it) }
        a.app.log.write("réglage", identity(a.snapshot).replace("\n", " · ") + " · " + lines.joinToString(" "))

        heading("", tr("C'est réglé"), "")
        for (l in lines) box.addView(Ui.text(c, l, 19f, Ui.TEXT).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
            Ui.lp(Ui.MATCH, Ui.WRAP, t = 14))
        if (bowlNote.isNotEmpty()) box.addView(Ui.text(c, bowlNote, 16f, Ui.TEXT3).apply { gravity = Gravity.CENTER },
            Ui.lp(Ui.MATCH, Ui.WRAP, t = 10))
        if (inBowl != null && outBowl != null) box.addView(Ui.text(c, tr("Vous pouvez remettre le bol en place."), 16f, Ui.TEXT3)
            .apply { gravity = Gravity.CENTER }, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
        buttons(Ui.button(c, tr("Terminer"), Ui.Style.PRIMARY, height = 64) { a.back() })
    }

    private fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    /** Ce qu'on sait du robot : utile dans le journal et pour signaler un modèle encore inconnu. */
    private fun identity(s: Snapshot): String {
        val system = systemProperty("ro.custom.build.version").ifEmpty { Build.DISPLAY.orEmpty() }
        val vendor = try {
            a.packageManager.getPackageInfo(a.prefs.vendorPackage, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val parts = ArrayList<String>()
        parts += tr("Tablette %s, système %s", Build.PRODUCT.orEmpty().ifEmpty { "?" }, system.ifEmpty { "?" })
        if (vendor.isNotEmpty()) parts += tr("appli d'origine %s", vendor)
        if (a.prefs.mode == Prefs.Mode.HARDWARE && s.hasSample && s.firmware > 0) parts += tr("base moteur %d", s.firmware)
        return parts.joinToString("\n")
    }

    private fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}
