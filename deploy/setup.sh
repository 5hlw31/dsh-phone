#!/bin/bash
# DeepSeek Harness 服务器部署脚本
#
# 用途：在一台干净的 Linux 服务器上，把 dsh web 跑起来并安全暴露到公网。
# 步骤：装 API Key → systemd 托管 dsh web → nginx 反代 + basic auth → 开防火墙 → 验证
#
# ⚠️ 脱敏版：
#    - 把 SERVER_HOST 换成你的服务器 IP 或域名
#    - API Key 由你自己填入 /etc/dsh/env（本脚本不读取任何第三方文件）
#
# 前置条件：
#    - 已安装 Node.js >= 22.19（dsh 的 engines 要求 ^22.19.0 || >=24.0.0）
#    - 已安装 dsh：npm i -g @deepseek-ai/dsh
#    - 已安装 nginx，且 htpasswd 可用（httpd-tools / apache2-utils）

set -e

SERVER_HOST="<SERVER_HOST>"        # ← 改成你的服务器 IP 或域名
DSH_PORT=18790                     # 对外暴露端口
DSH_LOCAL_PORT=18080               # dsh 本地监听端口（不对外）
BASIC_AUTH_USER="dsh"              # basic auth 用户名

echo "[1/6] 准备 API Key → /etc/dsh/env"
mkdir -p /etc/dsh
if [ ! -f /etc/dsh/env ]; then
    # 留空由人工填写：避免脚本里出现明文密钥，也避免误提交
    printf 'DEEPSEEK_API_KEY=\n' > /etc/dsh/env
    echo "      ⚠️ 已创建 /etc/dsh/env，请手动填入 DEEPSEEK_API_KEY 后重跑本脚本"
    echo "         格式： DEEPSEEK_API_KEY=sk-xxxxxxxx"
    chmod 600 /etc/dsh/env
    exit 0
fi
if ! grep -q '^DEEPSEEK_API_KEY=.\+' /etc/dsh/env; then
    echo "      ⚠️ /etc/dsh/env 里的 DEEPSEEK_API_KEY 为空，请填好后重跑"
    exit 1
fi
chmod 600 /etc/dsh/env
echo "      env 就绪 (文件权限 600)"

echo "[2/6] 生成 basic auth 密码"
if [ ! -f /etc/dsh/htpasswd ]; then
    # -c 会覆盖，所以只在文件不存在时创建
    htpasswd -c /etc/dsh/htpasswd "$BASIC_AUTH_USER"
else
    echo "      /etc/dsh/htpasswd 已存在，跳过（如需改密码：htpasswd /etc/dsh/htpasswd $BASIC_AUTH_USER）"
fi

echo "[3/6] 安装 systemd 服务 dsh-web"
sed -e "s|<SERVER_HOST>|$SERVER_HOST|g" \
    -e "s|<PORT>|$DSH_PORT|g" \
    -e "s|<LOCAL_PORT>|$DSH_LOCAL_PORT|g" \
    dsh-web.service > /etc/systemd/system/dsh-web.service
systemctl daemon-reload
systemctl enable --now dsh-web
sleep 3
systemctl is-active dsh-web

echo "[4/6] 安装 nginx 反代"
sed -e "s|<SERVER_HOST>|$SERVER_HOST|g" \
    -e "s|<PORT>|$DSH_PORT|g" \
    dsh-ssl.conf > /etc/nginx/conf.d/dsh-ssl.conf
nginx -t
systemctl reload nginx

echo "[5/6] 防火墙开放 $DSH_PORT"
if command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --add-port=${DSH_PORT}/tcp --permanent
    firewall-cmd --reload
elif command -v ufw >/dev/null 2>&1; then
    ufw allow ${DSH_PORT}/tcp
else
    echo "      ⚠️ 未检测到 firewall-cmd/ufw，请自行放行 $DSH_PORT"
fi
echo "      ⚠️ 云服务器还需在控制台安全组放行 $DSH_PORT（这一步脚本做不了）"

echo "[6/6] 验证"
ss -tlnp | grep "$DSH_LOCAL_PORT" || echo "!! $DSH_LOCAL_PORT 未监听"
curl -s -o /dev/null -w "本机探测 $DSH_LOCAL_PORT: HTTP %{http_code}\n" \
    "http://127.0.0.1:$DSH_LOCAL_PORT/" || echo "!! 本机探测失败"

echo
echo "完成。浏览器访问： https://$SERVER_HOST:$DSH_PORT  （自签证书需手动信任）"
