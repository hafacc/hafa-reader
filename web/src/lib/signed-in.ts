/** Set while an account is signed in, so Firebase loads on the next visit. */
const SIGNED_IN_KEY = "h-reader:signed-in";

/** Record whether an account is signed in on this browser. */
export function rememberSignedIn(signedIn: boolean): void {
  try {
    if (signedIn) {
      localStorage.setItem(SIGNED_IN_KEY, "1");
    } else {
      localStorage.removeItem(SIGNED_IN_KEY);
    }
  } catch {
    // storage can be blocked; sign-in then lasts only this visit
  }
}

/** Whether an account was signed in on this browser at the last visit. */
export function wasSignedIn(): boolean {
  try {
    return localStorage.getItem(SIGNED_IN_KEY) === "1";
  } catch {
    return false;
  }
}
