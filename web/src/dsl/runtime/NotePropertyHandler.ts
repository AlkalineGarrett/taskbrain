import type { Note } from '@/data/Note'
import type { DslValue, NoteVal } from './DslValue'
import { dateTimeVal, noteVal, stringVal, UNDEFINED } from './DslValue'
import type { Environment } from './Environment'
import { ExecutionException } from './ExecutionException'
import { MutationType } from './NoteMutation'

/**
 * Handles property access and assignment for NoteVal.
 */
export function getProperty(nv: NoteVal, property: string, env: Environment): DslValue {
  const note = nv.note
  switch (property) {
    case 'id': return stringVal(note.id)
    case 'path': return stringVal(note.path)
    case 'name': return stringVal(note.content.split('\n')[0] ?? '')
    case 'created': {
      if (!note.createdAt) throw new ExecutionException('Note has no created date')
      return dateTimeVal(note.createdAt.toDate().toISOString().slice(0, 19))
    }
    case 'modified': {
      if (!note.updatedAt) throw new ExecutionException('Note has no modified date')
      return dateTimeVal(note.updatedAt.toDate().toISOString().slice(0, 19))
    }
    case 'up': return getUp(note, 1, env)
    case 'root': return getRoot(nv, env)
    default: throw new ExecutionException(`Unknown property '${property}' on note`)
  }
}

export async function setProperty(nv: NoteVal, property: string, value: DslValue, env: Environment): Promise<void> {
  const note = nv.note
  const ops = env.getNoteOperations()
  if (!ops) throw new ExecutionException('Cannot modify note properties: note operations not available')

  switch (property) {
    case 'path': {
      if (value.kind !== 'StringVal') throw new ExecutionException('path must be a string')
      const updatedNote = await ops.updatePath(note.id, value.value)
      env.registerMutation({ noteId: note.id, updatedNote, mutationType: MutationType.PATH_CHANGED })
      break
    }
    case 'name': {
      if (value.kind !== 'StringVal') throw new ExecutionException('name must be a string')
      const freshNote = await ops.getNoteById(note.id)
      if (!freshNote) throw new ExecutionException(`Note not found: ${note.id}`)
      const lines = freshNote.content.split('\n')
      const newContent = lines.length <= 1 ? value.value : [value.value, ...lines.slice(1)].join('\n')
      const updatedNote = await ops.updateContent(note.id, newContent)
      env.registerMutation({ noteId: note.id, updatedNote, mutationType: MutationType.CONTENT_CHANGED })
      break
    }
    case 'id':
    case 'created':
    case 'modified':
      throw new ExecutionException(`Cannot set read-only property '${property}' on note`)
    default:
      throw new ExecutionException(`Unknown property '${property}' on note`)
  }
}

export function getUp(note: Note, levels: number, env: Environment): DslValue {
  if (levels === 0) return noteVal(note)
  const parent = env.getParentNote(note)
  if (!parent) return UNDEFINED
  if (levels === 1) return noteVal(parent)
  return getUp(parent, levels - 1, env)
}

function getRoot(nv: NoteVal, env: Environment): NoteVal {
  const parent = env.getParentNote(nv.note)
  if (!parent) return nv
  return getRoot(noteVal(parent), env)
}
