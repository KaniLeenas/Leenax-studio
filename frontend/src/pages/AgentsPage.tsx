import { Activity, Bot, Check, Clock3, Copy, Eye, GitBranch, History, Pause, Play, Plus, RefreshCw, Search, ShieldAlert, Trash2, Wrench } from 'lucide-react'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { useAccounts, useTextModels } from '../lib/aiQueries'
import {
  useAgent, useAgentAction, useAgentDashboard, useAgentTemplates, useAgentTools, useAgents, useCreateAgent,
  useExecution, useExecutionAction, useExecutionLogs, useExecutions, useRestoreAgentVersion,
  useRunAgent, useUpdateAgent, useAgentVersions,
  useWorkflows, useWorkflow, useWorkflowExecutions, useSaveWorkflow, useRunWorkflow, useWorkflowAction, useAgentPolicy,
  type Agent, type AgentExecution, type AgentInput, type AgentTemplate,
} from '../lib/agents'
import { formatDate, formatRelative } from '../lib/format'
import { toast } from '../stores/ui'

type Tab = 'agents' | 'workflows' | 'runs' | 'tools'

export function AgentsPage() {
  const [tab, setTab] = useState<Tab>('agents')
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState('')
  const [enabled, setEnabled] = useState('')
  const [editingId, setEditingId] = useState<string>()
  const [createOpen, setCreateOpen] = useState(false)
  const [playAgent, setPlayAgent] = useState<Agent>()
  const [inspectId, setInspectId] = useState<string>()
  const [workflowOpen, setWorkflowOpen] = useState(false)
  const [workflowId, setWorkflowId] = useState<string>()
  const dashboard = useAgentDashboard()
  const policy = useAgentPolicy()
  const agents = useAgents({ page, size: 20, search, category: category || undefined, enabled: enabled === '' ? undefined : enabled === 'true' })
  const executions = useExecutions({ page, size: 20 })
  const tools = useAgentTools()
  const templates = useAgentTemplates()
  const action = useAgentAction()
  const workflows = useWorkflows()
  const workflowRuns = useWorkflowExecutions()
  const runWorkflow = useRunWorkflow()
  const workflowAction = useWorkflowAction()

  const totalPages = Math.max(1, Math.ceil((agents.data?.total ?? 0) / 20))

  return (
    <div className="max-w-7xl mx-auto p-5 sm:p-8">
      <PageHeader title="AI Agents" description={policy.data?.allowPaidModels ? "Configure agents with existing provider accounts. Paid providers are enabled by the owner." : "Configure agents with Ollama or FreeLLMAPI. Other hosted provider calls are blocked by default."} actions={
        <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreateOpen(true)}>Create agent</Button>
      } />

      {dashboard.data && <div className="grid grid-cols-2 md:grid-cols-4 xl:grid-cols-7 gap-3 mb-7">
        <Metric label="Total agents" value={dashboard.data.totalAgents} icon={<Bot />} />
        <Metric label="Active" value={dashboard.data.activeAgents} icon={<Check />} />
        <Metric label="Disabled" value={dashboard.data.disabledAgents} icon={<Pause />} />
        <Metric label="Running" value={dashboard.data.runningExecutions} icon={<Activity />} />
        <Metric label="Queued" value={dashboard.data.queuedExecutions} icon={<Clock3 />} />
        <Metric label="Completed" value={dashboard.data.completedExecutions} icon={<Check />} />
        <Metric label="Failed" value={dashboard.data.failedExecutions} icon={<ShieldAlert />} />
      </div>}

      <div className="flex gap-2 border-b border-line mb-5" role="tablist" aria-label="Agent tools">
        {([['agents', 'Agents', Bot], ['workflows', 'Workflows', GitBranch], ['runs', 'Executions', History], ['tools', 'Tool registry', Wrench]] as const).map(([key, title, Icon]) => (
          <button key={key} role="tab" aria-selected={tab === key} onClick={() => setTab(key)} className={`px-4 py-3 text-sm border-b-2 ${tab === key ? 'border-marigold-500 text-ink font-medium' : 'border-transparent text-muted hover:text-ink'}`}>
            <span className="inline-flex items-center gap-2"><Icon className="size-4" />{title}</span>
          </button>
        ))}
      </div>

      {tab === 'agents' && <>
        <div className="flex flex-wrap items-end gap-3 mb-4">
          <div className="w-full sm:max-w-xs"><Field label="Search agents">{(id) => <div className="relative"><Search className="absolute left-3 top-3 size-4 text-faint" /><Input id={id} className="pl-9" value={search} onChange={(e) => { setSearch(e.target.value); setPage(0) }} placeholder="Name or description" /></div>}</Field></div>
          <div className="w-44"><Field label="Category">{(id) => <Select id={id} value={category} onChange={(e) => { setCategory(e.target.value); setPage(0) }}><option value="">All categories</option>{['DOCUMENT', 'EDUCATION', 'RESEARCH', 'CONTENT', 'VIDEO', 'PUBLISH'].map((v) => <option key={v}>{v}</option>)}</Select>}</Field></div>
          <div className="w-40"><Field label="Status">{(id) => <Select id={id} value={enabled} onChange={(e) => { setEnabled(e.target.value); setPage(0) }}><option value="">All statuses</option><option value="true">Enabled</option><option value="false">Disabled</option></Select>}</Field></div>
          <Button onClick={() => { setSearch(''); setCategory(''); setEnabled(''); setPage(0) }}>Clear filters</Button>
        </div>
        {agents.isPending ? <Spinner label="Loading agents" /> : agents.error ? <ErrorNotice error={agents.error} onRetry={() => agents.refetch()} /> : !agents.data?.items.length ? (
          <EmptyState title="No agents found" body="Create an agent from scratch or start with one of the specialist templates." action={<Button variant="primary" onClick={() => setCreateOpen(true)}>Create your first agent</Button>} />
        ) : <>
          <div className="rounded-2xl border border-line bg-surface overflow-x-auto">
            <table className="w-full text-sm"><thead className="text-left text-muted"><tr className="border-b border-line">
              <th className="px-4 py-3 font-medium">Agent</th><th className="px-4 py-3 font-medium">Category</th><th className="px-4 py-3 font-medium">Model</th><th className="px-4 py-3 font-medium">Version</th><th className="px-4 py-3 font-medium">Status</th><th className="px-4 py-3 font-medium">Updated</th><th className="px-4 py-3"><span className="sr-only">Actions</span></th>
            </tr></thead><tbody className="divide-y divide-line">{agents.data.items.map((a) => <tr key={a.id}>
              <td className="px-4 py-3 min-w-64"><button className="font-medium text-ink hover:underline" onClick={() => setEditingId(a.id)}>{a.name}</button><span className="block text-xs text-muted">{a.projectId ? 'Project agent' : 'Studio agent'}</span></td>
              <td className="px-4 py-3"><StatusPill tone="neutral">{a.category}</StatusPill></td><td className="px-4 py-3">{a.model}</td><td className="px-4 py-3">v{a.version}</td>
              <td className="px-4 py-3"><StatusPill tone={a.enabled ? 'done' : 'muted'}>{a.enabled ? 'Enabled' : 'Disabled'}</StatusPill></td><td className="px-4 py-3 whitespace-nowrap">{formatRelative(a.updatedAt)}</td>
              <td className="px-4 py-3"><div className="flex items-center justify-end gap-1">
                <Button size="sm" title="Test agent" aria-label={`Test ${a.name}`} onClick={() => { void agents.refetch(); setPlayAgent({ ...a, description: '', instructions: '', accountId: '', temperature: 0.2, maxOutputTokens: 2000, inputSchema: {}, outputSchema: {}, tools: [], permissions: [], timeoutSeconds: 90, retryCount: 1, createdBy: '', createdAt: '', enabled: a.enabled }) }}>Test</Button>
                <Button size="sm" title="Duplicate agent" aria-label={`Duplicate ${a.name}`} icon={<Copy className="size-3.5" />} onClick={() => action.mutate({ id: a.id, action: 'duplicate' }, { onSuccess: () => toast.success('Agent duplicated'), onError: (e) => toast.error(e.message) })} />
                <Button size="sm" title={a.enabled ? 'Disable agent' : 'Enable agent'} aria-label={a.enabled ? `Disable ${a.name}` : `Enable ${a.name}`} icon={a.enabled ? <Pause className="size-3.5" /> : <Play className="size-3.5" />} onClick={() => action.mutate({ id: a.id, action: a.enabled ? 'disable' : 'enable' }, { onError: (e) => toast.error(e.message) })} />
                <Button size="sm" title="Delete agent" aria-label={`Delete ${a.name}`} variant="danger" icon={<Trash2 className="size-3.5" />} onClick={() => { if (confirm(`Delete ${a.name}? Its execution history will remain.`)) action.mutate({ id: a.id, action: 'delete' }, { onSuccess: () => toast.success('Agent deleted'), onError: (e) => toast.error(e.message) }) }} />
              </div></td>
            </tr>)}</tbody></table>
          </div>
          <div className="mt-4 flex items-center justify-between text-sm text-muted"><span>{agents.data.total} agents</span><div className="flex gap-2"><Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button><span className="px-2 py-1">Page {page + 1} of {totalPages}</span><Button size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button></div></div>
        </>}
      </>}

      {tab === 'runs' && <ExecutionList data={executions.data?.items ?? []} pending={executions.isPending} error={executions.error} onRetry={() => executions.refetch()} onInspect={setInspectId} />}
      {tab === 'workflows' && <section className="space-y-5">
        <div className="flex justify-between items-center"><div><h2 className="font-semibold">Workflow builder</h2><p className="text-sm text-muted">Connect agents, branch on structured output, and pause for human approval.</p></div><Button variant="primary" icon={<Plus className="size-4" />} onClick={() => { setWorkflowId(undefined); setWorkflowOpen(true) }}>New workflow</Button></div>
        {workflows.isPending ? <Spinner label="Loading workflows" /> : workflows.error ? <ErrorNotice error={workflows.error} onRetry={() => workflows.refetch()} /> : <div className="grid lg:grid-cols-2 gap-4">{(workflows.data?.items ?? []).map((w) => <article key={w.id} className="rounded-xl border border-line bg-surface p-4"><div className="flex justify-between gap-4"><div><h3 className="font-medium">{w.name}</h3><p className="text-sm text-muted mt-1">{w.description || 'Agent workflow'} · v{w.version}</p></div><div className="flex gap-2"><Button size="sm" onClick={() => { setWorkflowId(w.id); setWorkflowOpen(true) }}>Edit</Button><Button size="sm" variant="primary" icon={<Play className="size-3.5" />} onClick={() => { const raw = prompt('Workflow input as JSON', '{\n  "topic": "Photosynthesis"\n}'); if (raw) { try { runWorkflow.mutate({ id: w.id, input: JSON.parse(raw) }, { onSuccess: () => toast.success('Workflow queued'), onError: (e) => toast.error(e.message) }) } catch { toast.error('Enter valid JSON.') } } }}>Run</Button></div></div></article>)}</div>}
        <div><h3 className="font-semibold mb-2">Recent workflow runs</h3>{workflowRuns.data?.items.length ? <div className="rounded-xl border border-line bg-surface divide-y divide-line">{workflowRuns.data.items.map((run) => <div key={run.id} className="flex flex-wrap items-center justify-between gap-3 p-3"><div><span className="font-medium">{run.workflowName}</span><span className="ml-3"><StatusPill tone={run.status === 'COMPLETED' ? 'done' : run.status === 'FAILED' ? 'muted' : 'progress'}>{run.status.replace('_', ' ')}</StatusPill></span><p className="text-xs text-muted mt-1">{run.currentNode ? `At ${run.currentNode} · ` : ''}{formatRelative(run.createdAt)}</p>{run.error && <p className="text-xs text-rose-signal mt-1">{run.error}</p>}</div><div className="flex gap-2">{run.status === 'WAITING_APPROVAL' && <><Button size="sm" variant="primary" onClick={() => workflowAction.mutate({ id: run.id, action: 'approve' }, { onError: (e) => toast.error(e.message) })}>Approve</Button><Button size="sm" variant="danger" onClick={() => workflowAction.mutate({ id: run.id, action: 'reject' }, { onError: (e) => toast.error(e.message) })}>Reject</Button><Button size="sm" onClick={() => workflowAction.mutate({ id: run.id, action: 'pause' }, { onError: (e) => toast.error(e.message) })}>Pause</Button></>}{run.status === 'PAUSED' && <Button size="sm" variant="primary" onClick={() => workflowAction.mutate({ id: run.id, action: 'resume' }, { onError: (e) => toast.error(e.message) })}>Resume to approval</Button>}{['QUEUED','RUNNING','WAITING_APPROVAL','PAUSED'].includes(run.status) && <Button size="sm" onClick={() => workflowAction.mutate({ id: run.id, action: 'cancel' }, { onError: (e) => toast.error(e.message) })}>Cancel</Button>}</div></div>)}</div> : <p className="text-sm text-muted">No workflow runs yet.</p>}</div>
      </section>}
      {tab === 'tools' && <div className="grid md:grid-cols-2 gap-3">{tools.data?.map((tool) => <div key={tool.key} className="rounded-xl border border-line bg-surface p-4"><div className="flex items-start justify-between gap-3"><div><h3 className="font-medium">{tool.name}</h3><p className="text-sm text-muted mt-1">{tool.description}</p></div><StatusPill tone={tool.enabled ? 'done' : 'muted'}>{tool.enabled ? 'Registered' : 'Disabled'}</StatusPill></div><div className="mt-3 flex gap-2 text-xs text-faint"><span>{tool.key}</span><span>·</span><span>{tool.permission}</span><span>·</span><span>{tool.timeoutSeconds}s timeout</span></div></div>)}<div className="md:col-span-2 rounded-xl bg-surface-2 p-4 text-sm text-muted">This is the allowlisted tool catalog. Agent runs currently use the connected text model and return structured JSON; runtime tool dispatch is not connected yet. Agents cannot run shell commands or receive provider credentials.</div></div>}

      <AgentEditorDialog id={editingId} onClose={() => setEditingId(undefined)} />
      <CreateAgentDialog open={createOpen} templates={templates.data ?? []} onClose={() => setCreateOpen(false)} />
      <PlaygroundDialog agent={playAgent} onClose={() => setPlayAgent(undefined)} />
      <ExecutionDialog id={inspectId} onClose={() => setInspectId(undefined)} />
      <WorkflowBuilderDialog open={workflowOpen} id={workflowId} onClose={() => setWorkflowOpen(false)} />
    </div>
  )
}

