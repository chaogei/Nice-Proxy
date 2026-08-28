// Package libnice 是 Nice-Proxy 的 sing-box 内核封装层，
// 通过 gomobile 绑定成 Android 可用的 AAR。
//
// 设计原则：API 面尽可能窄。
//
// 本应用不使用 TUN，因此完全不需要实现 sing-box 的 adapter.PlatformInterface
// （那套接口有 OpenTun / NetworkInterfaces / FindConnectionOwner 等十余个方法，
// 且大量使用 gomobile 不友好的类型）。
//
// 流量统计、连接列表、日志流、节点切换、延迟测速一律不走这里，
// 而是由 Kotlin 侧通过 Clash API（127.0.0.1）消费 —— gomobile 的跨语言
// 回调开销大且不支持切片/映射等类型，高频结构化数据走 HTTP/WebSocket 更合适。
// 见 docs/DESIGN.md §6.1 与 §6.9。
package libnice

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

const (
	// mobileGCPercent 比默认的 100 更激进。
	//
	// 默认值意味着「堆涨到存活对象的两倍才回收」，在服务器上这是一笔划算的买卖：
	// 用内存换 CPU。手机上这笔账反过来了 —— 前台服务的 RSS 直接决定它在内存
	// 紧张时被杀的顺序，而代理的存活集本来就小（几 MB 量级），把倍率压到 1.4
	// 增加的 GC 次数在实测里连 1% CPU 都不到。
	mobileGCPercent = 40

	// maxProcs 是 GOMAXPROCS 的上限，见 [gomaxprocsFor] 的说明。
	maxProcs = 4

	// 堆软上限取物理内存的 1/8，并夹在下面这对边界之间。
	//
	// 下界 64 MiB：低于这个数，几百条并发连接的读写缓冲就能顶到上限，Go 会陷入
	// 连续 GC（GC 抖动），表现为代理突然变得极慢却不报错 —— 比被杀掉还难排查。
	// 上界 512 MiB：再高就失去意义了，正常负载根本到不了，留着只会让异常情况下
	// 的内存泄漏不受约束地涨下去。
	memoryLimitDivisor  = 8
	minMemoryLimitBytes = 64 << 20
	maxMemoryLimitBytes = 512 << 20

	// startDrainTimeout 是 [Service.Close] 等待一次进行中的启动收尾的上限。
	//
	// 正常情况下 cancel 之后 `box.Start()` 会在毫秒级返回；给到 5 秒是留给
	// 「某个组件的关停路径不响应 context」的余量。等不到也必须往下走，
	// 因为 Close 通常是在 Service.onDestroy 里调的，卡住等于 ANR。
	startDrainTimeout = 5 * time.Second
)

// Go 运行时的默认值是照着服务器调的，摆到手机上每一条都偏得很远。
//
// 这一段刻意放在 init 而不是导出成一个 Tune() 之类的函数：gomobile 的 API 面
// 越窄越好，而这些参数没有任何一个是调用方需要（或者有能力）决定的。
func init() {
	debug.SetGCPercent(mobileGCPercent)
	runtime.GOMAXPROCS(gomaxprocsFor(runtime.NumCPU()))
	if limit := memoryLimitFor(readTotalMemoryBytes()); limit > 0 {
		debug.SetMemoryLimit(limit)
	}
}

// gomaxprocsFor 决定内核最多同时用几个核。
//
// 不设上限的话，八核大小核手机上 Go 会开八个 P，而代理的工作负载几乎全是 IO 等待，
// 多出来的 P 只会把 goroutine 调度到小核上、再制造一堆跨核唤醒 —— 吞吐没涨，
// 后台耗电倒是实打实地涨了，而这正是「挂着代理一晚上掉 30% 电」的来源之一。
//
// 也不能压到 1：TLS 握手和 shadowsocks 的 AEAD 是真 CPU 活，单核会成为瓶颈。
func gomaxprocsFor(numCPU int) int {
	if numCPU < 1 {
		return 1
	}
	if numCPU > maxProcs {
		return maxProcs
	}
	return numCPU
}

// memoryLimitFor 按设备物理内存推一个 Go 堆的软上限。
//
// 为什么需要它：本进程是**前台服务**，Android 在内存紧张时会按 RSS 排序挑目标。
// Go 的默认策略是「堆涨到上次 GC 后的两倍才回收」，一次几百个连接的突发会把堆
// 顶上去，而那之后即使连接全断了，RSS 也要等下一轮 GC 才降下来。整屋断网的
// 后台被杀事件就发生在这个窗口里。
//
// 软上限不是硬限制：超过之后 Go 会更频繁地 GC 而不是 OOM，正是我们要的行为。
// 取物理内存的八分之一并封顶，读不到内存信息时返回 0 表示不设限 —— 宁可不设，
// 也不要拍一个可能远小于实际需要的数字，那会让内核在大量连接下 GC 抖到不可用。
func memoryLimitFor(totalBytes int64) int64 {
	if totalBytes <= 0 {
		return 0
	}
	limit := totalBytes / memoryLimitDivisor
	if limit < minMemoryLimitBytes {
		return minMemoryLimitBytes
	}
	if limit > maxMemoryLimitBytes {
		return maxMemoryLimitBytes
	}
	return limit
}

