# OsmAnd reuse and UX research

Official research dated 2026-09-29, on the requested [osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd) repository, commit **2c0061842370920aad3c25e3748a625c0df0d7e6**. Licenses and file headers were read before considering reuse. **No OsmAnd code, layout, or artwork has been incorporated.**

## What it contributes as a reference

Its [official README](https://github.com/osmandapp/OsmAnd/blob/2c0061842370920aad3c25e3748a625c0df0d7e6/README.md) highlights maps, position/orientation, destinations, profiles, route preview, starting guidance, rerouting, and local maps. NavFrame adopts common navigation functionality with its own implementation and interface: map as the main content, visible destination, preparing a route before starting, profile controls, and motorcycle preferences. It does not reproduce a concrete layout, iconography, extensive text, brand, or protected visual themes.

## License by component

The [license at the examined commit](https://github.com/osmandapp/OsmAnd/blob/2c0061842370920aad3c25e3748a625c0df0d7e6/LICENSE) distinguishes several groups:

| Component | License/evidence | Current decision |
|---|---|---|
| OsmAnd Android, OsmAnd-java, OsmAnd-core, its own engine/routing/rendering | GPLv3, with specific stated exceptions | Do not integrate into the current APK |
| Custom layouts, UI/UX design, icons, and artwork | CC-BY-NC-ND 4.0; proprietary resources and store-specific terms also exist | Use original UI and drawings; do not copy these assets |
| Google's Java Protobuf, e.g. CodedInputStream | [Three-clause BSD header](https://github.com/osmandapp/OsmAnd/blob/2c0061842370920aad3c25e3748a625c0df0d7e6/OsmAnd-java/src/main/java/com/google/protobuf/CodedInputStream.java), exception noted in the root license | Could be isolated with notices and an audit of changes/dependencies; not needed by current features |
| Third-party fonts and icons | Root license lists Apache 2.0, public domain, and other terms; no single license covers every directory | If needed, use the original source and verify each file individually |
| Code received through pull requests | PULL_REQUEST.MIT.LICENSE and MIT contribution declaration | Does not imply MIT relicensing for files mixed with GPL code |

The presence of a library inside OsmAnd does not make it GPL, and an exception does not make every file permissive. For example, `com/wdtinc/mapbox_vector_tile` is listed as a modified library, but the examined JtsAdapter lacks a sufficient independent license and depends on OsmAnd and JTS types. It is not treated as a clean authorized copy without more research. `com/jwetherell/openmap/common/LatLonPoint.java` retains BBN copyright; that header alone does not grant broad permission. NavFrame incorporates none of these pieces.

## Relationship to Pillion

The current NaviLite module retains Pillion-derived material under **PolyForm Noncommercial 1.0.0**, with the license and Required Notice distributed. GPLv3 requires a combined distributed work to retain freedoms of use/distribution without additional restrictions; a commercial-use restriction on that material cannot simply be added to a GPL work as a whole. Therefore directly combining OsmAnd's GPL engine/UI with NaviLite in one distributed work is not a matter of copying a few classes and adding attribution. This assessment is grounded in the [PolyForm license](https://polyformproject.org/licenses/noncommercial/1.0.0/) and [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html), especially sections 5 and 10. It does not claim that consulting functional ideas, using separate programs, or every private use is prohibited.

Viable future reuse would need to be specific: a permissively licensed library with verified terms/notices, alternative authorization from the rightsholders, or a separately evaluated app integration. Embedding OsmAnd-core/OBF/offline routing is outside the current increment. The existing PMTiles map is not an OsmAnd/Valhalla routing graph.

NavFrame already uses components suited to its contracts—MapLibre, Gson, OSM data, and NaviLite derived from Pillion—and keeps its own profile/guidance engine. The app is not rewritten to copy OsmAnd, and the new UX is not presented as a port of its code.
