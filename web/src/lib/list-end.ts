/**
 * Whether the list's end footer shows: only once every row is drawn, so it
 * never sits above rows still to come, and never for an empty list.
 */
export function showsListEnd(drawn: number, total: number): boolean {
  return total > 0 && drawn >= total;
}
