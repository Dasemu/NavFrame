package dev.navframe.core
import org.junit.Assert.*
import org.junit.Test
class SpeedCameraTest {
    private val camera = SpeedCamera("n/1", GeoPoint(43.004, -5.0))
    private val route = RouteResult(listOf(GeoPoint(43.0,-5.0), GeoPoint(43.01,-5.0)), emptyList(), 1100, 100)
    private fun fix(lat: Double, accuracy: Float = 5f, bearing: Float = 0f) = LocationFix(GeoPoint(lat,-5.0),bearing,15f,accuracy,0)
    @Test fun approachAlertsOnce() {
        val engine = SpeedCameraAlertEngine()
        assertNull(engine.update(fix(43.0),listOf(camera),0,route=route))
        val alert = engine.update(fix(43.0001),listOf(camera),1000,route=route)
        assertNotNull(alert)
        assertNull(engine.update(fix(43.0002),listOf(camera),2000,route=route))
    }
    @Test fun uncertainOrStaleGpsAndRecedingDoNotAlert() {
        for (bad in listOf(fix(43.0001,50f), fix(43.0001,bearing=180f))) {
            val engine = SpeedCameraAlertEngine()
            engine.update(fix(43.0),listOf(camera),0,route=route)
            assertNull(engine.update(bad,listOf(camera),1000,route=route))
        }
        val engine = SpeedCameraAlertEngine()
        engine.update(fix(43.0002),listOf(camera),0,route=route)
        assertNull(engine.update(fix(43.0001),listOf(camera),1000,route=route))
        assertNull(engine.update(fix(43.0003),listOf(camera),2000,6000,route))
    }
    @Test fun absentRouteAndParallelRoadDoNotAlert() {
        val engine = SpeedCameraAlertEngine()
        assertNull(engine.update(fix(43.0),listOf(camera),0))
        val offRoute = camera.copy(position=GeoPoint(43.004,-5.001))
        engine.update(fix(43.0),listOf(offRoute),0,route=route)
        assertNull(engine.update(fix(43.0001),listOf(offRoute),1000,route=route))
    }
}