type BuilderNode = { id: string; agentId: string; requiresApproval: boolean; next: string; fallback: string; conditionPath: string; conditionValue: string; inputMapping: string }

function WorkflowBuilderDialog({ open, id, onClose }: { open: boolean; id?: string; onClose: () => void }) {
  const detail = useWorkflow(id)
  const agentPage = useAgents({ page: 0, size: 100 })
  const save = useSaveWorkflow()
  const executeWorkflow = useRunWorkflow()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nodes, setNodes] = useState<BuilderNode[]>([])
  const [input, setInput] = useState('{\n  "topic": "Photosynthesis"\n}')
  const [error, setError] = useState('')
  useEffect(() => {
    if (!open) return
    const w = detail.data
    setName(w?.name ?? '')
    setDescription(w?.description ?? '')
    setNodes(w?.definition.nodes.map((n) => {
      const outgoing = w.definition.edges.filter((e) => e.source === n.id)
      const conditional = outgoing.find((e) => e.condition)
      return { id: n.id, agentId: n.agentId, requiresApproval: !!n.requiresApproval,
        next: conditional?.target ?? outgoing[0]?.target ?? '', fallback: outgoing.find((e) => !e.condition)?.target ?? '',
        conditionPath: conditional?.condition?.path ?? '', conditionValue: conditional ? JSON.stringify(conditional.condition?.equals) : '',
        inputMapping: JSON.stringify(n.inputMapping ?? {}, null, 2) }
    }) ?? [{ id: 'step_1', agentId: '', requiresApproval: false, next: '', fallback: '', conditionPath: '', conditionValue: '', inputMapping: '{}' }])
    setError('')
  }, [open, detail.data, id])
  const change = (index: number, values: Partial<BuilderNode>) => setNodes((old) => old.map((n, i) => i === index ? { ...n, ...values } : n))
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('')
    try {
      const builtNodes = nodes.map((n) => ({ id: n.id, agentId: n.agentId, requiresApproval: n.requiresApproval, inputMapping: JSON.parse(n.inputMapping || '{}') }))
      const edges = nodes.flatMap((n) => {
        if (!n.next) return []
        if (n.conditionPath.trim()) {
          const condition = { path: n.conditionPath.trim(), equals: JSON.parse(n.conditionValue) }
          return [{ source: n.id, target: n.next, condition }, ...(n.fallback ? [{ source: n.id, target: n.fallback }] : [])]
        }
        return [{ source: n.id, target: n.next }]
      })
      if (!name.trim() || builtNodes.some((n) => !n.agentId)) throw new Error('Enter a workflow name and select an agent for each step.')
      await save.mutateAsync({ id, name: name.trim(), description, enabled: true, definition: { nodes: builtNodes, edges } })
      toast.success('Workflow saved'); onClose()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save workflow.') }
  }
  const runNow = async () => {
    if (!id) { setError('Save this workflow before running it.'); return }
    try { const parsed = JSON.parse(input); await executeWorkflow.mutateAsync({ id, input: parsed }); toast.success('Workflow queued') }
    catch (e) { setError(e instanceof Error ? e.message : 'Enter valid JSON input.') }
  }
  return <Dialog open={open} title={id ? 'Edit workflow' : 'Build a workflow'} onClose={onClose} size="xl">
    {id && detail.isPending ? <Spinner label="Loading workflow" /> : <form onSubmit={submit} className="space-y-4 max-h-[75vh] overflow-y-auto pr-1">
      <div className="grid md:grid-cols-2 gap-3"><Field label="Workflow name">{(fieldId) => <Input id={fieldId} value={name} onChange={(e) => setName(e.target.value)} maxLength={160} required />}</Field><Field label="Description">{(fieldId) => <Input id={fieldId} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} />}</Field></div>
      <div className="flex justify-between items-center"><div><h3 className="font-medium">Agent steps</h3><p className="text-xs text-muted">Connect outputs to the next step; parallel branches run together. Add approval gates before sensitive stages.</p></div><Button type="button" onClick={() => setNodes((old) => { const serial = Math.max(0, ...old.map((n) => Number(n.id.match(/\d+$/)?.[0] ?? 0))) + 1; return [...old, { id: `step_${serial}`, agentId: '', requiresApproval: false, next: '', fallback: '', conditionPath: '', conditionValue: '', inputMapping: '{}' }] })} icon={<Plus className="size-4" />}>Add step</Button></div>
      <div className="space-y-3">{nodes.map((n, i) => <div key={n.id} className="rounded-xl border border-line bg-surface-2 p-3"><div className="flex justify-between items-center mb-3"><strong className="text-sm">{i + 1}. {n.id}</strong><Button type="button" size="sm" variant="danger" onClick={() => setNodes((old) => old.filter((_, j) => i !== j))}>Remove</Button></div><div className="grid md:grid-cols-2 gap-3"><Field label="Agent">{(fieldId) => <Select id={fieldId} value={n.agentId} onChange={(e) => change(i, { agentId: e.target.value })}><option value="">Choose an agent</option>{agentPage.data?.items.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>}</Field><Field label="Next step">{(fieldId) => <Select id={fieldId} value={n.next} onChange={(e) => change(i, { next: e.target.value })}><option value="">Finish after this step</option>{nodes.filter((candidate) => candidate.id !== n.id).map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}</Select>}</Field></div><label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={n.requiresApproval} onChange={(e) => change(i, { requiresApproval: e.target.checked })} />Pause here for human approval before continuing</label><details className="mt-3"><summary className="cursor-pointer text-sm text-muted">Input mapping and conditional route</summary><div className="grid md:grid-cols-2 gap-3 mt-3"><Field label="Input mapping JSON" hint={'Use "$input.topic" or "$nodes.step_1.result" as values.'}>{(fieldId) => <Textarea id={fieldId} value={n.inputMapping} onChange={(e) => change(i, { inputMapping: e.target.value })} rows={4} className="font-mono text-xs" />}</Field><div className="space-y-3"><Field label="Condition path (optional)" hint="Evaluated against this step's JSON output.">{(fieldId) => <Input id={fieldId} value={n.conditionPath} onChange={(e) => change(i, { conditionPath: e.target.value })} placeholder="verified" />}</Field>{n.conditionPath && <><Field label="Equals JSON value">{(fieldId) => <Input id={fieldId} value={n.conditionValue} onChange={(e) => change(i, { conditionValue: e.target.value })} placeholder={'"yes"'} />}</Field><Field label="Fallback step">{(fieldId) => <Select id={fieldId} value={n.fallback} onChange={(e) => change(i, { fallback: e.target.value })}><option value="">End if condition does not match</option>{nodes.filter((candidate) => candidate.id !== n.id && candidate.id !== n.next).map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}</Select>}</Field></>}</div></div></details></div>)}</div>
      {error && <p role="alert" className="text-sm text-rose-signal">{error}</p>}{agentPage.error && <ErrorNotice error={agentPage.error} />}
      {id && <div className="border-t border-line pt-3"><Field label="Run input JSON">{(fieldId) => <Textarea id={fieldId} value={input} onChange={(e) => setInput(e.target.value)} rows={3} className="font-mono text-xs" />}</Field><div className="flex justify-end mt-2"><Button type="button" icon={<Play className="size-4" />} onClick={runNow}>Run now</Button></div></div>}
      <div className="flex justify-end gap-2"><Button type="button" onClick={onClose}>Close</Button><Button type="submit" variant="primary" loading={save.isPending}>Save workflow</Button></div>
    </form>}
  </Dialog>
}

