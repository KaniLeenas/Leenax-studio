
# ==========================================
# Build stage
# ==========================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Download dependencies first for Docker layer caching
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Copy application source
COPY src ./src

# Run tests and package application
# FFmpeg is not needed here unless tests invoke it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && mvn -B package \
    && apt-get purge -y --auto-remove ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# ==========================================
# Runtime stage
# ==========================================
FROM eclipse-temurin:21-jre

# Install FFmpeg and create non-root user
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app leenax

WORKDIR /app

COPY --from=build /build/target/leenax-studio-backend-*.jar app.jar

USER leenax

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
