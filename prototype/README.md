# Python CLI prototype (superseded by the JOSM plugin)

This directory keeps the earlier standalone Python tool as a reference for the
algorithms now implemented in the Java `core` module. It is not required to
build or run the JOSM plugin.

```bash
cd prototype
python3 -m pip install -e ".[test]"
make test
```
