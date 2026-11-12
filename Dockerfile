FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests -Pproduction package

FROM eclipse-temurin:17-jre-alpine-3.21
RUN apk add --no-cache curl ca-certificates \
    && addgroup -S stockline \
    && adduser -S -G stockline -h /app -s /sbin/nologin stockline
WORKDIR /app
COPY --from=build --chown=stockline:stockline /workspace/target/stockline-1.0.0.jar /app/stockline.jar
COPY --chown=stockline:stockline docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh
USER stockline
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT}/actuator/health/readiness" || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
