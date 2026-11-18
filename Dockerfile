ARG JDK=21
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS base17
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS base21
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS base25
FROM base${JDK}
# The base image sets MAVEN_CONFIG to a directory; the wrapper script reads it as arguments.
ENV MAVEN_CONFIG=""
WORKDIR /tcf
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY kit/pom.xml kit/pom.xml
COPY server/pom.xml server/pom.xml
COPY runner/pom.xml runner/pom.xml
# Retry dependency downloads before any tests run. The wrapper JAR is supplied by the checkout.
RUN n=0; until ./mvnw -q -B dependency:go-offline; do n=$((n+1)); [ "$n" -lt 3 ] || exit 1; sleep 15; done
COPY . .
RUN ./mvnw -q -B package dependency:build-classpath -Dmdep.outputFile=target/cp.txt && touch runner/target/.tcf-build
RUN java -cp "runner/target/classes:server/target/classes:kit/target/classes:$(cat runner/target/cp.txt)" toolcallfaults.runner.cell.AdapterBuild
ENTRYPOINT ["./tcf"]
