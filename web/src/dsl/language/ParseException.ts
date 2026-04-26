export class ParseException extends Error {
  constructor(
    message: string,
    public readonly position: number,
  ) {
    super(`Parse error at position ${position}: ${message}`)
  }
}
