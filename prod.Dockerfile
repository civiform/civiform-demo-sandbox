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
    apk add --no-cache bash curl unzip

# ── Terraform ─────────────────────────────────────────────────────────────────
# Installed in the runner, not the builder stage: TerraformSandboxService shells
# out to this binary at provision time, so it has to exist at runtime.
#
# Keep this version in step with the dev Dockerfile. Divergence would mean a
# sandbox behaves differently in production than it did when tested locally,
# which is the one class of bug this whole architecture exists to avoid.
ARG TERRAFORM_VERSION=1.15.8
RUN set -eux; \
    arch="$(apk --print-arch)"; \
    case "$arch" in \
      x86_64)  tfarch=amd64; tfsha=d25ce7b6902013ad905db3d2eab0be4cd905887fe88b81a6171b8d5503c31f3d ;; \
      aarch64) tfarch=arm64; tfsha=8891e9dcedc9e3b8950bc6af9d4d8af1f4cfade3062f53b9dc403a89f6ce8c9c ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    wget -q "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_${tfarch}.zip" -O /tmp/terraform.zip; \
    echo "${tfsha}  /tmp/terraform.zip" | sha256sum -c -; \
    unzip -q /tmp/terraform.zip -d /usr/local/bin; \
    rm /tmp/terraform.zip; \
    terraform version

# The per-sandbox root module. Copied into the image rather than fetched at
# runtime so the infrastructure definition is versioned with the code that runs
# it: a given builder image always provisions exactly one known configuration.
#
# The service copies this to a fresh temp directory per job and never runs
# Terraform here, so the directory stays read-only in practice.
#
# Matches sandbox.terraform.moduleDir in application.conf.
COPY terraform/sandbox /app/terraform/sandbox

EXPOSE 9000
CMD ["/cf-sandbox-builder-0.0.1/bin/cf-sandbox-builder", "-Dconfig.file=/cf-sandbox-builder-0.0.1/conf/application.conf"]
