# AgentScope Java Harness 文件系统架构分析：三种模式、组合机制与记忆共享

> 分析日期：2026-06-09
>
> 分析对象：`agentscope-harness` 模块文件系统子系统（`io.agentscope.harness.agent.filesystem`）
>
> 对标文档：[https://java.agentscope.io/v2/zh/docs/harness/filesystem.html](https://java.agentscope.io/v2/zh/docs/harness/filesystem.html)
>
> 配套实例：`agentscope-examples/agents/agentscope-builder`

---

## 一、设计目标：把"工作区访问"从本机磁盘抽象成统一接口

`HarnessAgent` 把 agent 对工作区的访问抽象成统一接口。所有文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和可选的 `execute`（shell）都从这层走。**切换三种部署模式不改 agent 代码**——这是文档反复强调的卖点。

代码落地的统一契约是 `AbstractFilesystem`（`filesystem/AbstractFilesystem.java:44`），它定义 11 个操作：

```
ls / read / write / edit / grep / glob / uploadFiles / downloadFiles / delete / move / exists
```

两个关键设计点：

1. **每个操作都带 `RuntimeContext`**（如 `AbstractFilesystem.java:49`）—— 这是多租户隔离的"钥匙"，后端靠它把工作分桶到当前 session/user/sandbox。
2. **路径安全**：`validatePath()`（`AbstractFilesystem.java:178`）统一拦截 `..` 路径穿越。

> ⚠️ 接口里**没有** `execute`。shell 执行能力被刻意拆到子接口 `AbstractSandboxFilesystem`（`sandbox/AbstractSandboxFilesystem.java:27`），它在 `AbstractFilesystem` 之上只加了 `id()` 和 `execute()`。所以"某套文件系统是否提供 shell"在运行时就是一个 `instanceof AbstractSandboxFilesystem` 判断——`ReActAgent` / `HarnessAgent` 就是靠这个判断要不要挂 `execute` 工具。这是理解三种模式"有没有 shell"的根因。

---

## 二、类层次与目录结构

```
filesystem/
├── AbstractFilesystem              （统一契约：11 个文件操作）
├── OverlayFilesystem               （上层/下层叠加，COW 写时复制）
├── CompositeFilesystem             （按路径前缀路由到不同后端）
├── BakedContextFilesystem          （固定 RuntimeContext 的包装器，多租户用）
├── local/
│   ├── LocalFilesystem             （纯本机磁盘，无 shell）
│   └── LocalFilesystemWithShell    （本机磁盘 + sh -c，实现 AbstractSandboxFilesystem）
├── remote/
│   └── RemoteFilesystem            （KV 存储 BaseStore 后端，跨副本共享）
├── sandbox/
│   ├── AbstractSandboxFilesystem   （接口：加 execute + id）
│   ├── BaseSandboxFilesystem       （抽象基类：所有文件操作都用 shell 命令实现）
│   └── SandboxBackedFilesystem     （Docker/K8s 等共用的运行时实现，volatile Sandbox 注入）
├── spec/                           （三种"声明式模式"的配置规格）
│   ├── LocalFilesystemSpec         （模式 3）
│   ├── RemoteFilesystemSpec        （模式 1）
│   ├── SandboxFilesystemSpec       （模式 2 抽象基类）
│   └── DockerFilesystemSpec        （模式 2 的 Docker 后端）
└── model/                          （ReadResult / WriteResult / GrepResult / FileInfo ...）
```

**spec 包与运行时实现分离**：`spec/*` 只是构建期描述（文档说的"声明式"）。`SandboxFilesystemSpec` 的 Javadoc 自己强调 "this type is not a runtime filesystem implementation. It only describes how to create..."（`spec/SandboxFilesystemSpec.java:37-39`）。每个 spec 有个 `toFilesystem(...)` 方法把配置编译成真正的 `AbstractFilesystem` 实例。

---

## 三、三种声明式模式

`HarnessAgent.Builder` 用重载的 `filesystem(...)` 三选一（`HarnessAgent.java:1231-1246`），构建期校验**互斥**（`HarnessAgent.java:1525-1544`）：

- 三种 spec 最多配一个；
- `abstractFilesystem(...)` 是逃生口，与任何 `filesystem(...)` spec 互斥；
- `sandboxDistributed(...)` 必须配 sandbox 模式。

### 模式 3 · 本机 + shell（默认）

什么都不写就落到 `${user.dir}/.agentscope/workspace/`（`HarnessAgent.java:1546-1550`）。

关键细节 —— **本机模式本身就是一个 Overlay**（`spec/LocalFilesystemSpec.java:237-258`）：

```java
LocalFilesystemWithShell upper = new LocalFilesystemWithShell(workspace, mode, pathPolicy, ...);
LocalFilesystem lower = new LocalFilesystem(effectiveProject, true /*只读*/, 10, null);
return OverlayFilesystem.of(upper, lower);
```

- **upper** = `LocalFilesystemWithShell`，根 = workspace，默认 `LocalFsMode.ROOTED`（绝对路径只有在 project/workspace/additionalRoots 范围内才放行，类似 Claude Code 的 `--add-dir`）；
- **lower** = 只读的 `LocalFilesystem`，根 = project（默认 `user.dir`）；
- **shell 的 `pwd` = project**（`shellCwd = effectiveProject`，`LocalFilesystemWithShell.java:257`），所以 `execute()` 输出符合用户对"在项目根目录跑命令"的直觉，而文件读写根在 workspace。

这就是文档说的"工作区两层读取"在本地模式的实现：写永远落 workspace（upper），读先 workspace 后 project（lower）。

### 模式 1 · 共享存储（无 shell）

`RemoteFilesystemSpec`（`spec/RemoteFilesystemSpec.java`），`toFilesystem`（`:160`）产出一个 **`CompositeFilesystem`**：

- **default backend** = 纯 `LocalFilesystem`（**无 shell**，故意的，`spec/RemoteFilesystemSpec.java:163`）；
- 每个共享路由都是一个 **`OverlayFilesystem`**：upper = `RemoteFilesystem`（KV 存储 `BaseStore`，按路由分段的 namespace），lower = 只读 `LocalFilesystem`（workspace 下的模板目录）。

默认共享路由（`spec/RemoteFilesystemSpec.java:171-195`）：

| 路由 | namespace 段 | 共享的是什么 |
|------|-------------|------------|
| `AGENTS.md` / `MEMORY.md` / `tools.json`（精确文件） | `root` | 长期记忆主文件、agent 指令 |
| `memory/` | `memory` | 记忆条目目录 |
| `skills/` | `skills` | 技能 |
| `subagents/` | `subagents` | 子代理 |
| `knowledge/` | `knowledge` | 知识库 |
| `agents/<id>/sessions/` | `sessions` | 会话日志 |
| `agents/<id>/tasks/` | `tasks` | 子任务记录 |

每段独立 namespace 防止跨路由的 key 冲突。**这种模式刻意不提供 shell**——要 shell 就用模式 2 或 3。

> 🔎 **一个值得注意的文档/代码不一致**：文档的 IsolationScope 表把 `SESSION` 标为"(默认)"，但 `RemoteFilesystemSpec` 的字段默认值其实是 **`USER`**（`spec/RemoteFilesystemSpec.java:79`，字段 Javadoc 也写"USER (default)"）；而 `IsolationScope` 枚举的 Javadoc（`IsolationScope.java:56`）又说沙箱场景 SESSION 才是默认。即：**沙箱默认 SESSION，共享存储默认 USER**——两套后端默认值不同，文档表格把它们混为一谈了。实战建议显式传 `isolationScope(...)`，别依赖默认值。

构建期硬约束：Remote 模式要求 Session 后端必须是分布式的（如 `RedisSession`），否则报错（`HarnessAgent.java:1571-1577`）——因为它的设计前提就是多副本。

### 模式 2 · 沙箱（有 shell，隔离执行）

`SandboxFilesystemSpec` 是抽象基类，`DockerFilesystemSpec` / K8s / Daytona / E2B / AgentRun 都是子类。它 `toSandboxContext(...)` 产出 `SandboxContext`，**不直接产出 AbstractFilesystem**。

真正的运行时文件系统在 `HarnessAgent.build()` 里手工拼装（`HarnessAgent.java:1588-1626`）：

1. `new SandboxBackedFilesystem()` —— 一个**稳定代理**，持有 `volatile Sandbox sandbox`（`sandbox/SandboxBackedFilesystem.java:47`）；
2. `SandboxLifecycleMiddleware` 负责在**每次 call** 时把一个新鲜的 `Sandbox` 注入进去（setSandbox）；
3. `SandboxManager` + `SessionSandboxStateStore` 持久化沙箱元数据（`_sandbox.json`），下次 call 跨调用恢复同一份工作区（连 `node_modules` / `pip install` 都能恢复）；
4. **工作区投影**（`spec/SandboxFilesystemSpec.java:147-161`）：默认把宿主 workspace 下的 `AGENTS.md`、`skills`、`subagents`、`knowledge`、`.skills-cache` 投影进沙箱。

可选 `snapshotSpec(...)` 做快照；可选 `executionGuard(...)` 在 `AGENT`/`GLOBAL` scope 下串行化并发执行（`spec/SandboxFilesystemSpec.java:96-115`），避免多调用方在同一持久状态上竞争。

---

## 四、两个核心组合机制

### 4.1 `OverlayFilesystem` —— 写时复制（COW）

`filesystem/OverlayFilesystem.java`。语义（`:44-54`）：

| 操作 | 语义 |
|------|------|
| `ls` | 两层并集，upper 路径冲突时优先 |
| `read` | upper 先，没有退 lower |
| `write`/`edit` | **永远 upper**（edit 一个只在 lower 的文件 = 先读 lower → 写 upper → edit upper，`:184-191`） |
| `delete` | 只删 upper；lower 共享文件删不掉（报错） |
| `exists` | 任一层有即 true |

精妙之处在 `of(...)` 工厂（`:104-109`）：如果 upper 是 `AbstractSandboxFilesystem`（即带 shell），会返回一个内部 `ShellAwareOverlay`，把 `execute()` / `id()` 委托给 upper。**这就是为什么模式 3 套了 Overlay 之后 shell 能力还在**——不靠这个，`instanceof` 判断会失败，`execute` 工具就挂不上了。

### 4.2 `CompositeFilesystem` —— 路径前缀路由

`filesystem/CompositeFilesystem.java`。按前缀（最长优先）路由到不同后端，未命中走 default。带两个能耐：

- **跨后端 move**：`move` 发现 src 和 dst 在不同后端时，降级为 read → write → delete（`:483-499`）；
- **路径重映射**：`remapFileInfo` / `remapGrepMatch`（`:155-162`）在后端相对路径和外部可见路径之间翻译。

对 `ls("/")` 和 `glob(..., "/")` 它会**合并** default 后端结果 + 所有路由入口（`:191-210`、`:363-384`），让根目录看起来像一个统一的树。

> ⚠️ `CompositeFilesystem` **刻意只实现 `AbstractFilesystem`，不实现 `AbstractSandboxFilesystem`**（`CompositeFilesystem.java:46-51`）——因为"跨后端路由 shell 命令语义模糊"。这直接决定了模式 1 没有 shell（见第八节）。

---

## 五、沙箱文件系统的"内核"：一切皆 shell 命令

`BaseSandboxFilesystem`（`sandbox/BaseSandboxFilesystem.java`）把 `AbstractFilesystem` 的**全部 11 个方法都用 `execute` 派生出来**：

| 文件操作 | 实现方式 |
|---------|---------|
| `ls` | `for f in path/*; do [ -d ]... [ -f ]... done`（`:73-97`） |
| `read` | 文本用 `sed -n 'start,endp'`，二进制用 `base64`（`:100-146`） |
| `write` | 先 `test -e` 查存在性（保证 create-if-absent 语义），再 `uploadFiles`（`:149-186`） |
| `edit` | 生成一段 python3 脚本，base64 编码后通过 stdin 传入（`:189-273`） |
| `grep` | `grep -rHnF --include=...`（`:276-317`） |
| `glob` | `find path -type f -name pattern`（`:320-342`） |
| `delete` | `rm -rf`（`:345-354`） |
| `move` | `mkdir -p $(dirname) && mv`（`:357-369`） |
| `exists` | `test -e && echo yes`（`:372-380`） |

所以**一个新沙箱后端只要实现 4 个抽象方法**：`execute`、`uploadFiles`、`downloadFiles`、`id()`（`:58-70`），文件操作全套白送。Docker / K8s / E2B / Daytona / AgentRun 这些后端的差异全收敛到"怎么执行一条命令 + 怎么传字节"。

`SandboxBackedFilesystem`（`sandbox/SandboxBackedFilesystem.java`）就是这 4 个方法的具体实现：`upload` = `printf '%s' <base64> | base64 -d > path`（`:104-112`），`download` = `base64 path`（`:144`）。`execute` 直接转调注入的 `active.exec(...)`（`:69-89`），并把超时/异常统一翻译成 `ExecuteResponse`。

---

## 六、多租户隔离：`IsolationScope` + `RuntimeContext.userId`

`RuntimeContext.userId` 是切多用户的钥匙。代码里两条线：

**① `IsolationScope` 决定 namespace 形状**（共享存储 `spec/RemoteFilesystemSpec.java:248-266`；沙箱同理）：

| Scope | 共享存储 namespace | 含义 |
|-------|------------------|------|
| `SESSION` | `agents/<id>/sessions/<sid>` | 每 session 独立 |
| `USER` | `agents/<id>/users/<uid>` | 同 user 跨 session 共享 |
| `AGENT` | `agents/<id>/shared` | 同 agent 全用户共享 |
| `GLOBAL` | `global` | 全局一份（慎用） |

`userId` 缺省时退化为 `anonymousUserId`（默认 `_default`）。

**② `BakedContextFilesystem` 固定身份**（`filesystem/BakedContextFilesystem.java`）。这是个纯委托包装，但**忽略调用方传入的 RC，强行替换成构造时 baked 的 RC**（`:51-54` 等）。`HarnessAgent.workspaceFor(userId, sessionId)` 用它构造"代他人操作"的视图：外部 Controller 可能只传 `RuntimeContext.empty()`，但底层 namespace factory 必须拿到真实身份才能路由到对的桶，这个包装就负责保证这一点。

**userId 在三种模式下的落地**：

- **本机**：通过 `NamespaceFactory` 把 userId 拼成子目录 `workspace/<uid>/...`（`HarnessAgent.java:1560-1564`），shell cwd 也会跟着带 namespace 前缀（`LocalFilesystemWithShell.java:419-428`）；
- **共享存储**：作为 KV namespace 前缀，分布式副本天然共享；
- **沙箱**：作为沙箱状态的 slot key（配 `IsolationScope.USER`）。

---

## 七、并发与一致性（分布式场景）

`RemoteFilesystem` 面向多副本，在并发上做了正经工程化设计（`filesystem/remote/RemoteFilesystem.java`）：

- **write 是 CAS create-if-absent**：`store.putIfVersion(ns, key, value, 0L)`，已存在就报错（`:261-274`）——和 `write_file` 工具"已存在则报错"的语义对齐；
- **edit 是有界 CAS 重试**：`EDIT_MAX_RETRIES = 5`，每次重读当前 version，版本不匹配就重试，超过次数抛冲突错误（`:290-326`）；
- **uploadFiles 是 last-write-wins**（`:507`）——它是 snapshot-push API（会话镜像、审计日志轮转），需要 create-if-absent 语义的应该走 `write`；
- **WorkspaceIndex 加速**（`:132-135`）：`ls`/`glob`/`exists`/`grep` 先查本地索引，索引缺失再**全量扫描 store 兜底**（`searchAllItems`，`:622-639`，分页 100/页），保证"别的节点刚写、本节点索引还没更新"时不会漏结果。`HarnessAgent.build` 在 remote 模式下会 `WorkspaceIndex.open(workspace)`（`:1578-1579`）。

---

## 八、记忆共享：为什么是模式 1

**跨副本 / 跨会话共享长期记忆 → 模式 1（`RemoteFilesystemSpec`）**。这是三种模式里唯一为"记忆共享"量身定做的——文档原话："多副本要共享 `MEMORY.md` / 会话日志 / 子任务到 KV"。

`RemoteFilesystemSpec.toFilesystem`（`spec/RemoteFilesystemSpec.java:171-195`）把所有**"记忆类"路径**自动路由到共享 KV 存储：

| 路由 | namespace 段 | 共享的是什么 |
|------|-------------|------------|
| `MEMORY.md` / `AGENTS.md` / `tools.json` | `root` | 长期记忆主文件、agent 指令 |
| `memory/` | `memory` | 记忆条目目录 |
| `agents/<id>/sessions/` | `sessions` | 会话日志 |
| `agents/<id>/tasks/` | `tasks` | 子任务记录 |
| `skills/` `subagents/` `knowledge/` | 同名 | 技能 / 子代理 / 知识库 |

这些路径的 upper 层都是 `RemoteFilesystem`（KV 存储），lower 层是只读本地模板。所以**写永远落进 KV，多副本天然读同一份**。模式 3（本机）记忆在本地磁盘、模式 2（沙箱）记忆在容器里，都做不到跨副本共享。

### 8.1 最小可用配置

```java
// 1. 一个分布式 KV 存储（Redis / JDBC / 自定义 BaseStore）
BaseStore store = new RedisStore(...);

HarnessAgent agent = HarnessAgent.builder()
    .name("shared-memory-agent")
    .model(model)
    .workspace(workspace)
    .session(redisSession)   // ⚠️ 必须是分布式 Session
    .filesystem(new RemoteFilesystemSpec(store)
        .isolationScope(IsolationScope.USER))   // 跨 session 共享
    .build();
```

每次 call 时把 `userId` 放进 `RuntimeContext`，框架自动把 `MEMORY.md`、`memory/`、会话日志路由到 `agents/<id>/users/<userId>/...` 这个 KV 命名空间。

### 8.2 共享粒度速查

| Scope | 共享范围 | 适用 |
|-------|---------|------|
| `USER`（代码默认） | 同一用户跨 session/设备 | 个人助手多端共享长期记忆 |
| `AGENT` | 该 agent 全用户 | 公共知识库型 agent |
| `GLOBAL` | 全局一份 | 慎用 |
| `SESSION` | 每 session 独立（**不共享**） | 反而用来隔离 |

---

## 九、实例分析：agentscope-builder 用了哪种模式

**结论：模式 1（`RemoteFilesystemSpec` / 共享存储 Composite 模式）。**

铁证在 `BuilderConfig.java:219-222`：

```java
b.filesystem(
        new RemoteFilesystemSpec(baseStore)
                .isolationScope(IsolationScope.USER)   // 按用户共享记忆
                .addSharedPrefix("activity/"));        // 额外路由：审计日志也进共享存储
```

配套细节：

- **`BaseStore` 后端**：`BuilderConfig.java:154-156` 默认用一个建在 Spring `DataSource` 上的 `JdbcStore`（`application.yml` 默认 H2 文件库 `~/.agentscope-builder/db.*`）。生产可换成 Redis/MySQL bean。
- **Session**：`BuilderConfig.java:199-210` 注释明确指出 `RemoteFilesystemSpec` 要求分布式 Session（否则 harness 在 `HarnessAgent.java:1571-1577` 直接报错）。builder 的兜底是 `InMemorySession`——注意它能过校验，正是因为校验只拦 `WorkspaceSession`，而 `InMemorySession` 不是 `WorkspaceSession`（`BuilderConfig.java:204`）。生产多副本要换成 `RedisSession`/`MysqlSession`。
- **没有 shell**：builder 是对话/搭建类应用，不是 coding agent，模式 1 本来就不带 shell。

`application.yml:80-90` 注释也写得很直白："Builder always runs every HarnessAgent on a CompositeFilesystem ... per-(owner, agent) RemoteFilesystem routes for memory/, MEMORY.md, sessions/, tasks/, skills/, subagents/"。

---

## 十、模式 1 如何执行 shell 命令

### 10.1 为什么默认没有 shell

shell 能力的唯一入口是 `AbstractSandboxFilesystem` 接口（`sandbox/AbstractSandboxFilesystem.java:27`，只多了 `execute()` + `id()`）。框架注册 shell 工具的判定在 `HarnessAgent.java:1715-1716`：

```java
if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
    agentToolkit.registerTool(new ShellExecuteTool(sandbox));
}
```

而 `RemoteFilesystemSpec.toFilesystem(...)` 产出的是 `CompositeFilesystem`，它**刻意不实现 `AbstractSandboxFilesystem`**（`CompositeFilesystem.java:46-51`）——因为"跨后端路由 shell 命令语义模糊"。所以 `instanceof` 判定失败，`ShellExecuteTool`（`tool/ShellExecuteTool.java:27`）不会注册，`execute` 工具就不存在。文档那句"要 shell 请用模式 2 或 3"就是这么来的。

### 10.2 三种让它在模式 1 下也能跑 shell 的办法

**办法 1：手动注册一个独立的 shell 工具（最快）**

文件系统保持 `RemoteFilesystemSpec`（共享记忆不动），自己往 toolkit 里挂一个 shell 工具。`ShellExecuteTool` 的构造器接收任意 `AbstractSandboxFilesystem`（`tool/ShellExecuteTool.java:31`），可直接拿一个 `LocalFilesystemWithShell` 喂给它：

```java
b.filesystem(new RemoteFilesystemSpec(baseStore).isolationScope(IsolationScope.USER));
// 共享记忆照常路由到 BaseStore；shell 单独挂一个跑在宿主上的后端
toolkit.registerTool(new ShellExecuteTool(new LocalFilesystemWithShell(workspace)));
```

> ⚠️ 代价：shell 跑在**宿主机**（或你给的 sandbox backend），它操作的磁盘和共享存储是**两套**——shell 看不到/写不进 KV 里的 `MEMORY.md`、`memory/`。适合"记忆要共享、shell 只是临时辅助"的场景。要隔离就把 `LocalFilesystemWithShell` 换成 `SandboxBackedFilesystem`+沙箱 spec。

**办法 2：逃生口 `abstractFilesystem()` —— 真正的"共享记忆 + shell"二合一**

如果你要 shell 和共享记忆**同一套视图**，用 `HarnessAgent.java:1225` 的逃生口，自己拼一个**既实现 `AbstractSandboxFilesystem`、内部又用 `CompositeFilesystem` 路由记忆**的文件系统：

```java
// 1. 先用 RemoteFilesystemSpec 把"记忆路由"结构搭出来
CompositeFilesystem composite = (CompositeFilesystem) new RemoteFilesystemSpec(baseStore)
        .isolationScope(IsolationScope.USER)
        .toFilesystem(workspace, agentId, nsFactory);

// 2. 一个带 shell 的后端（本机或沙箱）
LocalFilesystemWithShell shell = new LocalFilesystemWithShell(workspace);

// 3. 包装层：文件操作走 composite，execute()/id() 走 shell
AbstractFilesystem combined = new SandboxShellOverComposite(composite, shell);

HarnessAgent.builder()
        ...
        .session(redisSession)
        .abstractFilesystem(combined)   // ← 逃生口，与 filesystem(...) 互斥
        .build();
// 因为 combined instanceof AbstractSandboxFilesystem → ShellExecuteTool 自动注册（HarnessAgent.java:1715）
```

`SandboxShellOverComposite` 只需：11 个文件方法转调 `composite`，`execute()`/`id()` 转调 `shell`。这是唯一能让"同一份文件系统视图既共享记忆又能 execute"的干净做法，代价是要写个薄包装类。

**办法 3：直接换成模式 2（沙箱）**

如果共享记忆不是硬需求、主要想要"隔离执行 + 跨调用恢复工作区"，直接用沙箱模式，shell 原生就有：

```java
.filesystem(new DockerFilesystemSpec().image("ubuntu:24.04").isolationScope(IsolationScope.USER))
```

沙箱靠快照 + 状态存储（`SessionSandboxStateStore`）在工作区层面做跨 call/跨 session 的"共享"，但那是**工作区状态共享**，不是 KV 记忆模型，且按调用串行复用——和模式 1 的多副本并行共享不是一回事。

---

## 十一、速查表

### 11.1 配置 → 结果对照

| 配置 | 结果 | shell | 典型场景 |
|------|------|-------|---------|
| 不写 `filesystem(...)` | 本机 + shell（模式 3） | ✅ `sh -c` | 单进程/本机/可信 |
| `filesystem(new LocalFilesystemSpec()...)` | 本机 + shell（显式） | ✅ | 同上，需调超时/env |
| `filesystem(new RemoteFilesystemSpec(store)...)` | 共享存储（模式 1） | ❌ | 多副本共享长期记忆 |
| `filesystem(new DockerFilesystemSpec()...)` 等 | 沙箱（模式 2） | ✅（沙箱内） | 隔离执行、跨调用恢复 |
| `abstractFilesystem(myFs)` | 完全自管 | 看实现 | 三种都不够时的逃生口 |

### 11.2 "我想要 X，选什么"

| 你想要的 | 选什么 |
|---------|--------|
| 只要共享记忆、不需要 shell | **模式 1**（builder 就是这么做的） |
| 共享记忆 + 偶尔跑命令（可接受两套磁盘） | 模式 1 + **办法 1** 手动挂 shell 工具 |
| 共享记忆 + shell 在同一视图 | 模式 1 结构 + **办法 2** 逃生口包装 |
| 隔离执行 + 跨调用恢复（不要 KV 记忆） | **模式 2** 沙箱 |
| 单机本机 shell | **模式 3** |

---

## 十二、一句话总结

2.0 文件系统是**一个契约（`AbstractFilesystem`）+ 两种组合原语（`Overlay` 做 COW 叠加、`Composite` 做前缀路由）+ 三种声明式 spec** 的设计。所有文件工具只认契约；shell 能力被收口到 `AbstractSandboxFilesystem` 一个 `instanceof` 判断；多租户靠 `RuntimeContext` + `IsolationScope` 在 namespace 层分桶；沙箱后端则靠"一切皆 shell 命令"的 `BaseSandboxFilesystem` 把 5 种后端的差异收敛到 4 个抽象方法。**换部署模式只是换 spec，agent 代码一行不动**——文档反复强调的卖点，代码也确实兑现了。

记忆共享的唯一正解是**模式 1**；而模式 1 没有 shell 是刻意为之，要在它之上加 shell 只能手动挂工具、走逃生口、或换模式 2。

---

## 附录：关键文件索引

| 关注点 | 文件 |
|--------|------|
| 统一契约 | `filesystem/AbstractFilesystem.java` |
| shell 接口 | `filesystem/sandbox/AbstractSandboxFilesystem.java` |
| COW 叠加 | `filesystem/OverlayFilesystem.java` |
| 前缀路由 | `filesystem/CompositeFilesystem.java` |
| 多租户包装 | `filesystem/BakedContextFilesystem.java` |
| 本机实现 | `filesystem/local/LocalFilesystem.java`、`LocalFilesystemWithShell.java` |
| KV 后端 | `filesystem/remote/RemoteFilesystem.java` |
| 沙箱内核 | `filesystem/sandbox/BaseSandboxFilesystem.java`、`SandboxBackedFilesystem.java` |
| 三种 spec | `filesystem/spec/{Local,Remote,Sandbox,Docker}FilesystemSpec.java` |
| 隔离枚举 | `IsolationScope.java` |
| Builder 装配 + 校验 | `HarnessAgent.java`（`build()` 及 `filesystem(...)` 重载） |
| shell 工具 | `tool/ShellExecuteTool.java` |
| 实例（模式 1） | `agentscope-examples/agents/agentscope-builder/.../BuilderConfig.java` |
