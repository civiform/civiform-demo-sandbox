# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine

ARG SBT_VERSION=1.10.7
ENV SBT_VERSION="${SBT_VERSION}"
ENV INSTALL_DIR=/usr/local
ENV SBT_HOME=/usr/local/sbt
ENV PATH="${PATH}:${SBT_HOME}/bin"
ENV SBT_URL="https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"

ENV PROJECT_HOME=/usr/src
ENV PROJECT_NAME=server
ENV PROJECT_LOC="${PROJECT_HOME}/${PROJECT_NAME}"

# Update and install system dependencies
RUN set -o pipefail && \
  apk update && \
  apk add --upgrade apk-tools && \
  apk upgrade --available && \
  apk add --no-cache --update bash wget curl npm git

# Download and install sbt
RUN set -o pipefail && \
  mkdir -p "${SBT_HOME}" && \
  wget -qO - "${SBT_URL}" | tar xz -C "${INSTALL_DIR}" && \
  mkdir -p /root/.cache/sbt/boot/sbt-launch/${SBT_VERSION} /root/.sbt /root/.ivy2 /root/.config/sbt && \
  echo "--allow-empty" > /root/.config/sbt/sbtopts

WORKDIR "${PROJECT_LOC}"

# Copy build definition files first for layer caching
COPY "${PROJECT_NAME}/project" "${PROJECT_LOC}/project"
COPY "${PROJECT_NAME}/build.sbt" "${PROJECT_LOC}/"
COPY "${PROJECT_NAME}/.sbtopts" "${PROJECT_LOC}/.sbtopts"
COPY "${PROJECT_NAME}/.jvmopts" "${PROJECT_LOC}/.jvmopts"
RUN sbt update

# Copy node package definitions and install npm dependencies
COPY "${PROJECT_NAME}/package*.json" "${PROJECT_LOC}/"
RUN npm ci

# Copy full source code
COPY "${PROJECT_NAME}" "${PROJECT_LOC}"

# Build frontend assets
RUN npm run build

EXPOSE 9000
EXPOSE 5173

ENTRYPOINT ["/bin/bash", "-c"]
CMD ["sbt run"]
