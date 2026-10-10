.PHONY: setup build test verify run clean smoke

setup:
	@command -v java >/dev/null || { echo "Install a JDK 17 or newer and put java on PATH."; exit 1; }
	@command -v javac >/dev/null || { echo "Install a JDK 17 or newer. A JRE is not enough."; exit 1; }
	@test -x ./mvnw || { echo "mvnw is missing. Run this target from the repository root."; exit 1; }
	./mvnw -version

build:
	./mvnw -B -DskipTests package

test:
	./mvnw -B test

verify:
	./mvnw -B verify

run: build
	java -jar target/nada.jar

clean:
	./mvnw -B clean

smoke: build
	./scripts/smoke.sh
