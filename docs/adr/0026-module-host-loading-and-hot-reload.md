---
status: accepted
---

# 模块宿主的加载、Binder 缓存与热重载约束

模块控制的唯一控制会话由 `com.android.bluetooth` 内的模块宿主持有（[ADR 0011](0011-dual-host-single-control-session.md)）。宿主必须在系统蓝牙进程的类加载器与生命周期中启动；系统会缓存绑定服务返回的 Binder；LSPosed 还会热重载模块。这三点决定了宿主怎样加载、发现端点和跨代际交接。

## 决策

- **承载**：`ModuleHostRuntime` 在 `com.android.bluetooth` 内，以宿主自己的 Context、uid 与权限承载唯一的控制会话。用户解锁设备后，它根据模块的持久化配置启动，不需要 OpenRose 的 Activity、Application、应用 Service，也不需要应用发布的凭据。
- **发现端点**：端点 Binder 由宿主内已有的服务针对 `BridgeWire.ACTION_CONTROL_HOST` 发放。拿到它不代表获得任何授权，`HostEndpoint` 会校验每一个调用者。
- **发现 Binder 被系统缓存**：系统对每个 Intent 只绑定该服务一次，并在服务存活期间缓存 `installDiscovery` 返回的 Binder，热重载后的新代际不会再被询问。因此发现时交出的是一个转发器（`EndpointForwarder`），它指向 `endpointSlot`。`endpointSlot` 比任何模块代际都活得久，每一代继承它，并把它指向自己的端点。
- **转发器**：`EndpointForwarder` 把每个事务转给 slot 当前指向的端点。进程内的 transact 保留 Binder 调用身份，所以调用者身份不变。没有端点时（某一代处于停止与启动之间）调用失败，调用方像对待任何不可达的宿主一样重试。
- **入口方法**：`AdapterService` 是蓝牙宿主的私有类，它的生命周期方法必须从宿主的类加载器取得（`OpenRoseBluetoothModule.bluetoothServiceEntry`）。其他作用域，以及取不到该方法时，使用 `Application.attach`（`applicationAttach`）：它在组件启动前就提供宿主 Context，而 `onCreate` 可能已被 ROM 重写。
- **跨代际只传递框架或目标拥有的值**：即目标 Context，以及在宿主进程中系统已缓存的发现 Binder 背后的 slot，由下一代把它指向自己的端点。
- **热重载时在框架线程解除 Hook**：框架在整个 `onHotReloaded` 调用期间持有 Hook 锁，所以必须在这个线程上解除 Hook。若交给客户端的读线程去做，`unhook()` 会一直阻塞到关闭超时，热重载随之被拒绝。

## 后果

- 模块可以热重载，系统不必重新绑定发现服务。代际切换期间到达的调用会失败一次，之后按宿主不可达重试。
- 发现端点与获得授权始终是两个步骤，这与 ADR 0011 的 IPC 约束一致。
