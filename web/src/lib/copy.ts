/** Words the interface shows. */
export const copy = {
  allItems: "All items",
  starred: "Starred",
  markAllRead: "Mark all as read",
  markRead: "Mark as read",
  markUnread: "Mark as unread",
  unread: (count: number): string => `${count} unread`,
  addFeed: "Add feed",
  findFeed: "Feed address or topic",
  findHint:
    "Paste a feed or website address, or type a topic such as cooking to search.",
  searching: "Searching…",
  /** The add button while a feed or file is being added. */
  adding: "Adding…",
  noResults: "Nothing found.",
  subscribers: (count: number): string =>
    `${count.toLocaleString()} subscribers`,
  importTitle: "Import from another reader",
  importBody:
    "Choose the OPML file exported from your old reader. Folders are kept.",
  importDrop: "Drop an OPML file here, or choose one",
  /** Button that saves the feed list as an OPML file. */
  exportOpml: "Export as OPML",
  importNone: "That file does not list any feeds.",
  importNothingNew: "You already follow every feed in that file.",
  /** The chosen file could not be read. */
  importFailed: "Could not read that file.",
  /** The OPML export could not be saved. */
  exportFailed: "Could not save the file.",
  unreadOnly: "Unread only",
  emptyTitle: "Add your first feed",
  emptyBody:
    "Paste a feed address, search for a topic, or import a list from another reader.",
  extensionTitle: "Reader needs its browser extension",
  extensionBody:
    "Websites do not let a web page read their feeds, so the extension fetches them for you and keeps them current in the background.",
  installExtension: "Install from the Chrome Web Store",
  iosTitle: "Reader is not on iPhone or iPad yet",
  iosBody:
    "For now it works on Android and on a computer with Chrome, where its browser extension fetches your feeds.",
  noEntries: "Nothing here",
  endOfList: "End of list",
  refreshButton: "Refresh",
  selectEntry: "Select an article",
  openOriginal: "Open original",
  settings: "View settings",
  sortLabel: "Sort",
  newest: "Newest",
  oldest: "Oldest",
  rulesLabel: "Words",
  actionHide: "Hide",
  actionOnly: "Only show",
  addPhrase: "Add a word or phrase, then press Enter",
  removeRule: "Remove",
  filterHint:
    "Words match whole words in the title or summary, ignoring capitals and spacing.",
  topics: "Topics",
  topicsHint:
    "Select a topic to turn it off. Articles with only turned-off topics are hidden.",
  nameLabel: "Name",
  groupLabel: "Group",
  noGroup: "No group",
  groupHint:
    "Type a group name, or pick one you already have. Clear it to take the feed out of its group.",
  deleteGroup: "Delete group",
  confirmDeleteGroup: "Press again to delete the group",
  deleteGroupHint: "The feeds in this group stay in your list, with no group.",
  unsubscribe: "Unsubscribe",
  confirmUnsubscribe: "Press again to unsubscribe",
  shortcutsTitle: "Keyboard shortcuts",
  keyNext: "Next article",
  keyPrevious: "Previous article",
  keyOriginal: "Open the original",
  keyStar: "Star or unstar",
  keyRead: "Mark read or unread",
  keyAllRead: "Mark all as read",
  keyRefresh: "Refresh this view",
  keyRefreshAll: "Refresh every feed",
  keyUnread: "Unread only on or off",
  keyAdd: "Add a feed",
  keySettings: "View settings",
  keySidebar: "Collapse or expand the sidebar",
  keyNextFeed: "Next feed or group",
  keyPreviousFeed: "Previous feed or group",
  keyGoAll: "Go to All items",
  keyGoStarred: "Go to Starred",
  keyClose: "Close the open panel or article",
  keyHelp: "Show this list",
  star: "Star",
  share: "Share",
  copied: "Copied",
  /** Name of the app, beside the logo. */
  appName: "Reader",
  back: "Back",
  close: "Close",
  /** Label of the feed list for screen readers. */
  feeds: "Feeds",
  expandSidebar: "Expand sidebar",
  collapseSidebar: "Collapse sidebar",
  /** Heading of the sign-in and sync section in the shortcuts panel. */
  syncHeading: "Sync",
  /** Signed out: one sentence on what signing in does, and that it is optional. */
  syncExplain:
    "Optional. Sign in with Google to keep your feeds and what you have read the same on every device.",
  /** Button that starts Google sign-in. */
  syncSignIn: "Sign in",
  firstRunSignInLine: "Already use Reader?",
  firstRunSignIn: "Sign in to sync your feeds",
  /** Button that signs out; data stays on the device. */
  syncSignOut: "Sign out",
  /** Label shown before the signed-in account's email address. */
  syncSignedInAs: "Signed in as",
  /** Status while Firebase starts and the account is being checked. */
  syncConnecting: "Connecting…",
  /** Status while changes are being sent or the account is being read. */
  syncSyncing: "Syncing…",
  /** Status when everything is sent and received. */
  syncSynced: "Synced",
  /** Status when the browser is offline; changes wait and go out later. */
  syncOffline: "Offline",
  /** Status when the account refused a read or write; details are in the console. */
  syncError: "Sync error",
  /** Extra line while articles older than 90 days are still loading on a new device. */
  syncLoadingOlder: "Older articles are still loading.",
  retentionLabel: "Keep synced articles",
  retentionNote:
    "Older articles are removed from your account only, never from this device.",
  retention30: "30 days",
  retention90: "90 days",
  retention365: "1 year",
  retentionForever: "Forever",
  /** Shown when sign-in did not work. */
  syncSignInFailed: "Sign-in did not work. Try again.",
  /** Shown when the browser blocked the sign-in window. */
  syncPopupBlocked:
    "The sign-in window was blocked. Allow pop-ups for this site, then try again.",
  /** Status while a different account than last time waits for the choice below. */
  syncWaiting: "Waiting for you",
  /** A different account signed in on a device holding another account's data. */
  syncSwitchBody:
    "This device holds another account's feeds and reading. Replace them with this account's, or cancel the sign-in.",
  /** Button: wipe this device's data and sync the new account. */
  syncSwitchReplace: "Replace this device's data",
  /** Button: sign the new account out and keep this device as it is. */
  syncSwitchCancel: "Cancel sign-in",
  /** Button that starts deleting the signed-in account. */
  syncDeleteAccount: "Delete account",
  /** The same button once pressed, asking for a second press. */
  syncDeleteConfirm: "Press again to delete your account",
  /** What deleting the account does. */
  syncDeleteHint:
    "Deletes your feeds, reading and settings from your account, then signs out. This device keeps its copy.",
  /** While the account is being deleted. */
  syncDeleting: "Deleting…",
  /** Deleting the account did not work. */
  syncDeleteFailed: "Could not delete the account. Try again.",
  /** Link to the privacy policy. */
  privacy: "Privacy",
  /** Link to the terms of service. */
  terms: "Terms",
};
