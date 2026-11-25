import type { ReactNode } from 'react'
import { useAuthStatus } from '../lib/queries'
import { EmptyState } from './ui'

/** Pages for administrators only. The server refuses members anyway; this says so plainly instead of showing errors. */
export function AdminOnly({ children }: { children: ReactNode }) {
  const role = useAuthStatus().data?.user?.role
  if (role === 'ADMIN') return <>{children}</>
  return <EmptyState title="For administrators" body="Only an administrator can open this page. Ask one if you need something changed here." />
}
