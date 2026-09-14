# DSH Phone · DeepSeek Harness 手机客户端

> 把 DeepSeek Harness 的 Web GUI 装进 Android WebView，并解决它在手机视口下的布局适配问题。

一个 **WebView 壳 + 服务端反代 + 注入式移动端皮肤** 的三层方案。Java 侧只有约 240 行，不含任何业务逻辑——真正的难点在于：**当一个 React 应用的响应式设计不为手机服务时，如何在不改它源码的前提下适配它。**

---

## 一、为什么需要这个

dsh（DeepSeek Harness）自带 Web GUI，用 `dsh web` 就能跑起来。它**是**响应式设计的，但它的断点是给**桌面窗口拖拽**设计的，不是给手机视口的。

拿手机浏览器直接打开会遇到五个问题，它们分别属于不同层：

| # | 问题 | 属于哪一层 |
|---|---|---|
| 1 | dsh 自身**没有认证机制**，公网暴露等于把 shell 交给所有人 | 服务端 |
| 2 | 自签证书被浏览器强制拦截，每次都要手动确认 | 客户端 |
| 3 | basic auth 密码每次都要手输 | 客户端 |
| 4 | 网页里的「附件」按钮在手机浏览器里点不动 | 客户端 |
| 5 | 字号偏大、底部按钮互相挤压、侧边栏展开后挤掉正文 | 前端布局 |

前两个用 nginx 解决，中间两个用 WebView 壳解决，最后一个**只能靠注入 JS/CSS 改页面本身**。

---

## 二、架构

```
┌─────────────────────────────────────────┐
│  Android App（本项目 app/）              │
│  ┌───────────────────────────────────┐  │
│  │ WebView                            │  │
│  │  ├─ 加载 dsh 的 Web GUI            │  │
│  │  ├─ onReceivedSslError   → 放行证书│  │
│  │  ├─ onReceivedHttpAuthRequest      │  │
│  │  │      → 自动填 basic auth 凭据   │  │
│  │  ├─ onShowFileChooser              │  │
│  │  │      → 拉起系统文件选择器       │  │
│  │  └─ onPageFinished                 │  │
│  │         → 注入 MOBILE_SKIN_JS      │  │
│  │              （布局适配，见第四节） │  │
│  └───────────────────────────────────┘  │
└────────────────────┬────────────────────┘
                     │ HTTPS（自签证书）
                     ▼
┌─────────────────────────────────────────┐
│  nginx（本项目 deploy/dsh-ssl.conf）     │
│  ├─ TLS 终结                            │
│  ├─ basic auth 密码门                   │
│  └─ 反向代理 → 127.0.0.1:18080          │
└────────────────────┬────────────────────┘
                     ▼
┌─────────────────────────────────────────┐
│  dsh web（deploy/dsh-web.service）       │
│  systemd 托管，只监听 loopback           │
│  EnvironmentFile=/etc/dsh/env           │
│         → DEEPSEEK_API_KEY              │
└────────────────────┬────────────────────┘
                     ▼
              DeepSeek API
```

**关键设计**：`--host 127.0.0.1` —— dsh 只监听本机，公网访问一律经 nginx。
这样即使 nginx 配错，dsh 也不会直接裸奔在公网。

---

## 三、原理（一）：WebView 壳解决的三个问题

这三件事**浏览器刻意不给你做**，而 WebView 是可编程的：

### 1. 自签证书放行

```java
@Override
public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
    handler.proceed();   // ⚠️ 见第五节「安全权衡」
}
```

浏览器遇到证书错误必须由用户手动确认（这是正确的默认行为）。WebView 允许你编程决定。

### 2. basic auth 免输入

```java
@Override
public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
        String host, String realm) {
    handler.proceed(AUTH_USER, AUTH_PASS);
}
```

nginx 返回 `401 WWW-Authenticate` 时，系统默认会弹输入框。这个回调让你直接把凭据递过去，实现「打开即用」。
（凭据留空时退回系统默认行为，方便本地无认证场景。）

### 3. 系统文件选择器

WebView 默认**不处理** `<input type="file">` —— 点「附件」按钮没有任何反应。
`onShowFileChooser` 是唯一的接口：把请求转成 `Intent` 打开系统选择器，结果再通过 `onActivityResult` 回灌。

```java
@Override
public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
        FileChooserParams params) {
    fileChooserCallback = callback;
    Intent intent = params.createIntent();
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
    return true;
}
```

注意 `onActivityResult` 里**多选和单选走不同字段**：多选在 `data.getClipData()`，单选在 `data.getData()`。

---

## 四、原理（二）：移动端布局适配 —— 本项目的核心

### 核心洞察

> **dsh 是 React 应用。React 的状态在 JS 里，纯 CSS 覆盖改不了状态。**

