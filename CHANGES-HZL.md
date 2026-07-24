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

新增判断（兼容 HZL outpre 新旧两种 bridge 形态）：

- 旧：`connectionInFlight` 类名以 `icu.h2l.login.vServer.outpre.` 开头；
- 新（26.7.3 包装式 VSC）：看 inFlight 后端 active session handler 包名，或客户端 active handler 包名；
- 若 handler 被 AMB 包成 `ForgeLoginSessionHandler`，先 `getOriginal()` 再判断。

```java
private static boolean isOutPreBridge(ConnectedPlayer player) {
  if (isOutPreServerConnection(player.getConnectionInFlight())) {
    return true;
  }
  return isOutPreSessionHandler(player.getConnection().getActiveSessionHandler());
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

### 8. 客户端握手看门狗 + 转发失败收敛（2026-07-24）

文件：

```text
src/main/java/org/adde0109/ambassador/forge/VelocityForgeBackendConnectionPhase.java
src/main/java/org/adde0109/ambassador/forge/VelocityForgeClientConnectionPhase.java
```

背景：生产日志中出现「玩家认证成功 → 后端 30 秒登录超时踢线」的静默失败：后端下发的
Forge 登录握手包（RegistryPacket/ConfigDataPacket）经桥接转发给停留在 `LOGIN` 状态的
客户端后无任何应答（客户端假死/网络单向不通），双方互等直至各自超时；客户端通道最终
关闭时，积压的 write future 集中失败，产生每次约 90 条重复 WARN。

修改：

1. `forwardForgeLoginPacketToClient`：
   - 客户端通道已关闭时直接跳过写入；
   - 写失败告警每个连接只记 **一条**（`ambassador.forge-forward-failure-logged` channel attr 去重）。
2. 新增握手看门狗：向客户端转发第一个后端 Forge 登录包时布置（每连接一次），每 25 秒
   检查一次——若握手仍未完成、客户端仍在 `LOGIN` 且窗口内 **没有收到客户端任何 Forge
   应答**，主动断开玩家并提示「Forge 握手超时，请重新连接」。有应答但未完成则顺延观察
   下一窗口。25 秒早于后端 30 秒原版登录超时，玩家能看到可操作的提示而非泛化的
   「无法连接」。
3. `VelocityForgeClientConnectionPhase` 两个客户端应答入口（常规握手应答与 CRP reset
   ACK）打 `ambassador.forge-client-handshake-progress` 进度标记，供看门狗区分
   「慢但活着」与「死连接」。

注意：这不是 outpre reset/re-register 联调的完整方案（真·双连接判定仍待做），但把
静默 30 秒挂死变成了带明确提示的快速失败，且消除了刷屏。

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