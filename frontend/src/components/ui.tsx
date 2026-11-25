import { Loader2, X } from 'lucide-react'
import {
  forwardRef,
  useEffect,
  useId,
  useRef,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { cn } from '../lib/cn'

// ---- Button -------------------------------------------------------------

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: 'sm' | 'md'
  loading?: boolean
  icon?: ReactNode
}

const VARIANTS: Record<Variant, string> = {
  primary:
    'bg-marigold-500 text-[#1e2240] hover:bg-marigold-400 active:bg-marigold-600 font-semibold shadow-[inset_0_-2px_0_rgba(0,0,0,0.12)]',
  secondary: 'bg-surface text-ink border border-line hover:bg-surface-2',
  ghost: 'text-muted hover:text-ink hover:bg-surface-2',
  danger: 'bg-rose-signal text-white hover:brightness-110 font-semibold',
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading, icon, className, children, disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-lg transition-colors disabled:opacity-55 disabled:cursor-not-allowed whitespace-nowrap',
        size === 'sm' ? 'h-8 px-3 text-sm' : 'h-10 px-4',
        VARIANTS[variant],
        className,
      )}
      {...rest}
    >
      {loading ? <Loader2 className="size-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  )
})

// ---- Form fields -----------------------------------------------------------

interface FieldProps {
  label: string
  hint?: string
  error?: string
  children: (id: string, describedBy?: string) => ReactNode
}

export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId()
  const hintId = hint || error ? `${id}-hint` : undefined
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      {children(id, hintId)}
      {(error || hint) && (
        <p id={hintId} className={cn('text-sm', error ? 'text-rose-signal' : 'text-faint')}>
          {error ?? hint}
        </p>
      )}
    </div>
  )
}

const fieldClass =
  'w-full rounded-lg border border-line bg-surface px-3 text-ink placeholder:text-faint focus:border-marigold-500 focus:outline-none focus:ring-2 focus:ring-marigold-500/25'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cn(fieldClass, 'h-10', className)} {...rest} />
})

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea(
  { className, ...rest },
  ref,
) {
  return <textarea ref={ref} className={cn(fieldClass, 'py-2 min-h-24 resize-y', className)} {...rest} />
})

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn(fieldClass, 'h-10 pr-8', className)} {...rest}>
      {children}
    </select>
  )
}

// ---- Feedback ---------------------------------------------------------------

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 text-muted py-10 justify-center">
      <Loader2 className="size-5 animate-spin" aria-hidden />
      <span>{label}…</span>
    </div>
  )
}

export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong.'
  return (
    <div role="alert" className="rounded-xl border border-rose-signal/40 bg-rose-signal/8 p-4 flex items-start justify-between gap-4">
      <p className="text-ink">{message}</p>
      {onRetry && (
        <Button size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  )
}

export function EmptyState({ title, body, action }: { title: string; body: string; action?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-line px-6 py-12 text-center">
      <h3 className="text-lg font-semibold">{title}</h3>
      <p className="text-muted mt-1 max-w-md mx-auto">{body}</p>
      {action && <div className="mt-5 flex justify-center">{action}</div>}
    </div>
  )
}

export function StatusPill({ tone, children }: { tone: 'neutral' | 'progress' | 'done' | 'muted'; children: ReactNode }) {
  const tones = {
    neutral: 'bg-surface-2 text-muted',
    progress: 'bg-marigold-500/15 text-marigold-700 dark:text-marigold-300',
    done: 'bg-peacock-500/15 text-peacock-600 dark:text-peacock-400',
    muted: 'bg-surface-2 text-faint',
  }
  return <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', tones[tone])}>{children}</span>
}

// ---- Dialog -----------------------------------------------------------------

interface DialogProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  size?: 'md' | 'lg' | 'xl'
}

/** Native <dialog>: focus trapping, Escape and backdrop come from the browser. */
export function Dialog({ open, title, onClose, children, footer, size = 'md' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) el.showModal?.()
    if (!open && el.open) el.close?.()
  }, [open])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose()
      }}
      className={cn(
        'm-auto rounded-2xl border border-line bg-surface text-ink p-0 backdrop:bg-[#0d1020]/60 backdrop:backdrop-blur-[2px]',
        size === 'xl' ? 'w-[min(72rem,calc(100vw-2rem))]' : size === 'lg' ? 'w-[min(44rem,calc(100vw-2rem))]' : 'w-[min(32rem,calc(100vw-2rem))]',
      )}
    >
      {open && (
        <div className="p-6">
          <div className="flex items-start justify-between gap-4 mb-4">
            <h2 id={titleId} className="text-xl font-semibold">
              {title}
            </h2>
            <button onClick={onClose} className="text-faint hover:text-ink p-1 -m-1 rounded" aria-label="Close">
              <X className="size-5" />
            </button>
          </div>
          {children}
          {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}
        </div>
      )}
    </dialog>
  )
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
      <div className="min-w-0">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">{title}</h1>
        {description && <p className="text-muted mt-1 max-w-2xl">{description}</p>}
      </div>
      {actions && <div className="flex gap-2 flex-wrap">{actions}</div>}
    </header>
  )
}
