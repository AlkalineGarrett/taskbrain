/** Material Design icon: 24x24 viewBox SVG, color via currentColor. */
export function MdIcon({
  path,
  size = 20,
  className,
  title,
}: {
  path: string
  size?: number
  className?: string
  title?: string
}) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden={title ? undefined : true}
    >
      {title && <title>{title}</title>}
      <path d={path} />
    </svg>
  )
}