function Metric({ label, value, icon }: { label: string; value: number; icon: ReactNode }) {
  return <div className="rounded-xl border border-line bg-surface px-3 py-3"><div className="flex items-center justify-between text-muted"><span className="text-xs">{label}</span><span className="size-4 [&>svg]:size-4">{icon}</span></div><p className="text-2xl font-semibold mt-2 tabular-nums">{value}</p></div>
}

function AgentEditorDialog({ id, onClose }: { id?: string; onClose: () => void }) {
  const agent = useAgent(id)
  const versions = useAgentVersions(id)
  const restore = useRestoreAgentVersion()
  const update = useUpdateAgent()
  if (!id) return null
  return <Dialog open title="Agent configuration" onClose={onClose} size="xl">
    {agent.isPending ? <Spinner label="Loading configuration" /> : agent.error ? <ErrorNotice error={agent.error} onRetry={() => agent.refetch()} /> : agent.data ? <AgentForm key={`${agent.data.id}-${agent.data.version}`} initial={agent.data} onClose={onClose} save={(body) => update.mutateAsync({ ...body, id })} saving={update.isPending}>
      <div className="mt-5 border-t border-line pt-4"><h3 className="font-medium mb-2">Configuration history</h3><div className="max-h-36 overflow-y-auto space-y-2">{versions.data?.map((v) => <div key={v.version} className="flex justify-between items-center text-sm"><span>Version {v.version} · {formatDate(v.createdAt)}</span>{v.version !== agent.data?.version && <Button size="sm" icon={<History className="size-3.5" />} onClick={() => restore.mutate({ id, version: v.version }, { onSuccess: () => toast.success(`Restored version ${v.version}`), onError: (e) => toast.error(e.message) })}>Restore</Button>}</div>)}</div></div>
    </AgentForm> : null}
  </Dialog>
}

