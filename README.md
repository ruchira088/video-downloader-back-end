## Video Downloader Backend
[![CircleCI](https://circleci.com/gh/ruchira088/video-downloader-back-end/tree/dev.svg?style=svg)](https://circleci.com/gh/ruchira088/video-downloader-back-end/tree/dev)

### Instructions to set-up local JKS

When prompted for password enter: `changeit`

```bash
mkcert -pkcs12 cert.p12 localhost "api.localhost"

keytool -importkeystore  \
  -srckeystore cert.p12 \
  -destkeystore localhost.jks \
  -srcstoretype PKCS12 \
  -deststoretype jks
```

### Instructions to set-up nginx SSL certificates

```bash
mkcert -key-file key.pem \
  -cert-file cert.pem \
  localhost "api.localhost"
```

Copy `key.pem` and `cert.pem` to `nginx/ssl/`
