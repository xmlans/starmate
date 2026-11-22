package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import org.xmyun.starmate.core.ui.theme.StarmateTheme
import org.xmyun.starmate.core.ui.theme.wallpaperBottom
import org.xmyun.starmate.core.ui.theme.wallpaperStar
import org.xmyun.starmate.core.ui.theme.wallpaperTop

/**
 * 聊天壁纸：一片从主题色渐变到邻近色的天空，上面散着小星星。纯装饰，读屏不会读到。
 *
 * 星星的位置由固定的种子算出，每次打开都一样，不会随重组跳动。
 * 毛玻璃要有内容可糊才看得出来，所以聊天页的标题栏和输入框下面垫的就是这张壁纸。
 */
@Composable
fun StarryWallpaper(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val top = colors.wallpaperTop
    val bottom = colors.wallpaperBottom
    val star = colors.wallpaperStar
    Box(
        modifier.fillMaxSize().drawWithCache {
            val sky = Brush.linearGradient(
                colors = listOf(top, bottom),
                start = Offset(size.width * 0.15f, 0f),
                end = Offset(size.width * 0.85f, size.height),
            )
            val cell = CELL.toPx()
            val columns = (size.width / cell).toInt() + 1
            val rows = (size.height / cell).toInt() + 1
            val sparkles = Path()
            val faintSparkles = Path()
            val dots = ArrayList<Pair<Offset, Float>>()
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val random = Random(row * 73 + column * 151 + 7)
                    val center = Offset(
                        x = (column + 0.15f + random.nextFloat() * 0.7f) * cell,
                        y = (row + 0.15f + random.nextFloat() * 0.7f) * cell,
                    )
                    when (random.nextInt(10)) {
                        0, 1, 2 -> sparkles.addSparkle(center, (4f + random.nextFloat() * 4f).dp.toPx())
                        3, 4 -> faintSparkles.addSparkle(center, (2.5f + random.nextFloat() * 2f).dp.toPx())
                        5, 6, 7 -> dots += center to (0.9f + random.nextFloat() * 0.9f).dp.toPx()
                        else -> Unit
                    }
                }
            }
            onDrawBehind {
                drawRect(sky)
                drawPath(sparkles, star)
                drawPath(faintSparkles, star, alpha = 0.6f)
                dots.forEach { (center, radius) -> drawCircle(star, radius, center, alpha = 0.75f) }
            }
        },
    )
}

/** 四角星：四个尖，边向里凹。 */
private fun Path.addSparkle(center: Offset, radius: Float) {
    val waist = radius * 0.2f
    moveTo(center.x, center.y - radius)
    quadraticTo(center.x + waist, center.y - waist, center.x + radius, center.y)
    quadraticTo(center.x + waist, center.y + waist, center.x, center.y + radius)
    quadraticTo(center.x - waist, center.y + waist, center.x - radius, center.y)
    quadraticTo(center.x - waist, center.y - waist, center.x, center.y - radius)
    close()
}

private val CELL = 92.dp

@PreviewLightDark
@Composable
private fun StarryWallpaperPreview() {
    StarmateTheme { StarryWallpaper() }
}
