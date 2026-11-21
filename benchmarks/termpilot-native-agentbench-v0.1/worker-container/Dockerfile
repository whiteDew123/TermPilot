FROM eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11

LABEL org.opencontainers.image.title="TermPilot Native AgentBench Candidate Worker"
LABEL org.opencontainers.image.description="Networkless runtime for a read-only mounted TermPilot Candidate jar"
LABEL com.termpilot.agentbench.role="candidate-worker"
LABEL com.termpilot.agentbench.scope="relay-worker-only"

USER root
RUN addgroup -S -g 10002 termpilotworker \
    && adduser -S -D -H -u 10002 -G termpilotworker -h /home/termpilot -s /sbin/nologin termpilotworker \
    && mkdir -p /workspace /home/termpilot /opt/termpilot \
    && chown 10002:10002 /workspace /home/termpilot \
    && chmod 0555 /opt/termpilot

ENV HOME=/home/termpilot \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /workspace
USER termpilotworker:termpilotworker

ENTRYPOINT ["java"]
CMD ["-version"]
