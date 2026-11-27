import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

export interface Agent {
  id: string; name: string; description: string; category: string; instructions: string; accountId: string; model: string
  temperature: number; maxOutputTokens: number; inputSchema: Record<string, unknown>; outputSchema: Record<string, unknown>
  tools: string[]; permissions: string[]; timeoutSeconds: number; retryCount: number; projectId?: string
  createdBy: string; enabled: boolean; version: number; createdAt: string; updatedAt: string
}
export type AgentInput = Omit<Agent, 'id' | 'createdBy' | 'version' | 'createdAt' | 'updatedAt'>
export interface AgentPage { page: number; size: number; total: number; items: Pick<Agent, 'id' | 'name' | 'category' | 'projectId' | 'enabled' | 'model' | 'version' | 'updatedAt'>[] }
export interface AgentDashboard { totalAgents: number; activeAgents: number; disabledAgents: number; runningExecutions: number; failedExecutions: number; completedExecutions: number; queuedExecutions: number }
export interface AgentPolicy { allowPaidModels: boolean }
export interface AgentTemplate { key: string; name: string; description: string; category: string; configuration: Record<string, unknown> }
export interface AgentTool { id: string; key: string; name: string; description: string; inputSchema: Record<string, unknown>; outputSchema: Record<string, unknown>; permission: string; timeoutSeconds: number; enabled: boolean }
export interface AgentExecution {
  id: string; agentId?: string; agentName: string; projectId?: string; createdBy: string; status: string; provider?: string; model?: string
  input: Record<string, unknown>; output?: Record<string, unknown>; error?: string; inputTokens?: number; outputTokens?: number
  estimatedCost?: number; startedAt?: string; completedAt?: string; createdAt: string; retryOf?: string
}
export interface ExecutionPage { page: number; size: number; total: number; items: AgentExecution[] }
export interface WorkflowNode { id: string; agentId: string; requiresApproval?: boolean; inputMapping?: Record<string, unknown> }
export interface WorkflowEdge { source: string; target: string; condition?: { path: string; equals: unknown } }
export interface Workflow { id: string; name: string; description: string; projectId?: string; definition: { nodes: WorkflowNode[]; edges: WorkflowEdge[] }; enabled: boolean; version: number; updatedAt: string }
export interface WorkflowExecution { id: string; workflowId: string; workflowName: string; status: string; currentNode?: string; input: Record<string, unknown>; output?: Record<string, unknown>; error?: string; createdAt: string }

export const agentKeys = {
  dashboard: ['agents', 'dashboard'] as const,
  policy: ['agents', 'policy'] as const,
  list: (params: object) => ['agents', 'list', params] as const,
  detail: (id: string) => ['agent', id] as const,
  templates: ['agents', 'templates'] as const,
  tools: ['agents', 'tools'] as const,
  executions: (params: object) => ['agent-executions', params] as const,
  execution: (id: string) => ['agent-execution', id] as const,
  logs: (id: string) => ['agent-execution-logs', id] as const,
  versions: (id: string) => ['agent-versions', id] as const,
  workflows: ['agent-workflows'] as const,
  workflowExecutions: ['agent-workflow-executions'] as const,
}

export function useAgentDashboard() { return useQuery({ queryKey: agentKeys.dashboard, queryFn: () => api.get<AgentDashboard>('/agents/dashboard'), refetchInterval: 10_000 }) }
export function useAgentPolicy() { return useQuery({ queryKey: agentKeys.policy, queryFn: () => api.get<AgentPolicy>('/agents/policy'), staleTime: Infinity }) }
export function useAgents(params: { page: number; size: number; search?: string; category?: string; enabled?: boolean }) {
  const qs = new URLSearchParams({ page: String(params.page), size: String(params.size) })
  if (params.search) qs.set('search', params.search)
  if (params.category) qs.set('category', params.category)
  if (params.enabled !== undefined) qs.set('enabled', String(params.enabled))
  return useQuery({ queryKey: agentKeys.list(params), queryFn: () => api.get<AgentPage>(`/agents?${qs}`), placeholderData: keepPreviousData })
}
export function useAgentTemplates() { return useQuery({ queryKey: agentKeys.templates, queryFn: () => api.get<AgentTemplate[]>('/agents/templates') }) }
export function useAgentTools() { return useQuery({ queryKey: agentKeys.tools, queryFn: () => api.get<AgentTool[]>('/agents/tools') }) }
export function useAgent(id?: string) { return useQuery({ queryKey: agentKeys.detail(id ?? ''), queryFn: () => api.get<Agent>(`/agents/${id}`), enabled: !!id }) }
export function useAgentVersions(id?: string) { return useQuery({ queryKey: agentKeys.versions(id ?? ''), queryFn: () => api.get<{ version: number; configuration: AgentInput; changedBy?: string; createdAt: string }[]>(`/agents/${id}/versions`), enabled: !!id }) }
export function useExecutions(params: { page: number; size: number; status?: string }) {
  const qs = new URLSearchParams({ page: String(params.page), size: String(params.size) })
  if (params.status) qs.set('status', params.status)
  return useQuery({ queryKey: agentKeys.executions(params), queryFn: () => api.get<ExecutionPage>(`/agent-executions?${qs}`), refetchInterval: 5000 })
}
export function useExecution(id?: string) {
  return useQuery({ queryKey: agentKeys.execution(id ?? ''), queryFn: () => api.get<AgentExecution>(`/agent-executions/${id}`), enabled: !!id, refetchInterval: (q) => ['QUEUED', 'PENDING', 'RUNNING'].includes(q.state.data?.status ?? '') ? 2000 : false })
}
export function useExecutionLogs(id?: string) {
  return useQuery({ queryKey: agentKeys.logs(id ?? ''), queryFn: () => api.get<{ id: number; level: string; stage: string; message: string; createdAt: string }[]>(`/agent-executions/${id}/logs`), enabled: !!id, refetchInterval: 3000 })
}