// readTotalMemoryBytes 从 /proc/meminfo 读物理内存总量，读不到返回 0。
func readTotalMemoryBytes() int64 {
	file, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "MemTotal:") {
			continue
		}
		fields := strings.Fields(line)
		// 形如 "MemTotal:       7906084 kB"
		if len(fields) < 2 {
			return 0
		}
		kb, err := strconv.ParseInt(fields[1], 10, 64)
		if err != nil {
			return 0
		}
		return kb * 1024
	}
	return 0
}

// recoverAsError 把当前 goroutine 的 panic 转成一个普通错误。
//
// 为什么必须有：gomobile 生成的 JNI 桥不拦 panic，Go runtime 直接走 SIGABRT，
// 进程当场消失 —— 没有 Java 堆栈、没有崩溃对话框、logcat 里只剩一段 Go 的
// goroutine dump。对用户来说就是「应用突然没了，全屋断网」。
//
// **这个手段的覆盖面必须说清楚，不要指望它挡住一切。** recover 只对**当前
// goroutine** 生效，而 sing-box 在 Start() 之后会自己派生大量 goroutine
// （每条入站连接、DNS 查询、urltest 探测各一条）。那些 goroutine 里的 panic
// 依然会终止整个进程，本函数一点忙都帮不上。
//
// 也就是说：它只覆盖「Kotlin 同步调下来的这一段调用栈」—— 配置解析、组件装配、
// 启动与关停。这几段恰好也是最容易因为畸形配置而 panic 的地方（sing-box 内部
// 对配置字段大量使用直接索引与类型断言），所以仍然值得做。
func recoverAsError(scope string, err *error) {
	if r := recover(); r != nil {
		// 带上 Go 侧的栈：进程活下来之后，这段文本是唯一能定位问题的线索
		*err = fmt.Errorf("内核 %s 时发生崩溃: %v\n%s", scope, r, debug.Stack())
	}
}

// Version 返回内嵌的 sing-box 版本号。
// Kotlin 侧用它在「关于」页展示，并校验配置生成器的目标版本是否匹配。
func Version() (version string) {
	// 读一个编译期常量，实际上不可能 panic。仍然兜一层，是因为「哪几个导出函数
	// 可以省掉 recover」这种判断迟早会被后来的改动推翻，而代价是整个进程 abort。
	defer func() {
		if recover() != nil {
			version = "unknown"
		}
	}()
	return constant.Version
}

// CheckConfig 只做反序列化校验，用于输入时的快速反馈。
//
// 注意它**只能发现 schema 问题**（字段名写错、类型不对、废弃字段），
// 发现不了语义问题。真正校验请用 [ValidateConfig]。
func CheckConfig(configJSON string) (err error) {
	defer recoverAsError("校验配置", &err)
	_, err = parseConfig(configJSON)
	return err
}

// ValidateConfig 解析配置并完整构造一个内核实例，然后立即释放。
//
// 相比 [CheckConfig]，它会跑完 sing-box 的组件装配与交叉引用检查，
// 能抓到「引用了不存在的出站」「DNS 绕道到空 direct 出站」这类
// 反序列化阶段看不出来的语义错误 —— 后者就是实测中真实踩到的坑。
//
// 不会绑定任何端口，可以安全地在保存配置时调用。
// 这也是「内核契约测试」的执行入口，见 docs/DESIGN.md §11.4。
func ValidateConfig(configJSON string) (err error) {
	defer recoverAsError("装配配置", &err)
	options, err := parseConfig(configJSON)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	defer cancel()
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return err
	}
	return instance.Close()
}

// serviceState 是这个句柄的全部合法状态。
//
// 用显式状态机而不是一个 running 布尔，是因为「正在启动」必须能被区分出来 ——
// 那段时间可能长达几十秒（远程 rule-set 下载就在里面），而这期间既不能再启动一次，
// 也不能假装它已经在跑。
type serviceState int

const (
	stateIdle serviceState = iota
	stateStarting
	stateRunning
	stateClosed
)

