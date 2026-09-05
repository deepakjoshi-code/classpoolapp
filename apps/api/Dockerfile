# Build context must be the REPO ROOT (not apps/api) — see the -f flag below and
# apps/api/pom.xml's <resources> filtering note: application.yml's
# spring.flyway.locations bakes in an absolute path derived from ${project.basedir}
# that points two directories up at infra/db/migrations, so that directory has to
# exist at build time in the same relative position apps/api does, and stay there at
# runtime too (this is a single-stage image for exactly that reason — a multi-stage
# copy-just-the-jar build would leave that baked-in absolute path dangling).
#
# Build (from repo root): docker build -f apps/api/Dockerfile -t classpool-api .
# Render's "Root Directory" should stay unset (repo root) with this Dockerfile path.
FROM eclipse-temurin:21-jdk

RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY . .

RUN mvn -f apps/api/pom.xml -B -q -DskipTests package

EXPOSE 8080
CMD ["sh", "-c", "java -jar apps/api/target/*.jar"]