function useInvalidateAgents() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['agents'] })
    qc.invalidateQueries({ queryKey: ['agent-executions'] })
    qc.invalidateQueries({ queryKey: ['agent-execution'] })
  }
}
export function useCreateAgent() {
  const invalidate = useInvalidateAgents()
  return useMutation({ mutationFn: (body: AgentInput) => api.post<Agent>('/agents', body), onSuccess: invalidate })
}
export function useUpdateAgent() {
  const invalidate = useInvalidateAgents()
  return useMutation({ mutationFn: ({ id, ...body }: AgentInput & { id: string }) => api.put<Agent>(`/agents/${id}`, body), onSuccess: invalidate })
}
export function useAgentAction() {
  const invalidate = useInvalidateAgents()
  return useMutation<Agent | void, Error, { id: string; action: 'enable' | 'disable' | 'duplicate' | 'delete' }>({ mutationFn: ({ id, action }) =>
    action === 'delete' ? api.delete(`/agents/${id}`) : api.post<Agent>(`/agents/${id}/${action}`), onSuccess: invalidate })
}
export function useCreateFromTemplate() {
  const invalidate = useInvalidateAgents()
  return useMutation({ mutationFn: ({ key, name, accountId, model }: { key: string; name: string; accountId: string; model: string }) =>
    api.post<Agent>(`/agents/templates/${key}/create`, { name, accountId, model }), onSuccess: invalidate })
}
export function useRunAgent() {
  const invalidate = useInvalidateAgents()
  return useMutation({ mutationFn: ({ id, input, test }: { id: string; input: Record<string, unknown>; test?: boolean }) =>
    api.post<AgentExecution>(`/agents/${id}/${test ? 'test' : 'execute'}`, { input }), onSuccess: invalidate })
}
export function useExecutionAction() {
  const invalidate = useInvalidateAgents()
  return useMutation({ mutationFn: ({ id, action }: { id: string; action: 'retry' | 'cancel' }) =>
    api.post<AgentExecution>(`/agent-executions/${id}/${action}`), onSuccess: invalidate })
}
export function useRestoreAgentVersion() {
  const invalidate = useInvalidateAgents()
  const qc = useQueryClient()
  return useMutation({ mutationFn: ({ id, version }: { id: string; version: number }) => api.post<void>(`/agents/${id}/versions/${version}/restore`), onSuccess: (_d, vars) => { invalidate(); qc.invalidateQueries({ queryKey: agentKeys.detail(vars.id) }) } })
}
export function useWorkflows() { return useQuery({ queryKey: agentKeys.workflows, queryFn: () => api.get<{ items: Workflow[]; total: number }>('/agent-workflows?page=0&size=100') }) }
export function useWorkflow(id?: string) { return useQuery({ queryKey: ['agent-workflow', id], queryFn: () => api.get<Workflow>(`/agent-workflows/${id}`), enabled: !!id }) }
export function useWorkflowExecutions() { return useQuery({ queryKey: agentKeys.workflowExecutions, queryFn: () => api.get<{ items: WorkflowExecution[]; total: number }>('/agent-workflow-executions?page=0&size=50'), refetchInterval: 5000 }) }
export function useSaveWorkflow() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: ({ id, ...body }: { id?: string; name: string; description: string; definition: Workflow['definition']; enabled: boolean }) => id ? api.put<Workflow>(`/agent-workflows/${id}`, body) : api.post<Workflow>('/agent-workflows', body), onSuccess: () => { qc.invalidateQueries({ queryKey: agentKeys.workflows }) } })
}
export function useRunWorkflow() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: ({ id, input }: { id: string; input: Record<string, unknown> }) => api.post<WorkflowExecution>(`/agent-workflows/${id}/execute`, { input }), onSuccess: () => qc.invalidateQueries({ queryKey: agentKeys.workflowExecutions }) })
}
export function useWorkflowAction() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: ({ id, action }: { id: string; action: 'approve' | 'reject' | 'pause' | 'resume' | 'cancel' | 'retry' }) => api.post<WorkflowExecution>(`/agent-workflow-executions/${id}/${action}`), onSuccess: () => { qc.invalidateQueries({ queryKey: agentKeys.workflowExecutions }) } })
}
