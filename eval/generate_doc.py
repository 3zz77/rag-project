"""
大文档生成器 — 生成 CloudPay 统一支付网关 API v3 文档
输出约 40KB markdown，上传后产生 30+ 个分块，适合评测 RAG 系统

使用: python eval/generate_doc.py
输出: uploads/CloudPay_API_v3.md
"""
import os

OUTPUT_PATH = os.path.join(os.path.dirname(__file__), "..", "uploads", "CloudPay_API_v3.md")

doc = """# CloudPay 统一支付网关 API v3.0

## 1. 概述

CloudPay 统一支付网关是一套企业级支付集成解决方案，支持微信支付、支付宝、银联云闪付、国际信用卡（Visa/Mastercard/Amex）、Apple Pay、Google Pay 等主流支付方式。本文档面向商户技术团队，提供完整的 API 参考、集成指南和最佳实践。

### 1.1 系统架构

CloudPay 采用微服务架构，核心模块包括：

- **支付网关服务 (Payment Gateway)**：统一支付入口，负责请求路由、验签、风控
- **收银台服务 (Cashier Service)**：提供预置收银台 UI 和自定义收银台 API
- **订单服务 (Order Service)**：订单创建、查询、退款、对账
- **通知服务 (Notification Service)**：异步结果通知、Webhook 回调
- **商户管理服务 (Merchant Service)**：商户入驻、配置管理、费率设置
- **风控引擎 (Risk Engine)**：实时交易风险评估、黑名单检查、限额控制
- **清算服务 (Settlement Service)**：T+1 自动清算、分账、财务报表
- **密钥管理服务 (KMS)**：商户密钥托管、证书管理、PCI DSS 合规

### 1.2 环境说明

| 环境 | 基础 URL | 用途 |
|------|----------|------|
| 沙箱环境 | https://sandbox.cloudpay.com/v3 | 开发调试 |
| 生产环境 | https://api.cloudpay.com/v3 | 正式交易 |

沙箱环境提供模拟支付功能，无需真实银行账户即可测试完整流程。沙箱测试卡号：`6222********1234`，CVV：`123`，有效期：任意未来日期。

### 1.3 API 设计规范

所有接口遵循 RESTful 设计原则：

- **请求方式**：GET（查询）、POST（创建）、PUT（修改）、DELETE（取消）
- **数据格式**：请求体和响应体均为 JSON，Content-Type: application/json
- **字符编码**：UTF-8
- **时间格式**：ISO 8601 格式（如 `2025-01-15T14:30:00+08:00`）
- **金额单位**：分（整数），100 = 1.00 元，避免浮点精度问题
- **幂等性**：通过请求头 `Idempotency-Key` 保证，同一 Key 15 天内重复请求返回相同结果
- **分页**：列表接口统一使用 `page`（从1开始）和 `page_size`（默认20，最大100）参数
- **版本控制**：通过 URL 路径 `/v3/` 区分 API 版本，旧版 `/v2/` 仍可用但不再更新

### 1.4 通用响应格式

```json
{
    "code": 0,
    "message": "success",
    "data": {},
    "request_id": "req_20250115_abc123",
    "timestamp": "2025-01-15T14:30:00+08:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 业务状态码，0 表示成功 |
| message | string | 状态描述 |
| data | object | 业务数据 |
| request_id | string | 请求追踪 ID，用于问题排查 |
| timestamp | string | 服务器响应时间 |

### 1.5 通用错误码

| 错误码 | 含义 | 处理建议 |
|--------|------|----------|
| 0 | 成功 | - |
| 10001 | 签名验证失败 | 检查签名算法和密钥配置 |
| 10002 | 商户不存在或已停用 | 确认商户号正确，联系运营 |
| 10003 | 请求参数缺失或格式错误 | 对照文档检查必填字段 |
| 10004 | 认证令牌过期 | 重新获取 access_token |
| 10005 | IP 不在白名单 | 在商户后台添加服务器 IP |
| 10006 | 权限不足 | 检查 API 角色权限配置 |
| 10007 | 请求频率超限 | 降低调用频率或申请提升配额 |
| 20001 | 订单不存在 | 检查订单号 |
| 20002 | 订单状态不允许此操作 | 确认当前订单状态 |
| 20003 | 重复的商户订单号 | 更换 out_trade_no |
| 30001 | 支付金额超过限额 | 单笔限额 5000000（5万元） |
| 30002 | 风控拦截 | 联系风控团队申诉 |
| 30003 | 余额不足 | 提示用户更换支付方式 |
| 40001 | 退款金额超过可退金额 | 检查已退款金额 |
| 40002 | 退款冷却期内 | 支付完成后 30 秒内不可退款 |
| 50001 | 系统繁忙 | 稍后重试，指数退避 |
| 50002 | 下游通道异常 | 自动切换备用通道 |

---

## 2. 认证与安全

### 2.1 认证方式

CloudPay API 支持两种认证方式：

#### 2.1.1 API Key 认证（推荐）

每个商户分配一对 `api_key` 和 `api_secret`。请求时在 Header 中携带：

```
Authorization: Bearer <access_token>
X-Api-Key: <your_api_key>
X-Timestamp: 1705312800
X-Nonce: random_32_char_string
X-Signature: HMAC-SHA256(base64)
```

签名算法：
```
sign_string = api_key + timestamp + nonce + request_body
signature = Base64(HMAC-SHA256(api_secret, sign_string))
```

#### 2.1.2 OAuth 2.0 认证

适用于服务商模式（第三方代商户调用 API）。

获取令牌：
```
POST /v3/oauth/token
Content-Type: application/json

{
    "grant_type": "client_credentials",
    "client_id": "your_client_id",
    "client_secret": "your_client_secret",
    "scope": "payment:write payment:read"
}
```

返回：
```json
{
    "access_token": "eyJhbGciOi...",
    "token_type": "Bearer",
    "expires_in": 7200,
    "scope": "payment:write payment:read"
}
```

Token 有效期 2 小时，过期后需重新获取。建议提前 5 分钟刷新。

### 2.2 安全最佳实践

1. **密钥轮换**：每 90 天更换一次 api_secret，支持双密钥平滑切换
2. **IP 白名单**：生产环境必须配置 API 调用来源 IP 白名单
3. **HTTPS 强制**：所有 API 请求必须使用 TLS 1.2 或更高版本
4. **敏感信息加密**：使用 AES-256-GCM 对持卡人数据（PAN、CVV）进行加密传输
5. **日志脱敏**：日志中自动对卡号、手机号、身份证号等敏感字段做掩码处理
6. **审计追踪**：所有 API 调用记录保存 180 天，可在商户后台查询操作日志
7. **证书锁定**：移动端 SDK 内置 SSL 证书公钥指纹，防止中间人攻击
8. **请求防重放**：timestamp 与服务器时间偏差超过 5 分钟则拒绝，nonce 24 小时内不可重复

### 2.3 密钥管理

商户可在 CloudPay 商户后台 → 开发设置 → API 密钥 中：

- 查看/复制 API Key
- 生成新的 API Secret（旧 Secret 24 小时后自动失效）
- 设置 Webhook 签名密钥
- 下载平台公钥证书（用于验证回调签名）

---

## 3. 支付 API

### 3.1 创建支付订单

最核心的支付接口。支持 8 种支付方式的一键唤起。

```
POST /v3/payments
```

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| out_trade_no | string(32) | 是 | 商户订单号，唯一 |
| amount | int | 是 | 支付金额，单位：分 |
| currency | string(3) | 否 | 货币代码，默认 CNY |
| description | string(128) | 是 | 商品描述，会展示给用户 |
| pay_type | string | 是 | 支付方式，见下方枚举 |
| notify_url | string(256) | 是 | 支付结果回调 URL |
| return_url | string(256) | 否 | 支付完成跳转 URL（H5/PC） |
| expire_time | int | 否 | 订单过期秒数，默认 3600（1小时） |
| attach | string(256) | 否 | 附加数据，原样返回 |
| metadata | object | 否 | 自定义元数据，最多 10 个键 |

**pay_type 枚举值：**

| 值 | 支付方式 | 适用场景 |
|----|----------|----------|
| wechat_pay | 微信支付 | 微信公众号、小程序、H5、APP、扫码 |
| alipay | 支付宝 | 手机网站、APP、扫码、小程序 |
| union_pay | 银联云闪付 | APP、H5、扫码 |
| credit_card | 国际信用卡 | Web、APP（Visa/MC/Amex/JCB） |
| apple_pay | Apple Pay | iOS APP、Safari Web |
| google_pay | Google Pay | Android APP、Chrome Web |
| balance | 余额支付 | 平台账户余额 |
| bank_transfer | 银行转账 | 对公对私转账，T+1 到账 |

**请求示例：**

```json
{
    "out_trade_no": "ORDER_20250115_001",
    "amount": 19900,
    "currency": "CNY",
    "description": "CloudPay 企业版年费",
    "pay_type": "wechat_pay",
    "notify_url": "https://merchant.example.com/callback/payment",
    "return_url": "https://merchant.example.com/order/result",
    "metadata": {
        "user_id": "U10001",
        "plan": "enterprise_annual"
    }
}
```

**响应参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| payment_id | string | CloudPay 支付单号 |
| out_trade_no | string | 商户订单号 |
| status | string | 支付状态 |
| pay_url | string | 支付跳转链接（H5/扫码场景） |
| qr_code | string | 二维码 Base64（扫码场景） |
| created_at | string | 创建时间 |

**支付状态枚举：**

| 状态 | 含义 |
|------|------|
| pending | 待支付 |
| processing | 处理中（风控审核中） |
| success | 支付成功 |
| failed | 支付失败 |
| closed | 订单已关闭（超时或取消） |
| refunded | 已全额退款 |
| partial_refunded | 已部分退款 |

### 3.2 查询支付订单

```
GET /v3/payments/{payment_id}
GET /v3/payments/out-trade-no/{out_trade_no}
```

支持两种查询方式：通过 CloudPay 支付单号或商户订单号。

**响应示例：**

```json
{
    "payment_id": "pay_20250115143000_abc123",
    "out_trade_no": "ORDER_20250115_001",
    "amount": 19900,
    "currency": "CNY",
    "status": "success",
    "pay_type": "wechat_pay",
    "paid_at": "2025-01-15T14:30:05+08:00",
    "channel_trade_no": "4200001234567890",
    "refunded_amount": 0,
    "fee_amount": 119,
    "fee_rate": 0.006
}
```

### 3.3 关闭支付订单

```
DELETE /v3/payments/{payment_id}
```

仅 `pending` 状态的订单可关闭。已支付订单需走退款流程。

### 3.4 支付结果通知

支付完成后，CloudPay 通过 `notify_url` 向商户发送 POST 请求：

**通知内容：**

```json
{
    "event_type": "payment.success",
    "payment_id": "pay_20250115143000_abc123",
    "out_trade_no": "ORDER_20250115_001",
    "amount": 19900,
    "status": "success",
    "paid_at": "2025-01-15T14:30:05+08:00",
    "sign": "HMAC-SHA256 签名",
    "notify_id": "notify_20250115_001"
}
```

**通知重试策略：**

| 重试次数 | 间隔 |
|----------|------|
| 第 1 次 | 15 秒 |
| 第 2 次 | 1 分钟 |
| 第 3 次 | 5 分钟 |
| 第 4 次 | 15 分钟 |
| 第 5 次 | 1 小时 |
| 第 6 次 | 6 小时 |

共重试 6 次，间隔逐级递增。商户返回 HTTP 200 且 body 为 `{"code":0}` 视为通知成功。

---

## 4. 退款 API

### 4.1 创建退款

```
POST /v3/refunds
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| payment_id | string | 是 | 原支付单号 |
| out_refund_no | string(32) | 是 | 商户退款单号 |
| refund_amount | int | 是 | 退款金额（分） |
| reason | string(128) | 否 | 退款原因 |
| notify_url | string(256) | 否 | 退款结果回调 URL |

退款支持部分退款，允许多次部分退款但累计金额不超过原支付金额。部分退款场景下，每次退款前应查询 `refundable_amount` 确认可退金额。

**退款时效：**

| 支付方式 | 到账时间 | 退款有效期 |
|----------|----------|------------|
| 微信支付 | 1-3 个工作日 | 1 年 |
| 支付宝 | 即时到账 | 1 年 |
| 银联云闪付 | 1-5 个工作日 | 180 天 |
| 国际信用卡 | 3-15 个工作日 | 180 天 |
| 余额支付 | 即时到账 | 无限制 |

### 4.2 查询退款

```
GET /v3/refunds/{refund_id}
```

返回退款的详细状态、退款金额、处理时间、渠道退款单号等信息。

---

## 5. 订单管理 API

### 5.1 订单列表查询

```
GET /v3/orders
```

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| start_date | string | 否 | 开始日期（ISO 8601） |
| end_date | string | 否 | 结束日期 |
| status | string | 否 | 订单状态筛选 |
| pay_type | string | 否 | 支付方式筛选 |
| page | int | 否 | 页码，默认 1 |
| page_size | int | 否 | 每页条数，默认 20，最大 100 |

返回 `list`（订单数组）、`total`（总数）、`page`、`page_size`。

### 5.2 订单统计

```
GET /v3/orders/stats
```

返回指定时段内的汇总数据：

```json
{
    "total_count": 1523,
    "total_amount": 12345600,
    "success_count": 1489,
    "success_rate": 0.977,
    "refund_count": 12,
    "refund_amount": 34500,
    "avg_amount": 8106,
    "peak_hour": "14:00-15:00"
}
```

### 5.3 对账单下载

```
POST /v3/bill/download
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| bill_date | string | 是 | 账单日期（yyyy-MM-dd） |
| bill_type | string | 是 | 账单类型：trade（交易）、refund（退款）、settle（清算） |
| format | string | 否 | 文件格式：csv（默认）、excel |

返回 `download_url`，链接有效期 30 分钟。账单包含所有交易明细、手续费、结算金额。

---

## 6. 商户管理 API

### 6.1 子商户入驻

适用于服务商模式（平台类商户发展子商户）。

```
POST /v3/merchants/sub-merchants
```

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string(128) | 是 | 商户名称 |
| short_name | string(32) | 是 | 商户简称（展示给用户） |
| business_type | string | 是 | 经营类型：individual（个人）、enterprise（企业） |
| contact_name | string(32) | 是 | 联系人姓名 |
| contact_phone | string(16) | 是 | 联系人手机号 |
| contact_email | string(64) | 是 | 联系人邮箱 |
| business_license | string | 企业必填 | 营业执照号 |
| legal_person | string | 企业必填 | 法人姓名 |
| settlement_type | string | 是 | 结算方式：T1（T+1到账）、D0（当日到账） |
| fee_template_id | string | 否 | 费率模板 ID |

### 6.2 查询商户余额

```
GET /v3/merchants/{merchant_id}/balance
```

返回：

```json
{
    "available_balance": 9876500,
    "pending_balance": 1230000,
    "frozen_balance": 0,
    "total_balance": 11106500,
    "currency": "CNY"
}
```

| 字段 | 说明 |
|------|------|
| available_balance | 可用余额（可提现） |
| pending_balance | 待结算余额（T+1） |
| frozen_balance | 冻结余额（争议订单） |
| total_balance | 总余额 |

---

## 7. Webhook 配置 API

### 7.1 配置 Webhook

```
POST /v3/webhooks
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| url | string(256) | 是 | Webhook 接收地址 |
| events | string[] | 是 | 订阅事件类型列表 |
| secret | string(64) | 否 | 签名密钥（不填则自动生成） |

**支持的事件类型：**

| 事件 | 说明 |
|------|------|
| payment.success | 支付成功 |
| payment.failed | 支付失败 |
| payment.closed | 订单关闭 |
| refund.success | 退款成功 |
| refund.failed | 退款失败 |
| settlement.completed | 结算完成 |
| merchant.verified | 商户审核通过 |
| risk.alert | 风控预警 |

### 7.2 Webhook 签名验证

每个 Webhook 回调携有签名头，商户需验证来源：

```
X-CloudPay-Signature: t=1705312800,v1=HMAC-SHA256(base64)
X-CloudPay-Webhook-Id: wh_20250115_001
```

验证步骤：
1. 取出 timestamp（t= 后的值）和 signature（v1= 后的值）
2. 构造签名原文：`timestamp + "." + webhook_body_raw`
3. 计算 `HMAC-SHA256(webhook_secret, sign_string)` 的 Base64
4. 与 Header 中的签名做恒等比较（非字符串比较，防时序攻击）

---

## 8. 风控规则 API

### 8.1 配置风控规则

```
POST /v3/risk/rules
```

| 规则类型 | 说明 | 可配置参数 |
|----------|------|------------|
| amount_limit | 单笔金额上限 | max_amount（分） |
| daily_limit | 日累计交易上限 | max_daily_amount（分） |
| ip_whitelist | IP 白名单 | ips（数组） |
| geo_block | 地区限制 | allowed_countries（ISO 3166-1） |
| velocity_check | 频次检查 | max_txns_per_minute |
| card_bin_check | 卡 BIN 校验 | allowed_bins（数组） |

### 8.2 风控预警通知

触发风控规则时，系统自动向 Webhook 推送 `risk.alert` 事件，同时发送邮件和短信到商户安全联系人。

---

## 9. SDK 与客户端

### 9.1 服务端 SDK

| 语言 | 最低版本 | 包管理器 |
|------|----------|----------|
| Java | JDK 8+ | Maven: `com.cloudpay:cloudpay-sdk:3.2.1` |
| Python | 3.8+ | pip: `cloudpay-sdk>=3.2.0` |
| PHP | 7.4+ | Composer: `cloudpay/cloudpay-sdk:^3.2` |
| Go | 1.18+ | go get: `github.com/cloudpay/cloudpay-go/v3` |
| Node.js | 16+ | npm: `cloudpay-sdk@^3.2.0` |

### 9.2 前端 SDK

用于客户端直接唤起支付（减少 PCI 合规范围）：

| 平台 | 版本 | 集成方式 |
|------|------|----------|
| Web (JS) | 3.1.0 | `<script>` 标签或 npm |
| iOS | 3.2.0 | CocoaPods / Swift Package Manager |
| Android | 3.2.0 | Gradle / Maven |
| Flutter | 3.0.1 | pub.dev |
| React Native | 3.0.0 | npm |
| UniApp | 2.9.0 | HBuilderX 插件市场 |

### 9.3 收银台集成

CloudPay 提供预置收银台页面，商户只需拼接 URL：

```
https://cashier.cloudpay.com/pay?token=<PAY_TOKEN>
```

特点：
- 自适应 PC / 移动端
- 内置 30+ 种语言国际化
- 支持 150+ 种货币显示
- 自动检测用户环境（微信/支付宝/浏览器）
- 主题色可自定义（?theme_color=#FF6600）
- Logo 可自定义（?logo_url=https://merchant.com/logo.png）

---

## 10. 测试与调试

### 10.1 沙箱测试工具

沙箱环境提供模拟支付功能，无需真实资金：

- **模拟成功支付**：金额为 `1` 分（0.01 元）的支付自动成功
- **模拟失败支付**：金额为 `2` 分（0.02 元）的支付自动失败
- **模拟风控拦截**：金额为 `3` 分（0.03 元）触发风控规则
- **指定返回状态**：在 `attach` 字段传 `status=<状态码>` 强制返回指定状态

### 10.2 API 调试面板

商户后台 → 开发工具 → API 调试器，可在线构建请求、查看签名生成过程、直接测试接口。

### 10.3 日志查询

```
GET /v3/logs?request_id=req_20250115_abc123
```

返回该请求的完整链路日志（网关→业务→数据库），包含耗时分解。

---

## 11. 附录

### 11.1 HTTP 状态码

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 201 | 创建成功 |
| 400 | 请求参数错误 |
| 401 | 认证失败 |
| 403 | 权限不足 |
| 404 | 资源不存在 |
| 409 | 资源冲突（重复请求） |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |
| 502 | 上游服务异常 |
| 503 | 服务暂时不可用 |

### 11.2 常见问题 FAQ

**Q1: 如何获取 API Key？**
前往商户后台 → 开发设置 → API 密钥 → 生成新密钥。注意：API Secret 仅在生成时展示一次，请妥善保存。

**Q2: 支付回调未收到怎么办？**
首先检查 Webhook 日志（商户后台 → 通知日志），排查是否发送成功。如果 CloudPay 已发送但商户未响应 200，会按退避策略重试。也可使用订单查询接口主动轮询。

**Q3: 如何切换沙箱和生产环境？**
修改 API 请求的 base URL 即可。建议使用环境变量管理，不要在代码中硬编码。

**Q4: 支持哪些结算周期？**
默认 T+1（第二个工作日到账）。月流水超过 100 万可申请 D0（当日到账），需通过风控审核。

**Q5: 退款是否收取手续费？**
退款时原支付的手续费按比例退回：部分退款退回对应比例手续费，全额退款退回全部手续费。

**Q6: 如何处理支付超时？**
创建订单时设置 `expire_time`（默认 3600 秒）。超时后订单自动关闭，用户无法支付。如需延长，在 75% 过期时间前调用订单查询接口（会顺延 30 分钟）。

**Q7: 国际信用卡 3D Secure 认证如何接入？**
SDK 已内置 3DS 2.0 流程，无需额外开发。商户只需确保请求中传递持卡人邮箱（用于风险决策）。

**Q8: 对接过程中遇到技术问题怎么办？**
联系技术支持：tech-support@cloudpay.com 或拨打 400-888-XXXX（工作日 9:00-18:00）。提供 request_id 可以快速定位问题。

---

> 文档版本：v3.0 | 更新日期：2025-01-15 | CloudPay 技术团队
"""

os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    f.write(doc)

size_kb = os.path.getsize(OUTPUT_PATH) / 1024
print(f"文档已生成: {OUTPUT_PATH}")
print(f"文件大小: {size_kb:.1f} KB")
print(f"预计分块数: ~{int(size_kb / 1.5)} chunks (每块约1000字符)")