function CreateAgentDialog({ open, templates, onClose }: { open: boolean; templates: AgentTemplate[]; onClose: () => void }) {
  const accounts = useAccounts()
  const policy = useAgentPolicy()
  const [template, setTemplate] = useState<AgentTemplate>()
  const [name, setName] = useState('')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const models = useTextModels(accountId || undefined)
  const selectableAccounts = accounts.data?.filter((a) => policy.data?.allowPaidModels || a.provider === 'OLLAMA' || a.provider === 'FREE_LLM_API')
  const create = useCreateAgent()
  useEffect(() => { if (selectableAccounts?.length && !selectableAccounts.some((a) => a.id === accountId)) setAccountId(selectableAccounts[0].id) }, [selectableAccounts, accountId])
  useEffect(() => { if (models.data?.length && (!model || !models.data.includes(model))) setModel(models.data[0]) }, [models.data, model])
  useEffect(() => { if (!open) { setTemplate(undefined); setName(''); setModel('') } }, [open])
  const beginTemplate = (item: AgentTemplate) => { setTemplate(item); setName(item.name) }
  return <Dialog open={open} title={template ? `Create ${template.name}` : 'Create an agent'} onClose={onClose} size="xl">
    {!template ? <>
      <p className="text-sm text-muted mb-4">Choose a specialist template or build a custom agent. {policy.data?.allowPaidModels ? 'The owner has enabled hosted providers.' : 'Only local Ollama and FreeLLMAPI accounts are selectable. FreeLLMAPI uses the free-tier accounts you configure in its dashboard; provider limits apply.'}</p>
      <div className="grid sm:grid-cols-2 gap-2 max-h-[55vh] overflow-y-auto">{templates.map((item) => <button key={item.key} onClick={() => beginTemplate(item)} className="text-left rounded-xl border border-line p-3 hover:bg-surface-2"><span className="block font-medium">{item.name}</span><span className="block text-sm text-muted mt-1">{item.description}</span><span className="text-xs text-faint mt-2 block">{item.category}</span></button>)}</div>
      <div className="mt-4 flex justify-end"><Button onClick={() => setTemplate({ key: '', name: 'Custom Agent', description: '', category: 'CONTENT', configuration: {} })}>Build custom agent</Button></div>
    </> : <>
      <div className="grid md:grid-cols-2 gap-4 mb-5"><Field label="Agent name">{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />}</Field>
        <Field label={policy.data?.allowPaidModels ? 'AI account' : 'Free AI account'}>{(id) => <Select id={id} value={accountId} onChange={(e) => { setAccountId(e.target.value); setModel('') }}><option value="">Choose {policy.data?.allowPaidModels ? 'account' : 'free AI account'}</option>{selectableAccounts?.map((a) => <option key={a.id} value={a.id}>{a.name} · {a.provider === 'OLLAMA' ? 'local' : a.provider}</option>)}</Select>}</Field>
        <Field label="Model">{(id) => <Select id={id} value={model} onChange={(e) => setModel(e.target.value)}><option value="">Choose model</option>{models.data?.map((m) => <option key={m}>{m}</option>)}</Select>}</Field>
      </div>
      {accounts.error && <ErrorNotice error={accounts.error} />}{accounts.data && !selectableAccounts?.length && <p className="text-sm text-rose-signal mb-3">{policy.data?.allowPaidModels ? 'Connect an AI account in AI Accounts & Keys first.' : 'Connect Ollama or FreeLLMAPI in AI Accounts & Keys first. Other hosted provider accounts are blocked for agent runs by default.'}</p>}
      <AgentForm key={template.key || 'custom'} initial={{ name, description: template.description, category: template.category, instructions: `You are ${template.name}. ${template.description} Return a JSON object that follows the requested output schema.`, accountId, model, temperature: 0.2, maxOutputTokens: 2000, inputSchema: { type: 'object' }, outputSchema: { type: 'object' }, tools: Array.isArray(template.configuration.tools) ? template.configuration.tools as string[] : [], permissions: ['EXECUTE', 'VIEW_LOGS', 'VIEW_OUTPUT'], timeoutSeconds: 90, retryCount: 1, enabled: true }} onClose={onClose} save={async (body) => {
        if (!name.trim() || !accountId || !model) throw new Error('Enter a name and choose an AI account and model.')
        await create.mutateAsync({ ...body, name: name.trim(), accountId, model })
      }} saving={create.isPending} accountId={accountId} model={model}>
        <p className="text-xs text-muted">{policy.data?.allowPaidModels ? 'This references your existing encrypted AI account.' : 'FreeLLMAPI sends requests to the providers enabled in its fallback chain, where free-tier limits apply; local Ollama stays on your server.'}</p>
      </AgentForm>
    </>}
  </Dialog>
}

