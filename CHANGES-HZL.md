# HyperZoneLogin outpre 兼容修改说明

本文档记录 `hzl-outpre-compat` 分支相对上游 Ambassador 的关键修改。目标是让 Ambassador 可以和 HyperZoneLogin 的 `outpre` 模式一起工作。

## 背景

HZL `outpre` 会在认证阶段临时建立一个后端桥接连接。这个连接不是 Velocity 标准登录流程里的普通 `VelocityServerConnection` 使用方式，因此 Ambassador 原本的一些假设会失效。

典型冲突点：

- Ambassador 需要在客户端仍处于 `LOGIN` 状态时完成 Forge ModList/Channel 协商。
- HZL outpre 会提前进入临时登录/桥接流程，并通过自定义 bridge 接入后端。
- 如果 Ambassador 把 HZL outpre bridge 当成普通后端连接重复注册，可能触发重复连接、超时或 session handler 类型不匹配。

## 修改摘要

### 1. 兼容被包装的 ChannelInitializer

文件：

```text
src/main/java/org/adde0109/ambassador/velocity/VelocityServerChannelInitializer.java
src/main/java/org/adde0109/ambassador/velocity/VelocityBackendChannelInitializer.java
```

原逻辑只使用：

```java
delegate.getClass().getDeclaredMethod("initChannel", Channel.class)
```

这在 HZL 或其他插件包装 initializer 后可能找不到父类里的 `initChannel`。现在改为沿继承链查找：

```java
private static Method findInitChannel(Class<?> cls) {
  Class<?> current = cls;
  while (current != null) {
    try {
      return current.getDeclaredMethod("initChannel", Channel.class);
    } catch (NoSuchMethodException ignored) {
      current = current.getSuperclass();
    }
  }
  throw new RuntimeException("initChannel(Channel) not found in hierarchy of " + cls.getName());
}
```

### 2. 避免重复安装 Forge decoder

文件：

```text
src/main/java/org/adde0109/ambassador/velocity/VelocityEventHandler.java
```

PostLogin 阶段只在 Forge phase 尚未完成时介入：

```java
if (player.getPhase() instanceof VelocityForgeClientConnectionPhase phase && !phase.consideredComplete()) {
```

并且安装前检查 pipeline：

```java
if (player.getConnection().getChannel().pipeline().get(ForgeConstants.FORGE_HANDSHAKE_DECODER) != null) {
  return;
}
```

### 3. 识别 HZL outpre bridge，避免重复注册玩家

文件：

```text
src/main/java/org/adde0109/ambassador/forge/VelocityForgeClientConnectionPhase.java
```

上游在 Forge 握手完成后会直接注册玩家。HZL outpre 下玩家连接可能已经由 HZL 管理，重复注册会破坏流程。

新增判断：

```java
private static boolean isOutPreBridge(ConnectedPlayer player) {
  return player.getConnectionInFlight() != null
          && player.getConnectionInFlight().getClass().getName().startsWith("icu.h2l.login.vServer.outpre.");
}
```

完成阶段只在非 outpre bridge 时注册：

```java
if (!isOutPreBridge(player)) {
  ((VelocityServer) Ambassador.getInstance().server).registerConnection(player);
}
```

### 4. 动态解析 MinecraftConnection 的真实 association

文件：

```text
src/main/java/org/adde0109/ambassador/forge/pipeline/ForgeLoginWrapperHandler.java
```

HZL outpre 中 handler 有时拿到的是 `MinecraftConnection`，真实对象需要从 `getAssociation()` 获取。

```java
private Object resolveAssociation() {
  if (connection instanceof MinecraftConnection minecraftConnection) {
    return minecraftConnection.getAssociation();
  }
  return connection;
}
```

### 5. 转发后端 Forge login 包前保持 LOGIN 状态

文件：

```text
src/main/java/org/adde0109/ambassador/forge/VelocityForgeBackendConnectionPhase.java
```

Forge 登录协商包必须在客户端 `LOGIN` 状态下转发。现在统一通过：

```java
private static void forwardForgeLoginPacketToClient(ConnectedPlayer player, IForgeLoginWrapperPacket<?> message) {
  player.getConnection().setState(StateRegistry.LOGIN);
  ChannelFuture writeFuture = player.getConnection().write(message);
  ...
}
```

### 6. 后端 bridge 阶段放宽 session handler 类型

文件：

```text
src/main/java/org/adde0109/ambassador/velocity/backend/ForgeLoginSessionHandler.java
src/main/java/org/adde0109/ambassador/velocity/backend/VelocityForgeBackendHandshakeHandler.java
```

原本代码假设后端 active session handler 一定是 `LoginSessionHandler`。HZL outpre 桥接阶段不一定满足这个假设。

现在改为使用更通用的：

```java
MinecraftSessionHandler
```

并在 handler 为空时放行 `channelActive`，避免空指针或强转失败。

### 7. FML marker 添加时防御非标准 association

文件：

```text
src/main/java/org/adde0109/ambassador/velocity/backend/FMLMarkerAdder.java
```

原逻辑直接强转：

```java
VelocityServerConnection serverConnection = (VelocityServerConnection) connection.getAssociation();
```

现在改为：

```java
Object association = connection.getAssociation();

if (association instanceof VelocityServerConnection serverConnection
        && serverConnection.getPlayer().getConnection().getType() instanceof ForgeFMLConnectionType FMLType
        ...
) {
```

## 部署建议

推荐组合：

- Velocity 代理端：HyperZoneLogin + 本分支构建出的 Ambassador Velocity 插件。
- 客户端：安装 Ambassador 辅助 mod。
- 登录服/主城服：不安装 Ambassador Velocity 插件。

已验证构建命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat build
```

构建产物：

```text
build/libs/Ambassador-Velocity-1.5.3-beta-all.jar
```