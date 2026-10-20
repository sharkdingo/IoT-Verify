// src/api/simulation.ts - Simulation API
import api from './http'
import { saveBlobResponseAsFile } from '@/utils/attachmentDownload'
import type {
  SimulationRequest,
  SimulationResult,
  SimulationTrace,
  SimulationTraceSummary,
  SimulationTask,
  SimulationTaskSummary
} from '@/types/simulation'
import type { TaskCancellationResult } from '@/types/task'
import {
  validateSimulationTask,
  validateSimulationTaskSummaryList,
  validateSimulationResult,
  validateSimulationTrace,
  validateSimulationTraceSummaryList,
  validateTaskCancellationResult
} from '@/utils/runResponse'

// 辅助函数：解包Result
const unpack = <T>(response: any): T => {
  return response.data.data
}

const SERVER_BOUNDED_REQUEST = { timeout: 0 } as const

export default {
  // 执行模拟（不保存）- 同步
  simulate: async (req: SimulationRequest): Promise<SimulationResult> => {
    return validateSimulationResult(
      unpack<unknown>(await api.post('/simulate', req, SERVER_BOUNDED_REQUEST))
    )
  },

  // 执行异步模拟并接收服务端真实任务快照
  simulateAsync: async (req: SimulationRequest): Promise<SimulationTask> => {
    return validateSimulationTask(
      unpack<unknown>(await api.post('/simulate/async', req))
    )
  },

  // 获取任务状态
  getTask: async (taskId: number): Promise<SimulationTask> => {
    return validateSimulationTask(
      unpack<unknown>(await api.get(`/simulate/tasks/${taskId}`))
    )
  },

  // 获取当前用户的仿真任务收件箱
  getTasks: async (excludeTaskIds: number[] = []): Promise<SimulationTaskSummary[]> => {
    const params = excludeTaskIds.length > 0
      ? { excludeTaskIds: excludeTaskIds.join(',') }
      : undefined
    return validateSimulationTaskSummaryList(
      unpack<unknown>(await api.get('/simulate/tasks', { params }))
    )
  },

  deleteTask: async (taskId: number): Promise<void> => {
    return unpack<void>(await api.delete(`/simulate/tasks/${taskId}`))
  },

  // 取消任务
  cancelTask: async (taskId: number): Promise<TaskCancellationResult> => {
    return validateTaskCancellationResult(
      unpack<unknown>(await api.post(`/simulate/tasks/${taskId}/cancel`)),
      taskId,
      'Simulation task cancellation'
    )
  },

  // 执行模拟并保存到数据库
  simulateAndSave: async (req: SimulationRequest): Promise<SimulationTrace> => {
    return validateSimulationTrace(
      unpack<unknown>(await api.post('/simulate/traces', req, SERVER_BOUNDED_REQUEST))
    )
  },

  // 获取用户模拟记录列表
  getUserSimulations: async (): Promise<SimulationTraceSummary[]> => {
    return validateSimulationTraceSummaryList(
      unpack<unknown>(await api.get('/simulate/traces'))
    )
  },

  // 获取单条模拟记录
  getSimulation: async (id: number): Promise<SimulationTrace> => {
    return validateSimulationTrace(
      unpack<unknown>(await api.get(`/simulate/traces/${id}`))
    )
  },

  // 删除模拟记录
  deleteSimulation: async (id: number): Promise<void> => {
    return unpack<void>(await api.delete(`/simulate/traces/${id}`))
  },

  // 下载本次模拟执行的 SMV 模型文件
  downloadSimulationSmvModel: async (id: number): Promise<void> => {
    const response = await api.get(`/simulate/traces/${id}/smv`, {
      responseType: 'blob',
      headers: { Accept: 'text/plain' }
    })
    saveBlobResponseAsFile(response, `simulation-trace-${id}.smv`)
  }
}