function AgentForm({ initial, onClose, save, saving, children, accountId, model }: {
  initial: Pick<Agent, 'name' | 'description' | 'category' | 'instructions' | 'accountId' | 'model' | 'temperature' | 'maxOutputTokens' | 'inputSchema' | 'outputSchema' | 'tools' | 'permissions' | 'timeoutSeconds' | 'retryCount' | 'enabled' | 'projectId'>
  onClose: () => void; save: (body: AgentInput) => Promise<unknown>; saving: boolean; children?: ReactNode; accountId?: string; model?: string
}) {
  const [name, setName] = useState(initial.name)
  const [description, setDescription] = useState(initial.description)
  const [category, setCategory] = useState(initial.category)
  const [instructions, setInstructions] = useState(initial.instructions)
  const [temperature, setTemperature] = useState(initial.temperature)
  const [tokens, setTokens] = useState(initial.maxOutputTokens)
  const [inputSchema, setInputSchema] = useState(JSON.stringify(initial.inputSchema, null, 2))
  const [outputSchema, setOutputSchema] = useState(JSON.stringify(initial.outputSchema, null, 2))
  const [tools, setTools] = useState(initial.tools.join(', '))
  const [timeout, setTimeout] = useState(initial.timeoutSeconds)
  const [retries, setRetries] = useState(initial.retryCount)
  const [error, setError] = useState('')
  const activeAccountId = accountId ?? initial.accountId
  const activeModel = model ?? initial.model
  const onSubmit = async (e: FormEvent) => {
    e.preventDefault(); setError('')
    try {
      const body: AgentInput = { name, description, category, instructions, accountId: activeAccountId, model: activeModel, temperature, maxOutputTokens: tokens,
        inputSchema: JSON.parse(inputSchema), outputSchema: JSON.parse(outputSchema), tools: tools.split(',').map((v) => v.trim()).filter(Boolean),
        permissions: initial.permissions, timeoutSeconds: timeout, retryCount: retries, enabled: initial.enabled, projectId: initial.projectId }
      await save(body); toast.success('Agent configuration saved'); onClose()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save this configuration.') }
  }
  return <form onSubmit={onSubmit} className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
    <div className="grid md:grid-cols-2 gap-4"><Field label="Agent name">{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />}</Field><Field label="Category">{(id) => <Select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>{['DOCUMENT', 'EDUCATION', 'RESEARCH', 'CONTENT', 'VIDEO', 'PUBLISH'].map((v) => <option key={v}>{v}</option>)}</Select>}</Field></div>
    <Field label="Description">{(id) => <Input id={id} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} />}</Field>
    <Field label="System instructions" hint="These instructions are stored as configuration. Provider keys are never included.">{(id) => <Textarea id={id} value={instructions} onChange={(e) => setInstructions(e.target.value)} rows={6} maxLength={12000} required />}</Field>
    <div className="grid md:grid-cols-3 gap-4"><Field label="Temperature">{(id) => <Input id={id} type="number" min="0" max="2" step="0.1" value={temperature} onChange={(e) => setTemperature(Number(e.target.value))} />}</Field><Field label="Maximum output tokens">{(id) => <Input id={id} type="number" min="1" max="32000" value={tokens} onChange={(e) => setTokens(Number(e.target.value))} />}</Field><Field label="Timeout (seconds)">{(id) => <Input id={id} type="number" min="5" max="900" value={timeout} onChange={(e) => setTimeout(Number(e.target.value))} />}</Field></div>
    <div className="grid md:grid-cols-2 gap-4"><Field label="Input JSON schema">{(id) => <Textarea id={id} value={inputSchema} onChange={(e) => setInputSchema(e.target.value)} rows={7} className="font-mono text-xs" />}</Field><Field label="Output JSON schema">{(id) => <Textarea id={id} value={outputSchema} onChange={(e) => setOutputSchema(e.target.value)} rows={7} className="font-mono text-xs" />}</Field></div>
    <Field label="Assigned tool keys" hint="Registered keys only. Runtime dispatch for these tools is still pending.">{(id) => <Input id={id} value={tools} onChange={(e) => setTools(e.target.value)} placeholder="document.extract, content.write" />}</Field>
    <Field label="Retry attempts">{(id) => <Input id={id} type="number" min="0" max="5" value={retries} onChange={(e) => setRetries(Number(e.target.value))} />}</Field>
    {error && <p role="alert" className="text-sm text-rose-signal">{error}</p>}{children}
    <div className="flex justify-end gap-2 pt-2"><Button type="button" onClick={onClose}>Cancel</Button><Button type="submit" variant="primary" loading={saving}>Save configuration</Button></div>
  </form>
}

