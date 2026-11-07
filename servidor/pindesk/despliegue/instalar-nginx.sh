#!/bin/sh
# **Instala el servidor de PinDesk en una VPS que YA tiene nginx** (otra web en los puertos 80/443), sin tocarla:
#   sudo sh despliegue/instalar-nginx.sh pindesk.51-222-80-12.nip.io
# Añade solo un sitio nuevo de nginx para ese dominio (con su certificado de Let's Encrypt, por certbot) que pasa a
# Node en 127.0.0.1. No cambia los otros sitios, ni el Node que ya haya, ni el cortafuegos.
set -e
DOMINIO="${1:?Uso: sudo sh despliegue/instalar-nginx.sh tu.dominio}"
PUERTO="${2:-8787}"
cd "$(dirname "$0")/.."
[ "$(id -u)" = 0 ] || { echo "Hace falta sudo"; exit 1; }

# El puerto de dentro tiene que estar libre (otra app puede usar el 8080).
# (Si ya estaba instalado, el puerto es el suyo: volver a correr el script, p. ej. con otro dominio, no choca.)
if [ ! -f /etc/pindesk/config.env ] && ss -ltn | grep -q ":$PUERTO\s"; then echo "El puerto $PUERTO está ocupado: elige otro (2.º argumento)"; exit 1; fi

# Node: sirve el del sistema si es 18 o más; si no hay ninguno en /usr/bin, se pone el 22. Nunca se cambia uno que ya está.
if [ -x /usr/bin/node ]; then
  M=$(/usr/bin/node -p 'process.versions.node.split(".")[0]')
  [ "$M" -ge 18 ] || { echo "El Node del sistema es $(/usr/bin/node -v): hace falta 18 o más"; exit 1; }
else
  apt-get update -y && apt-get install -y curl ca-certificates
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs
fi
command -v certbot >/dev/null || { apt-get update -y && apt-get install -y certbot python3-certbot-nginx; }

id pindesk >/dev/null 2>&1 || useradd --system --home /var/lib/pindesk --shell /usr/sbin/nologin pindesk
mkdir -p /opt/pindesk /var/lib/pindesk /etc/pindesk
cp -r src package.json /opt/pindesk/
chown -R pindesk:pindesk /var/lib/pindesk
node /opt/pindesk/src/claves.mjs /etc/pindesk
if [ ! -f /etc/pindesk/config.env ]; then
  sed "s/^PUERTO=.*/PUERTO=$PUERTO/" despliegue/config.env.ejemplo > /etc/pindesk/config.env
fi
chown -R root:pindesk /etc/pindesk && chmod 750 /etc/pindesk && chmod 640 /etc/pindesk/*
cp despliegue/pindesk.service /etc/systemd/system/pindesk.service
systemctl daemon-reload
systemctl enable --now pindesk
systemctl restart pindesk

# El sitio de nginx (solo este dominio), y luego el certificado.
P=$(grep '^PUERTO=' /etc/pindesk/config.env | cut -d= -f2)
cat > /etc/nginx/sites-available/pindesk <<NGX
server {
    listen 80;
    server_name $DOMINIO;
    client_max_body_size 20m;
    location / {
        proxy_pass http://127.0.0.1:$P;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
}
NGX
ln -sf /etc/nginx/sites-available/pindesk /etc/nginx/sites-enabled/pindesk
nginx -t && systemctl reload nginx
certbot --nginx -d "$DOMINIO" --non-interactive --agree-tos --register-unsafely-without-email --redirect

echo
echo "Listo. Falta: editar /etc/pindesk/config.env (GOOGLE_CLIENT_IDS y CLAUDE_API_KEY) y «sudo systemctl restart pindesk»."
echo "Prueba: curl https://$DOMINIO/salud   ·   Panel: https://$DOMINIO/admin"
