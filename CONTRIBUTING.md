# 参与贡献（Contributing）

很高兴你有兴趣改进本项目！

## 环境准备
- JDK 17 + Android SDK（用 Android Studio 一键安装即可）
- 拉取子模块：`git submodule update --init --recursive`

## 提 Issue
请写清楚：**做了什么 → 期望什么 → 实际怎样**，并附上复现步骤、手机型号和系统版本。

## 提 Pull Request
1. 从最新 `master` 切一个新分支。
2. 改完务必跑通：`gradlew.bat :app:assembleStandardDebug`（看到 `BUILD SUCCESSFUL`）。
3. 说明改了哪里、为什么（尽量贴关键代码 / 改动前后对比）。
4. 遵守 **GPL-3.0**：你的改动同样按 GPL-3.0 授权。

## 代码风格
- 尊重原项目风格；改中文文案时用 **UTF-8**，并存到对应语言包 `strings.xml`（`values/` 与 `values-zh-rCN/`）。
- 不要提交本地环境文件（`local.properties`、`build/`、`.idea/` 等）——`.gitignore` 已处理。

感谢你的贡献！
