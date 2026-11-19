#!/bin/sh
set -eu

# Both goals only resolve downloads. The root POM has no plugins bound to validate.
mavenrc=
if [ "$#" -eq 0 ]; then
    set -- -N validate
elif [ "$#" -eq 2 ] && [ "$1" = --image ] && [ -n "$2" ]; then
    mavenrc=$2
    set -- dependency:go-offline
else
    echo 'usage: prepare-maven.sh [--image MAVENRC]' >&2
    exit 2
fi

prepare() {
    ./mvnw -q -B -U "$@"
}

if prepare "$@"; then exit 0; fi
sleep 15

# The wrapper and Maven resolve downloads separately.
alternate=https://repo1.maven.org/maven2
settings=.github/scripts/maven-central-fallback.xml
config="${MAVEN_CONFIG:+$MAVEN_CONFIG }--global-settings $settings"
if (export MVNW_REPOURL="$alternate" MAVEN_CONFIG="$config"; prepare "$@"); then
    if [ -n "$mavenrc" ]; then
        # Each later wrapper reads this file, including the adapter builds. Persist
        # only the fixed endpoint and settings path, preserving earlier startup settings.
        cat >> "$mavenrc" <<EOF

export MVNW_REPOURL=$alternate
export MAVEN_CONFIG="\${MAVEN_CONFIG:+\$MAVEN_CONFIG }--global-settings $settings"
EOF
    else
        printf '%s\n' "MVNW_REPOURL=$alternate" "MAVEN_CONFIG=$config" >> "$GITHUB_ENV"
    fi
    exit 0
fi
sleep 15
prepare "$@"
