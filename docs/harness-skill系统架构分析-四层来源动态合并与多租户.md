# AgentScope Java Harness Skill 系统架构分析：四层来源、动态合并、自学习闭环与多租户

> 分析日期：2026-06-09
>
> 分析对象：`agentscope-core.skill` + `agentscope-harness.agent.skill` / `agent.tool` / `agent.middleware`（skill 注入、运行时、自学习闭环子系统）
>
> 对标文档：[https://java.agentscope.io/v2/zh/docs/harness/skill.html](https://java.agentscope.io/v2/zh/docs/harness/skill.html)
>
> 关联文档：[harness-文件系统架构分析-三种模式与记忆共享](./harness-文件系统架构分析-三种模式与记忆共享.md)（skill 的多租户隔离复用了文件系统的 `IsolationScope` 机制）

---

## 一、设计目标：让 agent 按需加载"能力包"

一个 **skill** 就是一个目录,核心是一份 `SKILL.md`(YAML frontmatter 的 `name` + `description`,加给 agent 看的指令正文),可再带 `references/`(参考资料)和 `scripts/`(可执行脚本)。

```
code-reviewer/
├── SKILL.md          # 必需：frontmatter(name + description) + 指令正文
├── references/       # 可选：长篇参考，agent 按需读取
└── scripts/          # 可选：agent 通过 shell 调用的脚本
```

关键设计:**agent 每轮推理只在 system prompt 里看到每个 skill 的最少元数据**(name + description),觉得相关才调内置工具 `load_skill_through_path` 加载详情。这样框架能挂载大量 skill 而不撑爆上下文。

skill 在 v2.0 横跨两个模块:

| 模块 | 包 | 角色 |
|------|----|----|
| `agentscope-core` | `io.agentscope.core.skill` | 数据模型、仓库接口、(已废弃的)静态注入 |
| `agentscope-harness` | `io.agentscope.harness.agent.skill.*` / `agent.tool` / `agent.middleware` | 运行时合并、加载、shell 路径、自学习闭环 |

> ⚠️ **重要前提**:`agentscope-core/skill/` 整个包在 2.0 已标注 `@Deprecated`(如 `AgentSkill.java:64`、`SkillBox.java:41`、`DynamicSkillMiddleware`),但代码仍完整可用。harness 不复用 core 的 `DynamicSkillMiddleware`,而是自建 `HarnessSkillMiddleware` 并显式关掉 core 自动安装:`HarnessAgent.java:1850` `inner.dynamicSkillsEnabled(false)`。**分析以 harness 路径为准。**

---

## 二、核心数据模型与仓库层

### 2.1 AgentSkill / RegisteredSkill(内容与状态分离)

**`AgentSkill`**(`AgentSkill.java`)—— skill 的**不可变内容载体**(value object),全 `final` 字段(`:69-72`):

- `Map<String, Object> metadata`:元数据,`LinkedHashMap` 存储,**必需键** `name` / `description`,其余自由扩展;
- `String skillContent`:markdown 正文,不可为空;
- `Map<String, String> resources`:辅助资源,key 为相对路径,值为文本或 `base64:` 前缀二进制;
- `String source`:来源标识,null 默认 `"custom"`(`:143`)。

派生键:`getSkillId() = name + "_" + source`(`:238-240`)——这是 registry/catalog 的主键。必填校验集中在 `getRequiredMetadataString`(`:468-480`)。

**`RegisteredSkill`**(`RegisteredSkill.java`,package-private)—— `AgentSkill` 的**运行态包装**,字段极简:`skillId`(`:29`)+ `boolean active`(`:30`,唯一可变字段,默认 `false`)。`getToolsGroupName() = skillId + "_skill_tools"`(`:74-76`)。

两者关系:`AgentSkill` 是"内容是什么",`RegisteredSkill` 是"是否被激活";通过 `skillId` 间接关联,不互相持有引用。

### 2.2 SkillRegistry / SkillBox(存储与门面分离)

**`SkillRegistry`**(`SkillRegistry.java:34-38`)—— 纯存储层,两张 `ConcurrentHashMap`(`skills` / `registeredSkills`,`:40-41`),`registerSkill` 对同 id **直接 `put` 覆盖**(`:54-57`),不做优先级比较或字段合并。不做解析、不暴露给 prompt。

**`SkillBox`**(`SkillBox.java`)—— 门面/编排层,持有 `SkillRegistry`(`:49`)+ `AgentSkillPromptProvider`(暴露给 prompt,`:50`)+ `SkillToolFactory`(生成 skill 工具,`:51`)+ 绑定的 `Toolkit`(`:52`)。`getSkillPrompt()` 委派给 `AgentSkillPromptProvider`(`:84-96`)。

### 2.3 SKILL.md 解析:`MarkdownSkillParser` + `SkillFileSystemHelper`

`MarkdownSkillParser.java` 用正则(`:77-79`,配合 `Pattern.DOTALL`)切分 frontmatter(YAML 段)与正文:

```
^---\s*[\r\n]+(.*?)[\r\n]*---(?:\s*[\r\n]+)?(.*)
```

YAML 用 SnakeYAML 的 **`SafeConstructor`**(`:206-208`)并加多重安全限制(`:73,189-196`:`codePointLimit=16384`、`maxAliasesForCollections=10`、`nestingDepthLimit=10`)。`normalizeMetadataValue`(`:229-262`)把杂乱容器规整为标量 / `LinkedHashMap` / `List` 三种稳定形态。**必需字段约束不在解析层,而在 `AgentSkill` 构造层**。

`SkillFileSystemHelper.java` 是仓库层共享的文件操作工具类,被 `Classpath`/`FileSystem` 仓库复用:

- `validateAndResolvePath`(`:280-296`):归一化 + `startsWith(baseDir)` **防 `..` 路径穿越**;
- `findSkillDirectoryByName`(`:429-442`):列出子目录 → 过滤含 `SKILL.md` → 过滤 SKILL.md 的 `name` 元数据等于目标名。**匹配依据是 frontmatter 里的 `name`,不是目录名**;
- `loadSkillFromDirectory`(`:84-106`):读 `SKILL.md` → 收集资源 → `SkillUtil.createFrom` 构造 `AgentSkill`;
- `readAndPutResource`(`:389-406`):先按 UTF-8 读,失败(`MalformedInputException`)则降级为 `base64:` 二进制——与 `AgentSkill.resources` 的 base64 约定闭环一致。

### 2.4 仓库接口:`AgentSkillRepository` 与内置实现

接口 `AgentSkillRepository.java`(继承 `AutoCloseable`)核心方法:

| 方法 | 行 | 说明 |
|------|----|----|
| `getSkill(String name)` | `:44` | 按名取单个 |
| `getAllSkillNames()` | `:53` | 列举所有名字 |
| `getAllSkills()` | `:60` | **列全部——关键:无 `RuntimeContext` 参数** |
| `save(List, boolean)` / `delete(String)` | `:73` / `:81` | 写入/删除 |
| `getSource()` | `:107` | `repositoryType_location` |
| `getRepositoryInfo()` | `:98` | `(type, location, writable)` |

> **`getAllSkills()` 不带 `RuntimeContext` 是多租户分析的决定性事实**(见第六章)。

两个内置实现:

- **`ClasspathSkillRepository`**(`ClasspathSkillRepository.java`):只读,从 classpath/JAR 加载。JAR 内部用 `FileSystems.newFileSystem` 建虚拟 FS(`:140-147`);`save`/`delete` 全部忽略,`isWriteable()` 恒 `false`(`:198-208`)。扫描逻辑全委派 `SkillFileSystemHelper`。
- **`FileSystemSkillRepository`**(`FileSystemSkillRepository.java`):可读写(默认 `writeable=true`,`:59-61`),从本机目录加载。`source = "filesystem-" + parent + "_" + name`。

扩展实现(各为独立 maven 模块):`GitSkillRepository`、`MysqlSkillRepository`、`PostgresSkillRepository`、`NacosSkillRepository`(详见第八章)。

---

## 三、四层来源与优先级

文档讲"四个来源 + 优先级表",源码里就是 `HarnessAgentBuilderSupport.composeSkillRepositories()`(`:706-755`),按**低 → 高优先级**顺序往 `ordered` 列表加:

| 层 | 优先级 | 来源 | 源码 | 读取方式 |
|----|--------|------|------|---------|
| **Layer 1** | 最低 | 项目全局目录 | `new FileSystemSkillRepository(projectGlobalSkillsDir)`(`:716`) | host 端,注册时预载内存 |
| **Layer 2** | 中 | 市场(Git/MySQL/Classpath/Nacos/PG) | `ordered.addAll(b.skillRepositories)`(`:726`) | 后端预载内存;shell 跑前由 Stager 物化到 `.skills-cache` |
| **Layer 3** | 高 | 工作区共用 | `new FileSystemSkillRepository(wsManager.getSkillsDir())`(`:732`) | host 端 `workspace/skills/`,预载内存 |
| **Layer 4** | 最高 | 用户隔离 | `new WorkspaceSkillRepository(filesystem, "skills", currentRcSupplier, "workspace-namespaced", false)`(`:745-751`) | 走 `AbstractFilesystem`,SKILL.md 预载,其它文件**懒加载**,per-user 命名空间 |

**去重合并**在 `HarnessSkillMiddleware.mergeRepositories()`(`:207-231`):遍历低 → 高,`merged.put(skill.getName(), new RepoBound(skill, repo))`——**后入者覆盖先入者,即高优先级层同名 skill 整体覆盖低优先级层**。这正是文档"下层独有的 skill 仍保留,只在重名时被上层覆盖"。

> 注意是按 `skill.getName()` 去重(不是 `skillId`),所以同名的 skill 真的会互相覆盖;不同 source 的同名 skill **不会**共存——这与 core `SkillRegistry`(按 skillId 不去重)行为不同。

---

## 四、每轮动态合并的七步流程

`HarnessSkillMiddleware`(`middleware/HarnessSkillMiddleware.java`)是 2.0 skill 注入的核心,类 Javadoc(`:46-64`)自述七步。`onSystemPrompt()`(`:140-189`)实现:

1. **取当前 RC** `resolveContext(agent)`(`:142,195`)——拿到本次调用的 userId / sessionId;
2. **低 → 高合并** `mergeRepositories(ctx)`(`:144,207`)——遍历四层 `repo.getAllSkills()`,按 name 去重;
3. **可见性过滤** `applyVisibility(merged.values(), ctx)`(`:150,233`)——套 `SkillVisibilityFilter`(灰度/白名单/环境门控);
4. **物化市场 skill** `stager.stage(visible, sourceNamespaces)`(`:156`)——`MarketplaceStager` 把 Layer 1/2 资源落到 `.skills-cache/`;
5. **构建 catalog** 给每个可见 skill 算 `filesRoot` 并包成 `HarnessSkillEntry`(`:159-174`);
6. **安装** `runtime.install(catalog, toolkit)`(`:178`)——幂等地(只注册一次)挂上 `load_skill_through_path`;
7. **渲染 prompt** `runtime.renderPrompt(catalog, effective)`(`:182`)→ 追加到当前 system prompt(`:186-188`)。

**"动态"的本质**:Layer 4 的内容依赖 `RuntimeContext`(同一 skill 名,不同用户调会返回不同内容),按 skillId 缓存会掩盖这种交换,故**每次 `call()` 都重建**。想关掉用 `disableDynamicSkills()`(`HarnessAgent.java:1353`),回退到 build 时合并一次的静态 `SkillBox`(`HarnessAgentBuilderSupport.staticSkillBoxFromRepos` `:762`)。

**注入位置**:在 `ReActAgent` 构建 system message 时,经 `applySystemPromptMiddlewares(base)`(`ReActAgent.java:371,382`)进入中间件链,`onSystemPrompt` 用 flatMap 串接(`:407-411`)。粒度是"每次 `call()`",不是"每个 reasoning step"。

---

## 五、Agent 如何读取与执行 Skill

### 5.1 读取:`load_skill_through_path` 三段式

`SkillLoadTool.loadOne()`(`runtime/SkillLoadTool.java:143-167`)严格按文档"内存命中 → 文件系统读取 → 返回可用路径清单":

1. **`path == "SKILL.md"` 特例**(`:147-149`):直接返回解析后的 markdown 正文;
2. **内存命中**(`:152-155`):Layer 1/2/3 预载的 `skill.getResources()` map;
3. **文件系统懒加载回退**(`:158-163`):`entry.lazyResources().read(path)`——即 `WorkspaceSkillRepository.FilesystemSkillResources.read()`(`:563-577`),走 `AbstractFilesystem`,自动遵循 per-user 命名空间;
4. **未命中 → 返回清单** `formatNotFound()`(`:204-239`):合并 `SKILL.md` + 内存 keys + `lazy.list()`,去重编号输出,绝不死路。

`getParameters()`(`:86-109`)把当前 catalog 的 `ids()` 作为 `skillId` 的 `enum`——**每轮 enum 跟着 catalog 变**。`callAsync`(`:126-134`)从 `catalogRef.get()` 查 entry,查不到返回 "Skill not found"。

> 文档提到的 `read_skill` 工具在源码里**不存在**——`SkillUsageMiddleware`(`:47-54`)把它列为保留 VIEW 名,但实际只注册了 `load_skill_through_path`。

### 5.2 执行:`<files-root>` 与 ShellPathPolicy

skill 带脚本时,agent 要靠绝对路径才能用 `execute_shell_command` 调它。这个绝对路径就是 prompt 里每个 `<skill>` 的 `<files-root>`,由 `ShellPathPolicy.resolve()`(`runtime/ShellPathPolicy.java:85-126`)按**文件系统模式**算:

| 文件系统模式 | 工作区 skill(Layer 3/4) | 市场 skill(Layer 2 物化) |
|------------|------------------------|-------------------------|
| **Sandbox** | `/workspace/skills/<name>` | `/workspace/.skills-cache/<ns>/<name>` |
| **Local-with-shell** | `<workspaceRoot>/skills/<name>` | `<workspaceRoot>/.skills-cache/<ns>/<name>` |
| **No-shell**(Remote/Composite/local 无 shell) | `null` | `null` |

`HarnessAgent.java:1858-1872` 根据 `filesystem instanceof LocalFilesystemWithShell` / `SandboxBackedFilesystem` / 其它选模式;`disableShellTool` 时强制 `noShell()`。`SkillPromptBuilder`(`:140-142,153-155`)只在**至少一个可见 skill 的 filesRoot 非空**时才输出 `<code_execution>` 段,否则会误导模型以为有 shell 工具。每个 `<skill>` 内放自己的 `<files-root>`(`:175-177`),指令模型用各自根而非单一硬编码根。

### 5.3 市场 skill 的物化:`MarketplaceStager`

`MarketplaceStager.stage()`(`runtime/MarketplaceStager.java:91-145`)每轮重建白名单 + 增量物化 + 孤儿 GC:

- **物化目标路径**:`<wsRoot>/.skills-cache/<sourceNs>/<skillName>/`(`CACHE_DIR=.skills-cache` `:64`;路径计算 `:108,132`);
- **SHA-256 去重**:`writeIfChanged()`(`:198-207,291-304`)——文件已存在且哈希相同则跳过,只重写变化的文件;
- **孤儿清理**:`garbageCollectOrphans()`(`:240-268`)——下架的 skill / 被移除的整个仓库留下的目录,同轮顺手删掉;
- **`getSource()` 冲突加后缀**:`resolveSourceNamespaces()`(`:312-347`)——两个仓库返回相同 source,第二个加 `_2`/`_3` 后缀并 warn,保证 skill-id 和路径不撞;
- Sandbox 模式下 `.skills-cache` 默认在 workspace projection roots 里,沙箱启动时与 `workspace/skills/` 一起 hydrate 进容器。

---

## 六、多租户隔离机制

### 6.1 四层的租户感知能力(决定性证据)

回到 `composeSkillRepositories()`(`:706-755`)与合并 `mergeRepositories()`(`HarnessSkillMiddleware.java:207-231`,只调 `repo.getAllSkills()`):

| 层 | `getAllSkills()` 是否感知 `RuntimeContext` | 租户隔离 |
|----|-------------------------------------------|---------|
| Layer 1(host 绝对路径) | 否 | ❌ 全员共享 |
| Layer 2 市场(含 Nacos/Git/MySQL…) | **否**——接口无 ctx 参数 | ❌ **全局共享** |
| Layer 3(host `workspace/skills/`) | 否 | ❌ 全员共享 |
| Layer 4(`WorkspaceSkillRepository`) | **是**——通过 `currentRcSupplier` 取 ctx,读走 `AbstractFilesystem` | ✅ **唯一可隔离层** |

接口硬限制:`AgentSkillRepository.getAllSkills()` 无参,所以**任何市场仓库都无法按请求用户返回不同 skill 集合**——它们 build 时加一次,对所有调用者返回相同内容。

### 6.2 Layer 4 的隔离:复用文件系统的 `IsolationScope`

Layer 4 的 `WorkspaceSkillRepository` 逻辑路径恒为 `skills/`,**它自己不拼 userId**;真正的 per-user 隔离交给底层文件系统——这正是文件系统架构分析里的 `IsolationScope` 机制(参见关联文档):

- `WorkspaceSkillRepository` 用 `Supplier<RuntimeContext> currentRcSupplier`(`:749`)每次取当前合并后的 ctx;
- 读操作委派 `filesystem.read(ctx, "skills/...")` / `filesystem.glob(ctx, ...)`;
- 当文件系统是配了 `IsolationScope.USER` 的 `RemoteFilesystem`(如 builder 的 `RemoteFilesystemSpec`),`skills/` 会被路由到该用户专属命名空间。

| IsolationScope | Layer 4 的 `skills/` 命名空间 | 隔离粒度 |
|----------------|------------------------------|---------|
| `SESSION` | 每会话一套 | 最细,会话级 |
| `USER` | 每用户一套(需 ctx 带 userId) | **典型多租户** |
| `AGENT` | 每 agent 一套(跨用户共享) | agent 级 |
| `GLOBAL` | 全局一套 | 无隔离 |

**三要素缺一不可**:① 文件系统配 `IsolationScope.USER`;② 调用时 `RuntimeContext` 带 userId(否则降级匿名命名空间);③ 不关 `disableDynamicSkills`。效果:用户 A 写的 `skills/foo/` 只有 A 可见;因 Layer 4 优先级最高,用户还能覆盖共享层同名 skill。

---

## 七、自学习闭环(M1 / M4 / M5,可选)

文档讲的"agent 自己起草 → 审核 → 整理"闭环,在 `HarnessAgent.java:1766-1845` 接线,分三个独立可开的 milestone:

### 7.1 M1 起草(`enableSkillManageTool`)

启用后(`:1374`):

- 把默认只读 Layer 4 repo 换成可写的 `WorkspaceSkillRepository(..., smConfig.mainDir(), ..., "workspace-writable")`(`:1778-1797`);
- 额外建草稿区 `WorkspaceSkillRepository(..., smConfig.draftsDir(), ..., "workspace-drafts")`(`:1798-1803`),默认 `skills/_drafts`(`SkillManageConfig.java:32`);
- 注册两个工具:`skill_manage`(六动作 create/edit/patch/write_file/remove_file/delete,`SkillManageTool.java:228,256-277`)+ `propose_skill`(`ProposeSkillTool.java:44`,`:1815`);
- 建 `SkillUsageStore`(`skills/.usage.json`)、`SkillAuditLog`(`skills/.audit/`)、挂 `SkillUsageMiddleware`(`:1804-1818`)。

`autoPromote=false`(默认)→ 草稿落 `skills/_drafts/<name>/`,**下一轮不可见**,必须经 gate 晋升;`autoPromote=true` → 直写 `skills/<name>/`,立即生效(`SkillManageTool.java:333,356-360`)。

> 注:草稿落盘是 `skills/_drafts/<name>/`,**无 `<userId>` 段**;遥测是 agent 级非 user 级(`SkillUsageStore.java:44-47`)。`<userId>` 只在运行时灰度(`CanaryFilter` 的 hash)出现。

### 7.2 M4 审核闸门 + 可见性过滤(`enableSkillPromotionGate`)

**晋升闸门** `SkillPromotionGate`(`:1390`),三种内置实现:

| 实现 | 行为 |
|------|------|
| `RejectAllGate`(默认,`:1826`) | 恒 `Defer`,草稿只能靠外部 `agent.promoteSkill()` 晋升 |
| `LocalApprovalGate`(`LocalApprovalGate.java:91-126`) | stdin `[y/N]` 单副本 HITL,默认 30min 超时回退 Defer |
| `NotifyAndWaitGate`(`:100-118`) | 写 `.review_request.json` + 通知所有 `NotificationSink`,等任一副本外部批准 |

晋升执行器 `SkillPromoter.promote()`(`SkillPromoter.java:87`):载入草稿全量资源 → 安全扫描 → `gate.review` → `Approve` 时物理移动 `_drafts→skills`(`workspaceManager.moveSkill`,`:143`)→ 更新 sidecar + 审计。

**可见性过滤** `SkillVisibilityFilter`,决定推理时 agent 能看到哪些 agent 自建 skill。基类 `AbstractAgentCreatedFilter` 对手写/预置 skill 放行,只对 agent 自建套子类规则:

- `EnvironmentFilter`(`:46-68`):按部署环境(默认 `prod`);
- `CanaryFilter`(`:56-83`):按 `userId|skillName` 稳定 hash 灰度比例;
- `AllowListFilter`(`:38-41`):白名单;
- `CompositeFilter`(`:40-49`):AND 组合。

### 7.3 M5 后台整理(`enableSkillCurator`)

周期性 `SkillCurator.runOnce()`(`SkillCurator.java:317`):

- **Phase 1** 纯状态机 `applyAutomaticTransitions`(`:173-222`):`ACTIVE ↔ STALE → ARCHIVED`(超 `staleAfterDays=30` 标 stale,超 `archiveAfterDays=90` 归档到 `skills/.archive/`,**永不删除只归档**);
- **Phase 2** LLM 伞合并扫描 `runUmbrellaDryRunReport`(`:238-304`):**M5 仅 dry-run**,写报告到 `skills/.curator_reports/`,LIVE 留待后续。

节流 `shouldRunNow()`(`:146-165`)看 `intervalHours`(默认 7 天,`SkillCuratorConfig.java:90`)+ `paused`;实际触发由 `SkillCuratorMiddleware` 在每次 `call()` 完成后投递到单线程 daemon 异步跑。程序化绕过节流:`runCuratorOnce()`(`HarnessAgent.java:240`)。

### 7.4 安全与遥测

- **`SkillSecurityScanner`**(`SkillSecurityScanner.java:46`):纯静态正则,6 类规则(EXFILTRATION/INJECTION/DESTRUCTIVE/PERSISTENCE/NETWORK/OBFUSCATION,`:55-62,99-251`)。`INSTALL_POLICY` 对 `AGENT_CREATED` 档:SAFE/CAUTION 放行,DANGEROUS 回滚(`:293-304`)。自定位"明显错误拦截",真正沙箱在 `ShellExecuteTool` 侧(`:42-44`)。
- **`SkillUsageStore` + `SkillUsageMiddleware`**:`Middleware` 负责读侧自动记账(每次 `load_skill_through_path` → `bumpView`,`SkillUsageMiddleware.java:63-71,93-94`);`SkillManageTool` 负责写侧(patch → `bumpPatch`)。两者写入同一个 `.usage.json` sidecar。`bumpIfAgentTracked` provenance 闸(`:240-261`):计数器只对 `createdBy!=null` 的 agent 自建 skill 生效。
- **`SkillAuditLog`**(`:52`):append-only JSONL,按 UTC 日分文件 `skills/.audit/YYYY-MM-DD.jsonl`,与 OTel 分离的恢复/取证副本。`queryAudit()`(`HarnessAgent.java:228`)查审计。

**闭环数据流**:

```
起草  skill_manage(create)/propose_skill → skills/_drafts/<name>/ → 安全扫描 → .usage.json + .audit
审核  外部/审批 promoteSkill() → SkillPromoter(再扫安全 + gate.review) → Approve 移动到 skills/
可见  HarnessSkillMiddleware 合并 → SkillVisibilityFilter 链(Environment ∩ Canary ∩ AllowList)
遥测  SkillUsageMiddleware 每次 load → bumpView；SkillManageTool 每次 patch → bumpPatch → .usage.json
整理  SkillCuratorMiddleware 每 call 后 → shouldRunNow → ACTIVE↔STALE→ARCHIVED + dry-run 报告
```

---

## 八、Nacos Skill 仓库集成

### 8.1 依赖与 API

`agentscope-extensions-nacos-skill` 引入 `com.alibaba.nacos:nacos-client`,版本由 BOM 统一锁定为 **`3.2.1-2026.03.30`**(`agentscope-dependencies-bom/pom.xml:107`)。它用 Nacos 的 AI Skill API:`com.alibaba.nacos.api.ai.AiService`(`NacosSkillRepository.java:18`)。思路:Nacos 把 skill(含 `SKILL.md` + 资源)打包成 **ZIP** 存服务端,AgentScope 按名字下载、解包成 `AgentSkill`。

### 8.2 三种下载方式

`downloadSkillZipBytes()`(`:253-261`)按构造时解析的 version/label 派发:

| 条件 | 调用 |
|------|------|
| 设了 `version` | `downloadSkillZipByVersion(name, version)` |
| 只设了 `label` | `downloadSkillZipByLabel(name, label)` |
| 都没设 | `downloadSkillZip(name)` |

version / label 优先级(`:128-137`):Properties > JVM 系统属性(`agentscope.nacos.skill.version/label`) > 环境变量(`AGENTSCOPE_NACOS_SKILL_VERSION/LABEL`)。两者同时存在时 **version 胜出**。

### 8.3 frontmatter 适配

下载的 ZIP 进入 `adaptNacosSkillZipForYamlFrontmatter()`(`:269-318`)。原因:Nacos 导出的 `SKILL.md` 常用**缩进续行** YAML(续行无 `key:` 前缀),而 AgentScope 的 `MarkdownSkillParser` 只认扁平 `key: value` 行。仓库把续行折叠回上一个 key(`normalizeFoldedFlatYaml` `:372-400`)、必要时给值加双引号(`yamlValueNeedsQuoting` `:422-452`),重写 ZIP 后再交给解析器。ZIP 入口名做安全校验:`..` 段、绝对路径一律拒绝(`normalizeZipEntryName` `:479-494`)。

### 8.4 ⚠️ 关键限制:只读 + 不能列举

类 Javadoc(`:54-59`)与测试都确认:

| 方法 | 行为 |
|------|------|
| `getSkill(name)` / `skillExists(name)` | ✅ 按名下载 ZIP |
| `isWriteable()` | 恒 `false`(只读) |
| `save` / `delete` / `setWriteable` | no-op + warn |
| **`getAllSkills()` / `getAllSkillNames()`** | ⚠️ **返回空列表**(`:206-215`) |

**影响**:harness 发现流程 `mergeRepositories()` 只通过 `getAllSkills()` 枚举 skill。Nacos 返回空,意味着:

- Nacos skill **不进入每轮 `SkillCatalog`**;
- **不出现**在 `<available_skills>` prompt 块;
- `load_skill_through_path` 的 `skillId` enum(`SkillLoadTool.java:88,97`)不含 Nacos skill;模型硬调则 `catalog.get(skillId)` 查不到返回 "Skill not found"。

**结论**:`skillRepository(new NacosSkillRepository(...))` 单独挂上,在当前实现下**不能让 Nacos skill 自动可见**。文档"agent 看得到这个仓库里的 skill"在此实现下不成立——Nacos skill 实际只能靠 `getSkill(name)` 按已知名字取。

### 8.5 Nacos 客户端 SDK 的能力边界(决定性)

我读了 Nacos 仓库的 [`AiService.java`](https://github.com/alibaba/nacos/blob/master/api/src/main/java/com/alibaba/nacos/api/ai/AiService.java) 接口全文。它的 skill 方法**只有三个,全按名下载**:

```java
byte[] downloadSkillZip(String skillName);
byte[] downloadSkillZipByVersion(String skillName, String version);
byte[] downloadSkillZipByLabel(String skillName, String label);
```

整个接口(MCP / Skill / AgentSpec / Prompt)**没有任何 list / enumerate 方法**。下载方法签名里**也没有 namespace 参数**——namespace 在构造 `AiService` 客户端实例时绑定(properties / `NacosFactory`),即 **一个 `AiService` 实例 = 一个 namespace**。agentscope wrapper 的 `namespaceId` 参数只用来拼 `source = "nacos:<ns>"` 标签(`:126`),真正生效的 namespace 来自 `aiService` 客户端自身配置。

所以 8.4 的"列举为空"**忠实反映了 `nacos-client` runtime SDK 的能力边界**——这个 SDK 就是点查 + 订阅,不列举。Nacos 平台层面列举是支持的,但走别的通道:`nacos-cli skill-list`、Console API `/v3/console/ai/skill`、Admin API、`nacos-maintainer-client`(运维 SDK)——均非 `nacos-client` 的 `AiService`。

---

## 九、多租户专题:为什么 Nacos 不适合做"每租户一套 skill"

### 9.1 SkillBase 没有 group——决定性证据

我读了 Nacos 的 [`SkillBase.java`](https://github.com/alibaba/nacos/blob/master/api/src/main/java/com/alibaba/nacos/api/ai/model/skills/SkillBase.java)(skill 身份基类),字段只有三个:

```java
public class SkillBase {
    private String namespaceId;
    private String name;
    private String description;
}
```

`Skill.java` 注释直说:"**Simplified structure with core fields only.**"。对照 Nacos 配置项身份是经典三元组 `(dataId, group, namespace)`——但 **skill 把 `group` 砍掉了,只剩 `(namespace, name)`**。即:

- Nacos 配置/服务发现能做的"一个 namespace + group=租户"细粒度隔离,**skill 做不到**;
- skill 的结构性隔离轴**只有 namespace 一个**。

这是设计如此、无法绕过——不是 wrapper 的锅,是 Nacos skill 模型本身没有 group。

### 9.2 单 namespace 内的补救手段(都不理想)

| 手段 | 做法 | 问题 |
|------|------|------|
| skill 名字编码租户 | `tenantA__code-reviewer` | 名字污染、全局冲突;下载可行但**列举仍需 Console/maintainer API**,且要自己按前缀过滤;本质把租户塞进 name,很 hack |
| PRIVATE 可见性 + 授权用户 | 每租户 skill 标 PRIVATE,授权该租户用户 | 是"访问控制"不是"分区";租户一多授权关系爆炸;wrapper 没接 |
| Business Tags 过滤 | 打 `[tenantA]` 标签 | 只用于列表筛选/展示,**不做权限隔离**,任何人都能下载 |

### 9.3 结论

**"namespace-per-tenant"确实是 Nacos skill 的硬伤**:它没有 group,namespace 是唯一结构化隔离轴,租户多了 namespace 会爆炸。在没有 group 的前提下,其它手段都是在 name/可见性上打补丁,不如直接换隔离载体。

---

## 十、文档与源码差异澄清

| 文档说法 | 源码实际 | 证据 |
|---------|---------|------|
| `read_skill` 工具 | 实际只有 `load_skill_through_path` | `SkillLoadTool` 唯一加载工具;`read_skill` 仅 `SkillUsageMiddleware:47-54` 作保留名 |
| 草稿落 `skills/_drafts/<userId>/` | 实际 `skills/_drafts/<name>/`,无 userId 段;遥测 agent 级非 user 级 | `SkillManageConfig:32`;`SkillUsageStore.java:44-47` |
| 整个 core skill 包 | 已 `@Deprecated`,2.0 由 harness `HarnessSkillMiddleware` 接管 | 各 core 类 `@Deprecated`;`HarnessAgent.java:1850` 关 core 自动装 |
| Nacos "agent 看得到仓库里的 skill" | `getAllSkills()` 返回空,实际不可见 | `NacosSkillRepository.java:212-215`;`AiService` 无 list 方法 |

文档的**优先级表**(四层)、**shell 路径表**(三模式)、**三段式加载**(内存→文件→清单)与源码完全吻合。

---

## 十一、落地建议与推荐架构

### 11.1 多租户 skill:不要用 Nacos,用内建 Layer 4

需求是"每租户一套 skill、又不想 namespace 泛滥"——正中内建方案强项:**Layer 4 + `IsolationScope.USER`**:

- **零 namespace、零 group**:隔离在 agentscope 自己的文件系统命名空间,按 `RuntimeContext.userId` 切目录,Nacos 一个 namespace 都不用多建;
- **细到用户级**:比 namespace/group 都细,天然支持海量租户;
- **开箱即用**:builder 已在用(`RemoteFilesystemSpec.isolationScope(USER)`),`WorkspaceSkillRepository` 走 `AbstractFilesystem` 自动按用户路由。

### 11.2 推荐架构:两层各司其职

把 Nacos 与内建方案**组合**,而非二选一:

```
Layer 4 (per-user, IsolationScope.USER)   ← 租户专属 skill,海量租户零 namespace 开销
        ↑ 优先级最高,可覆盖
Layer 2 (Nacos / Git, 单 namespace 共享)  ← 团队通用 skill 市场,所有租户共享
```

- **Nacos 退回它擅长的角色**:做**所有租户共享的公共 skill 市场**(草稿 → 评审 → 发布管线、安全扫描、版本管理),只用一个 namespace;
- **租户隔离交给 Layer 4**:每个用户的专属/覆盖 skill 写进自己的 `skills/`。

> 注意:即便如此,Layer 2 的 Nacos skill 因 `getAllSkills()` 为空仍**不会自动出现在 prompt**。要让 Nacos 公共 skill 可见,需要补"名字来源"——或改用 Git/MySQL 仓库(它们能列举),或自写一个用 `nacos-maintainer-client` 补列举的 ctx 感知仓库。

### 11.3 要做真·每租户 Nacos skill 的话

若坚持用 Nacos 做每租户隔离,代价较大,需自定义仓库:

- 实现 `TenantAwareNacosSkillRepository`(`AgentSkillRepository` + `LazyResourceCapable`),内部按 `RuntimeContext.userId` 选 `AiService`(对应租户 namespace);
- 用 `nacos-maintainer-client` 或 Console API 补 `getAllSkills()`(因 `AiService` 列举不了);
- 仍受 Nacos skill 无 group 限制,namespace 数随租户增长。

**不推荐**:成本远高于内建 Layer 4 方案。

---

## 十二、Builder 入口与文件索引

### 12.1 `HarnessAgent.Builder` skill 入口

| 方法 | file:line | 作用 |
|------|-----------|------|
| `skillRepository(repo)` | `HarnessAgent.java:1163` | 追加市场仓库,可重复 |
| `skillRepositories(list)` | `:1173` | 一次性替换所有市场仓库 |
| `projectGlobalSkillsDir(path)` | `:1189` | 启用 Layer 1 项目全局目录(优先级最低) |
| `disableDynamicSkills()` | `:1353` | 关闭每轮重合并,改 build 时合并一次 |
| `enableSkillManageTool(config)` / `(autoPromote)` | `:1374` / `:1381` | M1:开 skill_manage + propose_skill |
| `enableSkillPromotionGate(gate, filter)` | `:1390` | M4:晋升闸门 + 可见性过滤 |
| `enableSkillCurator(config)` | `:1404` | M5:后台整理(需先开 M1) |
| `queryAudit(day, predicate)` | `:228` | 实例方法:查审计日志 |
| `runCuratorOnce()` | `:240` | 实例方法:强制跑一次整理(绕过节流) |
| `promoteSkill(name, reviewer)` | `:251` | 实例方法:手动晋升草稿 |

### 12.2 关键源码索引

**core(`io.agentscope.core.skill`)— 已 `@Deprecated`,仅作兼容**

| 文件 | 行 | 要点 |
|------|----|----|
| `AgentSkill.java` | `:69-72,238-240` | 不可变内容;`skillId = name + "_" + source` |
| `RegisteredSkill.java` | `:29-30` | `skillId` + 可变 `active` |
| `SkillRegistry.java` | `:40-41,54-57` | 纯存储,同 id 覆盖 |
| `SkillBox.java` | `:49-52,84-96` | 门面,持 Registry + PromptProvider + ToolFactory |
| `repository/AgentSkillRepository.java` | `:60,107` | `getAllSkills()` 无 ctx;`getSource()` |
| `repository/ClasspathSkillRepository.java` | `:140-152,198-208` | 只读,JAR 虚拟 FS |
| `repository/FileSystemSkillRepository.java` | `:59-61,152-155` | 可读写 host 目录 |
| `util/MarkdownSkillParser.java` | `:77-79,206-208` | frontmatter 切分;SafeConstructor |
| `util/SkillFileSystemHelper.java` | `:280-296,429-442` | 路径穿越防御;按 metadata name 找目录 |

**harness 运行时(`io.agentscope.harness.agent.skill.runtime`)— 当前主路径**

| 文件 | 行 | 要点 |
|------|----|----|
| `HarnessSkillMiddleware.java`(middleware/) | `:140-189,207-231` | 七步合并;`mergeRepositories` 按名后入覆盖 |
| `SkillRuntime.java` | `:39-52,70-89` | 持 catalog;幂等注册 load tool |
| `SkillCatalog.java` | `:42-51` | LinkedHashMap 按 skillId,低→高 |
| `SkillLoadTool.java` | `:143-167,204-239` | 三段式加载;未命中返回清单 |
| `SkillPromptBuilder.java` | `:140-155,165-179` | `<available_skills>` + per-skill `<files-root>` |
| `MarketplaceStager.java` | `:91-145,198-207,240-268,312-347` | 物化 `.skills-cache`;SHA-256 去重;孤儿 GC;source 冲突后缀 |
| `ShellPathPolicy.java` | `:40-44,85-126` | NO_SHELL / SANDBOX / LOCAL_WITH_SHELL 三模式路径 |
| `HarnessSkillEntry.java` | `:37` | `skill + lazyResources + filesRoot` |
| `WorkspaceSkillRepository.java`(skill/) | `:159-195,227-233,563-625` | 工作区 skill 发现;懒资源按需读 |
| `SkillResources.java` / `LazyResourceCapable.java` | `:51` | 懒资源 marker 接口 `resourcesFor(name, ctx)` |

**harness 自学习闭环**

| 文件 | 行 | 要点 |
|------|----|----|
| `tool/SkillManageTool.java` | `:228,256-277,333,356-360` | `skill_manage` 六动作;autoPromote 选目标仓库 |
| `tool/ProposeSkillTool.java` | `:44` | `propose_skill` 便捷起草 |
| `tool/SkillManageConfig.java` | `:32,35,50,78` | `skills/_drafts` / `skills` / autoPromote 默认 false |
| `skill/curator/SkillPromoter.java` | `:87,143` | 晋升流水线;moveSkill _drafts→skills |
| `skill/curator/SkillPromotionGate.java` | `:46,49-58` | review 决策 Approve/Reject/Defer |
| `skill/curator/RejectAllGate.java` | `:42-48` | 默认 gate,恒 Defer |
| `skill/curator/LocalApprovalGate.java` | `:91-126` | stdin HITL |
| `skill/curator/NotifyAndWaitGate.java` | `:100-118` | 写 review_request + 通知 sinks |
| `skill/curator/AbstractAgentCreatedFilter.java` | `:41,52-56` | 只过滤 agent 自建 skill |
| `skill/curator/EnvironmentFilter.java` | `:46-68` | 按部署环境 |
| `skill/curator/CanaryFilter.java` | `:56-83` | 按 hash 灰度比例 |
| `skill/curator/AllowListFilter.java` | `:38-41` | 白名单 |
| `skill/curator/CompositeFilter.java` | `:40-49` | AND 组合 |
| `skill/curator/SkillCurator.java` | `:173-222,238-304,317` | 状态机 + dry-run 伞合并 |
| `skill/curator/SkillCuratorConfig.java` | `:90-93` | intervalHours 168 / stale 30 / archive 90 |
| `skill/curator/SkillSecurityScanner.java` | `:55-62,99-251,293-304` | 6 类规则;INSTALL_POLICY |
| `skill/curator/SkillUsageStore.java` | `:44-47,63,240-261` | `.usage.json` agent 级;provenance 闸 |
| `skill/curator/SkillAuditLog.java` | `:52,60-61,144-175` | `.audit/YYYY-MM-DD.jsonl` |
| `middleware/SkillUsageMiddleware.java` | `:47-54,63-71,93-94` | 读侧自动 bumpView |
| `middleware/SkillCuratorMiddleware.java` | `:48-66,73-94` | 每 call 后异步触发 curator |

**Nacos 扩展**

| 文件 | 行 | 要点 |
|------|----|----|
| `NacosSkillRepository.java` | `:18,126,206-215,253-261,269-318,372-400` | AiService;只读;列举为空;frontmatter 适配 |
| `agentscope-dependencies-bom/pom.xml` | `:107` | nacos-client `3.2.1-2026.03.30` |
| Nacos `AiService.java`(alibaba/nacos) | — | 只有按名下载 3 方法,无 list,namespace 客户端级绑定 |
| Nacos `SkillBase.java`(alibaba/nacos) | — | 身份只有 `(namespaceId, name)`,**无 group** |

---

## 十三、总结

1. **Skill = SKILL.md 目录**;agent 每轮只看 name/description,需要时 `load_skill_through_path` 按需加载。
2. **四层来源**(项目全局 → 市场 → 工作区共用 → 用户隔离)在 `composeSkillRepositories` 低 → 高组装,`HarnessSkillMiddleware.mergeRepositories` 用"后入覆盖"实现优先级。
3. **2.0 注入核心是 `HarnessSkillMiddleware`**,每轮 `call()` 重新合并(因 Layer 4 内容随用户变),取代已废弃的 core `SkillHook`/`DynamicSkillMiddleware`。
4. **Shell 执行**靠 `ShellPathPolicy` 按文件系统模式算 `<files-root>`,市场 skill 由 `MarketplaceStager` 物化到 `.skills-cache`(SHA-256 去重 + 孤儿 GC)。No-shell 模式不渲染 files-root,自然无 shell 能力。
5. **多租户隔离复用文件系统的 `IsolationScope`**:仅 Layer 4 可隔离(唯一 ctx 感知层);市场仓库(含 Nacos)全局共享。
6. **Nacos 不适合做"每租户一套 skill"**:其 skill 模型无 group,namespace 是唯一结构化隔离轴,租户一多 namespace 爆炸;且 `nacos-client` 的 `AiService` 无列举 API,wrapper 列举为空。正确做法——租户隔离走 Layer 4 + `IsolationScope.USER`,Nacos 仅作单 namespace 共享市场。
7. **自学习闭环** M1(起草)→ M4(审核+灰度)→ M5(整理)三段独立可开,由 `SkillUsageStore`/`SkillAuditLog`/`SkillSecurityScanner` 串成数据流。
