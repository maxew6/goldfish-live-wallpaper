#!/bin/sh
#
# Gradle wrapper launcher (POSIX). Runs the version pinned in
# gradle/wrapper/gradle-wrapper.properties (Gradle 8.5).
#
# If gradle/wrapper/gradle-wrapper.jar is missing (it is a binary file), this script
# generates it once using a locally installed `gradle` command, then continues.
#

app_path=$0
while [ -h "$app_path" ]; do
    ls=$(ls -ld -- "$app_path")
    link=${ls#*' -> '}
    case $link in
        /*) app_path=$link ;;
        *) app_path=$(dirname "$app_path")/$link ;;
    esac
done
APP_HOME=$(cd "$(dirname "$app_path")" > /dev/null && pwd -P) || exit
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    if command -v gradle > /dev/null 2>&1; then
        echo "gradle-wrapper.jar not found - generating it with the local gradle command..." >&2
        TMP_DIR=$(mktemp -d)
        ( cd "$TMP_DIR" && gradle wrapper --gradle-version 8.5 -q ) \
            && cp "$TMP_DIR/gradle/wrapper/gradle-wrapper.jar" "$WRAPPER_JAR"
        rm -rf "$TMP_DIR"
    fi
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    cat >&2 <<MSG
ERROR: gradle/wrapper/gradle-wrapper.jar is missing.

Fix (any one of these):
  1. Open this folder in Android Studio and let it sync (it downloads Gradle itself), then
     use Build > Build APK(s)  -  or run ./gradlew again afterwards.
  2. Install Gradle (https://gradle.org/install) and run:  gradle wrapper --gradle-version 8.5
  3. Copy gradle-wrapper.jar from any other Gradle project into gradle/wrapper/.
MSG
    exit 1
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
    command -v java > /dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no 'java' command was found. Install JDK 17." >&2
        exit 1
    }
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
