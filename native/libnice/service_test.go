package libnice

import (
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// 这一组测试守护的是 Service 的**并发契约**，而不是 sing-box 的行为。
//
// 分界线值得说清楚：内核起没起来、端口绑没绑上，那是 sing-box 的事，契约测试
// （contract_test.go）已经在管。这里管的是「宿主在内核启动的那几十秒里，还能不能
// 问出话来」—— 也就是 Start 的持锁范围、重入防护、以及 Close 撞上 Start 时的收尾。
// 这几条一旦破掉，症状是看门狗误杀正在启动的内核，而不是启动失败，
// 从日志上根本看不出是这里的问题，所以必须由测试钉住。

func TestGOMAXPROCSIsCappedForMobile(t *testing.T) {
	cases := []struct {
		numCPU int
		want   int
	}{
		{numCPU: -1, want: 1}, // runtime.NumCPU 不会返回负数，兜底而已
		{numCPU: 0, want: 1},
		{numCPU: 1, want: 1},
		{numCPU: 2, want: 2},
		{numCPU: maxProcs, want: maxProcs},
		{numCPU: 8, want: maxProcs},  // 常见的大小核八核
		{numCPU: 16, want: maxProcs}, // 平板/折叠屏
	}
	for _, tc := range cases {
		if got := gomaxprocsFor(tc.numCPU); got != tc.want {
			t.Errorf("gomaxprocsFor(%d) = %d, 期望 %d", tc.numCPU, got, tc.want)
		}
	}
}

func TestInitAppliedRuntimeTuning(t *testing.T) {
	// GOMAXPROCS(0) 只读不写
	if got := runtime.GOMAXPROCS(0); got > maxProcs {
		t.Errorf("GOMAXPROCS = %d，超过了移动端上限 %d，init 里的调优没生效", got, maxProcs)
	}
}

func TestMemoryLimitTracksDeviceMemory(t *testing.T) {
	const mib = 1 << 20
	cases := []struct {
		name  string
		total int64
		want  int64
	}{
		// 读不到内存信息时必须返回 0（不设限），而不是拍一个小数字下去
		{name: "读取失败", total: 0, want: 0},
		{name: "负数", total: -1, want: 0},
		// 1/8 之后低于下界，抬到下界：低配机上宁可多占点内存，也不能陷入 GC 抖动
		{name: "极低配 256MiB", total: 256 * mib, want: minMemoryLimitBytes},
		{name: "刚好在下界", total: 8 * minMemoryLimitBytes, want: minMemoryLimitBytes},
		{name: "低配机 1GiB", total: 1024 * mib, want: 128 * mib},
		{name: "中端机 4GiB", total: 4096 * mib, want: 512 * mib},
		{name: "旗舰机 12GiB", total: 12288 * mib, want: maxMemoryLimitBytes},
		{name: "上界封顶", total: 64 * 1024 * mib, want: maxMemoryLimitBytes},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := memoryLimitFor(tc.total); got != tc.want {
				t.Errorf("memoryLimitFor(%d) = %d, 期望 %d", tc.total, got, tc.want)
			}
		})
	}
}

func TestReadTotalMemoryReturnsZeroWhenUnavailable(t *testing.T) {
	// Linux 上（含 Android 与 CI）应当读得到；其他平台返回 0 也是合法结果。
	// 断言的是「不会返回负数、不会 panic」这条底线 —— 负数会让 memoryLimitFor
	// 静默退化成不设限，是那种永远不会有人发现的失效。
	if got := readTotalMemoryBytes(); got < 0 {
		t.Fatalf("readTotalMemoryBytes() = %d，不应为负", got)
	}
}

