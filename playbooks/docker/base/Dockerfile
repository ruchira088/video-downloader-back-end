FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.source https://github.com/ruchira088/video-downloader-back-end

RUN apt-get update && \
    apt-get install ffmpeg python3-pip -y && \
    rm -rf /var/lib/apt/lists/

RUN pip3 install youtube-dl