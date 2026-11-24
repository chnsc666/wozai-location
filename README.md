# 我在哪（Android App）

打开 App 即显示**当前详细地址 + 经纬度**，专为老人设计：
- 超大字体（24–40sp）、高对比度米黄色背景
- 零操作：打开即自动申请权限并定位，每 5 秒自动刷新
- 一个「分享位置」大按钮（自动复制位置信息并跳转微信）、一键「重新定位」
- 界面显示**网络卡片**：运营商、制式（仅 4G/5G）、小区标识 CID 并在括号内直接折算出「基站ID_小区标识」（2G/3G 不显示）
- 兼容 Android 7.0 ~ 14（minSdk 24 / targetSdk 34）
- **纯系统原生定位**（LocationManager 的 GPS + 基站/WiFi），不依赖 Google 服务，专为国产手机设计
- 地址解析采用**高德 → 腾讯 → 系统 Geocoder 三级回退**：优先高德，其次腾讯，两家都未配置或失败时用系统自带地址服务

## 项目结构

```
我在哪/
├── build.gradle.kts            # 顶层构建脚本
├── settings.gradle.kts         # 仓库配置（内置阿里云镜像注释，可打开加速）
├── gradle.properties
└── app/
    ├── build.gradle.kts        # 仅 androidx 基础库，无 Google 服务依赖
    └── src/main/
        ├── AndroidManifest.xml # 定位权限声明
        ├── java/com/elderly/location/MainActivity.kt  # 定位、地址解析、分享
        ├── java/com/elderly/location/CellInfoReader.kt # 运营商/制式/小区标识读取
        └── res/                # 界面布局、样式、图标
```

## 如何生成 APK（两种方式任选）

### 方式一：Android Studio（推荐，最简单）
1. 电脑安装 [Android Studio](https://developer.android.com/studio)（国内可直接搜官网或使用镜像下载）。
2. 打开 Android Studio → `Open` → 选择本项目文件夹 `我在哪`。
3. 等待右下角 Gradle Sync 完成（首次会自动下载依赖，约几分钟；如下载慢，打开 `settings.gradle.kts` 取消阿里云镜像两行注释）。
4. 菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)`。
5. 生成路径：`app/build/outputs/apk/debug/app-debug.apk`，把 APK 发到老人手机安装即可。

### 方式二：命令行
```bash
cd wozina
./gradlew assembleDebug     # Windows 用 gradlew.bat assembleDebug
```
> 工程已内置 Gradle Wrapper（8.9），未安装 Gradle 也可直接构建；首次运行会自动下载 Gradle 与依赖。
> Windows 也可直接双击 `build.bat`，脚本会自动设置 JDK 17 与 SDK 路径后执行打包。

## 权限说明
App 首次打开会弹出定位权限询问，老人只需点「允许」。
如之前误点「拒绝」，需到手机「设置 → 应用 → 我在哪 → 权限 → 位置信息」开启。

## 后续可扩展
- 把经纬度换成 GCJ-02（火星坐标）以适配高德/腾讯地图显示
- 增加「一键拨打家人电话」按钮
- 增加后台持续定位 + 短信上报（用于走失求助）


## 基站信息说明（重要）
- App 读取的是运营商基站编号（小区标识 CID），**这些数字本身不含经纬度**。
- 想把基站编号换算成坐标，需要调用基站定位数据库接口（高德/腾讯定位 API，需申请 Key），
  如需此功能可在此基础上扩展。
- 读取基站信息需要定位权限（已有）；部分机型在 Android 10+ 上如返回空，
  需确认系统"位置信息"开关已打开。
- 5G NR 的 NCI 读取要求 Android 10 及以上系统。


## CID 拆分规则（基站ID_小区标识）
- 4G LTE：ECI（CID）= 基站 ID（eNB ID，前 20 位）<< 8 | 小区标识（后 8 位）
  例：CID 37281665 → 基站ID_小区 = 145631_129
- 5G NR：按运营商常见的 gNB ID 24 位 + 小区 12 位拆分
  例：NCI 885423136 → 基站ID_小区 = 216167_3104
  注：个别运营商 gNB ID 位数可能不是 24 位，如发现与网管数据对不上可调整 `CellInfoReader.kt` 中的移位位数。


## 常见问题：定位需要地图 Key 吗？
**不需要。** 经纬度来自手机自身的定位能力（GPS + 基站/WiFi），与任何地图厂商的 Key 无关：
- 4G/5G 手机打开「位置信息」即可定位，App 不申请、也不需要填写任何 Key
- 所有手机统一走系统 `LocationManager`（GPS + 基站/WiFi 双来源持续监听），不依赖 Google 服务

**什么情况才需要 Key：**
| 需求 | 是否需要 Key |
|---|---|
| 显示经纬度、分享位置 | 不需要 |
| 显示当前位置的地图画面（高德/腾讯地图 SDK） | 需要 |
| 把经纬度转换成详细地址（云端逆地理编码，比系统 Geocoder 更稳） | 需要（免费额度够用） |
| 用基站 CID 反算经纬度（基站定位数据库） | 需要 |

**关于详细地址：** App 支持高德、腾讯两家 Web 服务逆地理编码接口，按「高德 → 腾讯 → 系统 Geocoder」顺序自动回退。详见下节配置方法。


## 地图 Key 配置（高德 + 腾讯）

Key **在 App 内填写、保存在手机本机**，源码中为空，不需要改代码。入口（隐藏操作，防止老人误触）：

> 长按标题「我现在的位置」的第一个字「我」→「设置」→ 再长按「设置」标题 → 进入「地图 Key 与定位设置」，分别填写高德、腾讯 Key。

两家都填可获得「高德 → 腾讯 → 系统 Geocoder」三级回退；只填一家也能用；都不填则只用系统自带地址服务（部分机型可能查不到详细地址）。

### 高德 Web 服务 Key
1. 打开 https://console.amap.com/ 注册并实名认证
2. 应用管理 → 创建新应用 → 添加 Key
3. **服务平台选择「Web服务」**（不是 Android 平台，Web 服务 Key 不限制包名/SHA1，可直接在 App 内使用）
4. 复制 Key 填入 App 的「高德 Key（Web服务）」输入框

### 腾讯 WebService Key
1. 打开 https://lbs.qq.com/ 注册并登录控制台
2. 应用管理 → 创建应用 → 添加 Key
3. 勾选 **「WebServiceAPI」**，校验方式留空（不绑定包名/SHA1）
4. 复制 Key 填入 App 的「腾讯 Key（WebService）」输入框

### 说明
- 保存时 App 会用固定坐标联网实测每家 Key 是否有效，全部通过才保存
- 免费额度：高德个人开发者每日约 5000 次、腾讯也有免费额度，老人日常使用完全够用
- Key 属于私有凭证，只存在本机且被系统备份规则排除，**不要写进源码或提交到公开仓库**
- 坐标系已自动处理：手机 GPS 是 WGS-84，调用高德/腾讯前自动转 GCJ-02
