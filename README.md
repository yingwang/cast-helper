# 📺 电视投屏助手 Cast Helper

把不带"投屏"按钮的网站视频,投到支持 Chromecast 的电视上。提供两种形态:一个纯静态网页,和一个安卓 App。无后端、不收集任何数据。

**在线网页:<https://yingwang.github.io/cast-helper/>**(需要电脑或安卓上的 Chrome / Edge)

## 📱 安卓 App(推荐给 Boox / 安卓手机)

App 内自带浏览器,自动嗅探视频、一键投屏,比网页版省事——**打开网站 → 登录播放 → 点「投屏到电视」**,不用弄书签、不用复制链接。

- **下载安装包:<https://github.com/yingwang/cast-helper/releases/download/app-latest/cast-helper.apk>**
- 安装:Boox 上用浏览器打开上面的链接下载,点开安装;若提示,请在「设置 → 安全」里允许该浏览器「安装未知来源应用」。
- 前提:设备装有 Google Play 服务、和电视连同一 Wi-Fi。
- ⚠️ 仅安卓可用;**iPhone / iPad 无法安装非 App Store 应用**,请用在线网页或 Web Video Caster 之类的现成 App。

> 安装包由 GitHub Actions 自动编译发布([Build Android APK](../../actions/workflows/android.yml)),源码见 [`android/`](android/)。

## 用法

1. 把页面里的「🎯 抓视频直链」按钮拖到浏览器书签栏;
2. 在视频网站上让视频播放几秒,点一下这个书签,视频直链会被自动找出并复制;
3. 回到工具页粘贴直链,点「投到电视」——电视原生拉流播放,页面自带播放/进度/音量遥控条。

直链投不动的网站(防盗链、跨域限制),用 Chrome 自带的「投放标签页」兜底,页面里有完整步骤说明。

## 限制

- 仅供投放**你自己有权观看**的内容;不支持、也不会绕过 DRM(Netflix / Disney+ 等不可用);
- iPhone / iPad 的浏览器不支持 Chromecast 投屏,请用电脑或安卓设备;
- 你的设备需要和电视连在同一网络。

## 本地运行

下载 `index.html`,在其所在目录运行:

```bash
python3 -m http.server 8000
```

然后浏览器打开 <http://localhost:8000>(投屏组件要求页面通过 `https://` 或 `localhost` 打开,双击文件的 `file://` 方式不行)。