function PlaygroundDialog({ agent, onClose }: { agent?: Agent; onClose: () => void }) {
  const [input, setInput] = useState(`{
  "topic": "Photosynthesis",
  "audience": "secondary school"
}`)
  const [executionId, setExecutionId] = useState<string>()
  const run = useRunAgent()
  const execution = useExecution(executionId)
  const execute = async (e: FormEvent) => {
    e.preventDefault()
    try { const result = await run.mutateAsync({ id: agent!.id, input: JSON.parse(input), test: true }); setExecutionId(result.id) }
    catch (err) { toast.error(err instanceof Error ? err.message : 'Agent test failed') }
  }
  useEffect(() => { if (!agent) { setExecutionId(undefined); setInput('{\n  "topic": "Photosynthesis",\n  "audience": "secondary school"\n}') } }, [agent])
  return <Dialog open={!!agent} title={agent ? `Test ${agent.name}` : 'Test agent'} onClose={onClose} size="lg">
    {agent && <form onSubmit={execute} className="space-y-4"><p className="text-sm text-muted">Enter a JSON object matching the agent input schema. This uses the selected existing provider account and records an execution.</p><Field label="Sample input JSON">{(id) => <Textarea id={id} value={input} onChange={(e) => setInput(e.target.value)} rows={9} className="font-mono text-sm" />}</Field>
      <div className="flex justify-end"><Button type="submit" variant="primary" loading={run.isPending} icon={<Play className="size-4" />}>Run test</Button></div>
      {execution.data && <ExecutionResult data={execution.data} />}</form>}
  </Dialog>
}

