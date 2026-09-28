# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -Dmaven.test.skip=true clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=dsv
ENV JAVA_OPTS=""
COPY --from=build /workspace/target/api-engenharia-operacoes-*.jar /app/app.jar
EXPOSE 8080
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar /app/app.jar"]
