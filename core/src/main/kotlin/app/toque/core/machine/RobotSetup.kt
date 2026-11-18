package app.toque.core.machine

/**
 * Réglage du robot au premier lancement : les Monsieur Cuisine connect ne se comportent pas tous
 * pareil (capteur du bol signalé à l'envers sur certaines bases, bouton rotatif qui n'envoie pas
 * les mêmes touches), et rien dans Android ne dit de quelle série est le robot. On ne devine donc
 * pas : on fait faire deux gestes et on retient ce que le robot répond.
 *
 * Ici, la logique seule (sans Android), pour pouvoir la tester.
 */
object RobotSetup {
    /** Ce que vaut le bit « bol » de la base par rapport à la présence réelle du bol. */
    enum class BowlSensor {
        /** Bit à 1 bol en place, à 0 bol retiré. */
        NORMAL,
        /** Bit à 0 bol en place, à 1 bol retiré : lecture à inverser. */
        INVERTED,
        /** Le bit ne change pas quand on retire le bol : capteur inutilisable, on ne s'y fie pas. */
        UNUSABLE,
    }

    /** [bitWithBowl] et [bitWithoutBowl] : le bit brut de la base, lu bol en place puis bol retiré. */
    fun bowlSensor(bitWithBowl: Boolean, bitWithoutBowl: Boolean): BowlSensor = when {
        bitWithBowl == bitWithoutBowl -> BowlSensor.UNUSABLE
        bitWithBowl -> BowlSensor.NORMAL
        else -> BowlSensor.INVERTED
    }

    /** Touches envoyées par le bouton rotatif ; -1 : pas encore relevée (ou pas de bouton). */
    data class Knob(val right: Int = -1, val left: Int = -1, val press: Int = -1) {
        val complete: Boolean get() = right >= 0 && left >= 0 && press >= 0

        /**
         * Retient [keyCode] pour le prochain geste attendu (droite, puis gauche, puis appui).
         * Une touche déjà retenue pour un autre geste est refusée : un tour de bouton envoie
         * plusieurs crans, et ceux qui arrivent encore après la capture ne doivent pas compter
         * pour le geste suivant. Renvoie le même objet si la touche est refusée.
         */
        fun accept(keyCode: Int): Knob = when {
            keyCode < 0 || keyCode == right || keyCode == left || keyCode == press -> this
            right < 0 -> copy(right = keyCode)
            left < 0 -> copy(left = keyCode)
            press < 0 -> copy(press = keyCode)
            else -> this
        }
    }
}
