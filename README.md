# DSH Phone

一个早期的 Android WebView 练习：把 DeepSeek Harness 网页放进手机应用，处理文件选择、窄屏布局和服务器反代。使用 Java 和 Android 原生 WebView；DSH 引擎和网页来自上游。

后续手机端选择社区的 [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)，由 **ccch1mneyyy** 开发。本仓库保留旧实现和学习记录，接入说明见 [迁移到 DSH Go](docs/dsh-go-migration.md)。

## 旧版本做了什么

- WebView 加载服务器上的 DSH 网页。
- `onShowFileChooser` 调用 Android 系统文件选择器，接收单选和多选结果。
- 页面加载后注入 CSS 和侧栏脚本，尝试改善手机视口下的布局。
- nginx 负责 HTTPS、Basic Auth 和反向代理；systemd 托管仅监听回环地址的 DSH 进程。

Android 部分主要负责网页承载与系统接口。布局补丁依赖上游网页结构，这也是后续选择维护中的专用客户端的原因。

## 已知局限

- 文件选择器接通后，上传流程仍需验证取消选择、连续选择、类型限制和实际服务器接收。
- 注入脚本包含 CSS Modules 类名、固定布局假设和轮询，上游更新后可能失效。
- 旧源码无条件放行 TLS 证书错误，并通过源码常量配置地址与 Basic Auth。这些代码记录原实现；真实凭据应留在仓库之外。
- 旧构建脚本面向 Windows。本次只更新文档和迁移说明，APK 构建和新版 Android 真机验收尚待完成。

## 文件

| 路径 | 内容 |
|---|---|
| `app/java/com/dsh/phone/MainActivity.java` | WebView、文件选择器和布局注入 |
| `app/AndroidManifest.xml` | Android 权限和入口 |
| `app/build.bat` | 旧版 Windows 构建脚本 |
| `deploy/` | 原服务器部署示例，地址与凭据使用占位符 |
| `docs/termux-deploy.md` | 原 Termux 部署记录 |
| `docs/dsh-go-migration.md` | DSH Go 来源、SSH 接入和验证范围 |

## 这段经历可以说明什么

这是一次从网页到手机端的集成练习：需要分清原生应用、网页、代理和后端的职责，也接触到了文件选择、鉴权、证书和响应式布局。

后续工作重心放在 Agent Phone 的文件处理、任务状态与结果核对，以及 persona-lab 的对话体验。DSH Phone 保留作早期项目；DSH Go 的原生界面、本机运行和其他功能属于上游作者的工作。

## 上游

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)
- [DSH Go 发布页](https://github.com/ccch1mneyyy/dsh-Go/releases/latest)
- [DSH Go SSH 与引擎说明](https://github.com/ccch1mneyyy/dsh-Go/blob/main/docs/guide/zh/03-engine-hosts.md)
