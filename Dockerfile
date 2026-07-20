# OpenTV web server: same domain layer as the Android app (:core + :data),
# plus REST API, stream proxy and the web client.
#
# WARNING: the server has NO authentication. Anyone who can reach it can use
# your playlists (and your provider credentials embedded in stream URLs).
# Run it behind an authenticating reverse proxy; never expose it directly.

FROM node:22-slim AS webapp
WORKDIR /webapp
COPY server/webapp/package.json server/webapp/package-lock.json ./
RUN npm ci
COPY server/webapp/ ./
RUN WEBAPP_OUT=/webapp/dist npm run build

FROM gradle:8.14-jdk17 AS build

# The Android Gradle plugin needs an SDK just to configure the :app/:core/:data
# modules, even though only the JVM server is compiled here.
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    curl -sSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null && \
    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" > /dev/null && \
    rm /tmp/tools.zip

WORKDIR /src
COPY . .
COPY --from=webapp /webapp/dist server/src/main/resources/web
RUN gradle --no-daemon -PwebappPrebuilt :server:installDist

FROM eclipse-temurin:17-jre

# ffmpeg powers the track-exposing remux for direct VOD files in browsers.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --create-home opentv && \
    mkdir /data && chown opentv /data

COPY --from=build /src/server/build/install/server /opt/opentv

USER opentv
ENV OPENTV_DATA=/data \
    PORT=8080
VOLUME /data
EXPOSE 8080

ENTRYPOINT ["/opt/opentv/bin/server"]
