#!/bin/sh
# Build-time network downloads only. Never called by the Android application.
set -eu
cd "$(dirname "$0")/../.."
mkdir -p build/offline
navframe_jar=build/offline/planetiler-0.10.2.jar
navframe_pbf=build/offline/asturias.osm.pbf
if [ ! -f "$navframe_jar" ]; then
  curl --fail --location --output "$navframe_jar.part" https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar
  mv "$navframe_jar.part" "$navframe_jar"
fi
if [ ! -f "$navframe_pbf" ]; then
  curl --fail --location --output "$navframe_pbf.part" https://download.geofabrik.de/europe/spain/asturias-latest.osm.pbf
  mv "$navframe_pbf.part" "$navframe_pbf"
fi
# Existing source is preserved; remove it deliberately to update to today's extract.
java -Xmx2g -jar "$navframe_jar" generate-custom \
  --schema=tools/offline/navframe.yaml \
  --output=build/offline/asturias.pmtiles \
  --bounds=-7.2,42.9,-4.45,43.75 \
  --tmpdir=build/offline/tmp --force --download-osm-tile-weights=false
sha256sum "$navframe_pbf" build/offline/asturias.pmtiles
# Explicit packaging: cp build/offline/asturias.pmtiles app/src/main/assets/offline/asturias.pmtiles
# When replacing the bundled pack, update its byte-count/date constants and notices as well.
