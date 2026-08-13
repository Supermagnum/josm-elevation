# Refreshing live fixtures

Record small Overpass + NVDB responses once, then keep tests offline.

```bash
# 1) Overpass (south,west,north,east)
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  --data-urlencode 'data=[out:json][timeout:60];(way["highway"](62.59,9.69,62.61,9.72););(._;>;);out meta;' \
  https://overpass-api.de/api/interpreter \
  -o tests/fixtures/live_sample/overpass.json

# 2) NVDB segmented links — convert the same bbox to UTM33 first, then:
# kartutsnitt=minX,minY,maxX,maxY&srid=5973
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  -H 'X-Client: nvdb-incline-review' \
  'https://nvdbapiles.atlas.vegvesen.no/vegnett/api/v4/veglenkesekvenser/segmentert?kartutsnitt=MINX,MINY,MAXX,MAXY&srid=5973&antall=1000&inkluderAntall=false' \
  -o tests/fixtures/live_sample/nvdb_segmentert.json

# 3) Optional datakatalog snapshot
curl -A 'nvdb-incline-fixture-refresh/0.1' \
  -H 'X-Client: nvdb-incline-review' \
  'https://nvdbapiles.atlas.vegvesen.no/datakatalog/api/v1/vegobjekttyper' \
  -o tests/fixtures/live_sample/datakatalog.json
```

Trim fixtures to a few ways/links before committing. Prefer the checked-in synthetic fixtures for CI.
