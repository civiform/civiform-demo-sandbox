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
  apk add --no-cache --update bash wget curl npm git unzip

# ── Terraform ─────────────────────────────────────────────────────────────────
# Required by TerraformSandboxService, which shells out to this binary.
#
# Alpine works here because Terraform ships as a statically linked Go binary —
# no glibc shim needed, unlike the AWS CLI v2 (which has no musl build, and
# which this project deliberately does not depend on).
#
# Pinned rather than "latest": the builder runs `terraform init` on every
# provision, and an unpinned version would mean two sandboxes created a week
# apart could be applied by different Terraform releases. Must stay >= 1.10,
# which is where native S3 state locking (use_lockfile) landed.
ARG TERRAFORM_VERSION=1.15.8
RUN set -eux; \
  arch="$(apk --print-arch)"; \
  case "$arch" in \
    x86_64)  tfarch=amd64; tfsha=d25ce7b6902013ad905db3d2eab0be4cd905887fe88b81a6171b8d5503c31f3d ;; \
    aarch64) tfarch=arm64; tfsha=8891e9dcedc9e3b8950bc6af9d4d8af1f4cfade3062f53b9dc403a89f6ce8c9c ;; \
    *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
  esac; \
  wget -q "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_${tfarch}.zip" -O /tmp/terraform.zip; \
  # Verify before unpacking. This binary is handed credentials that can create
  # and destroy infrastructure, so an unverified download is not acceptable.
  echo "${tfsha}  /tmp/terraform.zip" | sha256sum -c -; \
  unzip -q /tmp/terraform.zip -d /usr/local/bin; \
  rm /tmp/terraform.zip; \
  terraform version

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
