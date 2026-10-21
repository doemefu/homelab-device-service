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
# Each listed root is deleted if present (a failed delete fails the build); the final check fails the build if any of them is still in cacerts, e.g. under a renamed alias.
# "changeit" is the JDK's public default cacerts password, not a secret.
RUN for a in "cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]" \
             "cn_emsign_root_ca__c1,o_emudhra_inc,ou_emsign_pki,c_us [jdk]" \
             "cn_emsign_ecc_root_ca__c3,o_emudhra_inc,ou_emsign_pki,c_us [jdk]"; do \
      if keytool -list -cacerts -storepass changeit -alias "$a" >/dev/null 2>&1; then \
        keytool -delete -cacerts -storepass changeit -alias "$a" || exit 1; \
      fi; \
    done \
 && if keytool -list -cacerts -storepass changeit | grep -qiE "emsign_ecc_root_ca__g3|emsign_root_ca__c1|emsign_ecc_root_ca__c3"; then \
      echo "distrusted emSign root still present in cacerts" >&2; exit 1; \
    fi

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/device-service-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
