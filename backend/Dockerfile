# Faz 9 — üretim imajı. Çok aşamalı yapı: derleme katmanı imaja taşınmaz.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Bağımlılıklar önce indirilir: kaynak değişince katman önbelleği korunur.
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Testler CI'da ayrı adımda koşar; imaj yapımı testcontainers'a bağımlı olmamalı.
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Uygulama root olmayan bir kullanıcıyla çalışır.
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# /actuator/health uygulamanın kendi sağlık ucudur; herkese açıktır ve veri döndürmez.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
