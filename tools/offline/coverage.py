"""Read Geofabrik's Osmosis polygon format, preserving islands and holes."""
import math


def read_geojson(path):
    polygons, current, hole = [], None, False
    for raw in path.read_text().splitlines()[1:]:
        value = raw.strip()
        if not value:
            continue
        if value == '!':  # historical local fixture separator
            if current:
                _finish(polygons, current, hole)
                current = None
            continue
        if value == 'END':
            if current is None:
                break
            _finish(polygons, current, hole)
            current = None
            continue
        parts = value.split()
        if len(parts) == 1:
            if current:
                _finish(polygons, current, hole)
            hole, current = value.startswith('!'), []
        elif len(parts) == 2:
            point = [float(parts[0]), float(parts[1])]
            if not all(math.isfinite(v) for v in point):
                raise ValueError('Non-finite polygon coordinate')
            if current is None:
                current, hole = [], False
            current.append(point)
        else:
            raise ValueError('Invalid polygon line')
    if current:
        _finish(polygons, current, hole)
    if not polygons:
        raise ValueError('Empty coverage polygon')
    return {'type': 'Polygon', 'coordinates': polygons[0]} if len(polygons) == 1 else {'type': 'MultiPolygon', 'coordinates': polygons}


def _finish(polygons, ring, hole):
    if len(ring) < 4 or ring[0] != ring[-1]:
        raise ValueError('Unclosed coverage ring')
    if hole:
        if not polygons:
            raise ValueError('Hole before exterior ring')
        polygons[-1].append(ring)
    else:
        polygons.append([ring])
