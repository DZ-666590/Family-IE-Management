# 行情适配器运维说明

该适配器提供公开 A 股目录、A 股只读日线，以及独立缓存的港股/美股只读目录和未复权日线；不接收家庭、账户、交易或凭据，也不写入证券、交易、报价快照或现金账本。进程固定监听 `127.0.0.1:8091`，北交所 K 线返回 `supported=false`。当前应用发布流水线只交付 JAR，不会自动安装或更新此 Python sidecar。

## 首次安装

以下路径是通用部署约定，不包含真实服务器连接参数。以具备 sudo 权限的运维账号执行：

操作系统需预先提供 Python 3.10 及对应的 `python3.10-venv` 包；本次交付不会自动安装系统软件包。

```bash
sudo useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin family-finance-market
sudo install -d -o family-finance-market -g family-finance-market /opt/family-finance/market-data
sudo install -o family-finance-market -g family-finance-market -m 0755 scripts/market-data/server.py /opt/family-finance/market-data/server.py
sudo install -o family-finance-market -g family-finance-market -m 0644 scripts/market-data/overseas.py /opt/family-finance/market-data/overseas.py
sudo install -o family-finance-market -g family-finance-market -m 0755 scripts/market-data/overseas_sources.py /opt/family-finance/market-data/overseas_sources.py
sudo install -o family-finance-market -g family-finance-market -m 0644 scripts/market-data/requirements.txt /opt/family-finance/market-data/requirements.txt
sudo -u family-finance-market python3.10 -m venv /opt/family-finance/market-data/.venv
sudo -u family-finance-market /opt/family-finance/market-data/.venv/bin/python -m pip install -r /opt/family-finance/market-data/requirements.txt
sudo install -m 0644 docs/operations/market-data-adapter.service /etc/systemd/system/family-finance-market.service
sudo systemctl daemon-reload
```

依赖必须安装在隔离 venv 中，禁止全局 pip 安装。`requirements.txt` 固定 AKShare 1.18.88 与 BaoStock 0.9.3；海外目录解析和 SINA 解码直接使用其固定依赖 `requests`、`openpyxl`、`pandas` 以及 Linux 上的 `py-mini-racer`。不得单独升级这些传递依赖；生产安装前应在受控构建环境生成并归档完整 lock/hash 清单，并确认锁定结果包含上述四项。

安装完成后服务仍保持未启用状态。先做一次前台验证：

```bash
sudo -u family-finance-market /opt/family-finance/market-data/.venv/bin/python /opt/family-finance/market-data/server.py
curl --fail --max-time 60 http://127.0.0.1:8091/directory
curl --fail --max-time 30 'http://127.0.0.1:8091/candles?symbol=600000.SH&adjust=none'
curl --fail --max-time 5 'http://127.0.0.1:8091/overseas/search?market=HK&q=00700'
curl --fail --max-time 5 'http://127.0.0.1:8091/overseas/search?market=US&q=AAPL'
```

海外目录首次请求是非阻塞的，正常会先返回 `state=SYNCING`；等待后台完整校验后再探测，只有 `state=READY` 且结果包含正确 `currency`、`exchange`、`timezone` 时才能继续 K 线探针：

```bash
curl --fail --max-time 30 'http://127.0.0.1:8091/overseas/candles?market=HK&symbol=00700'
curl --fail --max-time 30 'http://127.0.0.1:8091/overseas/candles?market=US&symbol=AAPL'
```

确认响应后由运维人员单独决定是否执行 `systemctl enable --now family-finance-market`，并为 Spring Boot 明确配置 `MARKET_DATA_URL=http://127.0.0.1:8091` 后再重启业务服务。未配置时目录状态为 `DISABLED`，不会调用 sidecar。

## 可重复更新与回滚

更新时先在临时目录建立新 venv、运行仓库内 Python 单元测试和只读 HTTP 探针，再将 `server.py`、`overseas.py`、`overseas_sources.py`、`requirements.txt` 与 `.venv` 作为同一版本原子替换。随后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart family-finance-market
sudo systemctl status --no-pager family-finance-market
```

保留上一版目录和 venv；失败时恢复上一版文件并重启 sidecar。业务数据库目录同步是增量发布：相同 `tsCode` 更新名称和验证标记但保留证券 ID，完整目录失败不会覆盖上一版引用。

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
