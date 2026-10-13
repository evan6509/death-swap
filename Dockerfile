# syntax=docker/dockerfile:1
# One Java 25 image runs either the Velocity proxy or a Fabric backend.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon build :velocity-plugin:build

FROM eclipse-temurin:25-jdk AS server-install
ARG FABRIC_INSTALLER_VERSION=1.1.2
ARG MINECRAFT_VERSION=26.2
ARG FABRIC_LOADER_VERSION=0.19.3
ARG FABRIC_API_VERSION=0.154.2+26.2
ARG FABRIC_PROXY_LITE_VERSION=v2.12.0
ARG FABRIC_PROXY_LITE_SHA256=dca0d05685afaa25d554372ad118d90b6b27f85ded93e6db0b85d822aa29342a
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /install/mods \
    && curl --retry 3 -fsSL -o /install/fabric-installer.jar \
       "https://maven.fabricmc.net/net/fabricmc/fabric-installer/${FABRIC_INSTALLER_VERSION}/fabric-installer-${FABRIC_INSTALLER_VERSION}.jar" \
    && java -jar /install/fabric-installer.jar server -mcversion "${MINECRAFT_VERSION}" \
       -loader "${FABRIC_LOADER_VERSION}" -dir /install -downloadMinecraft \
    && rm /install/fabric-installer.jar \
    && curl --retry 3 -fsSL -o /install/mods/fabric-api.jar \
       "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar" \
    && curl --retry 3 -fsSL -o /install/mods/fabricproxy-lite.jar \
       "https://api.modrinth.com/maven/maven/modrinth/fabricproxy-lite/${FABRIC_PROXY_LITE_VERSION}/fabricproxy-lite-${FABRIC_PROXY_LITE_VERSION}.jar" \
    && echo "${FABRIC_PROXY_LITE_SHA256}  /install/mods/fabricproxy-lite.jar" | sha256sum -c -
COPY --from=build /src/build/libs/death-swap-1.0.0+mc26.2.jar /install/mods/death-swap.jar

FROM eclipse-temurin:25-jdk AS proxy-install
ARG VELOCITY_SHA256=4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70
ARG VELOCITY_URL=https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl --retry 3 -fsSL -o /velocity.jar "${VELOCITY_URL}" \
    && echo "${VELOCITY_SHA256}  /velocity.jar" | sha256sum -c -

FROM eclipse-temurin:25-jdk AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 deathswap \
    && useradd --uid 10001 --gid deathswap --no-create-home deathswap \
    && mkdir -p /deathswap \
    && chown deathswap:deathswap /deathswap
COPY --from=server-install /install /opt/deathswap/server
COPY --from=proxy-install /velocity.jar /opt/deathswap/velocity.jar
COPY --from=build /src/velocity-plugin/build/libs/death-swap-proxy-1.0.0.jar /opt/deathswap/death-swap-proxy.jar
COPY docker/entrypoint.sh docker/runtime.py /opt/deathswap/
RUN chmod +x /opt/deathswap/entrypoint.sh
ENV MODE=server SERVER_ID=a BACKEND_SERVER_IDS=a,b SHARED_DIR=/deathswap \
    MC_MEMORY=3G SWAP_INTERVAL_SECONDS=300 START_COUNTDOWN_SECONDS=5 \
    VELOCITY_PORT=25577 EULA=FALSE
USER deathswap:deathswap
WORKDIR /deathswap
ENTRYPOINT ["/opt/deathswap/entrypoint.sh"]
