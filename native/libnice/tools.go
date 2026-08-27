//go:build tools

package libnice

// gomobile bind 需要能从当前模块解析到 bind 运行时包，
// 但没有任何业务代码会 import 它，go mod tidy 会把它当成无用依赖删掉。
// 这里用带 tools 构建标签的空白导入把版本钉住 —— 该文件不参与任何实际构建。
import _ "github.com/sagernet/gomobile/bind"
