# kucolorfulname

当前版本：`1.3.0`


## Folia 26.2 兼容性

- 编译基线：`dev.folia:folia-api:26.2.build.4-beta`，生成 Java 25 字节码。
- 插件描述：`api-version: '26.2'` 与 `folia-supported: true`。
- 实机验证：已在 Folia 26.2-4（API `26.2.build.4-beta`）上完成原生依赖解析、默认 SQLite 初始化和插件启用测试。
- 烟测说明：测试进程在确认插件启用和世界保存完成后由测试脚本结束。
- 测试边界：空载冒烟测试不包含真实玩家、跨区域负载、GUI、PlaceholderAPI 或真实 MySQL/MariaDB 连接；这些路径仍由单元测试与静态线程归属审查覆盖。

本版本以 Folia/Paper 26.2 和 Java 25 为运行基线，不承诺兼容更早的服务端或 Java 版本。

## 当前功能

- Adventure Component 强类型样式：内置 25 种纯色、15 种静态渐变和 16 种服务端动态预设。
- 通过 groups 为权限组配套解锁颜色，并支持每个颜色样式的独立使用权限与限时授权。
- 可选 SQLite WAL、MySQL 8+ 或 MariaDB 10.6+ 持久化；MySQL/MariaDB 支持连接池、事务和群组服缓存同步。
- 玩家仓库 GUI：首页区分已解锁与未解锁颜色；已解锁页支持分页和切换，未解锁页只读展示，当前样式置顶，并提供当前名字预览与卸下按钮。
- 管理员仓库 GUI：始终展示配置中的全部颜色，统一管理在线或离线玩家的 7 天/30 天授权、续期、撤销和最近审计记录。
- 统一 Paper/Folia 调度层按实体、区块与全局上下文投递任务；玩家显示、命令回执、GUI 与定期清理均遵守区域线程归属。
- 数据库请求使用有界执行队列；同一玩家的写入与缓存刷新串行化，重复加载自动合并，退出或停服后的旧回调不会复活缓存。
- Bukkit ServicesManager 注册 ColorfulNameApi，并提供授权、激活和变更事件。
- 可选 PlaceholderAPI 扩展：占位符只读取已加载的内存缓存，不会在解析时访问数据库。
- 动态名字使用服务端逐帧渲染；Folia 26.2 上会将同一帧的 Tab 更新合并为每位观察者最多一个标准数据包。

## 构建

~~~text
mvn test
mvn package
~~~

插件 Jar 生成于 `target/kucolorfulname-1.3.0.jar`。

SQLite JDBC、MySQL Connector/J、MariaDB Connector/J 和 HikariCP 均不内嵌于插件 Jar。Paper/Folia 会通过插件原生加载器，在首次启动时从阿里云或华为云 Maven 镜像自动下载并缓存运行时依赖；依赖下载完成后，后续启动直接使用本地缓存。

## 数据存储

MariaDB 提供独立的 `MARIADB` 后端，使用官方 MariaDB Connector/J 3.x 驱动
（`org.mariadb.jdbc.Driver`），并与 MySQL 后端共用有界 HikariCP 连接池、事务边界和
异步仓库执行器。启用时设置 `storage.type: MARIADB`，并编辑 `config.yml` 中独立的
`storage.mariadb` 节点；同一群组服的所有子服必须使用相同的表前缀和同步周期。
此模式不会借用 MySQL Connector/J 连接 MariaDB。

若原先用 `MYSQL` 类型连接 MariaDB，可在备份后改为 `MARIADB`，并保持数据库名和
`table-prefix` 不变；两种网络后端使用同一套表结构，不需要复制数据，但必须重启服务器。

~~~yaml
storage:
  type: MARIADB
  mariadb:
    host: "127.0.0.1"
    port: 3306
    database: "kucolorfulname"
    username: "minecraft"
    password: "change-me"
    table-prefix: "kucolorfulname_"
    ssl-mode: PREFERRED
    maximum-pool-size: 10
    minimum-idle: 2
    synchronization-interval-seconds: 60
~~~

