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
	"os"
	"runtime/debug"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

// Version 返回内嵌的 sing-box 版本号。
// Kotlin 侧用它在「关于」页展示，并校验配置生成器的目标版本是否匹配。
func Version() string {
	return constant.Version
}

// CheckConfig 只做反序列化校验，用于输入时的快速反馈。
//
// 注意它**只能发现 schema 问题**（字段名写错、类型不对、废弃字段），
// 发现不了语义问题。真正校验请用 [ValidateConfig]。
func CheckConfig(configJSON string) error {
	_, err := parseConfig(configJSON)
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
func ValidateConfig(configJSON string) error {
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
func NewService(configJSON string, workDir string) (*Service, error) {
	if workDir != "" {
		if err := os.MkdirAll(workDir, 0o700); err != nil {
			return nil, err
		}
		// 配置里的路径都由 Kotlin 侧写成绝对路径，这里切换工作目录
		// 只是为了兜住 sing-box 内部可能出现的相对路径。
		if err := os.Chdir(workDir); err != nil {
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
func (s *Service) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return nil
	}
	if err := s.instance.Start(); err != nil {
		return err
	}
	s.running = true
	return nil
}

// Close 停止内核并释放资源。可重复调用。
func (s *Service) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.instance == nil {
		return nil
	}
	err := s.instance.Close()
	s.cancel()
	s.instance = nil
	s.running = false
	// 代理停止后 Go 堆上会留下大量已释放的连接缓冲区，
	// 主动归还给操作系统，避免前台服务的常驻内存虚高（NFR-4）。
	debug.FreeOSMemory()
	return err
}

func (s *Service) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func parseConfig(configJSON string) (option.Options, error) {
	ctx := include.Context(context.Background())
	return json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
}
