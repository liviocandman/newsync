# Reference builds: Linux amd64. Registry digests checked on 2026-10-29.
FROM scratch AS build-context
COPY . /

FROM node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c AS node
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:0939ed943f8107b943b6a6ac2e21d24568c24e4443d496e4d8b8e8da1ccdde1c AS source-build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
WORKDIR /build
COPY config config
COPY backend backend
COPY frontend frontend
RUN cd backend && sh mvnw -B -DskipTests clean package
FROM scratch AS artifact
COPY --from=source-build /build/backend/target/news.jar /news.jar

# The runtime receives ONLY the approved JAR through a required named context.
# BuildKit does not recompile source-build when building this target.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:0939ed943f8107b943b6a6ac2e21d24568c24e4443d496e4d8b8e8da1ccdde1c AS probe-build
WORKDIR /probe
COPY scripts/ReadyProbe.java .
RUN javac --release 25 ReadyProbe.java

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:39d8a64f84d4c0aab4639757e1502264da350be831df4bb25d182d6a514113c2 AS runtime
WORKDIR /app
COPY --from=verified /news.jar /app/news.jar
COPY --from=probe-build /probe/ReadyProbe.class /app/ReadyProbe.class
ARG JAR_SHA256
ARG REVISION
RUN test -n "$JAR_SHA256" && echo "$JAR_SHA256  /app/news.jar" | sha256sum -c -
LABEL org.opencontainers.image.title="Caderno pessoal local" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT" \
      org.opencontainers.image.revision="$REVISION" \
      news.jar.sha256="$JAR_SHA256"
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=12 CMD ["java", "-cp", "/app", "ReadyProbe"]
ENTRYPOINT ["java", "-jar", "/app/news.jar", "--app.deployment=container-local", "--server.address=0.0.0.0", "--spring.config.import=configtree:/run/secrets/", "--spring.profiles.active=normal"]
