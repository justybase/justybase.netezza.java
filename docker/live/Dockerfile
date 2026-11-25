# Ephemeral LIVE-test image for one JDK.
# The Maven version and JDK come from the official Maven image selected by the caller.
ARG MAVEN_IMAGE=maven:3.10-eclipse-temurin-21
FROM ${MAVEN_IMAGE}

WORKDIR /workspace

# Keep dependency resolution in its own layer; source changes do not re-download it.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src

# The official Maven entrypoint stays in place and executes this command.
CMD ["mvn", "-B", "-Plive", "test"]