// Start 期间的锁必须是放开的。
//
// 这个测试模拟一次「慢启动」：占住 stateStarting 不放，然后要求 IsRunning 立刻回话。
// 如果哪天有人把 `defer s.mu.Unlock()` 加回 Start 的整个函数体，这里会直接超时。
func TestIsRunningDoesNotBlockDuringStart(t *testing.T) {
	svc := &Service{instance: nil}
	// beginStart 会拒绝 instance 为 nil 的句柄，这里要的是状态本身，直接摆好
	svc.state = stateStarting
	svc.startDone = make(chan struct{})

	answered := make(chan bool, 1)
	go func() { answered <- svc.IsRunning() }()

	select {
	case running := <-answered:
		if running {
			t.Fatal("正在启动的内核不应被报告为 running —— 看门狗会据此认定它已就绪")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("IsRunning 在启动进行期间被阻塞：Start 的持锁范围盖住了原生调用")
	}
}

// 并发 Start 只能有一个真的跑起来，其余必须拿到明确的错误。
func TestConcurrentStartIsRejected(t *testing.T) {
	svc := newIdleService(t)

	instance, _, _, done, err := svc.beginStart()
	if err != nil {
		t.Fatalf("首次 beginStart 失败: %v", err)
	}
	if instance == nil {
		t.Fatal("首次 beginStart 应当交出实例")
	}

	// 启动进行期间，第二次尝试必须被挡下
	if _, _, _, _, err := svc.beginStart(); err == nil {
		t.Fatal("重入防护失效：第二次启动被放行，会撞上自己刚绑的端口并误报「地址已被占用」")
	} else if !strings.Contains(err.Error(), "正在启动") {
		t.Fatalf("错误措辞不足以定位问题: %v", err)
	}

	if err := svc.endStart(done, nil); err != nil {
		t.Fatalf("endStart 失败: %v", err)
	}
	if !svc.IsRunning() {
		t.Fatal("启动成功后 IsRunning 应为 true")
	}

	// 已经在跑时再启动是幂等的：不报错，也不重复启动
	instance, _, _, _, err = svc.beginStart()
	if err != nil {
		t.Fatalf("对已运行的实例再次启动不应报错: %v", err)
	}
	if instance != nil {
		t.Fatal("对已运行的实例不应再交出实例去启动一遍")
	}
}

// 启动失败后必须退回 idle，而不是卡在 starting。
//
// 卡住的后果是这个句柄再也起不来了，用户点多少次「启动」都只会看到
// 「内核正在启动中」—— 一个永远不会结束的启动。
func TestFailedStartReturnsToIdle(t *testing.T) {
	svc := newIdleService(t)

	_, _, _, done, err := svc.beginStart()
	if err != nil {
		t.Fatalf("beginStart 失败: %v", err)
	}
	wantErr := errStub("端口被占用")
	if got := svc.endStart(done, wantErr); got != error(wantErr) {
		t.Fatalf("endStart 应原样返回启动错误，实际: %v", got)
	}
	if svc.IsRunning() {
		t.Fatal("启动失败后 IsRunning 不应为 true")
	}

	// 关键：还能再试一次
	instance, _, _, done, err := svc.beginStart()
	if err != nil {
		t.Fatalf("启动失败后应当可以重试，实际被拒: %v", err)
	}
	if instance == nil {
		t.Fatal("重试时应当交出实例")
	}
	_ = svc.endStart(done, nil)
}

// Close 撞上进行中的启动时，必须先取消内核上下文、等那一段收尾，再关实例。
func TestCloseWaitsForInFlightStart(t *testing.T) {
	svc := newIdleService(t)

	_, ctx, _, done, err := svc.beginStart()
	if err != nil {
		t.Fatalf("beginStart 失败: %v", err)
	}

	closeReturned := make(chan struct{})
	go func() {
		defer close(closeReturned)
		if err := svc.Close(); err != nil {
			t.Errorf("Close 返回错误: %v", err)
		}
	}()

	// Close 应当先取消内核的 context，让锁外那段启动尽快自己收尾
	select {
	case <-ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("Close 没有取消内核上下文：正在下载 rule-set 的启动不会被打断")
	}

	// 在启动收尾之前，Close 必须还在等
	select {
	case <-closeReturned:
		t.Fatal("Close 没等启动收尾就返回了：这是一边 Start 一边 Close")
	case <-time.After(100 * time.Millisecond):
	}

	// 模拟锁外那段启动终于结束
	startResult := svc.endStart(done, nil)
	if startResult == nil {
		t.Fatal("启动过程中被关停时，Start 必须报错，否则宿主会以为内核起来了")
	}

	select {
	case <-closeReturned:
	case <-time.After(startDrainTimeout + 2*time.Second):
		t.Fatal("启动收尾后 Close 仍未返回")
	}

	if svc.IsRunning() {
		t.Fatal("Close 之后 IsRunning 必须为 false")
	}
}

// Close 卡在一个不响应取消的启动上时，不能永远挂着 —— 那在 Android 上就是 ANR。
func TestCloseGivesUpWaitingEventually(t *testing.T) {
	if testing.Short() {
		t.Skip("需要等满 startDrainTimeout")
	}
	svc := newIdleService(t)
	if _, _, _, _, err := svc.beginStart(); err != nil {
		t.Fatalf("beginStart 失败: %v", err)
	}
	// 刻意不调 endStart：模拟一个不响应 context 取消的组件

	start := time.Now()
	if err := svc.Close(); err != nil {
		t.Fatalf("Close 返回错误: %v", err)
	}
	elapsed := time.Since(start)
	if elapsed < startDrainTimeout {
		t.Fatalf("Close 只等了 %v 就放弃，短于 startDrainTimeout %v", elapsed, startDrainTimeout)
	}
	if elapsed > startDrainTimeout+3*time.Second {
		t.Fatalf("Close 等了 %v，远超上限 %v，调用方会 ANR", elapsed, startDrainTimeout)
	}

	// 放弃之后，第二次 Close 不能再等一遍 —— onDestroy 里连着调两次是常态
	start = time.Now()
	if err := svc.Close(); err != nil {
		t.Fatalf("第二次 Close 返回错误: %v", err)
	}
	if again := time.Since(start); again > time.Second {
		t.Fatalf("第二次 Close 又等了 %v，说明放弃后没有清掉启动标记", again)
	}
}

// 关停之后再启动必须报错，而不是空指针解引用 —— 后者在 Go 里是整个进程消失。
func TestStartAfterCloseFailsInsteadOfCrashing(t *testing.T) {
	svc := newIdleService(t)
	if err := svc.Close(); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}

	err := svc.Start()
	if err == nil {
		t.Fatal("已关停的句柄不应能再次启动")
	}
	if !strings.Contains(err.Error(), "已关闭") {
		t.Fatalf("错误措辞不足以定位问题: %v", err)
	}
	if svc.IsRunning() {
		t.Fatal("已关停的句柄不应报告为 running")
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	svc := newIdleService(t)
	for i := 0; i < 3; i++ {
		if err := svc.Close(); err != nil {
			t.Fatalf("第 %d 次 Close 返回错误: %v", i+1, err)
		}
	}
}

// 并发地 Close 同一个句柄，实例只能被关一次。
//
// gomobile 的调用方是 Android 的 Service，onDestroy 与超时看门狗同时调 Close
// 是完全可能的；重复 Close 一个 box 会撞进 sing-box 内部的重复关停路径。
func TestConcurrentCloseClosesInstanceOnce(t *testing.T) {
	svc := newIdleService(t)

	const goroutines = 16
	var wg sync.WaitGroup
	wg.Add(goroutines)
	errs := make(chan error, goroutines)
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			if err := svc.Close(); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Errorf("并发 Close 报错: %v", err)
	}

	svc.mu.Lock()
	leftover := svc.instance
	svc.mu.Unlock()
	if leftover != nil {
		t.Fatal("Close 之后实例引用应被摘干净，否则会被重复关停")
	}
}

