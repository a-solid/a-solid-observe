# A-Solid Observe — Design System Master

> 单一权威设计源。所有页面（大盘、告警、配置）共用此基线，不允许各页自成一套。
> 配套文档：`docs/2026-07-19-frontend-prompts.md`（各页功能描述）。

**Project:** A-Solid Observe（可观测性平台）
**Generated:** 2026-07-19
**Base:** 由 `ui-ux-pro-max` skill 生成 + 本产品手工补语义层

---

## Global Rules

### Color Palette

| Role | Hex | CSS Variable |
|------|-----|--------------|
| Primary | `#1E40AF` | `--color-primary` |
| On Primary | `#FFFFFF` | `--color-on-primary` |
| Secondary | `#3B82F6` | `--color-secondary` |
| Accent/CTA | `#D97706` | `--color-accent` |
| Background | `#F8FAFC` | `--color-background` |
| Foreground | `#1E3A8A` | `--color-foreground` |
| Muted | `#E9EEF6` | `--color-muted` |
| Border | `#DBEAFE` | `--color-border` |
| Destructive | `#DC2626` | `--color-destructive` |
| Ring | `#1E40AF` | `--color-ring` |

**Color Notes:** Blue data + amber highlights [Accent adjusted from #F59E0B for WCAG 3:1]

### Typography

- **Heading Font:** Fira Code
- **Body Font:** Fira Sans
- **Mood:** dashboard, data, analytics, code, technical, precise
- **Google Fonts:** [Fira Code + Fira Sans](https://fonts.google.com/share?selection.family=Fira+Code:wght@400;500;600;700|Fira+Sans:wght@300;400;500;600;700)

**CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600;700&family=Fira+Sans:wght@300;400;500;600;700&display=swap');
```

### Spacing Variables

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | `4px` / `0.25rem` | Tight gaps |
| `--space-sm` | `8px` / `0.5rem` | Icon gaps, inline spacing |
| `--space-md` | `16px` / `1rem` | Standard padding |
| `--space-lg` | `24px` / `1.5rem` | Section padding |
| `--space-xl` | `32px` / `2rem` | Large gaps |
| `--space-2xl` | `48px` / `3rem` | Section margins |
| `--space-3xl` | `64px` / `4rem` | Hero padding |

### Shadow Depths

| Level | Value | Usage |
|-------|-------|-------|
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle lift |
| `--shadow-md` | `0 4px 6px rgba(0,0,0,0.1)` | Cards, buttons |
| `--shadow-lg` | `0 10px 15px rgba(0,0,0,0.1)` | Modals, dropdowns |
| `--shadow-xl` | `0 20px 25px rgba(0,0,0,0.15)` | Hero images, featured cards |

---

## Component Specs

### Buttons

```css
/* Primary Button */
.btn-primary {
  background: #D97706;
  color: white;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}

.btn-primary:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

/* Secondary Button */
.btn-secondary {
  background: transparent;
  color: #1E40AF;
  border: 2px solid #1E40AF;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}
```

### Cards

```css
.card {
  background: #F8FAFC;
  border-radius: 12px;
  padding: 24px;
  box-shadow: var(--shadow-md);
  transition: all 200ms ease;
  cursor: pointer;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
```

### Inputs

```css
.input {
  padding: 12px 16px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 16px;
  transition: border-color 200ms ease;
}

.input:focus {
  border-color: #1E40AF;
  outline: none;
  box-shadow: 0 0 0 3px #1E40AF20;
}
```

### Modals

```css
.modal-overlay {
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal {
  background: white;
  border-radius: 16px;
  padding: 32px;
  box-shadow: var(--shadow-xl);
  max-width: 500px;
  width: 90%;
}
```

---

## Style Guidelines

**Style:** Glassmorphism（浅色为主）

> ⚠ 注意：本产品**明确选择浅色为主基调**（背景 `#F8FAFC`），不采用 skill 默认推荐的 "Dark Mode OLED"。深色模式仅作为可选主题，由设计侧后续评估，不作为默认。

**Keywords:** frosted glass, transparent, blurred background, layered, depth, modern SaaS, data product, professional, restrained, breathing space

**Best For:** Modern SaaS, data dashboards, financial/operations tools, manager-facing showcase

**Key Effects:**
- `backdrop-filter: blur(10-16px)` 玻璃质感（用于卡片、导航、抽屉、modal overlay）
- 卡片 `background: rgba(255,255,255,0.6-0.8)` + `border: 1px solid rgba(30,64,175,0.08)`
- 柔和阴影 + 大量留白
- 不滥用 glow；仅在 severity/状态指示上用克制的高亮

**气质要求：**
- 像 Linear / Vercel Dashboard / Datadog 那种现代数据产品，**不是 Grafana 那种纯运维风**。
- 要能拿去给客户 Manager 对外展示。
- 数据感、专业、克制、有呼吸感。

### Page Pattern

**Pattern Name:** Real-Time Operations Dashboard（适用于 C1 大盘）

- **CTA Placement:** 主操作在右上（新建/发布/重试等），次操作收纳
- **Section Order:** 1. 顶部 hero/链路可视化, 2. KPI 概览, 3. 趋势/分布, 4. 明细列表/Top N

> 其他页面（B 告警处理、A 配置）按各自功能组织，但视觉语言（卡片、间距、字体、状态色）必须沿用本 MASTER。

---

## 语义层：状态与严重度色板（本产品必需）

可观测性产品的核心是状态可视化。除上面的 brand 色外，所有页面共用以下语义色：

### Severity（告警严重度）
| 角色 | Hex | CSS Variable | 形状/图标要求 |
|---|---|---|---|
| CRITICAL | `#DC2626` | `--severity-critical` | 配 ✗ 或 🔺 形状徽标 |
| WARNING  | `#D97706` | `--severity-warning` | 配 ⚠ 或 △ 形状徽标 |
| INFO     | `#0EA5E9` | `--severity-info`    | 配 ℹ 或 ○ 形状徽标 |

> **不只靠颜色**：每种 severity 必须配对应的图标/形状（`color-not-only` 规则），方便色盲用户与黑白打印场景。

### Alert Status
| 角色 | Hex | CSS Variable |
|---|---|---|
| FIRING   | `#DC2626` | `--status-firing`（沿用 critical 红） |
| RESOLVED | `#16A34A` | `--status-resolved` |

### Execution Result
| 角色 | Hex | CSS Variable | 图标 |
|---|---|---|---|
| SUCCESS         | `#16A34A` | `--exec-success` | ✓ |
| SHORT_CIRCUITED | `#D97706` | `--exec-short` | ⚡ |
| FAILED          | `#DC2626` | `--exec-failed` | ✗ |

### Pipeline Status
| 角色 | Hex | CSS Variable | 视觉表现 |
|---|---|---|---|
| PUBLISHED | `#16A34A` | `--pipe-published` | 稳定绿点 |
| DRAFT     | `#D97706` | `--pipe-draft` | 琥珀色慢呼吸（2s） |
| ARCHIVED  | `#64748B` | `--pipe-archived` | 灰色静止 |

### Failed Execution Status
| 角色 | Hex | CSS Variable | 视觉表现 |
|---|---|---|---|
| PENDING  | `#D97706` | `--fail-pending` | 琥珀呼吸光晕（吸引处理） |
| RESOLVED | `#64748B` | `--fail-resolved` | 灰色静止 |
| IGNORED  | `#94A3B8` | `--fail-ignored` | 灰色静止 |

### Source Type
| 角色 | 视觉表现 |
|---|---|
| CDC  | 脉冲波纹图标 |
| CRON | 时钟图标（可选缓慢转动） |
| API  | 闪电图标 |

---

## 招牌动效规则（贯穿全产品）

**核心隐喻**：数据像流体在"管道"中流动 —— 溯源链路 `Source → Subscription → Pipeline → Alert`。

| 动效 | 用途 | 规格 |
|---|---|---|
| **管道流光** | C2 演示、B2 详情溯源、A5 分叉预览 | 沿 SVG path 的 stroke-dashoffset 流动 + 粒子拖尾；克制不抢戏 |
| **数字 count-up** | 所有 KPI 卡片 | 0→目标值，~800ms ease-out |
| **卡片渐入 stagger** | 列表/网格 | opacity 0→1 + translateY 12px→0，stagger 30-50ms |
| **呼吸光晕** | severity / status 指示 | box-shadow 脉冲 2s 循环；只用于"需吸引注意"的状态 |
| **实时滑入** | 列表/图表 | 新条目从顶部滑入 + 闪一下白光；图表新数据点从右进入 |
| **hover 上浮** | 卡片 | translateY -2 ~ -4px，200ms；不改变 layout bounds |

**动效铁律（来自 skill UX 规则）：**
- 时长 150-300ms（复杂 ≤400ms，禁止 >500ms）
- 只用 transform/opacity，不动画 width/height/top/left
- 必须 respect `prefers-reduced-motion`（提供静态降级）
- 每屏最多 1-2 个关键动效，不要堆砌

---

## 招牌组件规格（本产品高频复用）

### KPI 卡片
- 玻璃质感卡片，圆角 16px
- 标题（小、灰）+ 大数字（Fira Code 等宽，count-up）+ 趋势箭头 + mini sparkline
- FIRING/CRITICAL 类 KPI：左缘 4px severity 色条 + 克制呼吸光晕

### 状态徽标（Badge）
- 圆角 999px（胶囊）或 4px（方块）
- severity/状态色作为背景或左缘色条
- 必须配图标，不只靠颜色

### 告警卡片（B1 列表用）
- 卡片非表格行
- 左缘 4px severity 色条
- CRITICAL+FIRING：左缘呼吸光晕（吸引值班）
- RESOLVED：opacity 0.5-0.6 弱化

### 时间轴泳道（B3 执行历史用）
- 每行：时间戳（等宽）+ triggerType 图标 + 耗时进度条（宽度∝durationMs，色=结果）+ 结果徽标
- 进度条加载从左填充，CSS transition

### 溯源链路条（B2 详情顶部、C1 hero、C2 演示、A5 预览）
- 横向 4 节点：Source → Subscription → Pipeline → Alert
- 节点：圆角胶囊，玻璃质感，含图标 + 名称
- 连接：渐变路径，激活时流光
- 可点击展开详情（右侧抽屉滑入）

### 分叉链路（A5 编辑器预览用）
- Source → Subscription 主干 → 分叉到多个 Pipeline 节点（数量 = pipelineIds）
- 加/减 pipeline 时分支 scale-in / scale-out

### 诊断面板（B4 失败执行用）
- 形态由设计发挥（信号通道 / 流程崩点 / 时间轴 / 示波图 均可）
- 必须清晰标注"失败发生在哪一步"
- 配 errorMessage（红色高亮）+ 可展开 stackTrace（等宽）

### 条件树编辑器（A5 用）
- AND/OR 容器节点 + Compare/In 叶子节点
- 节点可增删，配 + 号按钮
- 折叠/展开交互

### 顶部导航条
- 玻璃质感，固定，blur
- 左 logo / 中 namespace 切换器 / 右 角色页签（大盘/告警/配置）+ 时间范围
- namespace 切换：顶部一道流光横扫一次（全局反馈）
- 角色页签：当前激活下方有滑动指示条

---

## Anti-Patterns (Do NOT Use)

- ❌ 默认深色主题（本产品明确浅色为主）
- ❌ 像 Grafana / 传统运维监控后台那种灰暗拥挤
- ❌ 只靠颜色传达状态/严重度（必须配图标/形状）
- ❌ 死板的 CRUD 表格（列表应用卡片或富列表）
- ❌ 过度堆砌动效（每屏 ≤2 个关键动效）

### Additional Forbidden Patterns

- ❌ **Emojis as icons** — Use SVG icons (Heroicons, Lucide, Simple Icons)
- ❌ **Missing cursor:pointer** — All clickable elements must have cursor:pointer
- ❌ **Layout-shifting hovers** — Avoid scale transforms that shift layout
- ❌ **Low contrast text** — Maintain 4.5:1 minimum contrast ratio
- ❌ **Instant state changes** — Always use transitions (150-300ms)
- ❌ **Invisible focus states** — Focus states must be visible for a11y

---

## Pre-Delivery Checklist

Before delivering any UI code, verify:

- [ ] No emojis used as icons (use SVG instead)
- [ ] All icons from consistent icon set (Heroicons/Lucide)
- [ ] `cursor-pointer` on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard navigation
- [ ] `prefers-reduced-motion` respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px
- [ ] No content hidden behind fixed navbars
- [ ] No horizontal scroll on mobile
