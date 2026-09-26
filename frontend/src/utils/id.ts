/**
 * 生成数字人 ID：agent- + 毫秒时间戳（19 字符，远低于 32 上限）。
 * 人机交互节奏下毫秒级碰撞概率为零，时间戳天然有序便于排查。
 */
export function generateAgentId(): string {
  return `agent-${Date.now()}`
}
