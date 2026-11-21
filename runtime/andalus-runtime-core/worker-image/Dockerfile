# syntax=docker/dockerfile:1.7
# Supply a Java 25 JRE image pinned by immutable digest at build time.
ARG JRE_IMAGE
FROM ${JRE_IMAGE}

WORKDIR /opt/andalus
ENV ANDALUS_SHADOW_TRUST_DIRECTORY=/opt/andalus/trust

COPY --chown=0:0 --chmod=0444 target/andalus-runtime-core.jar /opt/andalus/worker.jar
COPY --chown=0:0 lib/ /opt/andalus/lib/
COPY --chown=0:0 --chmod=0444 trust/trusted-signers.ed25519 /opt/andalus/trust/trusted-signers.ed25519

USER 10001:10001
ENTRYPOINT ["java", "-cp", "/opt/andalus/worker.jar:/opt/andalus/lib/*", "tech.kayys.andalus.runtime.learning.ShadowEvaluationWorkerEntrypoint"]
