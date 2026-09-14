# dsh 手机部署（Termux）操作手册

目标：把 DeepSeek Harness 跑在手机本体（16G 内存 + 1T 存储），工作区 = 手机真实文件，
WebView APK 直连本机，**loopback 特权 API 全部可用**（服务器版被围栏挡住的
settings/credentials/pickDirectory 在手机上全通）。

## 0. 前置：APK

- **localhost 版 APK**：`D:\dsh-phone-local\out\dsh-phone.apk`（URL → http://127.0.0.1:3080/）
- 手机浏览器打开任意文件分享（QQ/微信/网盘）传到手机安装；需要允许"安装未知来源应用"

## 1. 安装 Termux

- 必须用 **F-Droid 版**（Google Play 版已废弃、包源过期）：
  `https://f-droid.org/packages/com.termux/`
- 安装后打开，等它初始化完

## 2. 装 Node.js + dsh

```bash
pkg update -y && pkg upgrade -y
pkg install -y nodejs-lts
node -v        # 必须 >= 22.19.0（dsh 要求 ^22.19.0 || >=24.0.0）
npm config set registry https://registry.npmmirror.com   # 国内加速
npm i -g @deepseek-ai/dsh
dsh --version
```

## 3. 存储授权 + API Key

```bash
termux-setup-storage        # 授权访问 /sdcard（首次会弹系统权限框，点允许）
# API Key 写入环境变量（用你自己的 DeepSeek Key）
echo 'export DEEPSEEK_API_KEY=sk-你的key' >> ~/.bashrc
source ~/.bashrc
```

## 4. 启动 dsh

```bash
termux-wake-lock            # 保活：锁屏不杀进程
dsh web --host 127.0.0.1 --port 3080
```

- `--host 127.0.0.1` 只绑本机，只有手机上的 APK 能访问，无需密码
- 保持这个终端开着（或后台：`nohup dsh web --host 127.0.0.1 --port 3080 >/dev/null 2>&1 &`）

## 5. 打开 APK

1. 打开 DSH App（localhost 版）→ 直连本机 3080
2. **添加工作区**：侧边栏 → "添加工作区" → 浏览器内目录选择器
   （browse 后端，纯 Node fs 实现，手机可用）→ 选
   - `/storage/emulated/0/` 根目录，或
   - `~/storage/`（Termux 映射到 /sdcard），或
   - Termux 内部目录 `~/`（数据更安全）
3. 开始用 —— 工作区就是手机本地文件，agent 直接读写手机目录

## 6. 开机自启（可选）

```bash
pkg install -y termux-boot
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/start-dsh.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
export DEEPSEEK_API_KEY=$(grep DEEPSEEK_API_KEY ~/.bashrc | cut -d= -f2)
dsh web --host 127.0.0.1 --port 3080 >/dev/null 2>&1 &
EOF
chmod +x ~/.termux/boot/start-dsh.sh
```

## 已知要点

| 项 | 说明 |
|---|---|
| 为什么手机版能选目录、服务器版不能 | pickDirectory 的 browse 后端无 GUI 依赖；服务器 403 是 loopback 围栏，手机本机不受限 |
| 密码 | 本地服务无密码；APK 的 basic auth 自动填充已置空 |
| 双端共存 | 服务器版 APK（18790）仍可用作备用；两个 APK 包名相同会互相覆盖安装——先装哪个就装哪个，切换需卸载重装 |
| 模型 | 仍是 DeepSeek API（DEEPSEEK_API_KEY），与服务器版共用额度 |
| 若 APK 白屏 | 检查 Termux 里 `dsh web` 是否在跑：`curl -s http://127.0.0.1:3080/api/health` 或看终端输出 |

## 排错

- `dsh: command not found` → `npm root -g` 确认，或 `export PATH=$PATH:$(npm prefix -g)/bin`
- 安装慢/失败 → 已切 npmmirror；还失败就 `pkg install -y nodejs`（24.x 也满足要求）
- APK 安装失败 → 手机设置允许未知来源；两个变体 APK 需先卸载旧的
- 锁屏后连不上 → 没开 wake-lock；Android 电池优化里把 Termux 设为"不受限制"
