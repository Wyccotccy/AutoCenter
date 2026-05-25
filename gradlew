#!/bin/sh

#
# Gradle wrapper for AutoCenter project
#

cd "$(dirname "$0")"

# Required: gradle-wrapper.jar
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "ERROR: gradle/wrapper/gradle-wrapper.jar not found."
    echo "Download from: https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
    exit 1
fi

exec java \
    -Xmx2048m \
    -Dfile.encoding=UTF-8 \
    -jar "gradle/wrapper/gradle-wrapper.jar" \
    "$@"
