import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ── 签名配置读取 ────────────────────────────────────────────────
// 优先读 keystore.properties（本地私有，已 gitignore）；
// 文件缺失时回退到环境变量，再回退到 AGP 默认 debug keystore。
// 这样 CI / 新机器不会因为缺文件而构建失败。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun prop(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = prop("storeFile", "BILIV3_STORE_FILE")
val releaseStorePassword = prop("storePassword", "BILIV3_STORE_PASSWORD")
val releaseKeyAlias = prop("keyAlias", "BILIV3_KEY_ALIAS")
val releaseKeyPassword = prop("keyPassword", "BILIV3_KEY_PASSWORD")

val hasReleaseKeystore = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.example.biliv3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.biliv3"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "1.6.6"
    }

    signingConfigs {
        // ── Debug：沿用 ~/.android/debug.keystore，但显式补齐 v1+v2+v3 ──
        // AGP 默认 debug 只出 v2（signing-config-versions.json 里 enableV1Signing=false），
        // 导致 META-INF/ 下没有 *.RSA/*.SF，只看 v1 的工具会显示「无证书」。
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }

        // ── Release：自签正式证书（4096-bit RSA / SHA256withRSA / 30 年） ──
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true   // Android 6 及以下 / 只看 v1 的校验工具
                enableV2Signing = true   // Android 7+ 全文件签名
                enableV3Signing = true   // Android 9+ 密钥轮换
                enableV4Signing = false  // 需要 .idsig 旁挂文件，侧载用不到
            }
        }
    }

    buildTypes {
        debug {
            // 显式绑定，避免被 AGP 默认值覆盖掉上面的 v1/v3 开关
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // ⚠️ 必须开：设置页「关于」的版本号要读 BuildConfig.VERSION_NAME。
        // 之前是**硬编码字符串**，versionName 升到 0.6.4 后设置页还写着 0.6.1
        // —— 这正是「反模式 #3 硬编码版本号」的实例。
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    /**
     * lint 纳入门禁（v1.5.3）。
     *
     * ## 为什么现在才开
     *
     * 之前 lint 有 **35 errors** 从未被发现 —— 因为 §1 的构建门禁
     * 只跑 `testDebugUnitTest / assembleDebug / assembleRelease`，
     * 不含 `lintDebug`。而 `abortOnError` 默认是开的，
     * 只是**没人跑**，所以那些错误一直存在。
     *
     * v1.5.3 把 35 个清到 **0 个**（30 条 Media3 `@UnstableApi` 类级
     * `@OptIn` + 2 条 `themes.xml` 的 `tools:targetApi` +
     * `remember` 返回 Unit + 组合期读 `StateFlow.value`），
     * 现在可以开成硬门禁。
     *
     * ⚠️ 只把 **error** 当门禁（`abortOnError = true`），
     * warning 不阻断 —— 剩下 21 条 warning 是依赖版本提示、
     * `Modifier` 参数顺序这类**建议**，逐条修收益低且噪音大。
     * 但 warning 会照常输出，新增时肉眼可见。
     *
     * ⚠️ 新增代码若引入 error（最典型的是用 Media3 非稳定 API 忘了
     * `@OptIn`），**构建会直接失败** —— 这是有意的。
     */
    lint {
        abortOnError = true
        // 只关心 error；warning 不阻断构建
        warningsAsErrors = false
        // release 构建也跑 lint（默认只对 debug 跑）
        checkReleaseBuilds = true
        // 报告里保留完整信息，便于排查
        checkDependencies = false
    }
}

/**
 * Compose 编译器指标（**只在手动开启时产出**，不进常规构建）。
 *
 * 用法：
 * ```
 * ./gradlew :app:assembleRelease -PcomposeMetrics
 * # 报告在 build/compose-metrics 与 build/compose-reports
 * ```
 *
 * ⚠️ **为什么需要它**：Compose 的最大性能陷阱是「参数不稳定 → 不可跳过」——
 * 父组件重组时，即使子组件参数没变也会被重新执行。这类问题**肉眼看不出**，
 * 只能靠编译器报告指出哪些 composable 是 `restartable skippable`、
 * 哪些参数被判定为 `unstable`。
 *
 * 本项目此前**从未开过**（所以「有没有不可跳过的热点」一直是未知数）。
 * 默认关闭是为了不给日常构建增加 IO。
 */
if (project.hasProperty("composeMetrics")) {
    composeCompiler {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 导航（阶段 0：接线全部死链）
    implementation(libs.androidx.navigation.compose)

    // 扫码登录二维码生成
    implementation(libs.zxing.core)

    // 播放器（主页暂不用，详情页阶段启用）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    // 直播（v1.6.3）：B 站直播只给 HTTP-FLV / HLS。
    // ⚠️ 缺了它 `HlsMediaSource` 是 Unresolved reference ——
    // 这就是此前"直播只能开浏览器"的直接原因（见 LiveViewModel 的历史说明）。
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // 后台播放 / 系统媒体中心（v1.3.0）。⚠️ 必须显式声明：
    // media3-exoplayer 不传递依赖 media3-session，缺了会 Unresolved reference。
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // ⚠️ 必须显式引入真实 org.json：android.jar 里的是 **stub**，
    // 本地 JVM 单元测试调用它的任何方法都会抛
    // `RuntimeException: Method xxx in org.json.JSONArray not mocked`。
    // 只影响 test classpath，不进 APK。
    testImplementation(libs.json)
}
