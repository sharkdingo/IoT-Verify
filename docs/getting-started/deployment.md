# Production Deployment

> Verified against code on 2026-07-22. Source: `backend/pom.xml`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/configure/ProductionSafetyCheck.java`,
> `frontend/package.json`, `frontend/src/api/`, `frontend/vite.config.ts`.

This guide covers packaging and deploying IoT-Verify for production. For the full list of environment variables and their defaults, see [configuration.md](./configuration.md) — it is the single source of truth. This page only names the variables that matter for a production rollout.

## Backend

Package the backend into a runnable jar and start it under the production profile:

```bash
cd backend
mvn clean package -DskipTests
java -jar target/Iot-Verify-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Production safety check

When the backend starts under the `prod` or `production` profile, `ProductionSafetyCheck` refuses to start unless the following values are overridden from their unsafe defaults:

- `JWT_SECRET`
- `DB_PASSWORD`
- `IOT_VERIFY_OPENAI_API_KEY`

If any of these still hold their default value, startup aborts with an error. Set real values before deploying — see [configuration.md](./configuration.md) for how each variable is resolved.

### CORS

`CORS_ORIGINS` must include the origin from which the deployed frontend is served (scheme, host, and port). Requests from an origin not in this list are rejected by the browser. See [configuration.md](./configuration.md).

## Frontend

Build the static bundle:

```bash
cd frontend
npm run build
```

This produces a `dist/` directory. Deploy `dist/` behind a web server (for example
Nginx). In the default same-origin build, frontend requests go to relative `/api`, so
the web server should proxy `/api` to the backend.

That same-origin proxy is the default and recommended production shape: leave
`VITE_API_BASE_URL` empty when building the frontend and proxy `/api` from the SPA host
to the backend. For a cross-origin deployment, build the frontend with an absolute
`VITE_API_BASE_URL` such as `https://api.example.com`; `src/api/http.ts` and
`src/api/chat.ts` append `/api` themselves. In that cross-origin shape, configure
`CORS_ORIGINS` on the backend to include the frontend origin, and the SPA host does not
need an `/api` reverse proxy.

## Nginx reference configuration

The web server must terminate TLS, redirect HTTP to HTTPS, serve the SPA bundle with a
`try_files` fallback to `index.html` (so client-side routes resolve), and proxy `/api`
to the backend on `localhost:8080`:

```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header X-Content-Type-Options "nosniff" always;
    client_max_body_size 4m;

    location / {
        root /var/www/iot-verify/dist;
        try_files $uri $uri/ /index.html;
    }

    # Portable scenes may include self-contained template snapshots and embedded icons.
    location = /api/board/scene {
        client_max_body_size 64m;
        proxy_pass http://localhost:8080;
    }

    location = /api/board/batch {
        client_max_body_size 64m;
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    location /api {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

Replace the certificate paths with certificates issued for the host. Make sure the deployed frontend origin (for example `https://your-domain.com`) is present in `CORS_ORIGINS` on the backend.
If `VITE_API_BASE_URL` points to a different origin, add that exact HTTPS origin to the CSP
`connect-src` directive as well; the reference policy intentionally permits same-origin API
and SSE connections only. Public pages use bundled fonts and inline topology graphics,
with no external background-media host.
Restrict backend port `8080` to the local reverse proxy or a trusted private network.
The application trusts Nginx's overwritten `X-Real-IP` for authentication rate limiting
only when the immediate peer is loopback; it does not trust that header on direct requests.
Keep the exact scene-batch location before the general `/api` location so its 64 MiB limit
matches `IOT_VERIFY_MAX_SCENE_REQUEST_BYTES`, while unrelated API requests remain capped at
4 MiB.

## Related

- Local setup: [installation.md](./installation.md)
- Configuration reference: [configuration.md](./configuration.md)
- Troubleshooting: [../guides/troubleshooting.md](../guides/troubleshooting.md)
- Documentation map: [../README.md](../README.md)
