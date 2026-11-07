#!/bin/sh
# **Instala el servidor de PinDesk en una VPS con Ubuntu o Debian** (como root):
#   sh despliegue/instalar.sh api.tudominio.com
# Hace: Node 22, Caddy (HTTPS), el usuario «pindesk», las claves (una vez), el servicio y el cortafuegos.
set -e
DOMINIO="${1:?Uso: sh despliegue/instalar.sh api.tudominio.com}"
cd "$(dirname "$0")/.."

apt-get update -y
apt-get install -y curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https ufw
# Node 22 (NodeSource)
if ! node --version 2>/dev/null | grep -q '^v2[2-9]'; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
# Caddy
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y && apt-get install -y caddy
fi

id pindesk >/dev/null 2>&1 || useradd --system --home /var/lib/pindesk --shell /usr/sbin/nologin pindesk
mkdir -p /opt/pindesk /var/lib/pindesk /etc/pindesk
cp -r src package.json /opt/pindesk/
chown -R pindesk:pindesk /var/lib/pindesk
node /opt/pindesk/src/claves.mjs /etc/pindesk
[ -f /etc/pindesk/config.env ] || cp despliegue/config.env.ejemplo /etc/pindesk/config.env
chown -R root:pindesk /etc/pindesk && chmod 750 /etc/pindesk && chmod 640 /etc/pindesk/*

sed "s/api.tudominio.com/$DOMINIO/" despliegue/Caddyfile > /etc/caddy/Caddyfile
cp despliegue/pindesk.service /etc/systemd/system/pindesk.service
systemctl daemon-reload
systemctl enable --now pindesk
systemctl reload caddy || systemctl restart caddy

# Cortafuegos: solo SSH y la web. El servidor de Node no se ve desde fuera (escucha en 127.0.0.1).
ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable

echo
echo "Listo. Falta: editar /etc/pindesk/config.env (GOOGLE_CLIENT_IDS y CLAUDE_API_KEY) y «systemctl restart pindesk»."
echo "Prueba: curl https://$DOMINIO/salud   ·   Panel: https://$DOMINIO/admin"