默认使用 `SQLITE`，数据文件为 `plugins/kucolorfulname/kucolorfulname.db`。单服无需额外配置。

MySQL 模式支持 MySQL 8.0 及以上版本，使用 InnoDB、HikariCP 连接池、预编译语句和事务。MySQL/MariaDB 目标数据库不存在时，插件会使用同一账号自动创建数据库，再创建带前缀的数据表和索引；认证、TLS 或网络错误不会误触发建库。数据库账号至少需要目标数据库的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`、`CREATE`、`INDEX` 权限。

启用 MySQL 时修改 `config.yml`：

~~~yaml
storage:
  type: MYSQL
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "kucolorfulname"
    username: "minecraft"
    password: "请替换为真实密码"
    table-prefix: "kucolorfulname_"
    ssl-mode: PREFERRED
    maximum-pool-size: 10
    minimum-idle: 2
    synchronization-interval-seconds: 60
~~~

所有子服必须使用相同数据库、表前缀和样式 key。其他子服产生的授权、续期、撤销和当前选择会按 `synchronization-interval-seconds` 刷新到本服在线玩家缓存；设置为 `0` 可关闭轮询。默认周期为 60 秒，在线玩家查询会按人数均匀分散到整个周期，且数据库内容未变化时不会重写昵称或 Tab 名。过期清理由事务和行锁保护，多子服同时执行不会重复删除同一授权。群组服各节点应使用时间同步服务，避免限时授权因系统时间偏差提前或延后失效。

连接中断时 HikariCP 会从连接池重新获取有效连接；单次操作失败会返回错误，不会在内存中伪造成功。插件启动时数据库不可用会停止启用，防止服务器在错误存储状态下运行。公网数据库建议使用 `VERIFY_IDENTITY` 并配置有效证书。

`storage.*` 下的配置只能在重启时生效，`/kcn reload` 不会在线切换数据库。当前版本不会自动复制 SQLite 与 MySQL 之间的数据；在正式投入使用前应选定存储后端。

## 菜单与语言

首次启动会在插件数据目录生成两个可编辑文件：

- `menus.yml`：`player-home` 首页、`player` 已解锁页、`player-locked` 未解锁页和 `admin` 管理页的标题、尺寸、槽位、按钮、材质、名称、Lore 与分页信息。文本支持 MiniMessage；修改后新打开的 GUI 会立即使用新内容。
- `lang.yml`：命令反馈、GUI 提示、审计输出和样式类型名称。文本支持 MiniMessage 与 `%player%`、`%style%`、`%duration%` 等占位符。

执行 `/kcn reload` 会原子重载 `config.yml`、`menus.yml` 和 `lang.yml`。数据库连接配置仍需要重启，菜单或语言 YAML 出现非法槽位、材质或格式时，重载会被拒绝并保留上一份有效配置。

从旧版升级时，插件不会覆盖现有的 `config.yml`、`menus.yml` 或 `lang.yml`。从 1.2.0 升级到 1.3.0
时，请将新版 `settings.animation`、`animated` 权限组和所需的 `ANIMATED` 样式合并到现有配置，
并在 `lang.yml` 补上 `gui.type-animated`；
也可以备份后移走旧配置，启动一次生成完整的 1.3.0 默认文件，再填回数据库连接等自定义项。要启用分类首页，请同时
合并新版 `menus.yml` 的 `player-home` 和 `player-locked` 区段；两者均缺失时插件继续使用旧玩家列表，
两者只存在一个时会拒绝重载并指出配置不完整。

## 命令

~~~text
/kcn
/kcn off
/kcn admin <玩家|UUID>
/kcn grant <玩家|UUID> <样式> <30s|5m|30m|1h|12h|1d|7d|30d|4w>
/kcn extend <玩家|UUID> <样式> <1h|12h|1d|7d|30d|4w>
/kcn revoke <玩家|UUID> <样式>
/kcn audit <玩家|UUID> [数量]
/kcn reload
~~~

输入 `/kcn ` 后会立即显示当前发送者有权使用的子命令，并按参数位置继续补全在线玩家、样式、时长和审计数量。对于已加载缓存的在线目标，`extend` 只提示限时授权，`revoke` 只提示已有的单独授权；补全过程只读取内存状态，不访问数据库。离线玩家仍可手动输入 UUID。

管理员 GUI 中左键发放或续期 7 天，Shift+左键发放或续期 30 天，右键撤销限时授权，底部书本按钮查看最近审计记录。管理员操作需要对应的 `kucolorfulname.admin.*` 权限。样式权限不会阻止管理员查看、发放、续期、撤销或审计。

## 权限组

默认配置提供 `default`、`vip`、`svip`、`admin`、`animated` 五个权限组。LuckPerms 等权限插件按权限组授予：

~~~text
kucolorfulname.group.vip
kucolorfulname.group.svip
kucolorfulname.group.admin
kucolorfulname.group.animated
~~~

每个权限组通过 `unlocked-styles` 配置该权限组配套解锁的样式 key，并可用单个
`default-style` 配置登录或权限组切换后自动装备的颜色。玩家同时匹配多个权限组时，
只采用 `priority` 最高的权限组，不合并多个权限组；`permission: ""` 的 `default` 是唯一兜底权限组。
默认配套如下：

- `default`：不配套解锁颜色，`unlocked-styles: []`，`default-style: ""`。
- `vip`：优先级 100，配套解锁从 `ruby` 到 `obsidian` 的全部 25 种纯色，默认装备 `ruby`。
- `svip`：优先级 200，配套解锁全部 40 种默认颜色，默认装备 `aurora`。
- `admin`：优先级 1000，配套解锁全部 40 种默认颜色，默认不自动装备，权限组节点默认授予 OP。
- `animated`：优先级 2000，配套解锁全部 56 种样式，默认不自动装备，权限节点默认不授予任何人。

`default-style` 只能填写一个已经列在同一权限组 `unlocked-styles` 中的样式 key，留空则不自动装备。
普通玩家的兜底权限组不能配置默认样式。玩家拥有多个权限组节点时，只使用最高 `priority` 权限组的
`default-style`；降级后若原颜色对新权限组已不可用，会切换到新最高权限组的默认颜色，否则恢复普通昵称。
兜底权限组只在没有任何带权限的权限组匹配时使用，不参与权限组之间的 `priority` 竞争。
玩家执行 `/kcn off` 后，在当前权限组不变期间不会被周期检查重新装备；权限组权重发生变化或重新登录时会再次按配置判断。

样式最终可用条件是三条独立路径取 OR：

~~~text
当前最高权限组的 unlocked-styles 包含该 key
OR 拥有该样式配置的任一独立权限
OR 拥有该样式尚未到期的限时授权
~~~

因此，权限组没有配套某个颜色时，样式独立权限或有效限时授权仍可直接解锁；限时授权不受
权限组配套列表限制。`unlocked-styles` 中的 key 必须存在于 `styles`，新增或改名样式时应同步检查权限组列表。

## 样式独立权限

每个 `styles.<key>` 节点可用 `permission` 配置单个独立解锁权限，也可用
`permissions: [权限A, 权限B]` 配置替代权限；玩家拥有列表中的任意一个权限即可解锁。
两种字段同时存在时会合并并去重。样式没有配置独立权限时，仍可由当前最高权限组配套
或有效限时授权解锁。

默认配置包含 40 种静态预设，每种静态样式都有唯一的 `kucolorfulname.style.<key>` 权限，且均
默认为 `false`。16 种动态预设默认不配置独立权限，只通过 `animated` 权限组或有效限时授权解锁。
`default` 不配套颜色，VIP/SVIP 则按上述权限组列表自动解锁。
示例 LuckPerms 配置：

~~~text
/lp group vip permission set kucolorfulname.group.vip true
/lp group svip permission set kucolorfulname.group.svip true
/lp group custom permission set kucolorfulname.style.aurora true
/lp user <玩家> permission settemp kucolorfulname.style.sunset true 7d
~~~

`kucolorfulname.admin.style-permission-bypass` 默认授予 OP，并包含在 `kucolorfulname.admin` 中。
拥有该节点时，所有配置了 `permission` 或 `permissions` 的样式都视为已通过样式独立权限
解锁；权限组配套与限时授权仍是互相独立的另外两条路径。

## 服务端动态样式

`ANIMATED` 样式由服务端实时计算 Adventure Component，可用模式为 `FLOW`、`RAINBOW`、
`PULSE` 和 `FLASH`。`animation-period-ticks` 表示一次完整循环的 tick 数，不是单帧停留时间。

~~~yaml
settings:
  animation:
    refresh-ticks: 1
    nms-tab-batching: true

styles:
  animated_neon_wave:
    display-name: "霓虹波浪"
    type: ANIMATED
    animation-mode: FLOW
    animation-period-ticks: 50
    colors: ["#00F5FF", "#FF00E6", "#7A00FF"]
    decorations: [BOLD]
    icon: ECHO_SHARD
~~~

`refresh-ticks: 1` 表示每 tick 刷新，即最高约 20 FPS；高人数服务器可设为 `2` 降至约 10 FPS。
`nms-tab-batching: true` 在 Minecraft 26.2 上合并同一帧的 Tab 更新，失败时自动回退到 Bukkit API。
刷新任务只在至少一名在线玩家实际装备动态颜色时运行；空载时不会维持 20Hz 扫描或玩家任务。
实现不注入 Netty Channel，不排队补发过期帧；最新批次始终包含当前完整动态状态，
退出动态的复位会保留到所有在线观察者确认，因此某个 Folia 区域落后一帧也能直接追上。

动态效果会反映在 Tab 列表和当前的服务端 `displayName` 上；GUI 和
`%kucolorfulname_name%` / `%kucolorfulname_name_mm%` 在每次请求时取当前帧。插件不处理聊天事件，
不重发聊天消息；已经出现在聊天历史中的文字不会继续变化。整套功能不需要资源包或客户端 Mod。

默认动态 ID：`animated_neon_wave`、`animated_rainbow_flow`、`animated_aurora_pulse`、
`animated_sunset_chase`、`animated_ocean_current`、`animated_cotton_candy`、`animated_magma_core`、
`animated_forest_breath`、`animated_galaxy_drift`、`animated_ice_shimmer`、`animated_sakura_bloom`、
`animated_cyber_lime`、`animated_royal_gold`、`animated_void_flame`、`animated_mint_lavender`、`animated_red_alert`。

## 在线校验与回退

在线玩家的显示名会在登录、激活、管理操作、配置重载及定期检查时重新校验，并在符合条件时自动装备权限组默认颜色。LuckPerms
权限组、权限组节点、样式独立权限发生变化，或限时授权到期后，最迟会在
`settings.expiry-check-seconds` 配置的检查周期内反映到昵称，默认是 30 秒。若所选样式的三种
解锁来源全部失效，显示会恢复为无颜色的原名；数据库中保存的所选样式 key 不会清除，任一
解锁来源恢复后会自动重新生效。

激活限制按以下顺序检查：样式存在、当前最高权限组配套/样式独立权限/有效限时授权至少
满足一项、外部插件激活事件。管理员发放授权不要求目标权限组预先配套该颜色。

## API

外部插件可通过 Bukkit ServicesManager 获取 `cc.chlchun.kucolorfulname.api.ColorfulNameApi`，或监听
`cc.chlchun.kucolorfulname.api.event` 包下的授权、续期、撤销、激活、停用和变更事件。
`canUseCached(Player, String)` 检查当前最高权限组配套、样式独立权限和有效限时授权组成的
完整使用条件；`hasStylePermission(Player, String)` 只检查样式独立权限。
`unlockSourceCached(Player, String)` 返回 `PERMISSION`、`GRANT` 或 `PERMISSION_AND_GRANT`；为保持
API 兼容，最高权限组配套和样式独立权限都归入 `PERMISSION` 路径。激活和变更事件中的
`grant()` 是 `Optional<NameGrant>`，并提供 `unlockSource()`；没有数据库授权对象时 `grant()`
为空。数据库操作返回 `CompletionStage`，外部插件操作玩家对象时仍需遵守 Paper/Folia 调度约束。

`cachedPlaceholderSnapshot(UUID)` 返回玩家最近一次在其实体调度器中计算的不可变占位符快照，适合异步读取；它不会访问数据库或 Bukkit 玩家对象。快照包含当前昵称组件、选中与生效样式、权限组、授权来源、状态以及每个样式的可用性。

`ColorfulNameApi.grant(...)` 和 `extend(...)` 的 `Duration` 必须非空且为正数。API 不接受 `null`
表示永久授权；需要不依赖到期时间的解锁时，应将样式加入权限组的 `unlocked-styles`，或通过
LuckPerms 等权限系统授予该样式配置的任一独立权限节点。

## PlaceholderAPI

安装 PlaceholderAPI 后，插件会自动注册 `kucolorfulname` 扩展。可用占位符如下：

~~~text
%kucolorfulname_name%          当前昵称（§ 颜色格式）
%kucolorfulname_name_raw%      当前昵称（去除格式）
%kucolorfulname_name_mm%       当前昵称（MiniMessage 格式）
%kucolorfulname_style%         当前正在使用且可用的样式 key
%kucolorfulname_selected_style%当前数据库中记录的选择（即使暂时不可用）
%kucolorfulname_status%        loading、active、available、locked 或 no_active_style
%kucolorfulname_group%       当前权限组名称
%kucolorfulname_loaded%        是否已完成玩家缓存加载
%kucolorfulname_expires%       限时授权到期时间 ISO-8601；仅权限解锁返回 permission
%kucolorfulname_remaining%     限时授权剩余时长；仅权限解锁返回 permission
%kucolorfulname_permission_unlocked% 是否由权限路径解锁（权限组配套或样式独立权限）
%kucolorfulname_unlock_source% permission、grant 或 permission_and_grant；权限组配套归入 permission
%kucolorfulname_owned%         已加载的有效授权数量
~~~

指定样式占位符（`<style>` 使用 `styles` 中的 key）如下：

~~~text
%kucolorfulname_can_use_<style>%          当前是否可使用该样式
%kucolorfulname_has_grant_<style>%       当前是否拥有有效限时授权
%kucolorfulname_permission_<style>%      当前是否由权限路径解锁（权限组配套或样式独立权限）
%kucolorfulname_grant_expires_<style>%   该样式授权到期时间；权限解锁时返回 permission
%kucolorfulname_grant_remaining_<style>% 该样式授权剩余时长；权限解锁时返回 permission
~~~

仅由权限路径解锁且没有限时授权时，`expires` 和 `remaining` 返回 `permission`，
`permission_unlocked` 返回 `true`。玩家加入后异步加载授权，并在玩家线程中预先计算最高权限
权限组、样式独立权限、授权状态和最终昵称快照；PlaceholderAPI 解析时只读取不可变并发缓存，
不访问数据库，也不调用在线 `Player` 的权限或显示名 API，因此适合异步解析和 Folia。缓存
尚未加载时，`name` 系列返回原名，`status` 返回 `loading`，`loaded` 返回 `false`，其余状态
占位符返回空值、`false` 或 0。对于动态样式，`name` 与 `name_mm` 每次解析时重新取当前帧；
若占位符使用方自身长时间缓存解析结果，其显示刷新速度仍由该插件决定。

## 数据库与版本策略

SQLite 使用 `src/main/resources/db/migration/V1__initial.sql` 初始化数据表；MySQL 使用等价的
InnoDB 表结构并按 `table-prefix` 自动建表。两种后端都只保存限时授权、当前选择和审计记录；
权限组配套颜色来自 `config.yml`，样式独立解锁来自权限系统。数据库迁移脚本仍为 V1；本插件
不提供旧版本或跨后端自动迁移。

## 后续扩展

- 玩家自定义样式创建器及更细粒度的颜色、装饰和数量权限。
- MiniPlaceholders、TAB 等更多显示适配器。
- 批量发放与 Web 管理面板。
