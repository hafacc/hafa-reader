/**
 * What has gone wrong with sync, kept apart by kind so each clears on its
 * own: a write clears with the next successful write, a read or a listener
 * with the next one that works.
 */
export class Health {
  private writeBroken: boolean;
  private readBroken: boolean;
  private readonly listeners = new Set<string>();

  constructor() {
    this.writeBroken = false;
    this.readBroken = false;
  }

  /** Whether anything is failing now. */
  get failed(): boolean {
    return this.writeBroken || this.readBroken || this.listeners.size > 0;
  }

  /** Note a failed write. */
  writeFailed(): void {
    this.writeBroken = true;
  }

  /** Note a write that went through. */
  writeWorked(): void {
    this.writeBroken = false;
  }

  /** Note a failed read, or a snapshot that could not be stored. */
  readFailed(): void {
    this.readBroken = true;
  }

  /** Note a read that went through. */
  readWorked(): void {
    this.readBroken = false;
  }

  /** Note a listener stopped by an error. */
  listenerFailed(name: string): void {
    this.listeners.add(name);
  }

  /** Note a listener that delivered again, which is also a read that worked; whether that changed anything. */
  heard(name: string): boolean {
    const changed = this.listeners.delete(name) || this.readBroken;
    this.readBroken = false;
    return changed;
  }
}
