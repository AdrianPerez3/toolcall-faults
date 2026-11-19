ARG JDK=21
FROM maven:3.9-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS base17
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS base21
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS base25
FROM base${JDK}
# The base image sets MAVEN_CONFIG to a directory; the wrapper script reads it as arguments.
ENV MAVEN_CONFIG=""
WORKDIR /tcf
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY kit/pom.xml kit/pom.xml
COPY server/pom.xml server/pom.xml
COPY runner/pom.xml runner/pom.xml
COPY .github/scripts/prepare-maven.sh .github/scripts/maven-central-fallback.xml .github/scripts/
# Fetch the wrapper distribution and plugin graph before running any tests.
RUN sh .github/scripts/prepare-maven.sh --image /etc/mavenrc
COPY . .
RUN ./mvnw -q -B package dependency:build-classpath -Dmdep.outputFile=target/cp.txt && touch runner/target/.tcf-build
RUN java -cp "runner/target/classes:server/target/classes:kit/target/classes:$(cat runner/target/cp.txt)" toolcallfaults.runner.cell.AdapterBuild
ENTRYPOINT ["./tcf"]
