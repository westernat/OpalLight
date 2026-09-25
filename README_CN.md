# OpalLight —— 彩色光源 API

[English Version](README.md)

---

## 简介

OpalLight 为 Minecraft 提供了一套**通用的彩色光源 API**。与常见的全屏着色器方案不同，OpalLight 采用**逐方块体素级别的 RGB
光传播**——光线的颜色通过 BFS 在方块间真实传播，随距离衰减，不同颜色混合叠加。这意味着彩色光照在拐角处、复杂结构中都能正确呈现，而非简单的屏幕空间特效。

核心优势：**自动兼容绝大多数 Iris Shaders**，无需用户额外配置或选择兼容光影。NeoForge 1.21.1 分支另外完整支持 VulkanMod 渲染器。
（当前分支对应 Minecraft 1.20.1 + Minecraft Forge。）

## 核心特性

### 强大的彩色光源 API

- **数据驱动 + 事件驱动双模式**：可通过 JSON 数据文件注册发光方块的色彩，也可通过 `ModificationEvent` 以编程方式动态注册。
- **StatePropertiesPredicate 支持**：同一方块的不同方块状态可配置不同颜色。
- **零侵入集成**：任意模组的任意方块都能接入彩色光照，只需注册数据或监听事件。

### 真实的色彩传播

- 基于 BFS 的体素级光传播，颜色随曼哈顿距离线性衰减。
- 多光源颜色混合叠加，产生自然的渐变效果。
- 不受地形复杂度影响，拐角、窄缝、洞穴中光照表现始终正确。

### 广泛的兼容性

- **Iris Shaders**：自动兼容绝大多数光影包，无需用户手动选择兼容配置。
- **VulkanMod**：NeoForge 1.21.1 分支原生支持 Vulkan 渲染器。
- 自定义着色器使用 Core Profile GLSL 150，标准合规，避免渲染冲突。

## 示例：七彩灯笼

模组内置了七种彩色灯笼作为 API 的使用示范：

| 灯笼                          | 配方                |
|-----------------------------|-------------------|
| 红 / 橙 / 黄 / 绿 / 青 / 蓝 / 紫灯笼 | 原版灯笼 + 对应染料（无序合成） |

每个灯笼都是通过 OpalLight 的 API 注册的，展示了如何将方块与 RGB 色彩关联。

## 面向开发者

```java
// 监听 ModificationEvent 即可为任意方块注册彩光
@SubscribeEvent
public static void onLightModification(LightDataLoader.ModificationEvent event) {
    event.getDataByBlock().put(
        MyBlocks.GLOWING_CRYSTAL,
        List.of(new LightDataLoader.OpalData(new OpalColor(0.2f, 0.8f, 1.0f), Optional.empty()))
    );
}
```

或在 `assets/<modid>/opal_data/` 下放置 JSON 文件即可零代码接入。

## 兼容性

| 项目                        | 状态               |
|---------------------------|------------------|
| Iris Shaders              | 自动兼容绝大多数光影       |
| VulkanMod                 | NeoForge 1.21.1 分支支持 |
| NeoForge 1.21.1 / Forge 1.20.1 | 原生平台         |
| 其他模组的发光方块                 | 通过 API 注册即可兼容    |
