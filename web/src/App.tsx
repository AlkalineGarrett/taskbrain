import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { AuthProvider } from '@/hooks/useAuth'
import { ProtectedRoute } from '@/components/ProtectedRoute'
import { AppLayout } from '@/components/AppLayout'
import { NoteListScreen } from '@/screens/NoteListScreen'
import { NoteEditorScreen } from '@/screens/NoteEditorScreen'
import { RecoverScreen } from '@/screens/RecoverScreen'
import { AdminScreen } from '@/screens/AdminScreen'
import { installFirestoreBootstrap } from '@/firebase/bootstrap'

installFirestoreBootstrap()

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ProtectedRoute>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<NoteListScreen />} />
              <Route path="/note/:noteId" element={<NoteEditorScreen />} />
              <Route path="/recover" element={<RecoverScreen />} />
              <Route path="/admin" element={<AdminScreen />} />
            </Route>
          </Routes>
        </ProtectedRoute>
      </AuthProvider>
    </BrowserRouter>
  )
}
