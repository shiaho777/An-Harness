# 功能追赶路线图：对标 dsh-Go

日期：2026-11-16。对标仓库：https://github.com/ccch1mneyyy/dsh-Go
（DeepSeek Harness 的 Flutter 手机客户端，AGPL-3.0）。

原则：只借鉴功能设计与交互，不复制其代码（许可证不兼容直接搬运，且其多数
能力来自上游 DSH 引擎，我们是自研循环，需要在自己的 core/app 内实现）。

## 现状结论

单机 agent 基本功已大体对齐，模型接入广度领先。差距集中在：
手机操控、逐调用授权交互、全双工语音、子代理/团队、远程主机、插件生态。

### 已对齐（不列入追赶）

沙箱+终端+文件管理（PRoot 免 Root）、模型接入（7 类提供商 + 7 种 OAuth +
models.dev 目录）、会话管理（分组/搜索/fork/重试/编辑重生成/中断恢复）、
steering 插话、/compact、/plan、模式切换、浏览器自动化（17 种操作）、
MCP（HTTP/SSE+STDIO+OAuth PKCE）、SKILL.md 技能、定时任务 CRUD+运行记录、
ASR（13 家）+VAD+纠错+TTS、rclone 备份、zip 导出、约 20 种语言。

## 阶段与验收标准

### P0 逐调用工具授权卡（先行，复用现有权限地基）

问题：现有三级权限（BYPASS/ASK_ONCE/NOT_ALLOWED）是预设制，缺少
逐调用的审批交互；对标产品的授权卡支持"允许本次/本对话都允许/权限预设"，
且定时任务新建/修改强制独立授权、完全权限也不可跳过。

验收：
- 敏感工具调用前在聊天 UI 弹审批卡：允许本次 / 本会话都允许 / 拒绝。
- 会话级授权仅存内存，会话结束即失效；不落盘、不提升预设档位。
- 定时任务 create/update 路径强制弹卡，BYPASS 也不可跳过。
- OffloadPermissionManager 仍是唯一权威：授权门只能收紧（把 BYPASS
  降为需确认、把拒绝传回循环），绝不放行 NOT_ALLOWED。
- beforeToolCall 拒绝成为 error 结果，不抛异常；core 契约单测覆盖。

### P1 手机操控闭环

问题：只有无障碍/Shizuku offload CLI 原料，没有"读屏→决策→点按"的
操控循环和产品化安全门。

验收：
- phone 工具组：读屏（无障碍树+OCR 兜底）、点按/滑动/输入、截图。
- 独立安全门：三档确认 + App 名单（支付类默认拒绝）+ 密码/系统设置
  界面硬拦截；解除限制需显式长按授权且会话级失效。
- 不打扰模式（Shizuku 虚拟屏，Android 14+）为可选增强，可后置。
- shell 类原生执行逐条确认，超时与输出上限明确。

### P2 全双工语音通话

验收：VAD 开口即停 TTS（barge-in）、通话态 UI、延迟可接受；
复用现有 ASR/TTS 提供商，不新增供应商依赖。

### P3 子代理与团队（需动 core:agent）

验收：
- core:agent 支持子循环派生：层数/并发上限、模型跟随父会话、
  子代理结果以工具结果回填父循环。
- UI：谱系树；团队模式（成员/任务板含依赖与写入范围/消息）可分期。
- 子代理继承且只能收紧父会话权限；单测覆盖派生/终止/权限继承。

### P4 远程主机（单独立项，最大架构改动）

验收：新增"引擎后端"抽象（本机/远程），SSH 传输先行；
会话状态断线重同步（快照+序列号折叠）；密钥走 Android secure storage。
加密隧道（Noise/WebRTC/中继）为二期。

### P5 生态与增值（按产品定位取舍）

- 插件：声明式 UI 贡献（JSON 视图原生渲染，不执行插件代码）+ 市场。
- 会话分享：E2E 加密链接（密钥在 URL fragment）+ 分享卡。
- 归档可撤销/安全删除、RRULE 定时、主题+壁纸取色、附件规格
  （每条 ≤9 个、单文件 200MB）、平板自适应。
