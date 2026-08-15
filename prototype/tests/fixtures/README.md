# Refreshing live fixtures

Record small Overpass + NVDB responses once, then keep tests offline.
Write outputs under this directory (example names — trim before committing).

```bash
# From repo root; paths below are under prototype/tests/fixtures/

# 1) Overpass (south,west,north,east)
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  --data-urlencode 'data=[out:json][timeout:60];(way["highway"](62.59,9.69,62.61,9.72););(._;>;);out meta;' \
  https://overpass-api.de/api/interpreter \
  -o prototype/tests/fixtures/overpass_refresh.json

# 2) NVDB segmented links — convert the same bbox to UTM33 first, then:
# kartutsnitt=minX,minY,maxX,maxY&srid=5973
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  -H 'X-Client: nvdb-incline-review' \
  'https://nvdbapiles.atlas.vegvesen.no/vegnett/api/v4/veglenkesekvenser/segmentert?kartutsnitt=MINX,MINY,MAXX,MAXY&srid=5973&antall=1000&inkluderAntall=false' \
  -o prototype/tests/fixtures/nvdb_segmentert_refresh.json

# 3) Optional datakatalog snapshot
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  -H 'X-Client: nvdb-incline-review' \
  'https://nvdbapiles.atlas.vegvesen.no/datakatalog/api/v1/vegobjekttyper' \
  -o prototype/tests/fixtures/datakatalog_refresh.json
```

Trim fixtures to a few ways/links before committing. Prefer the checked-in synthetic fixtures and `area_*` dirs under this folder for CI. The JOSM plugin’s offline steep-road fixtures live separately at `tests/fixtures/steep_roads/` (not Overpass).
