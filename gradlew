#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cd "$(dirname "$0")"

# This is the minimal Gradle wrapper for GitHub Actions.
# The full wrapper JAR will be downloaded by the `gradle-build-action` in CI.

# If you need to run locally, install Gradle 8.2+ or download the full wrapper via:
#   gradle wrapper --gradle-version 8.2

echo "Please use 'gradle wrapper --gradle-version 8.2' to generate the full gradlew wrapper locally."
echo "In GitHub Actions, the gradle-build-action handles this automatically."

# The CI build uses `gradle-build-action` which doesn't need the wrapper JAR.
# For local builds: ./gradlew assembleDebug (requires full wrapper)

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "ERROR: gradle-wrapper.jar not found. Run 'gradle wrapper' to generate it."
    exit 1
fi

exec java \
    -Xmx2048m \
    -Dfile.encoding=UTF-8 \
    -jar "gradle/wrapper/gradle-wrapper.jar" \
    "$@"