- 酒馆/桌宠：娱乐向，默认不做，除非产品定位要求。

## 工程配套（随各阶段顺带落地）

- rootfs 分层升级事务 + SafeMode 救援（对标 RuntimeInstaller/SafeMode）。
- 诊断信息脱敏打包导出。
- 沙箱资产版本化：对标"补丁链+双哈希锁定"，对 deps/scripts 产物
  做清单+哈希校验，升级可回滚。

## 顺序依据

P0/P1 复用 offload 通道与三级权限地基，性价比最高；P2 零件齐全只缺编排；
P3 动 core 契约需完整单测；P4 涉及架构抽象放最后。

## 已完成的功能细节优化（2026-11-16）

- P0 逐调用授权卡：强制审批门 enforceAlwaysAsk（BYPASS 不可跳过、
  只紧不松）、FIFO 审批队列修复并发覆盖、120s 超时兜底、会话删除
  清理临时授权。14 项单测。
- 压缩 token 估算 usage 锚定：Assistant 消息携带提供商真实用量
  （Anthropic 含缓存计数、OpenAI 兼容 include_usage），压缩触发
  判断锚定真实计数 + 增量估算，消除中文 chars/4 约 4 倍低估导致的
  漏压缩；无锚回退原启发式，失败原样返回契约不变。app 侧摘要入口
  注入 lastTurnContextTokens 锚点。core 12 项 + app 3 项新单测。
- 定时任务 agent 侧编辑：minis-scheduled update --id 字段级合并
  （只改传入旗标，id/createdAt/运行历史保留），走强制逐次授权；
  合并逻辑纯函数化（ScheduledTaskMerge）15 项单测。
- 顺手修复：编辑器保存任务不再清空运行历史（buildTask 遗漏字段）。

## 第二轮细节修复（2026-11-16）

- 终端 CR 折叠：列缓冲覆盖模型替代"最后一段全赢"，进度条/wget
  尾部不再丢失；ANSI 先剥离再折叠（零宽不占列）；去掉末尾 trim
  保住覆盖残留空格。4 个存量红测转绿。
- 会话持久化：JSONL 编解码补齐 assistant.toolCalls 与 usage，
  fork/重载后工具链完整（旧格式行兼容）；2 项新单测。
- 定时任务 CLI：错误统一 {error,message} JSON 信封 + --compact/-q
  整形，退出码语义不变（2=参数错，1=运行失败）。
- OpenAI 非流式兼容：sendMessage 恢复真 stream=false 请求；
  网关忽略 stream=true 回整块 JSON 时也能解析（choices[].message
  → 文本/工具/用量/终止事件）；仅 "[400]…stream" 拒绝才回退流式
  聚合；非流式入口补思考档位钳制。14 个存量红测转绿。
- 空响应守卫：收到 Finished 终止事件即视为正常结束（含空 choices
  无 finish_reason 的合法空回复），真断流（无任何终止事件）仍报错。
- 用量口径统一（T-usage-full-input-contract）：inputTokens 一律
  为完整 prompt（含缓存），cacheRead/cacheWrite 为子集单独上报；
  Anthropic 补齐缓存计数进 latestContextTokens（修复高缓存命中
  时上下文压力低估）；统计页/用量面板/压力回退不再重复加缓存，
  命中率分母修正。

剩余存量测试失败（24 个，全部同一根因）：AnthropicProviderTest
缺 app/provider-customization.properties（21 直接报缺配置，3 个
错误映射用例被同一初始化异常波及）。该文件含 OAuth 标识提示词，
需项目所有者按 .example 自行填写，不代填。

## P4 远程主机进度（2026-11-17）

PR #19（已合并 main）落地切片 1–3：
- 切片 1：SSH 远程执行后端（RemoteHostManager 加密存储 + TOFU +
  remote_shell 工具 + minis-remote CLI，每次强制审批）。
