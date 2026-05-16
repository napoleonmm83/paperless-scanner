# Connecting to a Self-Hosted Paperless-ngx Server

Paperless Scanner can talk to your self-hosted Paperless-ngx in two ways. **HTTPS is strongly recommended.** If you're on a private LAN and HTTPS is impractical, the app supports an explicit plain-HTTP path with consent.

## Recommended: HTTPS with a Self-Signed Certificate

This is the right setup if your server is reachable by IP (e.g. `192.168.x.x`) and you don't have a public domain name.

You'll create your own Certificate Authority (CA), use it to sign a certificate for your server (with the IP listed as a Subject Alternative Name), serve it from nginx, and install the CA on each Android device that should be allowed to connect.

### 1. On the server: create the CA and server certificate

```bash
mkdir -p ~/certs && cd ~/certs

# Root CA (10-year validity for a home network)
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -out ca.crt \
  -subj "/C=DE/ST=YourState/L=YourCity/O=Home/CN=Home-CA"

# Server key + CSR — replace 192.168.178.19 with your server's LAN IP
openssl genrsa -out paper.key 2048
openssl req -new -key paper.key -out paper.csr \
  -subj "/C=DE/ST=YourState/L=YourCity/O=Home/CN=192.168.178.19"

# SAN extension — Android REQUIRES the IP to appear here, CN alone is ignored
cat > paper.ext <<'EOF'
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName=@alt_names

[alt_names]
IP.1 = 192.168.178.19
EOF

openssl x509 -req -in paper.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out paper.crt -days 3650 -sha256 \
  -extfile paper.ext
```

### 2. Install the cert and key on the server

```bash
sudo cp paper.crt /etc/ssl/certs/paper.crt
sudo cp paper.key /etc/ssl/private/paper.key
sudo chmod 640 /etc/ssl/private/paper.key
sudo chown root:root /etc/ssl/private/paper.key
```

### 3. Configure nginx for HTTPS

Replace the contents of `/etc/nginx/sites-available/paperless` (or your equivalent site file) with:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    server_name 192.168.178.19;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name 192.168.178.19;
    client_max_body_size 50M;

    ssl_certificate     /etc/ssl/certs/paper.crt;
    ssl_certificate_key /etc/ssl/private/paper.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://localhost:8000;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade           $http_upgrade;
        proxy_set_header Connection        $connection_upgrade;
        proxy_read_timeout 300s;
    }
}
```

Reload nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 4. Install the CA on each Android device

Transfer `~/certs/ca.crt` to the device (email, USB, Nextcloud, etc.) and rename it to something like `home-ca.crt`.

On Android: **Settings → Security & privacy → More security & privacy → Encryption & credentials → Install a certificate → CA certificate**, then pick the file. Accept the "this will weaken your security" warning — that's the OS warning you about user-installed CAs in general, not anything specific to your cert.

Open the app, enter `https://192.168.178.19`, and you're done.

> **Why does Android need the CA installed?** Android only trusts certificates signed by the public CA roots it ships with. Your home CA isn't one of them, so you have to tell Android explicitly that you trust it. The app honors user-installed CAs (`<certificates src="user" />` in our `network_security_config.xml`) — many security-hardened apps don't.

## Alternative: Plain HTTP with In-App Consent

If you can't set up HTTPS on the server (for example, you're testing on a temporary network), the app supports plain-HTTP connections to LAN IPs with explicit consent.

1. Open the app.
2. In the server URL field, enter `http://192.168.178.19` (or your server's address with the `http://` prefix).
3. A dialog appears: **"This server uses HTTP. Allow unencrypted connection?"** Tap **Allow**.
4. Continue with login as normal.

**What this means for your data:**

- Traffic between your phone and the server is unencrypted. Anyone on the same network can read your password, your API token, and your documents.
- Only do this on a network you fully trust. Don't do this on hotel/coffee-shop/airport Wi-Fi.
- The consent applies per-host. The next time you connect to the same IP, the app remembers your choice. If you connect to a different host, you'll be asked again.

**What this does NOT do:**

- It doesn't disable HTTPS verification anywhere — `https://` URLs still need a trusted certificate (system CA or user-installed CA as in the Recommended path).
- It doesn't downgrade an HTTPS server to HTTP — if you enter `https://...` for a server that only speaks HTTP, the connection will fail rather than fall through.

## Troubleshooting

**"Sichere Verbindung nicht möglich" / "Secure connection not possible"** — Almost always: you entered `https://...` but the server is only listening on plain HTTP. Either set up the certificate above, or re-enter the address with `http://` and accept the consent dialog.

**"This server uses HTTP" dialog reappears every time** — Your phone may be clearing the app's storage between launches. Check that you don't have an aggressive "auto-clear cache" cleaner installed for this app.

**HTTPS works in the browser but not in the app** — The browser is using a different trust store. The app uses Android's user-installed CA store. Re-install your CA via the OS settings (step 4 above) and reboot the device once.

**Generating a diagnostic report** — When detection or login fails, the connection error screen offers a "Copy diagnostic report" button. The report is plain text and safe to share with support — it doesn't include your password or API token.
