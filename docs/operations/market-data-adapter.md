# 行情适配器运维说明

该适配器只提供公开 A 股目录和只读日线，不接收家庭、账户、交易或凭据。进程固定监听 `127.0.0.1:8091`，北交所 K 线返回 `supported=false`。当前应用发布流水线只交付 JAR，不会自动安装或更新此 Python sidecar。

## 首次安装

以下路径是通用部署约定，不包含真实服务器连接参数。以具备 sudo 权限的运维账号执行：

操作系统需预先提供 Python 3.10 及对应的 `python3.10-venv` 包；本次交付不会自动安装系统软件包。

```bash
sudo useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin family-finance-market
sudo install -d -o family-finance-market -g family-finance-market /opt/family-finance/market-data
sudo install -o family-finance-market -g family-finance-market -m 0755 scripts/market-data/server.py /opt/family-finance/market-data/server.py
sudo install -o family-finance-market -g family-finance-market -m 0644 scripts/market-data/requirements.txt /opt/family-finance/market-data/requirements.txt
sudo -u family-finance-market python3.10 -m venv /opt/family-finance/market-data/.venv
sudo -u family-finance-market /opt/family-finance/market-data/.venv/bin/python -m pip install -r /opt/family-finance/market-data/requirements.txt
sudo install -m 0644 docs/operations/market-data-adapter.service /etc/systemd/system/family-finance-market.service
sudo systemctl daemon-reload
```

依赖必须安装在隔离 venv 中，禁止全局 pip 安装。`requirements.txt` 固定 AKShare 1.18.88 与 BaoStock 0.9.3；两者仍会带来传递依赖，因此生产安装前应在受控构建环境生成并归档完整 lock/hash 清单。

安装完成后服务仍保持未启用状态。先做一次前台验证：

```bash
sudo -u family-finance-market /opt/family-finance/market-data/.venv/bin/python /opt/family-finance/market-data/server.py
curl --fail --max-time 60 http://127.0.0.1:8091/directory
curl --fail --max-time 30 'http://127.0.0.1:8091/candles?symbol=600000.SH&adjust=none'
```

确认响应后由运维人员单独决定是否执行 `systemctl enable --now family-finance-market`，并为 Spring Boot 明确配置 `MARKET_DATA_URL=http://127.0.0.1:8091` 后再重启业务服务。未配置时目录状态为 `DISABLED`，不会调用 sidecar。

## 可重复更新与回滚

更新时先在临时目录建立新 venv、运行仓库内 Python 单元测试和两个只读 HTTP 探针，再原子替换 `server.py`、`requirements.txt` 与 `.venv`。随后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart family-finance-market
sudo systemctl status --no-pager family-finance-market
```

保留上一版目录和 venv；失败时恢复上一版文件并重启 sidecar。业务数据库目录同步是增量发布：相同 `tsCode` 更新名称和验证标记但保留证券 ID，完整目录失败不会覆盖上一版引用。

## 运行边界

- `/directory` 只在完整、无重复且字段合法时刷新缓存；失败可返回标记 `stale=true` 的上一版。
- `/candles` 只接受六位代码加 `.SH/.SZ/.BJ` 以及 `none/qfq`，每个代码与复权方式独立缓存。
- 上游调用运行于有硬超时的子进程，BaoStock 会话串行，适配器限制并发处理线程和缓存容量。
- 日线截止到前一上海自然日，避免把尚未结束的交易日写入结果；成交量单位为股，成交额单位为元。
- AKShare 的声明用途偏向学术研究。本课程项目之外的商用或数据再分发必须重新完成授权审查。
