.PHONY: run test build package clean up down logs ps health openapi swagger

JAVA_21_HOME ?= $(shell find /usr/local/sdkman/candidates/java -maxdepth 1 -mindepth 1 -type d -name '21*' 2>/dev/null | sort | tail -n 1)
MVNW = env JAVA_HOME="$(if $(JAVA_21_HOME),$(JAVA_21_HOME),$(JAVA_HOME))" PATH="$(if $(JAVA_21_HOME),$(JAVA_21_HOME)/bin:$$PATH,$$PATH)" ./mvnw

run:
	$(MVNW) -P dsv spring-boot:run

test:
	$(MVNW) test

build:
	$(MVNW) clean verify

package:
	$(MVNW) -DskipTests clean package

clean:
	$(MVNW) clean

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f --tail=200

ps:
	docker compose ps

health:
	curl -fsS http://localhost:18020/api/v1/actuator/health

openapi:
	curl -fsS http://localhost:18020/api/v1/v3/api-docs >/dev/null

swagger:
	curl -I -fsS http://localhost:18020/api/v1/swagger-ui/index.html >/dev/null