很多适配问题看着像 CSS 问题，实际是「视觉改了但状态没改」，于是行为和长相不一致。

### 案例：侧边栏抽屉（最难的一个）

**症状**：手机竖屏下展开侧边栏，它会占掉固定的 280px，把正文压得没法看。

**问题结构**：侧边栏的展开/收起，本质是外层 frame 的 CSS grid 第一列在 `280px` / `56px` 之间切换。
而那个展开/收起按钮的 `aria-label` 会在「打开侧边栏」/「收起侧边栏」之间切换 —— **这个 label 就是 React 的真实状态标志**。

**天真解法为什么失败**：

第一反应是给侧边栏加 `position:fixed!important` 让它浮起来当抽屉。结果是：

```
元素 fixed → 脱离 grid 流
    ↓
dsh 的收起逻辑操作的是「grid 列宽」
    ↓
但元素已经不在流里了，列宽改动对它无效
    ↓
状态切换了，视觉没跟上 → 收起时死锁
```

**正确解法**：**布局切换必须由 JS 自己接管，不能只靠 CSS 覆盖。**

```
轮询读 aria-label  →  得到 React 的真实意图
       │
       ├─ 展开：grid 第一列压成 0，正文改到第 2 列占满，
       │        侧边栏转 fixed 抽屉 + 全屏遮罩（点击遮罩 = 收起）
       │
       └─ 收起：还原 React 原本的 grid 值（56px rail），清掉所有内联样式
```

细节：
- **用 `setInterval` 轮询（250ms），不用 `MutationObserver`** —— WebView 里 MutationObserver 对这类频繁的样式/属性变更**实测会漏回调**。250ms 轮询足够跟手，开销可忽略（只读几个 aria-label）。
- **用 `window.__dshBridge` 做单例守卫** —— `onPageFinished` 可能多次触发，避免重复挂载。
- **提供 `teardown()`** —— 能完整还原所有 CSS/DOM 写入。

### 其他适配点

| 症状 | 处理 |
|---|---|
| 字体偏大 | `html{zoom:0.9}` 整体缩放（字体+布局同步） |
| 底部按钮互相挤压 | 类名 `uV2eYG_row` 是 flex nowrap 真凶 → 强制 `flex-wrap:wrap` |
| 设置面板横向溢出 | 面板容器 `overflow-y:auto` + 内部 `*{min-width:0}`（flex 收缩） |
| 深色主题不生效 | 见下 |

### 深色主题：为什么不用 `setForceDark`

dsh 的主题脚本读 `matchMedia('(prefers-color-scheme: dark)')`。手机若是浅色模式，界面就是浅色。
`WebView.setForceDark()` 看似是正解，但它会**在页面自身深色主题之上再叠一层暗化 → 产生黑色块**。

正确做法是直接驱动 dsh 自己的主题开关：

```js
document.documentElement.style.colorScheme = 'dark';
document.body.setAttribute('data-ds-dark-theme', '');
```

### 代价：依赖 CSS Modules 哈希类名

选择器形如 `[class*="VOzbGW_panel"]`、`[class*="uV2eYG_row"]` —— 这是 CSS Modules 生成的哈希前缀。

**为什么还这么做**：结构选择器和 aria-label 都比类名稳，能在不知道哈希的情况下工作。
**代价**：**升级 dsh 后哈希会变，样式会失效**。届时用开发者工具重新取前缀即可。

> 这是「不改上游源码」这条路的必然代价 —— 换来的是升级 dsh 不需要重新打包 APK。

---

## 五、安全设计与权衡

### 已实现

| 机制 | 实现 |
|---|---|
| 服务端认证 | nginx basic auth（dsh 自身无认证） |
| 传输加密 | TLS（basic auth 凭据必须走加密信道） |
| 不暴露后端 | dsh 只监听 `127.0.0.1`，公网只能经 nginx |
| 凭据不落仓库 | `/etc/dsh/env` 权限 600，`.gitignore` 排除 |
| 签名密钥不落仓库 | `*.keystore` 已加入 `.gitignore`，缺失时脚本自动重新生成 |

### ⚠️ 主动接受的权衡（明知风险仍妥协）

| # | 妥协 | 风险 | 正确解法 |
|---|---|---|---|
| 1 | `onReceivedSslError` 无条件 `proceed()` | 🔴 **MITM** —— 同 Wi-Fi 攻击者可解密/篡改流量 | **证书指纹绑定**：比对证书 SHA-256，不匹配则 `cancel()`。自签证书不轮换，pinning 在这里最容易实现 |
| 2 | `AUTH_USER/AUTH_PASS` 硬编码 → 编译进 APK | 🔴 **APK 可反编译**，`strings` 即可读出明文 | 运行时输入 + 存 Android Keystore；或改用设备绑定的短时 token |
| 3 | basic auth = 长期共享密码 | 🟡 无过期/无吊销，泄露只能整体换 | 前置带会话的认证（Authelia / oauth2-proxy） |
| 4 | `build.bat` 里 keystore 密码明文 | 🔵 密钥本身不入库，影响低 | 环境变量 / CI secret |

