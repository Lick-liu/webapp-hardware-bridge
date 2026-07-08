# XPrinter XP-58 系列 Linux/CUPS 驱动快速配置

本文面向在 Linux 上使用 WebApp Hardware Bridge 的小票机用户，帮助快速创建可被 Java 打印服务识别的本地 CUPS 打印队列。

适用范围：

- XPrinter XP-58IIIH、XP-58IIH 等 XP-58 系列 58mm 热敏小票机
- 兼容 ESC/POS 的 ZJ-58、J-speed XP-58 同类 USB 小票机
- CachyOS、Arch Linux 及其衍生发行版

## 推荐方式：一键脚本

在仓库根目录执行：

```bash
scripts/linux/install-xprinter-xp58.sh
```

脚本会自动完成：

- 安装依赖：`cups`、`cups-filters`、`ghostscript`、`git`、`cmake`、`base-devel`
- 启用并重启 CUPS
- 从 `klirichek/zj-58` 构建 `rastertozj` CUPS filter 和 `xp58.ppd`
- 创建名为 `XP58` 的 CUPS 队列
- 将 `XP58` 设置为默认打印机

安装完成后测试：

```bash
printf 'XP-58 test\n\n\n' | lp -d XP58
```

## 常用自定义参数

修改队列名：

```bash
QUEUE_NAME=RECEIPT scripts/linux/install-xprinter-xp58.sh
```

安装后自动打印测试：

```bash
PRINT_TEST=1 scripts/linux/install-xprinter-xp58.sh
```

不设置为默认打印机：

```bash
SET_DEFAULT=0 scripts/linux/install-xprinter-xp58.sh
```

手动指定 USB URI：

```bash
DEVICE_URI='usb://Xprinter/USB%20Printer%20Port?serial=xxxx' scripts/linux/install-xprinter-xp58.sh
```

USB URI 可以用下面的命令查看：

```bash
lpinfo -v
```

## 在 WebApp Hardware Bridge 中配置

脚本成功后，系统里会出现一个名为 `XP58` 的打印机。

在 WebApp Hardware Bridge 的 Web UI 或 HTTP API 中添加打印机映射：

- `Type`：业务侧使用的映射名，例如 `RECEIPT`
- `Printer Name`：`XP58`
- `Force DPI`：可保持 `0` 自动检测；遇到缩放异常时可尝试 `203`
- `Reset imageable area`：建议保持默认开启

热敏小票建议优先使用 RAW/ESC-POS 打印。PDF、PNG、JPG 也可以打印，但会依赖 CUPS、Ghostscript 和驱动转换链路，排障成本更高。

## 常见问题

### `lpadmin：打印机驱动已被弃用`

这是 CUPS 对传统 PPD 驱动模型的未来兼容性警告。当前仍可使用，不代表安装失败。

### `lp` 返回 request id，但打印机没有反应

先看 CUPS 日志：

```bash
tail -200 /var/log/cups/error_log
```

如果看到：

```text
Unable to launch Ghostscript: gs: No such file or directory
```

说明缺少 Ghostscript，安装后重试：

```bash
sudo pacman -S --needed ghostscript
sudo systemctl restart cups
```

### 队列里一直显示正在发送数据

清理失败任务后重试：

```bash
cancel -a XP58
printf 'XP-58 test\n\n\n' | lp -d XP58
```

### 找不到 XPrinter USB 设备

确认打印机已开机、USB 已连接，然后检查：

```bash
lpinfo -v
lsusb
```

正常情况下会看到类似：

```text
direct usb://Xprinter/USB%20Printer%20Port?serial=xxxx
```

如果 `lpinfo -v` 能看到设备，但脚本没有自动识别，可以用 `DEVICE_URI` 手动传入。

### 判断是 CUPS 问题还是硬件连接问题

可以直接写入 USB 打印设备做 RAW 测试：

```bash
printf '\033@DIRECT USB TEST\nXP-58\n\n\n' > /dev/usb/lp0
```

如果直接写入也不出纸，优先检查电源、纸仓盖、纸卷方向、FEED 键是否能走纸，以及 USB 线是否接在打印口。

如果直接写入能出纸，但普通 `lp -d XP58` 不出纸，优先检查 CUPS 日志和 `ghostscript` 依赖。

## 与远程桌面打印映射配合

如果 WebApp Hardware Bridge 运行在本机，而浏览器或业务系统运行在远程桌面环境中，请先确保本机 `XP58` 队列可以正常打印，再配置远程桌面客户端的本地打印机重定向。

远程 Windows 中能看到 `XP58 (redirected ...)` 后，再在 WebApp Hardware Bridge 中映射该打印机名称。
