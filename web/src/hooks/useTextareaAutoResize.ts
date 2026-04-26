import { useEffect, useLayoutEffect, type RefObject } from 'react'

/**
 * Resizes a textarea to fit its wrapped content. Re-fires when the textarea's
 * content changes (synchronously, before paint) and when its parent's width
 * changes (wrapping may add/remove visual rows).
 */
export function useTextareaAutoResize(
  inputRef: RefObject<HTMLTextAreaElement | null>,
  content: string,
) {
  useLayoutEffect(() => {
    const textarea = inputRef.current
    if (!textarea) return
    textarea.style.height = '0'
    textarea.style.height = `${textarea.scrollHeight}px`
  }, [content, inputRef])

  useEffect(() => {
    const textarea = inputRef.current
    if (!textarea?.parentElement) return
    const resize = () => {
      textarea.style.height = '0'
      textarea.style.height = `${textarea.scrollHeight}px`
    }
    const observer = new ResizeObserver(resize)
    observer.observe(textarea.parentElement)
    return () => observer.disconnect()
  }, [inputRef])
}
