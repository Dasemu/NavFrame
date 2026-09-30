package dev.navframe.app

import dev.navframe.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Both result sources share the same route-sheet selection contract. */
data class PlaceSelection(
    val name: String,
    val position: GeoPoint,
    val sourceLabel: String,
    val attribution: String,
    val offlinePlace: OfflinePlace? = null,
) {
    companion object {
        fun offline(place: OfflinePlace) = PlaceSelection(place.name, place.position, "Offline · ${place.category.labelSpanish}", "© OpenStreetMap contributors · ODbL", place)
        fun online(place: SearchResult) = PlaceSelection(place.name, place.position, "Online · Nominatim", place.attribution)
    }
}
data class UnifiedSearchResults(val results: List<PlaceSelection>, val onlineError: String? = null)

data class PlaceViewport(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: GeoPoint get() = GeoPoint((south + north) / 2, if (west <= east) (west + east) / 2 else ((west + east + 360) / 2).let { if (it > 180) it - 360 else it })
}

class UnifiedPlaceSearch(private val repository: OfflinePlaceRepository) {
    suspend fun searchOffline(center: GeoPoint, query: String, category: PlaceCategory? = null): List<PlaceSelection> =
        repository.search(center, category, query).map(PlaceSelection::offline)

    /** Invoke only from a deliberate search button or IME action. Never from a camera/text listener. */
    suspend fun searchExplicit(center: GeoPoint, query: String, geocoder: Geocoder): UnifiedSearchResults {
        val local = searchOffline(center, query)
        return try {
            UnifiedSearchResults(merge(local, geocoder.search(query).map(PlaceSelection::online)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { UnifiedSearchResults(local, error.message ?: "No se pudo buscar online") }
    }
    companion object {
        internal fun merge(local: List<PlaceSelection>, online: List<PlaceSelection>): List<PlaceSelection> =
            (local + online).distinctBy { Triple(normalizePlaceSearch(it.name), (it.position.latitude * 100000).toLong(), (it.position.longitude * 100000).toLong()) }
    }
}

/** Cancelled camera queries cannot repaint a newer viewport. No GPS/network dependencies. */
class OfflineViewportController(
    private val scope: CoroutineScope,
    private val repository: OfflinePlaceRepository,
    private val onPlaces: (List<OfflinePlace>) -> Unit,
    private val onError: (Exception) -> Unit = {},
) {
    private var job: Job? = null
    private var generation = 0L
    fun refresh(viewport: PlaceViewport) {
        val request = ++generation
        job?.cancel()
        job = scope.launch {
            delay(300)
            try {
                val places = repository.visible(viewport)
                ensureActive()
                if (request == generation) onPlaces(places)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == generation) onError(error) }
        }
    }
    fun cancel() { generation++; job?.cancel(); job = null }
}
