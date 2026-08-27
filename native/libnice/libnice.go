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
	"context"
	"fmt"
	"os"
	"runtime/debug"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

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

// Service 是一个内核实例的句柄。非线程安全的操作已用互斥量保护，
// 因为 Kotlin 侧的启停可能来自不同线程（UI 线程与服务线程）。
type Service struct {
	mu       sync.Mutex
	instance *box.Box
	cancel   context.CancelFunc
	running  bool
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

	return &Service{instance: instance, cancel: cancel}, nil
}

// Start 启动内核。返回后所有入站已开始监听。
//
// 注意这一步可能相当慢：远程 rule-set 的下载就发生在这里，断网时会一直卡到
// 各自的超时。调用方必须假定它会阻塞几十秒。
func (s *Service) Start() (err error) {
	defer recoverAsError("启动", &err)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return nil
	}
	// Close() 会把 instance 置 nil。少了这一层，「关停后又被启动」这条
	// 本该报错的路径会变成一次空指针解引用 —— 在 Go 里就是整个进程消失。
	if s.instance == nil {
		return fmt.Errorf("内核实例已关闭，无法再次启动")
	}
	if err := s.instance.Start(); err != nil {
		return err
	}
	s.running = true
	return nil
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

// detach 在锁内摘走实例引用并把状态标记为已停止。
//
// 单独一个函数是为了拿 defer 解锁：Close 里 panic 之后还得能继续用这个 Service
// （至少不能让它死锁），而手写的 Lock/Unlock 一旦被 panic 跳过，之后每一次
// IsRunning 都会永久卡住。
func (s *Service) detach() (*box.Box, context.CancelFunc) {
	s.mu.Lock()
	defer s.mu.Unlock()
	instance, cancel := s.instance, s.cancel
	s.instance = nil
	s.cancel = nil
	s.running = false
	return instance, cancel
}

// IsRunning 只反映**宿主这边发过的指令**：Start 成功过、且还没 Close。
//
// 它**不能**用来判断内核是不是还活着。sing-box 因内部错误让某个组件退出时，
// 不会有任何回调通知到这里，这个字段依旧是 true。真正的存活判定请由 Kotlin 侧
// 去探测 Clash API，见 ProxyService.superviseCore。
func (s *Service) IsRunning() (running bool) {
	defer func() {
		if r := recover(); r != nil {
			running = false
		}
	}()
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func parseConfig(configJSON string) (option.Options, error) {
	ctx := include.Context(context.Background())
	return json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
}
