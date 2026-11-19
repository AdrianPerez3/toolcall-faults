#!/bin/sh
set -eu

# The root POM imports the BOMs. Its non-recursive validate phase runs no plugins.
prepare() {
    ./mvnw -q -B -U -N validate
}

if prepare; then exit 0; fi
sleep 15

# The wrapper and Maven resolve downloads separately. Keep the successful
# distribution cache and repository ID for the later verify and lint steps.
alternate=https://repo1.maven.org/maven2
config="${MAVEN_CONFIG:+$MAVEN_CONFIG }--global-settings .github/scripts/maven-central-fallback.xml"
if (export MVNW_REPOURL="$alternate" MAVEN_CONFIG="$config"; prepare); then
    printf '%s\n' "MVNW_REPOURL=$alternate" "MAVEN_CONFIG=$config" >> "$GITHUB_ENV"
    exit 0
fi
sleep 15
prepare