// IsRunning 在状态迁移的全过程中都必须能瞬时回话。
//
// 用竞态检测跑（go test -race）时，这个测试同时也在守护状态字段的读写同步。
func TestIsRunningStaysResponsiveUnderChurn(t *testing.T) {
	svc := newIdleService(t)

	stop := make(chan struct{})
	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			instance, _, _, done, err := svc.beginStart()
			if err != nil || instance == nil {
				continue
			}
			_ = svc.endStart(done, nil)
			svc.mu.Lock()
			svc.state = stateIdle
			svc.mu.Unlock()
		}
	}()

	deadline := time.Now().Add(300 * time.Millisecond)
	for time.Now().Before(deadline) {
		probeStart := time.Now()
		svc.IsRunning()
		if took := time.Since(probeStart); took > time.Second {
			close(stop)
			wg.Wait()
			t.Fatalf("IsRunning 耗时 %v，锁竞争已经影响到存活探测", took)
		}
	}
	close(stop)
	wg.Wait()
}

// StartWithTimeout：上下文已经被取消时，即使底层 Start 报成功也必须当成失败。
//
// 这条路径最容易被写漏。漏了的后果是宿主以为内核起来了，实际拿到的是一个
// 上下文已死的空壳 —— 所有出站都会立刻失败，而 UI 上显示「已连接」。
func TestStartReportsFailureWhenContextAlreadyCancelled(t *testing.T) {
	svc := newIdleService(t)
	svc.cancel()

	err := svc.StartWithTimeout(0)
	if err == nil {
		t.Fatal("上下文已取消时 Start 必须报错")
	}
	if svc.IsRunning() {
		t.Fatal("启动失败后不应报告为 running")
	}
}

