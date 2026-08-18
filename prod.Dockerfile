# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS builder

ARG SBT_VERSION=1.10.7
ENV SBT_VERSION="${SBT_VERSION}"
ENV INSTALL_DIR=/usr/local
ENV SBT_HOME=/usr/local/sbt
ENV PATH="${PATH}:${SBT_HOME}/bin"
ENV SBT_URL="https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"

RUN set -o pipefail && \
    apk update && \
    apk add --no-cache bash wget npm git curl && \
    mkdir -p "${SBT_HOME}" && \
    wget -qO - "${SBT_URL}" | tar xz -C "${INSTALL_DIR}"

ENV PROJECT_HOME=/usr/src
ENV PROJECT_NAME=server
ENV PROJECT_LOC="${PROJECT_HOME}/${PROJECT_NAME}"

COPY "${PROJECT_NAME}" "${PROJECT_LOC}"
WORKDIR "${PROJECT_LOC}"

RUN npm ci && \
    npm run build && \
    sbt update && \
    sbt dist && \
    unzip "${PROJECT_LOC}/target/universal/cf-sandbox-builder-0.0.1.zip" -d / && \
    chmod +x /cf-sandbox-builder-0.0.1/bin/cf-sandbox-builder

FROM eclipse-temurin:21-jre-alpine AS runner
COPY --from=builder /cf-sandbox-builder-0.0.1 /cf-sandbox-builder-0.0.1

RUN set -o pipefail && \
    apk update && \
    apk add --no-cache bash curl

EXPOSE 9000
CMD ["/cf-sandbox-builder-0.0.1/bin/cf-sandbox-builder", "-Dconfig.file=/cf-sandbox-builder-0.0.1/conf/application.conf"]
