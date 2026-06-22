# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the ssuMCP Spring Boot server.
#
# Build context: . (the Spring Boot repository root).
# The image is multi-arch capable; CI builds linux/arm64 for the Oracle
# Cloud Ampere A1 production target via docker buildx.
#
#   docker build -f Dockerfile -t ssumcp:dev .
#   docker buildx build --platform linux/arm64 -f Dockerfile \
#     -t ghcr.io/<owner>/ssumcp:<sha> --push .

# ---- rusaint native library stage -----------------------------------------
FROM --platform=$BUILDPLATFORM ubuntu:22.04@sha256:4f838adc7181d9039ac795a7d0aba05a9bd9ecd480d294483169c5def983b64d AS rusaint-builder  # ubuntu:22.04

ARG RUST_VERSION=1.95.0
ARG RUSAINT_REF=c2bdcf91c6efb313b971efa2a8a67ed79ad77b4b
ARG TARGETARCH
ENV PATH="/root/.cargo/bin:${PATH}" \
    RUSTUP_TOOLCHAIN="${RUST_VERSION}"
ENV CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc \
    CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc \
    CXX_aarch64_unknown_linux_gnu=aarch64-linux-gnu-g++ \
    AR_aarch64_unknown_linux_gnu=aarch64-linux-gnu-ar
WORKDIR /workspace

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      git \
      build-essential \
      gcc-aarch64-linux-gnu \
      g++-aarch64-linux-gnu \
      pkg-config \
      libssl-dev \
 && rm -rf /var/lib/apt/lists/*

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
  | sh -s -- -y --profile minimal --default-toolchain ${RUST_VERSION}

RUN case "${TARGETARCH}" in \
      amd64) echo "x86_64-unknown-linux-gnu" > /tmp/rust-target ;; \
      arm64) echo "aarch64-unknown-linux-gnu" > /tmp/rust-target ;; \
      *) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && rustup target add "$(cat /tmp/rust-target)"

RUN git clone https://github.com/EATSTEAK/rusaint.git rusaint \
 && cd rusaint \
 && git checkout ${RUSAINT_REF}

RUN --mount=type=cache,target=/root/.cargo/registry \
    --mount=type=cache,target=/root/.cargo/git \
    --mount=type=cache,target=/workspace/rusaint/target \
    cd rusaint \
 && cargo build --release --package rusaint-ffi --target "$(cat /tmp/rust-target)" \
 && cp "target/$(cat /tmp/rust-target)/release/librusaint_ffi.so" /workspace/librusaint_ffi.so

# ---- Build stage ----------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy@sha256:801b7e1a9c4befaf82bf9a2a58025ef43a7694bbc84779187ad0524d84742772 AS builder  # eclipse-temurin:21-jdk-jammy
WORKDIR /workspace

# Cache Gradle wrapper + dependency resolution before copying sources so
# that source-only changes don't bust the dependency download layer.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy@sha256:199aebeb3adcde4910695cdebfe782ada38dadb6cc8013159b58d3724451befd AS runtime  # eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as non-root. curl is used by the HEALTHCHECK below; libssl3 covers
# native rusaint builds that link OpenSSL on Ubuntu 22.04.
RUN groupadd --system spring \
 && useradd --system --uid 1001 --gid spring --shell /usr/sbin/nologin spring \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl libssl3 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar app.jar
COPY --from=rusaint-builder /workspace/librusaint_ffi.so /usr/local/lib/librusaint_ffi.so
RUN chown spring:spring app.jar /usr/local/lib/librusaint_ffi.so

USER 1001
EXPOSE 8080

# JVM defaults sized for a 1 GB pod request on the Ampere A1 host.
# Override with the JAVA_OPTS env var from the k8s ConfigMap when needed.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV LD_LIBRARY_PATH="/usr/local/lib"

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD curl --fail --silent http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