// 超时参数非正数时等同于不设截止时间，不能退化成「立刻超时」。
func TestNonPositiveTimeoutMeansNoDeadline(t *testing.T) {
	for _, timeoutMs := range []int64{0, -1} {
		svc := newIdleService(t)
		if err := svc.StartWithTimeout(timeoutMs); err != nil {
			t.Fatalf("StartWithTimeout(%d) 失败: %v", timeoutMs, err)
		}
		if !svc.IsRunning() {
			t.Fatalf("StartWithTimeout(%d) 之后应处于 running", timeoutMs)
		}
		if err := svc.Close(); err != nil {
			t.Fatalf("Close 失败: %v", err)
		}
	}
}

// Start 成功后 IsRunning 为真，Close 后为假 —— 走真实的 box，不是状态字段体操。
func TestStartCloseRoundTrip(t *testing.T) {
	svc := newIdleService(t)

	if svc.IsRunning() {
		t.Fatal("尚未启动就报告 running")
	}
	if err := svc.StartWithTimeout(30_000); err != nil {
		t.Fatalf("Start 失败: %v", err)
	}
	if !svc.IsRunning() {
		t.Fatal("Start 成功后 IsRunning 应为 true")
	}
	// 幂等：重复 Start 不报错
	if err := svc.Start(); err != nil {
		t.Fatalf("重复 Start 不应报错: %v", err)
	}
	if err := svc.Close(); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}
	if svc.IsRunning() {
		t.Fatal("Close 后 IsRunning 应为 false")
	}
}

// newIdleService 构造一个已装配、未启动的实例。
//
// 端口交给操作系统分配（listen_port 0），否则并行跑测试时会互相撞端口。
func newIdleService(t *testing.T) *Service {
	t.Helper()
	svc, err := NewService(idleConfig, t.TempDir())
	if err != nil {
		t.Fatalf("构造实例失败: %v", err)
	}
	t.Cleanup(func() { _ = svc.Close() })
	return svc
}

const idleConfig = `{
  "log": { "level": "error" },
  "dns": {
    "servers": [
      { "type": "udp", "tag": "dns-local", "server": "223.5.5.5" }
    ],
    "final": "dns-local"
  },
  "inbounds": [
    { "type": "mixed", "tag": "in-test", "listen": "127.0.0.1", "listen_port": 0 }
  ],
  "outbounds": [
    { "type": "direct", "tag": "direct" }
  ],
  "route": {
    "rules": [ { "action": "sniff" } ],
    "final": "direct",
    "auto_detect_interface": false,
    "default_domain_resolver": "dns-local"
  }
}`

type errStub string

func (e errStub) Error() string { return string(e) }
