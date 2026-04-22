# AI Agent Computer Use 技术解析

> 本文探讨了 AI Agent 如何实现"操控电脑"的能力，涵盖技术原理、市场产品对比、Claude Code 实操演示，以及浏览器操作与桌面 GUI 操作的能力边界。

## 一、什么是 Computer Use

Computer Use 是让 AI Agent 像人一样使用电脑的能力 — 看屏幕、点鼠标、敲键盘。

传统 Agent 只能通过 API 与系统交互，遇到没有 API 的场景就无能为力：

| 场景 | 例子 | 为什么没有 API |
|------|------|---------------|
| 老旧企业系统 | 10 年前的 OA/ERP | 开发团队已离职 |
| 桌面软件 | Excel、Photoshop | 为人类设计，非程序接口 |
| 第三方网站 | 竞品网站、政务门户 | 不希望被自动化 |
| 内部工具 | 公司自建工具 | 没预算开发 API |

Computer Use 的本质：**把界面操作从"人类专属"变成"Agent 也能做"**。

## 二、核心技术原理

### 2.1 感知-决策-行动循环

所有 Computer Use Agent 的核心都是一个 **Perceive → Decide → Execute** 闭环：

```
┌─────────────────────────────────────────────┐
│           Computer Use Agent                 │
│                                              │
│   ┌──────────┐   ┌──────────┐   ┌────────┐ │
│   │ 感知     │──→│ 决策     │──→│ 执行   │ │
│   │ Perceive │   │ Decide   │   │ Execute │ │
│   └──────────┘   └──────────┘   └────────┘ │
│        ↑              │              │       │
│        └──────────────┴──────────────┘       │
│                  循环继续                     │
└─────────────────────────────────────────────┘
```

每一步：

1. **截图** → 获取屏幕当前状态（AI 的"眼睛"）
2. **多模态大模型分析** → 理解屏幕内容，决定下一步操作（AI 的"大脑"）
3. **模拟鼠标/键盘** → 执行点击、输入、滚动（AI 的"手"）
4. **再次截图** → 验证操作结果，进入下一轮循环

### 2.2 感知层：AI 如何"看到"屏幕

| 技术 | 作用 | 具体方案 |
|------|------|---------|
| 截图采集 | 获取屏幕快照 | 系统 API 截图 / Playwright 截图 |
| UI 元素检测 | 识别按钮、图标、输入框位置 | YOLO 目标检测模型 |
| OCR 文字识别 | 提取界面上的文字 | PaddleOCR / Tesseract / Google Vision |
| 多模态理解 | 整体理解截图语义和布局 | Claude / Qwen-VL / Gemini 等视觉模型 |

两种路线：

- **纯视觉派**（Claude Computer Use）：只看截图，不读 DOM/Accessibility Tree
- **辅助信息派**（部分方案）：截图 + DOM 结构结合，提高准确率

### 2.3 决策层：AI 如何"想"

把截图发给多模态大模型，输出结构化操作指令：

```json
{
  "observation": "当前屏幕显示搜索页面，有搜索框",
  "reasoning": "需要在搜索框中输入查询内容",
  "action": {
    "type": "click",
    "x": 500,
    "y": 200,
    "text": "搜索内容"
  },
  "confidence": 0.9,
  "needs_confirmation": false
}
```

### 2.4 执行层：AI 如何"动手"

#### 浏览器内操作（Playwright + CDP 协议）

```
Playwright mouse.click(x, y)
    ↓
CDP: Input.dispatchMouseEvent
    → type: "mousePressed", x, y
    → type: "mouseReleased", x, y
    ↓
Chrome 浏览器触发 mousedown → mouseup → click
```

#### 桌面应用操作（Windows SendInput API）

```
程序调用 SendInput()
    ↓
User32.dll（Windows 用户态）
    ↓
ntoskrnl.exe（Windows 内核）
    ↓
向系统输入队列注入合成输入事件
    ↓
窗口管理器确定目标窗口/控件
    ↓
发送 Windows 消息：
    WM_MOUSEMOVE → WM_LBUTTONDOWN → WM_LBUTTONUP
    ↓
应用程序收到消息，执行对应操作
```

一次鼠标点击 = **移动 + 按下 + 释放** 三步：

```python
# pyautogui.click(500, 300) 底层实际做的事

# 1. 移动鼠标
SendInput(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, x=500, y=300)

# 2. 左键按下
SendInput(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_ABSOLUTE, x=500, y=300)

# 3. 左键释放
SendInput(MOUSEEVENTF_LEFTUP | MOUSEEVENTF_ABSOLUTE, x=500, y=300)
```

操作系统提供了 SendInput API，允许程序注入"合成输入事件"，对应用程序来说和真实鼠标点击**完全一样**。

### 2.5 关键技术难点

#### 坐标校准

不同电脑分辨率和 DPI 缩放不同，模型看到的坐标和实际屏幕坐标可能不一致：