function ExecutionList({ data, pending, error, onRetry, onInspect }: { data: AgentExecution[]; pending: boolean; error: unknown; onRetry: () => void; onInspect: (id: string) => void }) {
  const action = useExecutionAction()
  if (pending) return <Spinner label="Loading executions" />
  if (error) return <ErrorNotice error={error} onRetry={onRetry} />
  if (!data.length) return <EmptyState title="No executions yet" body="Run an agent from the Agents tab to see its progress and results here." />
  return <div className="rounded-2xl border border-line bg-surface overflow-x-auto"><table className="w-full text-sm"><thead className="text-left text-muted"><tr className="border-b border-line"><th className="px-4 py-3">Agent</th><th className="px-4 py-3">Status</th><th className="px-4 py-3">Model</th><th className="px-4 py-3">Started</th><th className="px-4 py-3">Duration</th><th className="px-4 py-3"><span className="sr-only">Actions</span></th></tr></thead><tbody className="divide-y divide-line">{data.map((e) => <tr key={e.id}><td className="px-4 py-3"><span className="font-medium">{e.agentName}</span><span className="block text-xs text-faint">{e.id.slice(0, 8)}</span></td><td className="px-4 py-3"><StatusPill tone={e.status === 'COMPLETED' ? 'done' : e.status === 'FAILED' ? 'muted' : 'progress'}>{e.status.replace('_', ' ')}</StatusPill></td><td className="px-4 py-3">{e.provider ? `${e.provider} · ` : ''}{e.model ?? '—'}</td><td className="px-4 py-3 whitespace-nowrap">{formatDate(e.startedAt ?? e.createdAt)}</td><td className="px-4 py-3">{duration(e.startedAt, e.completedAt)}</td><td className="px-4 py-3 text-right"><div className="flex justify-end gap-1"><Button size="sm" icon={<Eye className="size-3.5" />} onClick={() => onInspect(e.id)}>Inspect</Button>{e.status === 'FAILED' && <Button size="sm" icon={<RefreshCw className="size-3.5" />} onClick={() => action.mutate({ id: e.id, action: 'retry' }, { onError: (er) => toast.error(er.message) })}>Retry</Button>}{['QUEUED', 'PENDING', 'RUNNING'].includes(e.status) && <Button size="sm" icon={<Pause className="size-3.5" />} onClick={() => action.mutate({ id: e.id, action: 'cancel' }, { onError: (er) => toast.error(er.message) })}>Cancel</Button>}</div></td></tr>)}</tbody></table></div>
}

