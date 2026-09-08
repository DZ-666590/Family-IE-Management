# 行情适配器运维说明

该适配器提供公开 A 股目录、A 股只读日线，以及独立缓存的港股/美股只读目录和未复权日线；不接收家庭、账户、交易或凭据，也不写入证券、交易、报价快照或现金账本。进程固定监听 `127.0.0.1:8091`，北交所 K 线返回 `supported=false`。本版流水线将 JAR 与 Python sidecar 作为同一发布包交付；首次服务器引导仍由运维另行执行。

## 安装、更新与回滚

本版使用 root 所有的不可变 `releases/<commit>-<run>-<id>` 目录、`current` 符号链接与按 requirements SHA-256 标识的独立 venv。完整的首次安装、旧目录引导、依赖准备、发布和恢复命令见 [版本化发布操作说明](versioned-market-release.md)。不要继续单独复制 server.py 或只更新 JAR。

新 unit 通过 `current/.venv/bin/python current/server.py` 启动，保留原低权限用户及全部 systemd 加固项。进程仍只监听 loopback，不新增反向代理路由或放开防火墙。

依赖必须在隔离 venv 中准备。当前 requirements 固定 AKShare 1.18.88 与 BaoStock 0.9.3；海外功能还使用 requests、openpyxl、pandas、py-mini-racer。完整传递依赖及包哈希由运维在受控 Linux 环境锁定并归档；接收器不会运行 pip 或任何上传的安装脚本。已投入使用的 runtime 不得原地升级。依赖变化应提交新的 requirements，并预备新的 runtime。

`/health` 返回启动时校验的提交号与文件完整性状态。未版本化的本地脚本返回 503；这不影响原目录或日线接口，但不能通过新版发布门禁。目录首次请求可能为 SYNCING，发布门禁会重试，只有 HK/US 搜索 READY 且两者日线非空才接受。

## 运行边界

- `/directory` 只在完整、无重复且字段合法时刷新缓存；失败可返回标记 `stale=true` 的上一版。
- `/candles` 只接受六位代码加 `.SH/.SZ/.BJ` 以及 `none/qfq`，每个代码与复权方式独立缓存。
- `/overseas/search` 只接受 `market=HK|US` 和最长 80 字符的 `q`。HK 与 US 后台目录任务相互独立，完整校验成功后才原子发布；24 小时后后台刷新，失败至少间隔 60 秒重试，最多保留 7 天并标记 stale。
- `/overseas/candles` 只接受目录中已验证的代码，固定返回 `source=SINA`、`adjustment=none`。港股使用 `Asia/Hong_Kong`，美股使用 `America/New_York` 将交易日标签构造成当地午夜，并排除当地今天及未来数据。
- 海外目录缓存与 K 线缓存彼此独立，也与 A 股状态隔离。K 线缓存 6 小时、最多 256 项，同代码请求合并且并发有界；失败最多回退 7 天 stale 数据，不会发布畸形 OHLC 或部分目录。
- 上游调用运行于有硬超时的子进程，BaoStock 会话串行，适配器限制并发处理线程和缓存容量。
- 海外 SINA 解码也运行于 20 秒隔离子进程，输出上限 20 MB；V8 在创建上下文前固定为 `--single-threaded --jitless`。只执行已安装 AKShare 的解码常量，远端响应仅作为字符串参数，禁止执行远端 Python/JavaScript 源码。
- 日线截止到前一上海自然日，避免把尚未结束的交易日写入结果；成交量单位为股，成交额单位为元。
- AKShare 的声明用途偏向学术研究。本课程项目之外的商用或数据再分发必须重新完成授权审查。
