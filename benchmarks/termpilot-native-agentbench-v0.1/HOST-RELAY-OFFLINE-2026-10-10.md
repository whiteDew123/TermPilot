# 浏览器、多轮宿主中转与用量校准：代码和离线验证（2026-10-10）

本轮按用户要求完成代码和离线验证，没有调用真实模型 API，没有启动 Docker，没有提交或推送。28/28 题仍是材料原型：`finalReady=false`、`NOT_INTEGRATED`、`formalScores=null`、`publishable=false`。不能据此发布正式榜单。

## 已完成的行为

| 项目 | 具体行为 |
|---|---|
| D5 浏览器 | 宿主每题新建无登录态 Chrome，通过 TermPilot 原生 MCP 工具导航、读网页快照、填表、提交及读回执；保存实际 DOM 状态、操作、网络拒绝与全部原生工具结果 |
| 浏览器限制 | 仅允许本题随机本地端口的固定页面，不提供任意脚本；页面中的恶意网址不能扩大工具权限；重复提交、清空记录、越权访问即使最后答案正确也判失败 |
| E3/E4 多轮 | 同一个原生 Agent 接收宿主冻结的 9 轮用户输入；逐轮工具、请求、结果及摘要调用留证，中转协议 v13 对上下文观测逐条确认 |
| E4 压缩 | 第 9 轮前真正调用原生压缩；保存摘要请求、压缩前后视图、只追加的历史及 `view_checkpoint`，独立核对后再计分 |
| 全题预算 | 换轮不刷新预算；E3 总预算 1,000,000 Token，E4 为 300,000；正常请求最多 48 次，包含摘要请求；预算耗尽后仅允许一次无工具收尾；单次上下文 1,000,000、输出 16,384 |
| 用量校准 | 离线准备同一候选 jar 的完整 E3 长历史请求；确认后对 DeepSeek V4 Flash 和 GLM-5.3-Flash 各调用一次，检查真实 usage、模型身份和 60k–100k 输入范围 |
| 校准凭证 | 绑定题目源、候选 jar、完整请求、两个固定模型、运行日期及原始记录摘要；缺失、过时或合成凭证不能准备正式批次；不自动重试、不覆盖旧调用 |

`FormalBrowserBinding` 是把固定题目与宿主浏览器实例对应起来的代码；`FormalContinuationBinding` 负责固定的多轮输入和上下文证据；`UsageCalibrationMain` 提供用量校准命令。新增入口已接到正式请求准备、Docker Worker 中转和统一证据输出，但这些生产路径还没有启动 Docker 验证。

浏览器的 Python 和 Chrome 可执行文件使用规范绝对路径，记录文件 SHA-256，并在操作前检查。Python Playwright 包和完整浏览器环境仍需随正式运行环境一起冻结；本轮本地 Chrome 对照不能代替容器验收。

## 离线验证

最终针对性回归：**184 项，183 项通过、1 项跳过，失败 0、错误 0**。跳过项为 E1 可选保留样例，没有设置 `termpilot.test.e1.prototype.output`。包含 28 题生成器、原生浏览器/多轮对照、新旧协议、准备入口、使用假进程的 Docker Worker 测试以及供应商重试规则测试。

扩大回归中的原有失败通过单独解包当前 Git 提交后逐类对照。以下失败名单与旧提交相同，未计入通过范围：

| 测试类 | 原有失败数 | 原因范围 |
|---|---:|---|
| E1IndependentReplayTest | 54 | 旧测试对工具结果正文与文件读取格式的假设 |
| F4IndependentReplayTest | 21 | 将包有不可信数据标记的工具正文当作裸 JSON |
| D1McpRelayTest | 1 | 同上，最终结果比较不匹配 |
| D2McpRelayTest | 3 | 同上，固定测试响应解析失败 |
| D3IndependentReplayTest | 5 | 同上，固定测试响应解析失败 |
| D4IndependentReplayTest | 13 | 模型观察到的包装正文与原始工具正文摘要不同 |
| FormalBatchRunnerTest | 3 | 既有 D4 合成批次在第 21 题停止 |
| F2CommandAuditTest | 1 | TEAM 不兼容绑定的错误类型预期不匹配 |

总计 101 个原有失败。新版协议导致的 D3 两轮字段兼容遗漏及 F3 版本/工具集枚举测试已修复。这里不声称全仓库回归通过。

## 保存与后续运行

最新私有快照：`/Users/termpilot/.termpilot/benchmark-datasets/2026-10-10-host-relay/`。保留上一份 `2026-10-10-28-cases` 快照，不覆盖历史结果。

新快照保存题目源、私有种子、生成器 jar、可复现归档、逐文件校验清单、8 个正确/错误对照及校准准备请求。对照分数是固定响应下的验题结果，不能称为模型成绩。校准准备状态仍是 `PREPARED_NOT_CALIBRATED`，不会产生 `proof.json`。

先做离线准备的命令如下；输出必须是全新的 Git 外目录，父目录权限 0700：

```bash
java -cp /absolute/candidate.jar com.termpilot.eval.benchmark.UsageCalibrationMain \
  prepare /absolute/private-source /absolute/candidate.jar /absolute/new-calibration 2026-10-10
```

真实调用入口是 `UsageCalibrationMain execute-approved <prepared-root>`。**本轮没有执行该命令**，后续须先获得用户确认；改变候选 jar、题目源或运行日期后不能沿用旧凭证。生产批次通过 `--usage-calibration <private-proof.json>` 指定真实凭证。

真实用量校准、Docker 验证、Judge 校准、Worker 镜像及完整运行环境冻结、整套准入和 168 次正式批次仍待完成。模型记录继续使用 `deepseek-v4-flash` 与 `glm-5.3-flash`，不随产品默认模型名称改变。

题目源 569 个文件，完整树 SHA-256 为 `2465b3acd3fdd25941780afd80a3fe618ba1f86777cfae3d4aada2851b23e84d`；归档 SHA-256 为 `ff170a142b182c9813c420e27393fd4de0c0a72b5c98c8316206844a46b8fb95`。归档逐文件读回和上一份快照未变检查通过，打包与受信任入口包检查通过（120 个包内条目）。

后续用户已授权真实校准与 Docker，并改用 `deepseek-flash`、暂停 GLM。本文件保留当时的离线阶段记录，后续结果见 [真实校准与 Docker 验证记录](LIVE-CALIBRATION-DOCKER-2026-10-10.md)。
