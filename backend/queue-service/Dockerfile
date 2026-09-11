FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml /build/pom.xml
COPY gateway-service/pom.xml /build/gateway-service/pom.xml
COPY queue-service/pom.xml /build/queue-service/pom.xml
COPY result-service/pom.xml /build/result-service/pom.xml
COPY notification-service/pom.xml /build/notification-service/pom.xml
RUN mvn -q -N install
COPY queue-service /build/queue-service
RUN mvn -q -pl queue-service -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
# Run as a dedicated non-root user rather than the image default (root) —
# limits blast radius if the JVM process is ever compromised.
RUN addgroup -S sarthi && adduser -S sarthi -G sarthi
WORKDIR /app
COPY --from=build /build/queue-service/target/*.jar app.jar
RUN chown sarthi:sarthi app.jar
USER sarthi
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
