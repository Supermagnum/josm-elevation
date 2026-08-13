.PHONY: test install

install:
	python3 -m pip install -e ".[test]"

test:
	python3 -m pytest --disable-socket --allow-unix-socket
