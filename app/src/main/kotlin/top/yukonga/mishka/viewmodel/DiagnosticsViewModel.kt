package top.yukonga.mishka.viewmodel

import top.yukonga.mishka.data.diagnostics.ConfigDiagnosticsBuilder

/**
 * 配置诊断页面的薄包装：只持有 [ConfigDiagnosticsBuilder]，不缓存任何快照。
 *
 * 快照按需拉取、**不放进 ViewModel 状态**——诊断内容含订阅名 / 端口等运行时值，
 * 常驻在进程级 single 里既无必要也会延长敏感上下文的内存生命周期。
 * 校验会写临时 transform 文件并经 native 解析，故统一由 UI 协程按需触发。
 */
class DiagnosticsViewModel(
    val builder: ConfigDiagnosticsBuilder,
)
