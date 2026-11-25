import { AlertCircle, Check, Info, X } from 'lucide-react'
import { useToasts } from '../stores/ui'
import { cn } from '../lib/cn'

export function Toasts() {
  const { toasts, dismiss } = useToasts()
  return (
    <div aria-live="polite" className="fixed bottom-4 right-4 left-4 sm:left-auto z-50 flex flex-col gap-2 sm:w-96">
      {toasts.map((t) => {
        const Icon = t.tone === 'success' ? Check : t.tone === 'error' ? AlertCircle : Info
        return (
          <div
            key={t.id}
            role={t.tone === 'error' ? 'alert' : 'status'}
            className="flex items-start gap-3 rounded-xl bg-[#1e2240] text-[#e8eaf6] dark:bg-[#e8eaf6] dark:text-[#1e2240] px-4 py-3 shadow-lg"
          >
            <Icon
              className={cn(
                'size-5 shrink-0 mt-0.5',
                t.tone === 'success' && 'text-peacock-400 dark:text-peacock-600',
                t.tone === 'error' && 'text-rose-signal',
                t.tone === 'info' && 'text-marigold-400 dark:text-marigold-700',
              )}
              aria-hidden
            />
            <p className="flex-1">{t.message}</p>
            <button onClick={() => dismiss(t.id)} aria-label="Dismiss" className="opacity-60 hover:opacity-100">
              <X className="size-4" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
