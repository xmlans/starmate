# 网页字体的授权

介绍页用了 App 里打包的两款字体，都只保留了页面上出现的字符，以减小体积。两款字体都以 SIL Open Font License 1.1 授权。

| 文件 | 来源字体 | 改动 | 许可全文 |
|---|---|---|---|
| `kuaile.woff2` | 站酷快乐体 ZCOOL KuaiLe，© 2018 The ZCOOL KuaiLe Project Authors | 裁剪字符集，转为 WOFF2 | [ZCOOLKuaiLe-OFL.txt](ZCOOLKuaiLe-OFL.txt) |
| `round.woff2` | 寒蝉半圆体 ChillRoundM，© 2023 ChillType；基于 Zen Maru Gothic，© 2021 The Zen Maru Gothic Authors | 裁剪字符集，转为 WOFF2，改名为 Starmate Round | [ChillRoundM-OFL.txt](ChillRoundM-OFL.txt) |

寒蝉半圆体声明了保留字体名“ChillRoundF”和“ChillRoundM”。裁剪后的字体属于修改版，按许可要求换了名字，字体文件里的版权声明保持原样。

这两个文件由 `web/tools/build_assets.py` 生成，改了页面文字后重新运行一次即可。
