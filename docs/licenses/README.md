# 随 App 打包的第三方字体

星伴在 `core/ui/src/main/res/font/` 里打包了两个字体，都以 SIL Open Font License 1.1 授权，可以免费商用和再分发。

| 文件 | 字体 | 用在哪里 | 来源 | 许可全文 |
|---|---|---|---|---|
| `zcool_kuaile.ttf` | 站酷快乐体 ZCOOL KuaiLe | 标题、按钮、她的名字、分组小标题 | <https://github.com/google/fonts/tree/main/ofl/zcoolkuaile> | [ZCOOLKuaiLe-OFL.txt](ZCOOLKuaiLe-OFL.txt) |
| `chill_round_m.otf` | 寒蝉半圆体 ChillRoundM v1.805 | 聊天气泡、说明文字、列表 | <https://github.com/Warren2060/ChillRound> | [ChillRoundM-OFL.txt](ChillRoundM-OFL.txt) |

两个文件都是原样打包，只改了文件名（Android 资源文件名只能用小写字母、数字和下划线），没有改动字体内容。

寒蝉半圆体声明了保留字体名（Reserved Font Name）“ChillRoundM”。如果以后要裁剪字符集来减小体积，裁剪后的字体属于修改版，必须换一个名字，不能再叫 ChillRoundM。