```
模型看到的截图：1920×1080
实际屏幕（Retina）：2880×1620（1.5x 缩放）

最佳实践：使用归一化坐标（0~1）
模型输出：x=0.5, y=0.3
执行层转换：x = 0.5 × 2880 = 1440
```

#### 与传统自动化的本质区别

| | 传统自动化（Selenium） | Computer Use |
|---|---|---|
| 定位方式 | CSS/XPath 选择器 | 视觉识别 |
| 界面变了 | 脚本直接挂掉 | 自动适应 |
| 适用范围 | 只能操作 DOM | 任何可见界面 |
| 每步成本 | 几乎免费 | ~$0.01（一次视觉模型调用） |

## 三、市场产品对比

### 3.1 OpenClaw（小龙虾）

2025 年 11 月上线，2026 年初爆火的开源本地 AI 智能体。

**架构**：四层模型

```
┌─────────────────────────────────┐
│  网关层 (Gateway)               │  Node.js + WebSocket
│  统一接收 IM 消息（微信/飞书等） │  消息格式转换 + 安全检查
├─────────────────────────────────┤
│  智能决策层 (AI Brain)          │  多模态大模型
│  理解任务 + 分析截图 + 规划步骤 │  截图→理解→决策
├─────────────────────────────────┤
│  系统连接层 (Body)              │  鼠标/键盘/文件系统 API
│  截图/点击/输入/文件操作        │  模拟人类操作
├─────────────────────────────────┤
│  本地环境 (Local Environment)   │  用户电脑
│  浏览器/桌面应用/终端/文件系统  │
└─────────────────────────────────┘
```

**核心能力**：

| 能力 | 说明 |
|------|------|
| 桌面操控 | 模拟鼠标点击、键盘输入，像人一样操作软件 |
| 本地运行 | 全程离线，Win10 一键部署，零代码门槛 |
| IM 集成 | 通过微信/飞书/Telegram 远程下达指令 |
| 截图能力 | 通过 peekaboo Skill 实现远程截图 |

被评价为"接近下一代 OS 的雏形"。

### 3.2 MiniMax Agent

2026 年 4 月发布重大更新，两项核心功能：

| 功能 | 说明 |
|------|------|
| **Computer Use** | Agent 能看屏幕、操作鼠标键盘，直接操控本地软件（设计工具、报表系统等） |
| **Pocket 功能** | 通过微信/飞书/Slack 远程下达指令，Agent 在电脑上执行后回传结果 |

三项技术突破：

1. **多模态感知**：理解屏幕上的图像、文字、UI 元素
2. **屏幕内容解析**：将视觉信息转化为可操作的结构化数据
3. **鼠标键盘精确控制**：像素级精确定位和操作

### 3.3 Claude Computer Use（Anthropic 官方）

Anthropic 提供的 Computer Use 能力，通过 API 提供，采用纯视觉方案：

- 仅通过截图中的视觉信息感知环境
- 不依赖底层元数据（DOM 树、Accessibility Tree）
- Claude Code 可通过 Playwright MCP 操控浏览器，通过 Python 脚本操控桌面

### 3.4 综合对比

| 产品 | 桌面操控 | 浏览器操控 | 远程控制 | 自动化闭环 | 开源 |
|------|---------|-----------|---------|-----------|------|
| OpenClaw | 内置 | 内置 | 微信/飞书/Telegram | 完整闭环 | 是 |
| MiniMax Agent | 内置 | 内置 | 微信/飞书/Slack | 完整闭环 | 否 |
| Claude Computer Use | 需脚本编排 | Playwright MCP | 无 | 需手动编排 | API |
| Claude Code | pyautogui 脚本 | Playwright MCP | 无 | 需手动编排 | CLI 工具 |

## 四、Claude Code 操控桌面实战

以下为实际操作演示，证明 Claude Code 可以通过 Python 脚本调用 Windows SendInput API 操控桌面。

### 4.1 环境准备

```bash
# 安装 pyautogui（封装了 SendInput API）
pip install pyautogui Pillow
```

### 4.2 演示 1：截屏

```python
import pyautogui

screenshot = pyautogui.screenshot()
screenshot.save('desktop_screenshot.png')
```

### 4.3 演示 2：打开记事本并输入文字

```python
import pyautogui
import subprocess
import time
import pyperclip

# 启动记事本
subprocess.Popen(['notepad.exe'])
time.sleep(2)

# 英文输入
pyautogui.typewrite('Hello from Claude Code!', interval=0.05)
pyautogui.press('enter')

# 中文输入（通过剪贴板）
pyperclip.copy('Claude Code 可以通过 SendInput API 控制你的鼠标和键盘！')
pyautogui.hotkey('ctrl', 'v')
```

### 4.4 演示 3：打开豆包并搜索

```python
import pyautogui
import time
import pyperclip

# 启动豆包
import subprocess
subprocess.Popen([r'D:\soft\Doubao\Doubao.exe'])
time.sleep(5)

# 点击输入框
pyautogui.click(960, 1080)
time.sleep(1)

# 输入搜索内容（中文通过剪贴板）
pyperclip.copy('node的安装方法')
pyautogui.hotkey('ctrl', 'v')
time.sleep(0.5)

# 按回车发送
pyautogui.press('enter')
time.sleep(8)

# 截屏保存结果
screenshot = pyautogui.screenshot()
screenshot.save('doubao_result.png')
```

