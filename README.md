# 每日计划 App V1.3.0

基于 Kotlin、Jetpack Compose 和 Room 的 Android 任务管理应用，并提供可在 Android、iPhone 和电脑浏览器使用的网页/PWA。启用 Firebase 后，Android 与网页使用同一邮箱账号同步任务、分类和完成记录；本地数据库继续保留，离线时 Android 原有功能仍可使用。

## Android 打开与构建

1. 使用 Android Studio 打开本目录。
2. 安装 Android SDK Platform 35 和 JDK 17。
3. 等待 Gradle Sync 完成，然后运行 `app` 配置，或执行 `gradlew assembleDebug` 构建调试包。

应用最低支持 Android 8.0（API 26），包名为 `com.dailyplan.app`。

## 功能

- 每日、单次及自定义周期任务：每周指定星期、每隔 N 天、每月指定日期。
- 本地任务执行记录；完成、撤销、暂停、编辑和软删除，历史记录保留。
- 今日进度、逾期单次任务、月历和完整历史记录。
- 可编辑任务分类、首页和计划页分类筛选。
- 通过系统文件选择器导入或导出 CSV / Excel 计划文件。
- 长按多选任务，批量修改分类或周期起始日期、暂停/恢复、软删除及导出。
- Android 每项任务可设置提醒时间、准时或提前 5/10/30 分钟提醒。
- 自动或手动检查 GitHub Releases，下载后校验 SHA-256、包名、版本和签名，再调用系统安装界面。
- 自动更新检查默认开启，每 24 小时最多执行一次；设置页可关闭。
- 登录后在 Android 与网页间同步任务、分类及完成记录。

## 启用账号和云端同步

需要在 Firebase Console 创建一个 Firebase 项目。Android 和网页必须使用同一个 Firebase 项目。

1. 在 Firebase Authentication → Sign-in method 中启用 **Email/Password**。
2. 在 Firebase 项目中注册 Android 应用，包名填写 `com.dailyplan.app`，下载 `google-services.json` 并放到 `app/google-services.json`。
3. 在同一 Firebase 项目中注册一个 Web 应用，把 Console 显示的 `apiKey`、`authDomain`、`projectId`、`appId` 填入 `web/firebase-config.js`。
4. 在 Firebase Console 创建 Firestore 数据库，并将项目根目录 `firestore.rules` 中的规则发布到 Firestore Rules。
5. 在 Firebase Authentication 的授权域名列表中加入 `leo12138-bai.github.io`。
6. 将代码上传到 GitHub 仓库 `Leo12138-bai/DailyPlan` 的 `main` 分支。在仓库 Settings → Pages 中把部署来源设为 **GitHub Actions**。工作流会发布 `web/` 目录。
7. 等 GitHub Actions 成功后，用手机浏览器打开 `https://leo12138-bai.github.io/DailyPlan/`。Android 与网页注册/登录时使用相同邮箱和密码。

`google-services.json` 和网页 Firebase 配置是客户端配置，不是服务端私钥；Firestore 安全规则会限制用户只能访问自己账号下的数据。不要把 Firebase Admin SDK 服务账号私钥或其他服务端密钥放进网页或 GitHub。

第一次在已有 Android 数据的设备登录时，程序会把本地任务、分类和完成记录与账号云端数据合并，不会清空本地数据库。新设备登录后会下载该账号数据。每台 Android 设备的本地数据库绑定到首次使用的账号；为了防止不同账号数据混在一起，同一设备暂不支持直接切换到另一个账号。

网页端支持任务、周期、分类、完成记录、月历和 CSV 导入/导出。网页提醒只在页面保持打开时检查；浏览器关闭后的可靠推送需要另外配置推送服务。Android 原有系统提醒不受网页提醒限制。

## 发布 Android 更新

更新源在 `gradle.properties` 的 `UPDATE_GITHUB_REPOSITORY` 中配置，为 `Leo12138-bai/DailyPlan`。发布时：

1. 在 `app/build.gradle.kts` 提高 `versionCode`，更新 `versionName`。V1.3.0 对应 `versionCode = 4`、`versionName = "1.3.0"`。
2. 始终使用已发布正式版的同一 keystore、alias 和证书。不要新建密钥；密钥文件保存在项目目录之外。不要将密钥或密码提交到 GitHub。
3. 将 `DAILYPLAN_RELEASE_STORE_FILE`、`DAILYPLAN_RELEASE_STORE_PASSWORD`、`DAILYPLAN_RELEASE_KEY_ALIAS`、`DAILYPLAN_RELEASE_KEY_PASSWORD` 设置为构建进程的环境变量，然后执行 `gradlew assembleRelease`。Gradle 不会把密码写入项目文件。
4. 设置 `DAILYPLAN_RELEASE_CERT_SHA256` 为既有发布证书的 SHA-256 指纹，然后运行：

   ```powershell
   .\scripts\prepare-github-release.ps1 -ApkPath "app\build\outputs\apk\release\app-release.apk"
   ```

5. 将脚本生成的 `release-assets\DailyPlan-v<versionName>.apk` 与 `release-assets\dailyplan-update.json` 上传到 GitHub 正式 Release。Release 正文会作为更新日志显示。

覆盖升级要求 applicationId 不变且签名证书兼容。继续使用当前正式版的发布密钥，不能卸载旧版后换签名；否则可能丢失设备本地数据，也无法正常覆盖升级。

## 数据库迁移与计划模板

V1.3.0 将 Room 数据库从版本 2 增至版本 4，通过增量迁移增加同步时间戳和删除标记，不删除现有任务、分类、执行记录、重复规则或提醒配置。旧数据库会自动迁移。分类删除和撤销完成记录会带同步标记，避免离线设备重连后恢复已删除内容。

设置 → 计划导入与导出 → 空白模板，可生成 CSV 或 Excel 模板。项目内也提供 CSV 模板：`templates/DailyPlan-template.csv`。Android 导入默认跳过重复项，不覆盖现有任务；存在错误时需先修正文件或明确选择跳过错误行。网页目前支持 CSV，不支持 `.xlsx`。

## 提醒说明

Android 精确提醒需要用户在系统设置中允许“闹钟和提醒”，通知也需要单独授权。授权缺失时应用会显示状态，并使用非精确闹钟作为尽力回退；省电策略、厂商后台管理和勿扰模式仍可能影响送达时间。