// Service 是一个内核实例的句柄。
//
// **[Service.mu] 只保护状态迁移，绝不覆盖真正耗时的原生调用。** 这一点是刻意的：
// `box.Start()` 里包含远程 rule-set 的下载，弱网下几十秒很常见；持锁做的话，
// [Service.IsRunning] 这种本该瞬时返回的探测会跟着一起卡住 —— 而它正是宿主判断
// 「内核还活着吗」的入口，卡住的直接后果是看门狗误判、把一个正在正常启动的内核
// 当成死的杀掉重来，用户看到的是「怎么点了启动一直在转圈然后又失败了」。
type Service struct {
	mu       sync.Mutex
	state    serviceState
	instance *box.Box
	ctx      context.Context
	cancel   context.CancelFunc

	// startDone 在一次启动进行期间非 nil，启动结束时被关闭。
	// [Service.Close] 靠它等到锁外那段启动真正收尾，而不是一边 Start 一边 Close ——
	// 后者是否安全取决于 sing-box 内部实现，不该赌。
	startDone chan struct{}
}

// NewService 解析配置并构造内核实例，但不启动它。
//
// workDir 应为应用私有目录（Context.getFilesDir()）。sing-box 会在其中
// 写入 cache.db（保存规则集缓存、节点选择、urltest 结果）。
//
// 刻意**不**调用 os.Chdir：那是进程级副作用，会连同 JVM 侧和进程里其他所有
// 库的相对路径基准一起改掉，而本进程里跑着 Room、DataStore、OkHttp 缓存。
// 配置里的路径全部由 Kotlin 侧写成绝对路径，本来就不需要它。
func NewService(configJSON string, workDir string) (svc *Service, err error) {
	defer recoverAsError("构造实例", &err)

	if workDir != "" {
		if err := os.MkdirAll(workDir, 0o700); err != nil {
			return nil, err
		}
	}

	options, err := parseConfig(configJSON)
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: options,
	})
	if err != nil {
		cancel()
		return nil, err
	}

	return &Service{instance: instance, ctx: ctx, cancel: cancel}, nil
}

// Start 启动内核，不设截止时间。返回后所有入站已开始监听。
//
// 注意这一步可能相当慢：远程 rule-set 的下载就发生在这里，断网时会一直卡到
// 各自的超时。调用方必须假定它会阻塞几十秒 —— 或者改用 [Service.StartWithTimeout]。
func (s *Service) Start() error {
	return s.StartWithTimeout(0)
}

// StartWithTimeout 启动内核，并给这一次启动一个**独立的超时语境**。
//
// 为什么需要它：宿主那边的超时只是「不再等了」，它打不断已经跑起来的原生调用 ——
// 协程取消对一个阻塞中的 JNI 调用毫无作用。于是超时之后仍然有一个我们不要了、
// 却随时可能绑上端口的内核在跑，宿主只能把它记下来、等它自己结束再收尾。
//
// 这里做的是真正的中止：到点就 cancel 掉内核自己的 context，正在下载的远程
// rule-set 会立刻返回错误，`box.Start()` 随之收尾。取消的是**内核内部**的工作，
// 不是从外面掐它，所以不会留下半启动状态。
//
// @param timeoutMs 非正数表示不设截止时间，行为等同 [Service.Start]。
func (s *Service) StartWithTimeout(timeoutMs int64) (err error) {
	defer recoverAsError("启动", &err)

	instance, ctx, cancel, done, err := s.beginStart()
	if err != nil || instance == nil {
		// instance 为 nil 且无错，说明它已经在跑了
		return err
	}

	// 计时器在自己的 goroutine 上跑，这个标志必须原子读写
	var timedOut atomic.Bool
	if timeoutMs > 0 {
		timer := time.AfterFunc(time.Duration(timeoutMs)*time.Millisecond, func() {
			timedOut.Store(true)
			cancel()
		})
		defer timer.Stop()
	}

	// 关键：这一步在**锁外**。IsRunning 在这几十秒里照常瞬时返回。
	startErr := instance.Start()
	if startErr == nil && ctx.Err() != nil {
		// 上下文已经被取消，即使 Start 报了成功，这个实例也已经不可用了。
		// 必须当成失败往上报，否则宿主会以为内核起来了，而它其实是个空壳。
		startErr = fmt.Errorf("内核启动被中止: %w", ctx.Err())
	}
	if startErr == nil {
		return s.endStart(done, nil)
	}
	if timedOut.Load() {
		startErr = fmt.Errorf("内核启动超时（%d 毫秒）: %w", timeoutMs, startErr)
	}
	return s.endStart(done, startErr)
}

