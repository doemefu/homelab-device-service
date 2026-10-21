# Stage 1: build
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -q

COPY src src
RUN ./mvnw clean package -DskipTests -q

# Stage 2: runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# TEMPORARY workaround for the eMudhra intermediate-CA compromise
# (https://blog.cloudflare.com/emudhra-certificate-compromise): drop the emSign roots that
# NSS 3.131.1 removes. Remove this RUN once Temurin ships cacerts from NSS >= 3.131.1
# (temurin-build PR #4587). Tracking: doemefu/homelab#212 ("eMudhra: third-party images + Temurin follow-up").
# The ECC G3 delete must succeed (build fails if the alias was renamed); C1/C3 are removed only if present.
# "changeit" is the JDK's public default cacerts password, not a secret.
RUN keytool -delete -cacerts -storepass changeit -alias "cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]" \
 && for a in "cn_emsign_root_ca__c1,o_emudhra_inc,ou_emsign_pki,c_us [jdk]" "cn_emsign_ecc_root_ca__c3,o_emudhra_inc,ou_emsign_pki,c_us [jdk]"; do \
      if keytool -list -cacerts -storepass changeit -alias "$a" >/dev/null 2>&1; then \
        keytool -delete -cacerts -storepass changeit -alias "$a"; \
      fi; \
    done

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/device-service-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
