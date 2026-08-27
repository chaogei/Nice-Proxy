package libnice

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 内核契约测试。
//
// Kotlin 侧的 SingBoxConfigBuilder 是照着 sing-box 文档手写的，它和内核之间
// 没有编译期约束 —— 内核废弃一个字段、改一处语义，生成器不会有任何感知，
// 问题要到用户点「启动」时才暴露。
//
// 这个测试把 core:config 的 golden 快照喂给内核完整装配一遍，一旦两边脱节立刻失败。
// 见 docs/DESIGN.md §11.4。
func TestGeneratedConfigIsAcceptedByKernel(t *testing.T) {
	goldenDir := filepath.Join("..", "..", "core", "config", "src", "test", "resources", "golden")
	entries, err := os.ReadDir(goldenDir)
	if err != nil {
		t.Fatalf("读取 golden 目录失败: %v", err)
	}

	var checked int
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		name := entry.Name()
		t.Run(name, func(t *testing.T) {
			content, err := os.ReadFile(filepath.Join(goldenDir, name))
			if err != nil {
				t.Fatalf("读取 %s 失败: %v", name, err)
			}
			if err := ValidateConfig(string(content)); err != nil {
				t.Fatalf("内核拒绝了生成的配置:\n%v", err)
			}
		})
		checked++
	}

	if checked == 0 {
		t.Fatal("没有找到任何 golden 配置，测试没有实际生效")
	}
}

// 守护 ValidateConfig 相对 CheckConfig 的增量价值。
//
// CheckConfig 只做反序列化，下面这些配置它一律放行；只有走完组件装配才能发现。
// 如果哪天 ValidateConfig 被改弱成纯解析，这里会失败。
//
// 需要说明的是，装配期校验仍有盲区：像「DNS detour 到空 direct 出站」这类
// 错误只在 Start() 阶段抛出，装配期看不出来。那类约束由 Kotlin 侧的生成器
// 约束测试守护（C-10），不在这里重复。
func TestValidateConfigCatchesSemanticErrors(t *testing.T) {
	cases := map[string]struct {
		mutate      func(map[string]any)
		wantMessage string
	}{
		"REALITY 公钥格式非法": {
			mutate: func(cfg map[string]any) {
				outbounds := cfg["outbounds"].([]any)
				cfg["outbounds"] = append(outbounds, map[string]any{
					"type": "vless", "tag": "bad-node",
					"server": "example.com", "server_port": 443,
					"uuid": "11111111-2222-3333-4444-555555555555",
					"tls": map[string]any{
						"enabled": true, "server_name": "example.com",
						"reality": map[string]any{"enabled": true, "public_key": "not-a-key"},
						"utls":    map[string]any{"enabled": true, "fingerprint": "chrome"},
					},
				})
			},
			// 内核对「长度不对」和「不是合法 base64」会给出不同措辞，
			// 只断言共同的关键字，避免测试被无关的文案调整打断。
			wantMessage: "public_key",
		},
		"Shadowsocks 加密方式不存在": {
			mutate: func(cfg map[string]any) {
				outbounds := cfg["outbounds"].([]any)
				cfg["outbounds"] = append(outbounds, map[string]any{
					"type": "shadowsocks", "tag": "bad-ss",
					"server": "example.com", "server_port": 8388,
					"method": "totally-made-up", "password": "pw",
				})
			},
			wantMessage: "method",
		},
	}

	base := minimalConfig(t)
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			var cfg map[string]any
			if err := json.Unmarshal([]byte(base), &cfg); err != nil {
				t.Fatalf("解析基准配置失败: %v", err)
			}
			tc.mutate(cfg)
			mutated, err := json.Marshal(cfg)
			if err != nil {
				t.Fatalf("序列化失败: %v", err)
			}

			if err := CheckConfig(string(mutated)); err != nil {
				t.Logf("CheckConfig 也发现了该问题（意料之外但无妨）: %v", err)
			}

			err = ValidateConfig(string(mutated))
			if err == nil {
				t.Fatal("ValidateConfig 放行了一个内核实际会拒绝的配置")
			}
			if !strings.Contains(err.Error(), tc.wantMessage) {
				t.Fatalf("错误原因与预期不符\n实际: %v\n期望包含: %s", err, tc.wantMessage)
			}
		})
	}
}

func TestVersionIsReported(t *testing.T) {
	if Version() == "" {
		t.Fatal("Version() 返回空字符串，构建时可能漏了 -X constant.Version")
	}
}

// 一份最小可用配置，作为语义错误用例的基准。
func minimalConfig(t *testing.T) string {
	t.Helper()
	return `{
  "log": { "level": "error" },
  "dns": {
    "servers": [
      { "type": "udp", "tag": "dns-local", "server": "223.5.5.5" }
    ],
    "final": "dns-local"
  },
  "inbounds": [
    { "type": "mixed", "tag": "in-test", "listen": "127.0.0.1", "listen_port": 34567 }
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
}