> **为什么把第 1、2 条保留在代码里而不是悄悄修掉**：
> 它们是**有意识的取舍**，不是没发现的漏洞。自签证书 + 个人自用是当时的约束；
> 但「知道代价是什么、正确解法是什么」和「没想到」是两回事。

### 上游的设计约束（不要试图绕过）

- `/api` 下的特权方法（`settings` / `credentials` / `llm.discoverModels`）被 dsh **硬编码为仅允许 loopback 调用**，远程访问一律 403。这是它的安全设计。
- 因此远程版**无法**用服务器文件系统做工作区；工作区选择需要走下面的「手机本机」方案。

---

## 六、两种部署方式

### A · 服务器版（远程）

dsh 跑在云服务器，手机随时可连。**代价**：特权 API 被 loopback 围栏挡住，工作区只能用服务器侧目录。

```bash
# 1. 服务器上填 API Key
sudo mkdir -p /etc/dsh && sudo vim /etc/dsh/env     # DEEPSEEK_API_KEY=sk-xxx
sudo chmod 600 /etc/dsh/env

# 2. 跑部署脚本（改好 SERVER_HOST 再跑）
cd deploy && bash setup.sh
```

### B · 手机本机版（Termux）

dsh 跑在手机本体（Termux），APK 直连 `127.0.0.1`。**特权 API 全部可用**（loopback 不受限），工作区 = 手机真实文件。

完整步骤见 [`docs/termux-deploy.md`](docs/termux-deploy.md)。要点：

```bash
pkg install -y nodejs-lts
npm i -g @deepseek-ai/dsh
termux-wake-lock                                    # 保活：锁屏不杀进程
dsh web --host 127.0.0.1 --port 3080
```

> ⚠️ Termux 下 dsh 的 `koffi`（FFI 原生依赖）没有预编译产物，需要 CMake + clang 工具链才能装。
> 这是这条路的主要门槛。

**两种方案的区别**：

| | 服务器版 | 手机本机版 |
|---|---|---|
| 特权 API | ❌ loopback 围栏 | ✅ 全通 |
| 工作区 | 服务器目录 | 手机真实文件 |
| 需要认证 | ✅ 必须（公网） | ❌ 不需要（仅本机） |
| 门槛 | 低 | 需 Termux 编译工具链 |

---

## 七、构建 APK

不依赖 Gradle，`app/build.bat` 直接用 Android SDK 的 build-tools 手工构建：

```bat
cd app
build.bat
```

流程：`aapt2 compile` → `aapt2 link`（生成 R.java）→ `javac` → `d8`（生成 dex）→ `zipalign` → `apksigner`。

**前置**：Android SDK build-tools 36.1.0 + platform android-34。
（实测 **R8 8.2.2 在 JDK 25 下会崩**，必须用 build-tools 36.1.0 自带的版本。）

**签名**：keystore 不存在时脚本会自动生成一个自用密钥（`dsh.keystore`，已 gitignore）。

> **为什么不用 Gradle**：这是个单 Activity 的壳，Gradle 的构建时间与依赖体积都不划算。
> 手工链路更适合「改一行 Java → 立刻出包」的迭代节奏。

---

## 八、项目结构

```
dsh-phone/
├── app/                                Android WebView 壳
│   ├── java/com/dsh/phone/MainActivity.java   ★ 全部逻辑（含 MOBILE_SKIN_JS）
│   ├── AndroidManifest.xml
│   ├── build.bat                       手工构建脚本（无 Gradle）
│   └── res/                            图标与字符串
├── deploy/                             服务端
│   ├── dsh-web.service                 systemd 单元
│   ├── dsh-ssl.conf                    nginx 反代 + basic auth
│   └── setup.sh                        一键部署
├── docs/
│   └── termux-deploy.md                手机本机方案（Termux）
└── README.md
```

---

## 九、已知限制

按重要性排序：

- **依赖 CSS Modules 哈希类名** —— dsh 升级后注入的样式可能失效，需重新取前缀（第四节末）
- **只适配了竖屏窄屏**（≤560px 断点），平板/横屏未专门处理
- **`setInterval` 轮询**（250ms）—— 简单可靠但有固定开销；理论上可换成更精细的观察机制
- **无自动化测试** —— 布局适配的效果依赖真机目视验证
- **APK 仅自用签名** —— 不适合上架分发

---

## License

本仓库代码（`app/`、`deploy/`、`docs/`）未指定许可，使用前请先联系作者。
