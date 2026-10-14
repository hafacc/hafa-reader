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
  importBody: "Choose the OPML file exported from your old reader.",
  /** Label of the group field for imported feeds. */
  importGroupLabel: "Add to group",
  /** What the import group field says while empty, which keeps the groups the file has. */
  importKeepFolders: "Folders from the file",
  /** Plain http feeds in an imported file that have no https version, so were left out. */
  importSkippedHttp: (count: number): string =>
    count === 1
      ? "Skipped 1 http feed with no https version."
      : `Skipped ${count.toLocaleString()} http feeds with no https version.`,
  /** Someone typed something that is not a web address. */
  notWebAddress: "Only https addresses can be added.",
  /** An address being added got no answer at all. */
  noAnswer: "The site did not answer.",
  /** The browser extension that fetches feeds could not be reached. */
  extensionMissing: "The Reader extension is not installed.",
  /** An address being added answered with something other than a feed, and links to none. */
  notFeed: "That address is not a feed and does not point to one.",
  /** An address being added redirected away from https. */
  notHttps: "The feed is not on https.",
  /** Someone typed or pasted a plain http address. */
  httpRefused: "Reader can't add http addresses. Try https instead.",
  /** A followed feed whose address is plain http, with no https version found. */
  httpFeed: "This feed's address is http, not https, so Reader can't fetch it.",
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
  refreshButton: "Refresh",
  /** Said by the dot on the logo and the menu button when feeds are overdue for a check. */
  refreshSuggested: "Some feeds are due for a check",
  selectEntry: "Select an article",
  openOriginal: "Open original",
  settings: "View settings",
  sortLabel: "Sort",
  newest: "Newest",
  oldest: "Oldest",
  readingFontLabel: "Reading font",
  /** The reading font is one choice for the whole app, though it sits in each view's settings. */
  readingFontHint: "Applies to every view.",
  fontLiterata: "Literata",
  fontPublicSans: "Public Sans",
  rulesLabel: "Words",
  actionHide: "Hide",
  actionOnly: "Only show",
  addPhrase: "Add a word or phrase, then press Enter",
  /** Button that removes one word from a view's words, for screen readers. */
  removeWord: (word: string): string => `Remove ${word}`,
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
  /** The phone's menu button when feeds are overdue for a check, for screen readers. */
  feedsWithDue: "Feeds, some are due for a check",
  /** The view's name in the phone bar, which shows the feeds when tapped, for screen readers. */
  showFeedsFrom: (view: string): string => `${view}, show feeds`,
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
  deletePageTitle: "Delete your Reader account",
  deleteSignInLine:
    "Sign in to delete your account and everything synced to it.",
  deleteContinueGoogle: "Continue with Google",
  deleteSignedInAs: "Signed in as",
  deleteOtherAccount: "Use a different account",
  deleteExplain:
    "This deletes your account and everything synced to it: your feeds, articles, read marks and settings. It happens right away and can't be undone. Copies on your devices stay where they are.",
  deleteEverything: "Delete everything",
  deleteFailedPage:
    "Deleting didn't finish. Some data may already be gone. Please try again.",
  deleteDone: "Your account is deleted.",
  deleteLoading: "Loading",
  deleteAndroid: "The Android app has the same option in its account screen.",
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
  /** Link to the contact page. */
  contact: "Contact",
};
