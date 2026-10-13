<svelte:head>
  <title>Reader privacy policy</title>
  <meta
    name="description"
    content="What Reader reads, what it stores and where, and how to delete it."
  >
</svelte:head>

<h1>Reader privacy policy</h1>
<p class="text-muted text-sm">Last updated October 13, 2026</p>

<p>
  Reader shows new articles from the feeds you add. It has no server of its own.
  Without an account, your feeds, what you read and your settings stay on your
  device. To fetch feeds and pictures, and to search for new feeds, your device
  asks the sites that publish them, or Feedly, directly, so they see your
  device's internet address as they would on any visit.
</p>

<h2>What Reader reads</h2>
<ul>
  <li>
    The feeds you add, fetched straight from the sites that publish them: by the
    Reader Chrome extension for the web app, and by the app itself on Android.
    To find a feed, Reader may also read the page at an address you enter.
  </li>
  <li>
    Pictures in articles and the sites' icons, loaded from the sites that
    publish them. Those sites see the request as they would any visit.
  </li>
  <li>
    When you search for a feed by name instead of entering an address, what you
    type is sent to Feedly's public feed search, and Feedly's privacy policy
    applies to it.
  </li>
  <li>
    If you sign in with Google, on the web or in the Android app: the email
    address, name and picture of your Google account, which Google shares at
    sign-in. Reader shows the email address and the picture, to say which
    account is signed in; the picture is loaded from Google, which sees the
    request as it would any visit.
  </li>
</ul>

<h2>What Reader stores, and where</h2>
<ul>
  <li>
    On your device: your feeds and groups, their articles, which articles you
    have read or starred, and each view's settings. In the web app this is the
    browser's storage for reader.hafa.cc; the Chrome extension keeps the feeds
    it fetches until the web app picks them up, and when to check each feed
    next. On Android it is the app's own storage, together with when to check
    each feed next.
  </li>
  <li>
    Signing in is optional and only turns on sync. When you are signed in, the
    same data is also stored in Google Firebase under your account, so it
    follows you between devices: your feeds and groups, each article's details
    (title, link, author, date, excerpt, summary, topics and picture address),
    which articles you have read or starred, and your view settings. Only your
    account can read them. Your account also keeps how long synced articles
    stay. Articles older than that are removed from your account, never from
    your device; starred articles stay.
  </li>
  <li>
    The text of each article you sync is stored once in Firebase, shared by
    everyone who has the same article, under a fingerprint of the text. It holds
    nothing about who stored it, and a signed-in user can read it only by that
    fingerprint.
  </li>
  <li>Signing out leaves your data on that device and in your account.</li>
</ul>

<h2>How your data is protected</h2>
<ul>
  <li>
    Reader uses encrypted connections only: feeds, articles, pictures, search
    and sync all travel over https.
  </li>
  <li>
    Your Google sign-in is handled by Google and Firebase. What Reader stores in
    Firebase is protected by your Google account.
  </li>
</ul>

<h2>What Reader doesn't do</h2>
<ul>
  <li>
    It has no ads and no analytics. Your data is never sold or shared, and
    nothing about what you read is sent to us.
  </li>
</ul>

<h2>Google</h2>
<ul>
  <li>
    Sign-in and sync use Google Firebase, and Google's privacy policy applies:
    <a href="https://policies.google.com/privacy"
      >https://policies.google.com/privacy</a
    >. Reader's own terms are at
    <a href="https://reader.hafa.cc/terms">https://reader.hafa.cc/terms</a>.
  </li>
  <li>
    Reader's use of information received from Google APIs follows the Google API
    Services User Data Policy, including its Limited Use requirements.
  </li>
</ul>

<h2>Deleting your data</h2>
<ul>
  <li>
    Unsubscribing from a feed removes it and its articles from the device and,
    when you are signed in, from your account.
  </li>
  <li>
    Clearing the site data for reader.hafa.cc, removing the Chrome extension or
    uninstalling the Android app removes what that device stored.
  </li>
  <li>
    To delete your account, open Sync in the web app or the account screen in
    the Android app and press Delete account twice, or use the
    <a href="/delete">delete account page</a>
    in any browser. It removes your feeds, articles, read and starred marks and
    settings from your account, and your Google sign-in from Reader, then signs
    you out. What is on your device stays until you remove it as above. Shared
    article text holds nothing about you and stays.
  </li>
  <li>
    You can remove Reader's access to your Google account at any time at
    <a href="https://myaccount.google.com/permissions"
      >https://myaccount.google.com/permissions</a
    >.
  </li>
</ul>

<h2>Contact</h2>
<ul>
  <li>
    <a href="mailto:support@reader.hafa.cc">support@reader.hafa.cc</a>
  </li>
</ul>
