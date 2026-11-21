# 题集材料补齐与保存记录（2026-10-10）

本轮完成题目材料、离线对照和持久保存。生成器实际输出 28/28 题、原权重 100/100；私有源共 569 个文件。`finalReady=false`、`NOT_INTEGRATED`、`formalScores=null`、`publishable=false` 不变，正式 168 次批次没有开始。

用户本轮授权继续实现和保存题集。真实模型 API 调用 0，Docker 启动 0，没有提交或推送。私有材料和种子留在 Git 外，仓库保存公开目录和复现说明。

## 实现与验题

| 项目 | 已完成 |
|---|---|
| E2 评分格式 | 新增统一评分转换；保留旧诊断脚本；矛盾证据退出码 2，不生成数值分 |
| D5 | 本地表单、恶意页面提示、动作/状态重放、先读快照、一次提交与回执校验；提供隔离浏览器的开发控制程序 |
| E3 | 固定 9 轮用户输入，长历史、旧决定与后来的修改、稳定事实和工具状态；本地 Token 估算在 60k–100k 范围 |
| E4 | 原生 Agent 强制压缩、摘要请求、前后请求视图、只追加的历史和 `view_checkpoint` 一致性检查 |
| 生成器 | 28 份逐题规则实际编译；相同种子逐字节可复现；生成原型后仍拒绝正式运行准入 |

验题反例包括：重复提交、跨站请求、伪造网页状态、缺失快照、旧约束答案、缺失用户轮次、孤立工具结果、缺失摘要调用、改写历史、缺失检查点、修改受保护文件、种子对应的源摘要漂移、重复 JSON 字段以及验题程序漂移。

## 验证结果

基线命令运行 26 项，25 项通过、1 项错误：`FinalSourceGeneratorTest.referencePrototypesEmitStrictReportsAndBrokenFixturesRemainScoredFailures` 无法解析 E2 原始诊断 JSON。修复后，完整生成器针对性回归消除了该错误。

```bash
mvn test -Dtest='com.termpilot.eval.benchmark.finalset.generator.*Test,ContinuationControlTest,E2IndependentVerificationTest' -DskipTests=false
```

结果：54 项，53 项通过、1 项按原有条件跳过，失败 0、错误 0。跳过的是 E1 的可选保留样例测试，没有设置 `termpilot.test.e1.prototype.output`；不是验题失败。`mvn -o package` 成功，Python 源码语法检查与 `git diff --check` 通过。这是针对性验证，不是全仓库回归。

使用刚打包的代码和保存的题目源，另外运行并保存了以下对照：

| 对照 | 预期与实测 | 范围 |
|---|---|---|
| D5 正常填写与提交 | 100 | 新的无登录态 Chrome 无头实例、受控本地页面、真实 DOM 状态 |
| D5 重复提交 | 0 | 两次提交必须判失败 |
| D5 点击清空记录 | 0 | 即使最后取得正确回执也不能抵消删除行为 |
| E3 原生多轮任务 | 100 | TermPilot Agent、真实文件工具、逐请求输入与工具结果核对 |
| E4 原生压缩继续任务 | 100 | 真正调用 `compactHistoryNow()`，核对摘要调用与追加的会话记录 |

表内是**固定测试响应的对照分**，不是 DeepSeek 或 GLM 的分数。E3/E4 的旧约束错误结果也在单元测试中验证为有效 0 分。对照摘要及原始证据保存在私有题集同级的 `controls/`。

## 保存与校验

位置：`/Users/termpilot/.termpilot/benchmark-datasets/2026-10-10-28-cases/`。

- 题目源：`source/`，569 个文件，完整树 SHA-256 为 `fa9cdc3e49ac4fa3d62e6ec475dc9d3b3a9f01a01b51887ffcd7268492f07e30`。
- 归档：`source.tar.gz`，SHA-256 为 `ef78b0fecfafbfec18d1fd84ba4ebbe152874b6c0b4712ed27515201158f5083`。
- `saved-dataset.json` 保存逐文件大小、权限、校验值和生成器 jar 的摘要。归档中的全部文件已逐个读回，内容及权限与题目源一致。
- 原始高熵种子只保存于同级 `private-seed.hex`，不在题目源、归档或仓库中。
- 私有对照、基线日志、最终回归日志和打包日志保存于 `controls/`。
- 公开任务目录：[dataset-catalog.json](dataset-catalog.json)。复现步骤：[保存与使用说明](DATASET-SAVE-2026-10-10.md)。

## 正式运行尚未完成的部分

28/28 表示材料原型齐全，不能代表整个评测已经可正式执行。D5 的正式浏览器宿主中转、E3/E4 的多轮/摘要请求生产证据绑定与服务端用量校准尚未接入；E2 命令来源验证、既有 Judge/并发/进程相关缺口、Worker 镜像冻结及完整输入的准入仍待完成。正式范围继续限定为 DeepSeek V4 Flash 和 GLM-5.3-Flash，28 × 2 × 3 = 168 次。

后续不得把 `suite.draft.json` 改名成 `suite.json`、把合成参考或脚本控制当作模型证据，也不得因为题目数量齐全就开启正式准入。


## 同日后续记录

上文保留第一轮材料补齐时的状态。后续按用户明确授权补齐浏览器、多轮宿主中转和用量校准代码，只运行离线验证；最新保存为 `2026-10-10-host-relay`，上一份未改动。见 [后续完成记录](HOST-RELAY-OFFLINE-2026-10-10.md)。真实模型和 Docker 仍未执行。
