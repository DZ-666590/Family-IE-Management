# 当前分支自动部署

## 日常使用

向 `codex/family-finance-stage-2` 执行 `git push` 后，GitHub Actions 中的 **Stage 2 CI and deploy** 自动运行：

1. 检查部署安全逻辑、前端类型和测试，构建前端。
2. 执行 Java 测试，打包包含前端的 Spring Boot JAR，写入提交版本标记。
3. 保存压缩包与 SHA-256 校验值（GitHub 制品保留 7 天）。
4. 使用专用 SSH 密钥传输至现有开发服务器，校验内容和提交号。
5. 备份旧 JAR、原子替换、重启 systemd；最多等待 150 秒，检查前端、CSRF API 与实际版本。
6. 从 GitHub runner 再检查公网版本和 API。Actions 全部成功才算本次发布通过。

本地 `git commit` 不会触发，必须推送至 GitHub。其他分支不部署；向本分支提交的 PR 只检查，不获取部署 Secrets。密集推送时只保证最新提交部署，过时任务可能被跳过；正在部署的任务不会被新推送强制取消。

这是单实例重启部署，会有短暂不可用，并非零停机。前端改版及可靠性修复已归入 Stage 2，不再单独维护 Clarity 开发分支。

## 首次配置（维护者）

仓库工作流：`.github/workflows/deploy-stage2.yml`。

GitHub **Settings → Environments → development** 只允许 `codex/family-finance-stage-2` 部署，并设置以下环境 Secrets：

| 名称 | 内容 |
| --- | --- |
| `DEPLOY_HOST` | 服务器地址，仅填写主机名/IP |
| `DEPLOY_PORT` | SSH 端口 |
| `DEPLOY_USER` | 现有服务部署用户 |
| `DEPLOY_SSH_KEY` | 本项目专用 Ed25519 私钥，不使用个人登录密钥 |
| `DEPLOY_KNOWN_HOSTS` | 经管理员核对的服务器 SSH 主机公钥记录 |
| `DEPLOY_URL` | 公网访问根地址，不含末尾斜杠 |

禁止将真实服务器地址、密码、私钥写进仓库。数据库配置继续留在服务器原有环境文件，不经过 GitHub。

服务器应已有 Python 3、systemd、Java 17、Nginx，以及可正常运行的应用。由管理员审核并将 `scripts/ci_deploy.py` 安装为 root 所有的 `/usr/local/sbin/family-finance-ci-deploy`（0755），将以下配置保存为 `/etc/family-finance/ci-deploy.json`（root:root、0600）：

```json
{
  "jar": "/root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar",
  "state": "/var/lib/family-finance-ci",
  "service": "family-finance.service",
  "base_url": "http://127.0.0.1"
}
```

路径只是沿用现有服务布局；迁移环境先核对实际 `ExecStart`。安装前检查是否存在旧配置，不要盲目覆盖。

专用公钥的 `authorized_keys` 记录采用：

```text
restrict,command="/usr/bin/timeout --kill-after=210 600 /usr/local/sbin/family-finance-ci-deploy" ssh-ed25519 PUBLIC_KEY family-finance-github-actions
```

此密钥不能开 shell、PTY、端口转发或 SFTP，只接收格式严格的部署命令和压缩 JAR。脚本更新需要管理员通过正常管理连接审核安装，CI 不会自动更新服务器上的部署脚本。

**信任边界：**获准推送部署分支的人可以部署程序代码，并获得应用进程本身的权限。现有应用以 root 运行，限制 SSH 命令并不等于隔离恶意应用代码。仅允许可信维护者写入该分支；生产化时应另行迁移至非 root 应用账户并配置分支审核保护。

## 失败与恢复

- 检查或打包失败：不接触服务器。
- 上传中断、校验不一致、提交标记不符：不替换旧 JAR。
- 启动/本机健康检查失败：自动恢复旧 JAR、重启并检查恢复结果。任务保持失败，不能把回退当发布成功。
- 公网检查失败但服务器本机正常：任务失败，保留已部署版本；检查安全组、Nginx和网络，不因外网故障盲目回退程序。
- 备份位于 `/var/lib/family-finance-ci/backups`；最近成功版本及回退位置记录在 `current.json`。备份不自动删除，维护者定期检查磁盘并按需保留。
- 自动回退仅针对 JAR，**不会撤销 MySQL/Flyway 数据迁移**。不兼容迁移必须先备份数据库并按专门发布方案处理；恢复旧 JAR 不保证能兼容新结构。
- 系统断电、强制杀进程或磁盘损坏不能保证自动恢复。此时由管理员使用记录的备份人工恢复。

GitHub 的 Re-run jobs 可重试当前分支最新提交；旧提交的重跑会被跳过。修改触发分支时，须同步修改工作流中的分支过滤、部署判断、最新提交检查和环境分支白名单，避免旧版本覆盖。

## 验证命令

```bash
python3 -m unittest discover -s scripts/tests -v
cd frontend
npm ci
npm run typecheck
npm test -- --run
npm run build
cd ..
./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true verify
```

本地验证不使用服务器数据库。自动部署是否成功，以实际 Actions 记录和公网返回的提交号为准。

参考：[GitHub 部署环境及并发控制](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments)。
