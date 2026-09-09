# 在服务器配置 Qwen 3.8 Max

以下是供用户亲自执行的步骤，本任务没有连接或修改服务器。必须先通过项目的统一部署流程部署包含共享 AI 配置的版本，旧版本不会识别本指南中的配置。

## 1. 在阿里云百炼准备信息

登录阿里云百炼中国站，在北京地域/对应业务空间确认 `qwen3.8-max` 可调用，并开通按量付费、准备余额及费用预警。创建该地域/业务空间的 API Key，同时复制控制台给出的 OpenAI 兼容 Base URL。

当前官方北京格式：`https://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`，将 `<WorkspaceId>` 换成实际业务空间 ID；直接复制控制台地址最稳妥。API Key 和地址必须属于同一地域/业务空间。参考：[获取 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)、[官方接入地址](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)。

不要把 Key 发到聊天、截图、Git、终端命令参数或浏览器页面。编辑器中填写不会进入 shell 命令历史。

## 2. 创建独立的秘密配置文件

通过你平时的 SSH 方式登录服务器，然后执行（不会覆盖原数据库配置文件）：

```bash
sudo mkdir -p /etc/family-finance
sudo touch /etc/family-finance/ai.env
sudo chown root:root /etc/family-finance/ai.env
sudo chmod 600 /etc/family-finance/ai.env
sudo nano /etc/family-finance/ai.env
```

在编辑器填写下列两行，替换占位内容。若文件已有配置，请编辑对应行，不重复添加：

```dotenv
DASHSCOPE_API_KEY="在这里填写你自己的真实Key"
APP_AI_BASE_URL="https://你的业务空间ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
```

nano 中 `Ctrl+O`、回车保存，`Ctrl+X` 退出。模型已经在代码中固定为 `qwen3.8-max`，无需再填写模型或加密主密钥。

## 3. 让服务加载这个文件

仓库运维文档的服务名为 `family-finance`；如果你的实际服务名不同，请使用实际名称。创建独立的 systemd 配置片段，保留已有数据库 EnvironmentFile：

```bash
sudo mkdir -p /etc/systemd/system/family-finance.service.d
sudo nano /etc/systemd/system/family-finance.service.d/ai.conf
```

写入：

```ini
[Service]
EnvironmentFile=/etc/family-finance/ai.env
```

保存后，在新版代码已部署且可以短暂重启时执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart family-finance
sudo systemctl is-active family-finance
```

输出 `active` 只证明服务在运行，不证明 AI 密钥已验证。不要运行会打印进程环境、配置文件正文或 Authorization header 的诊断命令来分享排错信息。

## 4. 在网页确认

重新登录 → 个人中心 → 账号设置 → AI 服务。应显示“阿里云百炼 / qwen3.8-max / 服务器已配置”。勾选目录请求确认后点“测试系统连接”；只有这一步才会请求服务商，计入额度。

- 等待管理员配置：检查文件是否加载、变量名、Key 是否为空、地址是否正确；无需在网页输入 Key。
- 连接失败：检查服务器能否访问百炼、Key 地域是否匹配，以及服务商权限；不要把 Key 贴出来。
- 目录未列出模型：目录可访问，但尚不能确认模型调用权限。到百炼控制台确认模型；本模块不会自动用收费推理替代测试。
- 系统不完整响应/超时：后续文档解析时减少每次页数并重试，避免将半份结果录入贷款。

当前尚没有贷款文档上传入口。配置成功表示共享 AI 服务已准备好供业务模块使用，不能据此认定贷款合同解析功能已经上线。

## 替换或撤销密钥

编辑同一个 `ai.env` 后重启服务。若密钥泄露，先在百炼撤销该 Key，再替换文件内的值。停止 AI 使用可将 Key 置空并重启；财务功能继续运行。

当前每用户每日 20 次、每进程每日总计 200 次，重启会重置计数。共享调用费用计入你的百炼账户；配合平台费用管理使用。