- 切片 2：SSH 密钥存储 fail-closed（createFailClosed，绝不落明文）+
  SessionResync 断线重同步纯核心（快照+序列号折叠）。
- 切片 3：执行后端抽象（会话绑定远程主机，shell_execute 自动路由，
  /remote 命令；循环留手机，仅执行后端可切换）。

PR #20（feat/p4-remote-session）切片 4：
- 远程会话客户端：RemoteSessionProtocol 线协议（快照/事件帧 +
  send/stop/resync/snapshot 请求）+ 可折叠状态模型；SessionTransport
  可插拔接口 + RemoteSessionClient 重连客户端（复用 SessionResync，
  小间隙增量/大间隙全量）；SshSessionTransport 跑在加密 TOFU SSH
  exec 通道上；SshTransport.openVerifiedClient 复用验证连接。15 单测。

PR #20 切片 5+6（2026-11-17）：
- 远程会话 SERVER：assets/remote/minis-session-server.py（纯标准库
  python3，主机零依赖部署）。OpenAI 兼容 chat-completions 循环 +
  主机侧 shell 工具（超时 124、50k 头尾截断）、限轮数、可停止、
  序列号事件 + 快照 + resync（日志淘汰自动快照愈合）。折叠语义与
  Kotlin 客户端逐条对齐。23 个 python unittest（假 LLM/shell，无网络）。
- minis-remote deploy：上传服务端 + chmod + 符号链接，强制审批；
  SshTransport.uploadTextFile 复用验证连接。
- /remote session <hostId>：强制审批后连接远端引擎，折叠状态渲染进
  聊天消息（running/idle、工具结果、错误），断线自动重连 ≤5 次退避；
  /remote session send、/remote stop、/remote off。

P4 剩余（明确延后）：
- Noise/WebRTC 传输后端：SSH 已强加密+主机认证（TOFU），该后端仅
  服务免 SSH 中继场景；SessionTransport 抽象已就位，届时插入即可，
  不现在手写安全敏感的 Noise 栈。
- 远程引擎的完整工具面（文件/浏览器等）与多会话并发：当前服务端
  仅 shell 工具、单会话，属后续增强。

## P5 已落地（2026-11-17，PR #20）

- RRULE 定时重复（RFC 5545 子集）：DAILY/WEEKLY/MONTHLY/YEARLY +
  INTERVAL/BYDAY/BYMONTHDAY/COUNT/UNTIL；不支持的部分显式拒绝，
  绝不按错误日期触发；COUNT 从序列起点计数（测试抓出的真缺陷）。
  create/update --rrule，update --rrule none 清除。17 单测。
- 端到端加密会话分享：AES-256-GCM 设备端加密，自解密 HTML（WebCrypto，
  与 Kotlin 字节兼容），密钥只进剪贴板绝不进文件；防篡改/截断/错钥
  全部失败为 null。9 单测。托管链接模式可复用 EncryptedShare。
- 诊断脱敏打包：保守脱敏规则（凭据/令牌/JWT/邮箱/电话/家目录），
  凭据类文件名直接排除，2MB/16MB 上限，用户显式分享不自动上传。
  12 单测。
- 沙箱资产清单 + 启动哈希校验：23 个资产 SHA-256 钉住，python 生成器
  与 Kotlin 校验器互操作测试；漂移即跳过覆盖并告警，不静默执行被改
  代码。8 单测。

P5 明确不做/延后（取舍记录）：
- 归档可撤销/安全删除：需 DB 迁移与回收站语义，独立切片。
- 主题+壁纸取色、平板自适应：纯 UI 工程，独立切片。
- 附件规格（每条 ≤9、单文件 200MB）：现有管线已有预算制，规格对齐
  属产品决策。
- 插件声明式 UI + 市场、酒馆/桌宠：生态/娱乐向，按产品定位默认不做。
- rootfs 分层升级事务 + SafeMode：涉及安装器重构，独立立项。