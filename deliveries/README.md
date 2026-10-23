# XP58 已验收的本机交付包

`TeaPrintHelper-XP58-local-20261021.zip` 保存了已安装、纸面验收通过的
`1.3.2-local-xp58-manual-tear` 应用、ARM64 Java、冻结源码、离线重建工具、
70 项应用测试及 4 项启动器测试、使用和恢复说明。它不是通用安装器，
没有 Developer ID 公证；正式 Gradle 安装器构建未验证。

当前 Mac 的合成图案、业务页面切换桌台、缩短手撕纸底部留白均已验收。
仅指定队列通过 PDF 和已核验的 POS-58 驱动打印；默认 RAW 行为保留。
具体驱动指纹、启用条件和边界见 `../docs/macos-pos58-raster-compatibility.md`。

在此目录校验并解压到新目录：

```sh
shasum -a 256 -c TeaPrintHelper-XP58-local-20261021.zip.sha256
unzip TeaPrintHelper-XP58-local-20261021.zip -d /path/to/new-directory
```

解压不会安装、启动或打印。阅读包内 `交付说明.md`、`恢复说明.md` 后操作。
本机已正常工作的应用无需重新安装。不要恢复旧防重缓存或重放历史订单。

ZIP SHA256：`ae3fa981a2ed370ffeae8c2701b75536f4c7fa77fcdbdfab7eced41004452e58`。
主 JAR SHA256：`e462d27089d4a0b7817b97b7e4c2bb7ed8abd6ebbdda420763730f1cdc3ba405`。
压缩包保持交付时的原始字节，包内“尚未提交”的描述为当时快照；
随本目录提交即完成源码与产物入库。仓库新增的评审文档、测试入口不改变应用字节。

RAW 故障证据支持发送 API 以下的流首 64 字节未被正常解释；精确的
主机/集线器/固件责任仍未确定。64 个 NUL 的独立对照候选未并入本应用。
包内不包含个人配置、凭据、防重缓存或业务订单原始数据。
