# BDS 格式实测笔记

本模板的字段来自当前 `BdsParser`、`BdsTilParser`、`BdsCandidateParser`、单元测试和仓库中的逆向样本，而不是对百度格式的猜测。

| 文件 | 当前解析器读取的关键内容 |
| --- | --- |
| `Info.txt` | UTF-8（可含 BOM）的 `Name`、`Author`、`Description`、`VersionCode` |
| `port/`、`land/` | 每个存在的方向必须有 `gen.ini` 和 `res/default.css` |
| `gen.ini` | `PANEL.SIZE`，可选 `BACK_STYLE`，候选栏 `CAND.LAYOUT_NAME/VIEW_RECT` |
| `*.ini` | `[PANEL]` 和 `[KEYn]`；`VIEW_RECT`、可选 `TOUCH_RECT`、动作、前/背景样式 |
| `default.css` | `[STYLEn]` 中 `NM_IMG/HL_IMG`、`NM_COLOR/HL_COLOR`、`SHOW`、字号/字重 |
| `*.png` + `*.til` | `GLOBAL.TILE_NUM`、`IMGn.SOURCE_RECT`、可选 `INNER_RECT` |
| `cand1.cnd` | 候选背景/文字/单元样式、内边距、`CELL_W` |

图集引用形如 `NM_IMG=keys,1`，即同一资源目录或根 `res/` 的 `keys.png`/`keys.til` 中 `IMG1`。数字资源桶（如 `480/res/`）由当前视口宽度选取最接近项。

实际样本还出现 `Abilities`、`SkinFlags`、`NO_BLUR`、`TIP*`、`MORE`、`LIST`、`OFFSET*`、`POS_TYPE`、`STAT_STYLE`、`SOUND_STYLE`、粒子/复合动画等。解析器对其中部分作无损保留或有限解析；它们不构成这两个模板的运行条件。