### 4.5 pyautogui 核心能力一览

| 方法 | 功能 | 底层 API |
|------|------|---------|
| `click(x, y)` | 模拟左键点击 | SendInput (MOUSEINPUT) |
| `rightClick(x, y)` | 模拟右键点击 | SendInput (MOUSEINPUT) |
| `doubleClick(x, y)` | 模拟双击 | SendInput (MOUSEINPUT) |
| `moveTo(x, y)` | 移动鼠标 | SendInput (MOUSEEVENTF_MOVE) |
| `dragTo(x, y)` | 拖拽 | SendInput (MOVE + DOWN + UP) |
| `scroll(clicks)` | 滚轮 | SendInput (MOUSEEVENTF_WHEEL) |
| `typewrite(text)` | 键盘输入英文 | SendInput (KEYBDINPUT) |
| `hotkey('ctrl', 'c')` | 组合键 | SendInput (KEYBDINPUT) |
| `press('enter')` | 单键按下 | SendInput (KEYBDINPUT) |
| `screenshot()` | 截屏 | BitBlt / DXGI Desktop Duplication |

## 五、浏览器操作 vs 桌面操作

### 核心结论：能做，但体验参差不齐

| 维度 | 浏览器操作 | 桌面 GUI 操作 |
|------|-----------|-------------|
| 技术成熟度 | 很成熟 | 可用，但粗糙 |
| 工具链 | Playwright/CDP，精度高 | SendInput/pyautogui，靠坐标盲猜 |
| 核心难点 | DOM 结构清晰，选择器精确 | 截图→识别→定位坐标，容易偏移 |
| 出错率 | 低 | 较高（分辨率、缩放、窗口位置都会影响） |
| 代表产品 | Claude Code、Playwright MCP | OpenClaw、MiniMax Agent |

**根本差距**：

> 浏览器有结构化的 DOM 树，AI 能精确知道"第 3 个按钮在哪"。
> 桌面应用没有统一的结构化接口，AI 只能"看截图 → 猜坐标 → 盲点"。

OpenClaw、MiniMax Agent 的核心价值不是发明了新技术，而是在 **"截图 → 多模态理解 → 坐标定位"** 这个环节上做了大量优化，把盲猜的准确率提上去了。

Claude Code 能用同样的底层 API（SendInput），但没有这个自动化优化闭环，所以手动编排可以做到，自动化体验就差一截。

## 六、安全机制

Computer Use 赋予 Agent 巨大能力的同时，也带来巨大风险：

| 风险类型 | 说明 | 防护措施 |
|---------|------|---------|
| 误操作 | 点击了错误的按钮（如删除全部数据） | 危险操作人工确认 |
| 信息泄露 | 截图中包含敏感信息（密码、个人信息） | 自动屏蔽敏感区域 |
| 注入攻击 | 恶意网页诱导 Agent 执行危险操作 | 沙箱隔离 + 域名白名单 |
| 无限循环 | Agent 陷入无限重试 | 最大迭代次数限制 |
| 未授权访问 | 访问未经授权的系统 | 域名白名单 + 权限控制 |

常见的安全设计：

- **危险操作确认**：删除/支付/发送等操作需人工确认
- **沙箱隔离**：文件系统、网络访问受限
- **最大循环次数**：防止无限重试消耗资源
- **敏感信息遮蔽**：自动屏蔽截图中的密码字段

## 七、参考资料

- [Chapter 28: Computer Use | AI Agent Architecture](https://www.waylandz.com/ai-agent-book-en/chapter-28-computer-use/)
- [Anthropic - Developing a computer use model](https://www.anthropic.com/news/developing-computer-use)
- [Claude 技术指南 - Computer Use 工作原理](https://yeasy.gitbook.io/claude_guide/di-er-bu-fen-gong-ju-pian/05_computer_use/5.2_loop)
- [OpenClaw 架构解析 - 知乎](https://zhuanlan.zhihu.com/p/2013896374823973270)
- [MiniMax Agent 桌面端更新 - 新浪财经](https://finance.sina.com.cn/tech/roll/2026-04-14/doc-inhunicx9021727.shtml)
- [MOUSEINPUT 结构体 - Microsoft Learn](https://learn.microsoft.com/zh-cn/windows/win32/api/winuser/ns-winuser-mouseinput)
- [PyAutoGUI 原理解析 - 博客园](https://www.cnblogs.com/tlnshuju/p/19495845)
- [OpenAI - Computer-Using Agent](https://openai.com/index/computer-using-agent/)
- [Computer Use Agent - Microsoft Data Science](https://medium.com/data-science-at-microsoft/where-ai-meets-gui-an-overview-of-computer-using-agents-3085d3bbe332)
