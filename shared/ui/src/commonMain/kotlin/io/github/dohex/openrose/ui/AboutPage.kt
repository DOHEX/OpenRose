package io.github.dohex.openrose.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.ui.resources.Res
import io.github.dohex.openrose.ui.resources.app_icon
import org.jetbrains.compose.resources.painterResource

/** A row on 关于 that opens [url] in the system browser. */
private data class AboutLink(val title: String, val summary: String, val url: String)

private val projectLinks = listOf(
    AboutLink("GitHub 仓库", "DOHEX/OpenRose", "https://github.com/DOHEX/OpenRose"),
    AboutLink("问题反馈", "在 GitHub Issues 报告问题或提出建议", "https://github.com/DOHEX/OpenRose/issues"),
    AboutLink("许可证", "GPL-3.0-or-later", "https://github.com/DOHEX/OpenRose/blob/main/LICENSE"),
    AboutLink("开发者", "DOHEX", "https://github.com/DOHEX"),
)

/** Keep in step with the 致谢 section of README.md and README.en.md. */
private val acknowledgements = listOf(
    AboutLink("OppoPods", "HyperOS 系统集成参考了它的实现思路", "https://github.com/1812z/OppoPods"),
    AboutLink("Miuix", "Miuix 界面风格使用的组件库", "https://github.com/compose-miuix-ui/miuix"),
    AboutLink("Backdrop", "Miuix 液态玻璃底栏使用的效果库", "https://github.com/Kyant0/AndroidLiquidGlass"),
    AboutLink("LSPosed", "运行系统集成模块的 Xposed 框架", "https://github.com/LSPosed/LSPosed"),
    AboutLink("libxposed API", "模块使用的 Xposed API", "https://github.com/libxposed/api"),
    AboutLink("Compose Multiplatform", "跨平台界面框架", "https://github.com/JetBrains/compose-multiplatform"),
)

/** 关于, reached from Settings: the app's identity and build version, project links, and acknowledgements. */
@Composable
fun AboutPage() {
    val uriHandler = LocalUriHandler.current
    var failedLink by remember { mutableStateOf<AboutLink?>(null) }
    val open: (AboutLink) -> Unit = { link ->
        failedLink = if (runCatching { uriHandler.openUri(link.url) }.isSuccess) null else link
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AboutHeader()
        failedLink?.let { link -> LinkFailureCard(link) { failedLink = null } }
        AppGroup(title = "项目") {
            projectLinks.forEach { link -> LinkRow(link, open) }
        }
        AppGroup(title = "致谢") {
            acknowledgements.forEach { link -> LinkRow(link, open) }
        }
    }
}

@Composable
private fun LinkRow(link: AboutLink, onOpen: (AboutLink) -> Unit) {
    AppArrowRow(title = link.title, summary = link.summary, endIcon = RoseIcon.OPEN_IN_NEW, onClick = { onOpen(link) })
}

/** A host without a browser cannot open links; say so and show the address the user can open elsewhere. */
@Composable
private fun LinkFailureCard(link: AboutLink, onDismiss: () -> Unit) {
    AppDashboardCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(RoseIcon.WARNING, tint = appColor(AppColorRole.Error))
            AppText("无法打开“${link.title}”：当前设备没有可用的浏览器")
        }
        AppText(link.url, role = AppTextRole.Summary)
        AppButton("知道了", onDismiss, kind = AppButtonKind.Secondary)
    }
}

@Composable
private fun AboutHeader() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        AppText("OpenRose", role = AppTextRole.Display)
        Spacer(Modifier.height(4.dp))
        AppText("ROSE / ROSESELSA 耳机控制", role = AppTextRole.Summary)
        AppText("版本 ${AppVersion.NAME}", role = AppTextRole.Summary)
    }
}
