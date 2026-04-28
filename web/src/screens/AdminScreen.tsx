import { useState } from 'react'
import { firestoreUsage } from '@/data/FirestoreUsage'
import { ActionButton } from '@/components/ActionButton'
import {
  FIRESTORE_USAGE, FIRESTORE_USAGE_TITLE, FIRESTORE_USAGE_CLOSE, FIRESTORE_USAGE_RESET,
} from '@/strings'
import styles from './AdminScreen.module.css'

const IC_ASSESSMENT = "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"

export function AdminScreen() {
  const [usageReport, setUsageReport] = useState<string | null>(null)

  const openUsageReport = () => {
    const report = firestoreUsage.getReport()
    console.log(report)
    setUsageReport(report)
  }

  return (
    <div className={styles.container}>
      <div className={styles.actions}>
        <ActionButton icon={IC_ASSESSMENT} label={FIRESTORE_USAGE} onClick={openUsageReport} />
      </div>

      {usageReport != null && (
        <UsageReportDialog
          report={usageReport}
          onClose={() => setUsageReport(null)}
          onReset={() => {
            firestoreUsage.reset()
            setUsageReport(firestoreUsage.getReport())
          }}
        />
      )}
    </div>
  )
}

function UsageReportDialog({
  report,
  onClose,
  onReset,
}: {
  report: string
  onClose: () => void
  onReset: () => void
}) {
  return (
    <div className={styles.usageOverlay} role="dialog" aria-label={FIRESTORE_USAGE_TITLE}>
      <div className={styles.usageDialog}>
        <h3 className={styles.usageTitle}>{FIRESTORE_USAGE_TITLE}</h3>
        <pre className={styles.usagePre}>{report}</pre>
        <div className={styles.usageActions}>
          <button className={styles.dialogButton} onClick={onReset}>
            {FIRESTORE_USAGE_RESET}
          </button>
          <button className={styles.dialogButton} onClick={onClose}>
            {FIRESTORE_USAGE_CLOSE}
          </button>
        </div>
      </div>
    </div>
  )
}