// beginStart 在锁内完成状态迁移，并把启动真正需要的东西带到锁外。
//
// 返回的 instance 为 nil 且 err 为 nil 时表示「已经在跑，无需再启动」。
func (s *Service) beginStart() (*box.Box, context.Context, context.CancelFunc, chan struct{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	switch s.state {
	case stateRunning:
		return nil, nil, nil, nil, nil
	case stateStarting:
		// 重入防护。少了它，两次并发的 Start 会各自跑一遍 box.Start()，
		// 第二遍撞上自己刚绑好的端口，报出来的却是「地址已被占用」——
		// 用户看到这句话只会去关别的应用，而问题根本不在那儿。
		return nil, nil, nil, nil, errors.New("内核正在启动中")
	case stateClosed:
		return nil, nil, nil, nil, errors.New("内核实例已关闭，无法再次启动")
	}
	// Close() 会把 instance 置 nil。少了这一层，「关停后又被启动」这条
	// 本该报错的路径会变成一次空指针解引用 —— 在 Go 里就是整个进程消失。
	if s.instance == nil {
		return nil, nil, nil, nil, errors.New("内核实例已关闭，无法再次启动")
	}

	s.state = stateStarting
	s.startDone = make(chan struct{})
	return s.instance, s.ctx, s.cancel, s.startDone, nil
}

// endStart 把启动结果落到状态上，并唤醒可能正在等这次启动收尾的 Close。
func (s *Service) endStart(done chan struct{}, startErr error) error {
	s.mu.Lock()
	closedDuringStart := s.state == stateClosed
	if !closedDuringStart {
		if startErr != nil {
			s.state = stateIdle
		} else {
			s.state = stateRunning
		}
	}
	s.startDone = nil
	// close 不会阻塞，放在锁内是为了和状态迁移一起原子地对外可见
	close(done)
	s.mu.Unlock()

	if closedDuringStart {
		// Close 已经接手了这个实例，这里不能再碰它
		return errors.New("内核在启动过程中被关停")
	}
	return startErr
}

// Close 停止内核并释放资源。可重复调用。
func (s *Service) Close() (err error) {
	defer recoverAsError("关停", &err)

	instance, cancel := s.detach()
	if instance == nil {
		return nil
	}
	err = instance.Close()
	if cancel != nil {
		cancel()
	}

	// 代理停止后 Go 堆上会留下大量已释放的连接缓冲区，
	// 主动归还给操作系统，避免前台服务的常驻内存虚高（NFR-4）。
	//
	// 刻意放在解锁之后：这一步要跑完整整一轮 GC 再做 scavenge，耗时随堆上残留的
	// 连接缓冲增长，跑了几小时的内核上是秒级的。持锁做的话，[Service.IsRunning]
	// 这种本该瞬时返回的探测会跟着一起卡住。
	debug.FreeOSMemory()
	return err
}

// detach 摘走实例引用并把状态标记为已关闭。
//
// 关停撞上正在进行的启动时，先 cancel 掉内核上下文让那一段尽快收尾，再**等它结束**，
// 最后才关实例。不等的话就是一边 box.Start() 一边 box.Close()，安全与否完全取决于
// sing-box 内部的实现细节 —— 那不是应该拿用户的进程去赌的事情。
func (s *Service) detach() (*box.Box, context.CancelFunc) {
	s.mu.Lock()
	starting := s.startDone
	if starting != nil {
		s.state = stateClosed
		if s.cancel != nil {
			s.cancel()
		}
	}
	s.mu.Unlock()

	if starting != nil {
		select {
		case <-starting:
		case <-time.After(startDrainTimeout):
			// 等不到就只能往下走。留一个卡死的句柄比让调用方永远挂在 Close 上要好，
			// 至少端口还有机会随进程回收。
		}
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	instance, cancel := s.instance, s.cancel
	s.instance = nil
	s.cancel = nil
	// 清掉它，否则「等超时后放弃」的那条路径上，紧随其后的第二次 Close 会再等一遍
	// 整个 startDrainTimeout。onDestroy 里连着调两次是常态，那就是两倍的 ANR 风险。
	s.startDone = nil
	s.state = stateClosed
	return instance, cancel
}

// IsRunning 只反映**宿主这边发过的指令**：Start 成功过、且还没 Close。
//
// 它**不能**用来判断内核是不是还活着。sing-box 因内部错误让某个组件退出时，
// 不会有任何回调通知到这里，这个字段依旧是 true。真正的存活判定请由 Kotlin 侧
// 去探测 Clash API，见 ProxyService.superviseCore。
//
// 保证瞬时返回：它拿的锁只在状态迁移期间被持有，从不覆盖 `box.Start()`。
func (s *Service) IsRunning() (running bool) {
	defer func() {
		if r := recover(); r != nil {
			running = false
		}
	}()
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == stateRunning
}

func parseConfig(configJSON string) (option.Options, error) {
	ctx := include.Context(context.Background())
	return json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
}
