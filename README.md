# Ambassador

## HyperZoneLogin outpre 兼容维护版

这是 `sipo610/Ambassador` fork 的 `hzl-outpre-compat` 分支，用来兼容 HyperZoneLogin 的 `outpre` 模式。

适用目标：

- Velocity 代理端同时安装 HyperZoneLogin 与 Ambassador。
- Forge/Mohist 1.20.1 客户端通过 HZL outpre 认证后进入后端。
- 客户端已安装 Ambassador 辅助 mod。
- 后端登录服/主城服不安装 Ambassador Velocity 插件。

这不是上游 Ambassador 的通用新版本，而是一个针对 HZL outpre 桥接流程的兼容维护分支。完整修改说明见 [CHANGES-HZL.md](CHANGES-HZL.md)。

关键兼容点：

- `VelocityServerChannelInitializer` / `VelocityBackendChannelInitializer`：沿继承链查找 `initChannel(Channel)`，兼容 HZL 包装过的 initializer。
- `ForgeLoginWrapperHandler`：支持从 `MinecraftConnection#getAssociation()` 动态解析真实连接对象。
- `VelocityForgeClientConnectionPhase`：识别 HZL outpre bridge，避免 Forge 握手完成后重复 `registerConnection(player)`。
- `VelocityForgeBackendConnectionPhase`：转发后端 Forge login 包前保持客户端连接处于 `StateRegistry.LOGIN`。
- `ForgeLoginSessionHandler`：放宽原始 session handler 类型到 `MinecraftSessionHandler`，兼容 outpre 桥接阶段。

This is a Velocity plugin that makes it possible to host a modern Forge server behind a Velocity proxy!

Unlike other solutions, this plugin does not require any special modifications to the backend server nor the client. (The player doesn't need to do anything)
## Only for 1.13-1.20.1
Velocity has now added built-in support for newer mc versions and therefore don't need Ambassador. You might still need PCF mod on the server-side, for more info please visit: https://github.com/adde0109/Proxy-Compatible-Forge
## How to get started:
1. Download and install this plugin to your proxy.
2. After starting the server, configure the plugin it to your liking using the config file found in the folder "Ambassador".
3. If you want to use player-information forwarding you can use any of these mods on the 1.13+ forge server:
- https://github.com/adde0109/Proxy-Compatible-Forge (Modern forwarding) (Magma 1.18.2 and higher includes this)

- https://github.com/caunt/BungeeForge (Legacy forwarding)

## Features
* Server switching without any client side mod when the servers are similar. (Mods must match)
* ServerRedirect support for server switching.
* Server switching using Client Reset Packet Mod for instant server switching:
  
1.16.5: https://github.com/Just-Chaldea/Forge-Client-Reset-Packet

1.18.2+: https://www.curseforge.com/minecraft/mc-mods/forge-client-reset-packet-forward

## Stuck on "Negotiating":
This is an issue with Client Reset Packet Mod being partly incompatible with certain mods on the client. Please remove incompatible mods on the client if you have this issue. (Yes, this also includes client-side only mods.)

## Discord
https://discord.gg/Vusz9pBNyJ
