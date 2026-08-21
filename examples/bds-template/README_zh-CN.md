# BDS 皮肤模板

这里有两套由 `generator/build_templates.py` 确定性生成的原创 BDS 模板：`minimal/` 是可读性优先的最小竖屏皮肤；`showcase/` 演示横竖屏、四种布局、候选栏、多分辨率和安全按压动画。所有 PNG 都由标准库绘制的青绿、粉色、深灰几何图案构成；没有角色、商标、第三方字体或从其他皮肤提取的资源。

```sh
python3 examples/bds-template/generator/build_templates.py --clean
python3 examples/bds-template/generator/validate_templates.py
```

输出为 `build/bds-templates/bds-minimal-template.bds` 和 `build/bds-templates/bds-showcase-template.bds`。`.bds` 是 ZIP，文件直接在压缩包根目录，ZIP 时间戳固定，路径恒为 `/`，因此重复构建的内容结构一致。

## 修改方法

- 名称和作者：修改生成器中 `build_skin` 的参数和 `Info.txt` 模板字符串。
- 键盘背景：修改 `make_atlases()` 的 `panel` 图集；`STYLE1` 引用 `panel,1`。
- 按键颜色：修改 `key()` 的颜色，或 `css()` 中 `STYLE2/STYLE3` 的图集引用。
- 工具栏/输入模式图标：修改 `icon()`，图标序号由列表的下标和自动写出的 TIL 同时决定，不能只改单一文件。
- TIL 坐标：不要手写。`tile_sheet()` 根据图集拼接位置生成 `SOURCE_RECT` 和 `INNER_RECT`。
- 新布局：在 `build_skin()` 的 `names` 增加布局名，并在 `keys_ini()` 加布局的行/动作定义；随后重新构建和验证。

## App 导入测试

在应用的主题设置中导入两个 `build/bds-templates/*.bds` 文件。最小模板故意没有 `land/`：当前 resolver 已实现安全回退到 `port/`，方便验证缺横屏资源的行为。展示模板则包含原生横屏资源。连接设备后可执行：

```sh
./tools/bds-visual-test.sh --no-compare --name bds-template-showcase
```

该脚本需要已连接的 Android 设备和可安装的 debug APK；它的截图输出位于 `local-testdata/visual-golden/bds-template-showcase/`。

## 当前支持与回退

当前解析器直接使用 `Info.txt`、`gen.ini` 的 `PANEL.SIZE/BACK_STYLE`、布局的 `KEY*` 几何和 `CENTER/UP/LEFT/RIGHT/HOLD` 动作、CSS 的颜色/图集/文字、PNG+TIL、`cand1.cnd` 基本候选栏字段、以及 `anim.ini` 的基础动画。`F1/F4/F6/F10/F15/F36/F38/F39` 分别覆盖符号、文本、数字、Shift、语言、退格、空格、回车。

`MORE`、`HINT`、`LIST`、`OFFSET`、`STAT_STYLE`、`SOUND_STYLE` 及大多数真实百度动画字段会被保留以兼容真实包，但模板不依赖它们。未知 BDS 功能及没有对应 Fcitx 动作的 `F<number>` 会回退到 Fcitx5 默认实现；候选栏的展开按钮和工具栏交互也保留 Fcitx 的无障碍和行为实现。
