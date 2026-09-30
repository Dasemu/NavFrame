# Explicit destination search (English summary)

Typing in NavFrame searches installed local place catalogs. Online address search requires a separate user action; NavFrame sends the submitted search text to the configured geocoder and does not send GPS as part of that query. The default public Nominatim service is subject to the [OSMF usage policy](https://operations.osmfoundation.org/policies/nominatim/): one request per second aggregated across the application, no client-side autocomplete or systematic bulk queries, and an identifying User-Agent/Referer with attribution. A per-device delay does not satisfy a multi-user aggregate limit. Do not submit sensitive search text to a public service.

Detailed implementation and historical test notes follow in Spanish.

# Detailed geocoding notes (Spanish)

Investigación del 2026-09-29. NavFrame conserva la abstracción `Geocoder.search(query)` y el usuario selecciona un `SearchResult` antes de calcular ruta. Escribir no inicia red: solo el botón Buscar o acción explícita equivalente. Una búsqueda no inicia navegación, no sustituye una ruta activa y no presupone que el primer resultado sea correcto.

## Política del servidor público

**Leer la [política oficial Nominatim de OSMF](https://operations.osmfoundation.org/policies/nominatim/) antes de activar el servicio público.** Permite consultas explícitas con usuarios moderados; exige User-Agent/Referer identificativo, atribución y máximo absoluto **1 petición/s agregado de toda la aplicación**, no por usuario o teléfono. Prohíbe autocomplete, consultas sistemáticas y datos personales/confidenciales. La app debe poder cambiar backend sin actualizar APK; proxy/cache son recomendados. Su apartado LLM exige una decisión deliberada e informada del desarrollador y prohíbe ofrecerlo como geocoder genérico de plataformas de generación de apps.

El brief del desarrollador elige conscientemente Nominatim como proveedor posible, con búsqueda explícita y sin abuso. La implementación inicial se limita al prototipo personal NavFrame; no representa permiso para distribuir múltiples instalaciones con presupuestos independientes. Antes de ampliar usuarios, usar un proxy que limite el tráfico agregado o un proveedor propio/comercial con condiciones apropiadas. La pantalla debe mostrar la política y el proveedor seleccionado. No garantiza disponibilidad ni resultados; un bloqueo del proveedor debe respetarse.

## Endpoint y HTTP Android

Endpoint público documentado: `https://nominatim.openstreetmap.org/search`. Otra URL HTTPS **compatible con Nominatim jsonv2** puede seleccionarse en ajustes sin recompilar. Debe ser posible retirar el público como proveedor. Los [parámetros oficiales Search](https://nominatim.org/release-docs/latest/api/Search/) permiten q libre, jsonv2, limit y accept-language; no se usa el endpoint obsoleto search.php.

`nominatimSearchParameters(query)` devuelve `q` con espacios normalizados, `format=jsonv2`, `limit=5`, `accept-language=es`, `addressdetails=0`. Android codifica cada parámetro mediante encoder HTTP; no concatenar consultas sin escapar. La consulta tiene 2–200 caracteres; esto es una limitación UI propia, no del servicio. No enviar GPS, viewbox, identidad del usuario ni email. User-Agent debe identificar NavFrame/version y no el cliente HTTP genérico; el geocoder no requiere X-Client-Id ni UUID.

Una sola solicitud en vuelo, separación mínima de 1s entre inicios HTTP, timeout y cancelación al salir/sustituir búsqueda. No reintentar automáticamente errores ni HTTP429: mostrar pausa y respetar Retry-After antes de una acción manual. Una generación de búsqueda debe impedir que un callback tardío reemplace resultados más nuevos.

Cache de memoria acotada (objetivo 100 entradas/24h) por endpoint, consulta normalizada e idioma; incluir resultados vacíos. Consultas distintas no se mezclan y cambiar endpoint debe separar o limpiar cache. No persiste historial de destinos ni direcciones por defecto. En una instalación este cache y rate limiter reducen tráfico; no resuelven el límite global si se distribuye a múltiples teléfonos.

## Parser y selección

`parseNominatimResults(json)` utiliza Gson estricto y devuelve `SearchResult(name, position, attribution)`; conserva constructors anteriores con attribution default. `display_name` describe el destino, lat/lon son strings numéricos finitos con rango geográfico. `licence` del resultado se conserva como texto; si falta se muestra crédito OSM estándar. [Formato de resultados oficial](https://nominatim.org/release-docs/latest/api/Output/).

Respuesta limitada a 512Ki caracteres, máximo remoto 40 objetos, hasta cinco resultados para UI y labels de 2048 caracteres. Android debe limitar bytes también antes de cargar String. Se eliminan duplicados exactos nombre/coordenadas y controles del label, sin reinterpretar HTML. JSON/error/status/body incorrectos generan mensajes propios sin consulta ni coordenadas. Una lista vacía significa sin coincidencias, no fallo.

No se guarda place_id como identidad permanente ni se inferirá una entrada apta para moto a partir de un centroide de ciudad/POI. Mostrar el nombre completo y coordenadas de la selección antes de calcular; Valhalla puede ajustar el destino a otra posición de carretera. Búsqueda no garantiza acceso, existencia de aparcamiento ni restricciones del lugar.

## Privacidad, créditos y pruebas

El proveedor recibe el texto buscado, IP y metadata HTTP; no escribir direcciones privadas/confidenciales en el servicio público. Mostrar `© OpenStreetMap contributors` y enlace a [copyright/ODbL](https://www.openstreetmap.org/copyright) en resultados, manteniendo crédito de mapas y attribution remoto cuando proceda. No se incorpora código Nominatim: adapter/parser son originales y se consume HTTP. Gson/avisos ya están distribuidos en el APK.

`NominatimGeocodingTest` cubre fixture original jsonv2, coordenadas Unicode/antimeridiano, attribution, vacíos, duplicados/límites, JSON inválido/no estricto y errores backend, coordenadas string finitas/rangos, labels, parámetros Unicode/escape y validación antes de HTTP. No se ejecutan consultas públicas automáticas en tests. Las comprobaciones del adapter prueban cache, rate limit y cancelación; disponibilidad pública y calidad real de búsquedas requieren verificaciones independientes.

La fase 6 incluye además un ajuste de recuperación GPS: dos fixes nuevos estables tras pérdida/gap antes de adquirir progreso, sin interpretar el tiempo sin señal como movimiento demostrado. Se valida junto al incremento 0.6, sin alterar el APK 0.5 ya entregado.
