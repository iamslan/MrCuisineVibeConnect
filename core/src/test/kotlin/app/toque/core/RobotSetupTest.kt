package app.toque.core

import app.toque.core.machine.RobotSetup
import app.toque.core.machine.RobotSetup.BowlSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réglage du robot au premier lancement : capteur du bol et bouton rotatif. */
class RobotSetupTest {
    @Test fun bowlSensorIsReadFromTwoMeasurements() {
        assertEquals(BowlSensor.NORMAL, RobotSetup.bowlSensor(bitWithBowl = true, bitWithoutBowl = false))
        // SKMC 1200 B2 / C3, SKMC 1100 A1, base 4922 : bit à 0 bol en place.
        assertEquals(BowlSensor.INVERTED, RobotSetup.bowlSensor(bitWithBowl = false, bitWithoutBowl = true))
        // Le bit ne bouge pas : on ne s'y fie pas, dans un sens comme dans l'autre.
        assertEquals(BowlSensor.UNUSABLE, RobotSetup.bowlSensor(bitWithBowl = true, bitWithoutBowl = true))
        assertEquals(BowlSensor.UNUSABLE, RobotSetup.bowlSensor(bitWithBowl = false, bitWithoutBowl = false))
    }

    @Test fun knobKeysAreTakenInOrderRightLeftPress() {
        // Ce robot : F6 à droite (136), F7 à gauche (137), F5 à l'appui (135).
        var k = RobotSetup.Knob()
        assertFalse(k.complete)
        k = k.accept(136)
        assertEquals(RobotSetup.Knob(right = 136), k)
        k = k.accept(137)
        k = k.accept(135)
        assertEquals(RobotSetup.Knob(right = 136, left = 137, press = 135), k)
        assertTrue(k.complete)
    }

    @Test fun extraClicksOfTheSameTurnDoNotCountForTheNextGesture() {
        val right = RobotSetup.Knob().accept(136)
        // Le tour à droite envoie encore deux crans : ils ne deviennent pas « gauche ».
        assertSame(right, right.accept(136))
        val both = right.accept(137)
        // Ni la droite ni la gauche ne peuvent servir d'appui.
        assertSame(both, both.accept(136))
        assertSame(both, both.accept(137))
        // Une fois complet, plus rien ne change ; un code invalide est ignoré.
        val done = both.accept(135)
        assertSame(done, done.accept(140))
        assertSame(right, right.accept(-1))
    }
}
