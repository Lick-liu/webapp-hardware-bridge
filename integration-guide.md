# WebApp Hardware Bridge 集成指南

本文档介绍如何在 Web 项目中集成 WebApp Hardware Bridge，以实现静默打印和串口通信功能。

## 1. 工作原理

WebApp Hardware Bridge 是一个本地运行的中间件（Java 程序），它在本地暴露 WebSocket 服务（默认端口 `12212`）。Web 前端通过 WebSocket 与其通信，间接控制本地硬件。

`Browser` <== WebSocket ==> `Bridge (Java)` <== USB/Serial ==> `Printer/Device`

## 2. 环境准备

1.  **安装并启动 Bridge**: 确保目标电脑上运行了 `WebApp Hardware Bridge` 应用。
2.  **配置映射**:
    *   访问 [http://127.0.0.1:12212](http://127.0.0.1:12212) 打开 Web UI。
    *   **Printers**: 将业务类型（如 `INVOICE`, `RECEIPT`）映射到物理打印机。
    *   **Serials**: 将业务类型（如 `SCALE`）映射到物理串口（COM 口）。

## 3. 前端集成

### 3.1 建立连接

使用原生 WebSocket 连接到 Bridge 服务。

```javascript
const WS_URL = "ws://127.0.0.1:12212/printer"; // 打印服务
// const WS_URL = "ws://127.0.0.1:12212/serial";  // 串口服务

let ws = new WebSocket(WS_URL);

ws.onopen = () => {
    console.log("已连接到硬件桥接服务");
};

ws.onclose = () => {
    console.log("连接断开，建议实现自动重连逻辑");
};

ws.onerror = (err) => {
    console.error("连接错误", err);
};
```

### 3.2 发送打印任务

协议格式为 JSON。

#### 场景 A: 打印 PDF 或 图片 (URL)

适用于发票、运单等标准格式文档。

```javascript
const job = {
    'type': 'INVOICE',  // 对应 Web UI 中配置的 Type
    'url': 'https://example.com/files/invoice_123.pdf' // 必须是 Bridge 可访问的 URL
};

ws.send(JSON.stringify(job));
```

#### 场景 B: 打印热敏小票 (ESC/POS)

适用于收银小票、排队号票等动态文本内容。需要发送 Base64 编码的二进制原始指令。

**推荐使用 helper 类构造指令 (见附录):**

```javascript
// 使用 EscPosEncoder 辅助类
const encoder = new EscPosEncoder();
const base64Data = encoder
    .initialize()
    .text("Hello World!")
    .newline()
    .cut()
    .toBase64();

const job = {
    'type': 'RECEIPT', // 对应 Web UI 中配置的 Type
    'raw_content': base64Data
};

ws.send(JSON.stringify(job));
```

## 4. 附录：ESC/POS 辅助类

你可以将此代码复制到你的项目中保存为 `escpos-encoder.js`。

```javascript
class EscPosEncoder {
    constructor() {
        this.buffer = [];
    }

    /**
     * 初始化打印机
     */
    initialize() {
        this.buffer.push(0x1B, 0x40);
        return this;
    }

    /**
     * 添加文本 (目前仅支持 ASCII/UTF-8)
     * @param {string} content 
     */
    text(content) {
        // 简单实现，复杂字符集可能需要专门的编码库
        for (let i = 0; i < content.length; i++) {
            this.buffer.push(content.charCodeAt(i));
        }
        return this;
    }

    /**
     * 换行
     */
    newline() {
        this.buffer.push(0x0A);
        return this;
    }

    /**
     * 切纸
     */
    cut() {
        this.buffer.push(0x1D, 0x56, 0x42, 0x00);
        return this;
    }

    /**
     * 转换为 Base64 字符串
     */
    toBase64() {
        const binaryString = String.fromCharCode.apply(null, this.buffer);
        return window.btoa(binaryString);
    }
}
```

## 5. 常见问题

1.  **连接失败/连不上**:
    *   检查 Bridge 应用是否运行。
    *   检查端口 12212 是否被防火墙拦截。
    *   如果在 HTTPS 网站中使用，需要处理 Mixed Content 问题（浏览器禁止 HTTPS 页面连接 ws://），建议配置 Bridge 使用 WSS (见 `ADVANCED.md`)。

2.  **打印无反应**:
    *   检查 Web UI 中 `Type` 是否拼写一致。
    *   检查 Web UI 中映射的物理打印机是否处于就绪状态。
