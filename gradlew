#!/bin/sh

#
# Gradle wrapper for AutoCenter project
# Uses Gradle 8.2. The gradle-wrapper.jar is downloaded on first build
# when using `gradle wrapper` or the GitHub Actions setup.
#

# Determine the project root dir
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# If wrapper jar doesn't exist, download/install Gradle via SDKMAN or use system gradle
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    # Check if gradle is available on PATH (GitHub Actions provides this)
    if command -v gradle > /dev/null 2>&1; then
        echo "Generating Gradle wrapper..."
        gradle wrapper --gradle-version 8.2
    else
        echo "ERROR: gradle-wrapper.jar not found and 'gradle' not on PATH."
        echo "To fix: install Gradle 8.2+ and run 'gradle wrapper --gradle-version 8.2'"
        echo "Or: download https://services.gradle.org/distributions/gradle-8.2-bin.zip"
        echo "    and set GRADLE_HOME, then run 'gradle wrapper --gradle-version 8.2'"
        exit 1
    fi
fi

# Execute the wrapper
exec java \
    -Xmx2048m \
    -Dfile.encoding=UTF-8 \
    -jar "gradle/wrapper/gradle-wrapper.jar" \
    "$@"
