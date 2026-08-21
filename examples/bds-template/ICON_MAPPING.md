# 图标图集映射

`res/logo/pop_menu_icons.png/.til` 固定提供 `IMG0` 到 `IMG40`。每格是透明背景的原创几何符号；下列 ABI 名称与已逆向确认的 `MenuFunction.bitmapIndex` 对齐。

| IMG | 含义 |
| ---: | --- |
| 1 | Theme |
| 2 | InputMethod |
| 4 | NightMode |
| 5 | AdjustHeight |
| 6 | Settings |
| 7 | DayMode |
| 12 | Sound |
| 13 | Vibrate |
| 18 | Handwriting |
| 26 | Emoji |
| 29 | Voice |
| 31 | Search |
| 36 | Language |
| 37 | Translate |
| 38 | OCR |

其余 IMG0–40 为编号稳定的通用调试几何图标，名称请以应用源码 `BdsLegacyMenuIcon` 为准；它们并不声明模板新增功能。

`pop_input_icons.png/.til` 使用：`IMG0=拼音`、`IMG1=英文`、`IMG2=手写`、`IMG3=五笔`、`IMG4=笔画`、`IMG5=语音`、`IMG6=展开`、`IMG7=收起`。这些图标资源可由 TIL 单独裁切；输入模式实际切换仍由 Fcitx5 管理。
