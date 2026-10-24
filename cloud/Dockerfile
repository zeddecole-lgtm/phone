# Official Android 14 Google Play system image on a KVM-enabled Linux host.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:$PATH
ENV QT_X11_NO_MITSHM=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl unzip openjdk-17-jre-headless \
    xvfb x11vnc novnc websockify \
    libgl1 libglu1-mesa libpulse0 libasound2 libnss3 libdbus-1-3 \
    libx11-6 libx11-xcb1 libxext6 libxcb1 libxcb-xinerama0 \
    libxi6 libxrandr2 libxdamage1 libxcomposite1 libxcursor1 \
    libxkbcommon0 libxtst6 libfontconfig1 libfreetype6 procps \
    && rm -rf /var/lib/apt/lists/*

ARG COMMANDLINE_TOOLS=11076708
RUN set -eux; \
    mkdir -p /opt/android-sdk/cmdline-tools; \
    curl -fL --retry 3 "https://dl.google.com/android/repository/commandlinetools-linux-${COMMANDLINE_TOOLS}_latest.zip" -o /tmp/tools.zip; \
    unzip -q /tmp/tools.zip -d /opt/android-sdk/cmdline-tools; \
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest; \
    rm /tmp/tools.zip

# Building accepts the Android SDK terms; the user must accept the license.
RUN set -eux; \
    yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true; \
    sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --install \
      "platform-tools" "emulator" "system-images;android-34;google_apis_playstore;x86_64"

RUN useradd --create-home --uid 1000 --shell /bin/bash android && \
    mkdir -p /home/android/.android /home/android/bin && \
    chown -R android:android /home/android && \
    ln -sf /usr/share/novnc/vnc.html /usr/share/novnc/index.html

COPY --chown=android:android entrypoint.sh /home/android/bin/entrypoint.sh
RUN chmod 0755 /home/android/bin/entrypoint.sh

USER android
WORKDIR /home/android
ENV HOME=/home/android
ENV ANDROID_AVD_HOME=/home/android/.android/avd
ENV DISPLAY=:99

VOLUME ["/home/android/.android"]
EXPOSE 6080
ENTRYPOINT ["/home/android/bin/entrypoint.sh"]