function ExecutionDialog({ id, onClose }: { id?: string; onClose: () => void }) {
  const execution = useExecution(id)
  const logs = useExecutionLogs(id)
  return <Dialog open={!!id} title="Execution details" onClose={onClose} size="lg">{execution.isPending ? <Spinner label="Loading execution" /> : execution.error ? <ErrorNotice error={execution.error} /> : execution.data ? <div className="space-y-4"><ExecutionResult data={execution.data} /><div><h3 className="font-medium mb-2">Execution log</h3>{logs.data?.length ? <ol className="space-y-2 max-h-52 overflow-y-auto">{logs.data.map((log) => <li key={log.id} className="rounded-lg bg-surface-2 px-3 py-2 text-sm"><span className="text-xs text-faint mr-2">{formatDate(log.createdAt)}</span><strong className="mr-2">{log.stage}</strong>{log.message}</li>)}</ol> : <p className="text-sm text-muted">Waiting for the worker to start.</p>}</div></div> : null}</Dialog>
}

function ExecutionResult({ data }: { data: AgentExecution }) {
  const logs = useExecutionLogs(data.id)
  const result = useExecution(data.id)
  const current = result.data ?? data
  const action = useExecutionAction()
  return <div className="rounded-xl border border-line p-4 space-y-3"><div className="flex items-center justify-between"><StatusPill tone={current.status === 'COMPLETED' ? 'done' : current.status === 'FAILED' ? 'muted' : 'progress'}>{current.status}</StatusPill>{current.status === 'FAILED' && <Button size="sm" icon={<RefreshCw className="size-3.5" />} onClick={() => action.mutate({ id: current.id, action: 'retry' })}>Retry</Button>}</div>
    {current.error && <p className="text-sm text-rose-signal">{current.error}</p>}
    <div><p className="text-xs uppercase tracking-wide text-faint mb-1">Output</p><pre className="max-h-64 overflow-auto rounded-lg bg-surface-2 p-3 text-xs">{current.output ? JSON.stringify(current.output, null, 2) : ['QUEUED', 'RUNNING', 'PENDING'].includes(current.status) ? 'Waiting for the agent worker…' : 'No output'}</pre></div>
    {logs.data?.length ? <p className="text-xs text-faint">Latest: {logs.data.at(-1)?.message}</p> : null}
  </div>
}

function duration(start?: string, end?: string) {
  if (!start) return '—'
  const seconds = Math.max(0, Math.round(((end ? Date.parse(end) : Date.now()) - Date.parse(start)) / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}
