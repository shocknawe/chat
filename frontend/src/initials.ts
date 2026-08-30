/** Up to two initials from a display name, for gradient initial avatars. */
export function initials(displayName: string): string {
  const parts = displayName.trim().split(/\s+/)
  const first = parts[0]?.[0] ?? '?'
  const second = parts.length > 1 ? (parts.at(-1)?.[0] ?? '') : ''
  return (first + second).toUpperCase()
}
