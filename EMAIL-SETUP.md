# Adding an email account to Bold7

Bold7's Messages shows your email next to your texts, as the 9900 did. It talks to your provider directly over IMAP (to receive) and SMTP (to send). When a setup fails, it is almost always for one of three reasons: the provider wants an **app password** instead of your usual password, **IMAP is turned off** in the account, or the provider does not let other mail apps in at all. Bold7 now says which one, and this page goes through the common providers one by one.

## How it works in Bold7

1. Open **Messages**, press **Menu**, choose **Options**.
2. Choose **Email Account Management**.
3. Choose **Internet Mail Account** ("Add an email address"). The **Email Setup** screen opens.
4. Type your **Email address** and, in **Password**, your password or the app password your provider asks for.
5. Click **Next**. Bold7 shows "Checking your email address…", finds the servers, and logs in to both, incoming and outgoing, before it keeps anything.
6. When both answer, Bold7 says "Your email address ... is set up. Its messages will arrive in Messages."

**Not sure which password?** Type only the address and click **Next** with Password empty. Bold7 tries nothing and tells you what your provider asks for: an app password and where to make it, IMAP to turn on, or that the address signs in through the browser. For a provider that simply takes your mailbox password, it answers "Type your email address and its password."

When something goes wrong, Bold7 says what and what to do in a box that stays until you click **OK**, so you have time to read the steps.

A few things to know before you start:

- **Two-step verification stays on.** An app password replaces your password *in Bold7 only*. It does not replace two-step verification and does not weaken it: it is a separate password that only works for mail, that you can revoke at any time, and that you get *because* two-step verification is on. Never turn two-step verification off to make a mail app work.
- **The connection is always encrypted.** Bold7 never connects without TLS.
- **The password is typed once.** Bold7 seals it in the phone's keystore. Neither Messages nor the logs ever see it again.
- **Bold7 downloads the last 7 days**, up to 500 messages, from Inbox, Sent and Drafts. New mail arrives by push when the server supports it (almost all do), otherwise every 30 minutes.
- **Each account gets its own icon** on the home screen, next to Messages, which opens Messages on that account alone.

To change the signature added under every email you send ("Sent from my BlackBerry® smartphone" by default): Email Account Management, click the account, **Edit**, then **Use Auto Signature** and **Signature**, and **Save**. To remove an account: click it, **Delete**. Its messages stay on the server.

To change the password of an account (after you changed it at your provider, or revoked the app password), delete the account and add it again with the new one. While the old password is refused, that account stops receiving mail.

## How Bold7 finds the servers

1. **Known providers.** Bold7 has the servers of the providers below built in: Gmail, Outlook.com, iCloud, Yahoo, AOL, GMX, WEB.DE, mail.com, Zoho, Yandex, Proton, Orange, Free, SFR, La Poste and Bbox, with all their usual domains (`hotmail.co.uk`, `ymail.com`, `gmx.fr`, `wanadoo.fr`, `neuf.fr` and so on). You type nothing else.
2. **Other addresses.** When you click Next, Bold7 looks the domain up, as Thunderbird does: first in the public list of mail settings the Thunderbird project keeps, then in a settings file the domain itself may publish, then in the domain's mail records (MX). The MX tells it, for example, that your company's domain is a Google Workspace or Microsoft 365 domain, or that a personal domain is hosted by a provider in the list. This takes a few seconds, up to about thirty with a slow network, and only happens when you click Next.
3. **Nothing found.** Bold7 says "Type the servers of this email address." and shows the server fields (see [Typing the servers yourself](#typing-the-servers-yourself)). If it found servers but could not log in to them, it shows them in the same fields so you can correct them.

### Typing the servers yourself

The fields are:

- **User name:** the login of both servers. Leave it empty to use your email address, which is what nearly every provider wants. Type something else only if your provider says so.
- **Incoming server:**, **Port:**, and **Use STARTTLS**
- **Outgoing server:**, **Port:**, and **Use STARTTLS**

**Use STARTTLS** follows the port until you change it: ticked for 143 and 587, cleared for the others (993 and 465 are the usual ports for TLS from the start). Your provider's page says "SSL/TLS" (clear the box) or "STARTTLS" (tick it). Your provider's help pages give these values, usually under "IMAP", "mail app settings" or "Thunderbird / Outlook setup". For a domain of your own, ask the company that hosts your mail.

### A certificate the phone does not recognise

Some hosts, often small ones or a server of your own, use a certificate that Android cannot check: signed by the server itself, expired, or issued for another name. For servers you typed or that Bold7 found, Email Setup then shows a box with the server, the reason, the names in the certificate, who issued it and its **SHA-256** fingerprint, and asks "Accept it for this server only? Compare the fingerprint with your provider's first." **Cancel** has the focus.

- Accept only if the fingerprint matches the one your host publishes, or the one you see on your own server. Otherwise someone may be in the middle.
- **Accept** trusts that certificate, and only it, for that server and port, for this account. The outgoing server asks separately.
- A known provider's certificate (Gmail, Yahoo...) that fails is never accepted: something is wrong with the network, and Bold7 keeps nothing.
- When the host renews a self-signed certificate, the account stops receiving with a certificate error. Delete the account and add it again to accept the new one.

## What the messages mean

| Bold7 says | What it means | What to do |
|---|---|---|
| Type your email address. | The address is empty or not a complete address. | Check it. |
| Type your email address and its password. | Password is empty, and your provider takes your mailbox password. | Type it, then **Next**. |
| Gmail needs an app password..., iCloud needs an app-specific password..., Yahoo..., AOL..., GMX..., WEB.DE..., mail.com..., Zoho..., Yandex..., Orange... | What that provider asks of mail apps. You get it with an empty password, or when the password was refused. | Follow it; your provider's section below has the links. |
| The email address or the password is incorrect. / (server) refused the user name or the password. | The server refused the login. Bold7 may add "The server said:" and the server's own words, which often say why. | Check the password, whether the provider needs an app password, and that IMAP is on. |
| Outlook.com, Hotmail and Live addresses sign in through the browser only... | Microsoft takes no password from mail apps. | See [Outlook.com](#outlookcom-hotmail-live-msn). |
| This address is a Microsoft 365 work or school account... | Your domain's mail is at Microsoft 365. | See [Microsoft 365](#microsoft-365-at-work-or-school). |
| Proton Mail reaches other email apps only through Proton Mail Bridge... | Proton has no IMAP for phones. | See [Proton Mail](#proton-mail). |
| Type the servers of this email address. | Bold7 found no servers for this domain. | See [Typing the servers yourself](#typing-the-servers-yourself). |
| (server) cannot be found. Check the server name. | No server has that name. | Check the spelling. |
| Nothing answers at (server). Check the port. | The server exists but refuses that port. | Check the port in your provider's page. |
| (server) did not answer in time. Check the port and your connection. | No answer at all, often a wrong port or a network that blocks it (some Wi-Fi networks do). | Check the port, try mobile data. |
| (server) does not offer STARTTLS. Clear Use STARTTLS... | The port expects TLS from the start. | Clear **Use STARTTLS**, or use 993 / 465. |
| (server) did not start SSL/TLS on this port. Select Use STARTTLS... | The port expects STARTTLS. | Tick **Use STARTTLS**, or use 143 / 587. |
| (server) offers no login by password. | The server only takes sign-in methods Bold7 does not have. | Ask your provider whether IMAP with a password (or an app password) can be turned on. |
| Unable to reach the server. Check your connection and the server names. | Any other network failure. | Check that the phone is online. |
| The certificate of (server) cannot be trusted... Nothing was kept. | A known provider's certificate failed, which should never happen. | Try another network. |
| This email address is already set up, or cannot be added. | The address is already in Email Account Management. | Look at the list. |
| Browser sign-in is not available for this email address. Type an app password. | **Sign In With Browser** was clicked for a provider without it in your version. | Use an app password. |
| The sign-in was not completed. Nothing was kept. | You came back from the browser before the page said "Signed in". | Start again and wait for "Signed in". |
| No browser to sign in with. | No browser app is installed or enabled. | Enable Chrome (Browser) in Android's settings. |

## Browser sign-in

Some providers let a mail app sign in through their own web page instead of a password. When your version of Bold7 can do this for a provider, Email Setup shows a **Sign In With Browser** button under **Next**, with a line naming the providers it works for. When it cannot, Email Setup says "Browser sign-in (Gmail, Outlook) is off in this build of Bold7."

Type your address and click **Sign In With Browser**. The provider's own page opens in the browser: you type your password there, with your second step if you have one, and Bold7 never sees it. When the page says "Signed in. Press Escape to return to Email Setup.", press **Back** (the Q25's Escape). Bold7 then checks both servers and keeps the account. You have ten minutes to finish in the browser.

In releases of Bold7, browser sign-in can only exist for personal Microsoft accounts (Outlook.com, Hotmail, Live, MSN). Gmail's is left out of releases: Gmail works with an app password.

## Gmail

What you need: an **app password**. Your Google password does not work in mail apps.

1. Turn on 2-Step Verification if it is not on yet (Google Account, Security). App passwords only exist when it is on. Keep it on.
2. Open https://myaccount.google.com/apppasswords, sign in, give the app password a name (for example "Bold7"), and create it. Google shows 16 letters once.
3. In Email Setup, your Gmail address, and in Password the 16 letters without the spaces, then **Next**.

IMAP is always on for Gmail accounts. If the app passwords page says the setting is not available, your account uses only security keys as a second step, is enrolled in Advanced Protection, or is a work or school account. Google explains it at https://support.google.com/accounts/answer/185833.

## Google Workspace

A Gmail account at your company's or school's own domain. Bold7 recognises it from the domain's mail records and uses Gmail's servers: type your address and an **app password**, made as for Gmail above. Google stopped accepting the account password itself in mail apps in May 2025.

Your administrator decides whether this can work at all:
- IMAP must be allowed for your account, and for any mail client, not only for apps that sign in through Google ([Google's page for administrators](https://knowledge.workspace.google.com/admin/sync/turn-pop-and-imap-on-or-off-for-users)).
- App passwords must be available to you. They are not when the administrator requires security keys ([how 2-Step Verification works with older apps](https://knowledge.workspace.google.com/admin/security/how-2-step-verification-works-with-legacy-apps)).

## Outlook.com, Hotmail, Live, MSN

Microsoft stopped accepting passwords from mail apps for these accounts on September 16, 2024, app passwords included ([Microsoft](https://support.microsoft.com/en-US/support/known-issues/modern-authentication-methods-now-needed-to-continue-syncing-outlook-email-in-non-microsoft-email-ap)). Bold7 knows every Outlook, Hotmail, Live and MSN domain and never sends them a password.

- **If Email Setup offers Sign In With Browser for Outlook**, use it, as in [Browser sign-in](#browser-sign-in).
- **Otherwise**, clicking Next says "...browser sign-in is off in this build of Bold7. This address cannot be set up yet." Your version of Bold7 was built without the Microsoft sign-in, and an Outlook.com address cannot be added with it. No password will change that.

If the browser sign-in works but Bold7 then cannot reach the mailbox, check that IMAP is on in Outlook on the web: Settings, Mail, Forwarding and IMAP ([Microsoft](https://support.microsoft.com/en-us/office/pop-imap-and-smtp-settings-for-outlook-com-d088b986-291d-42b8-9564-9c414e2aa040)).

## Microsoft 365 at work or school

Not possible yet. Bold7 recognises these domains from their mail records and says so. Microsoft has turned off password sign-in to Exchange Online mailboxes for every organisation, app passwords included ([Microsoft](https://learn.microsoft.com/en-us/exchange/clients-and-mobile-in-exchange-online/deprecation-of-basic-authentication-exchange-online)), and Bold7's browser sign-in only covers personal Microsoft accounts. Even then, your organisation's administrator decides whether mail apps are allowed.

## iCloud Mail

What you need: an **app-specific password**, which needs two-factor authentication on your Apple Account (it is on for almost every account).

1. Go to https://account.apple.com and sign in.
2. **Sign-In and Security**, **App-Specific Passwords**, then generate one and give it a name ([Apple](https://support.apple.com/en-us/102654)).
3. In Email Setup, your `@icloud.com`, `@me.com` or `@mac.com` address and the app-specific password, then **Next**.

With your own domain on iCloud Mail, Bold7 may not recognise it: type the servers from [Apple's page](https://support.apple.com/en-us/102525) (`imap.mail.me.com`, 993; `smtp.mail.me.com`, 587 with Use STARTTLS), and your full address as User name if your domain's address does not work.

## Yahoo Mail and AOL Mail

Both need an **app password**:

- Yahoo: https://login.yahoo.com/account/security, then create an app password with a name ([Yahoo](https://help.yahoo.com/kb/SLN15241.html)). Yahoo may refuse to make one from a browser you have not used to sign in to Yahoo for a few days, or from a private window.
- AOL: https://login.aol.com/account/security, then generate an app password ([AOL](https://help.aol.com/articles/create-and-manage-app-password)).

Then your address and that app password in Email Setup.

## GMX, WEB.DE and mail.com

These turn IMAP off until you allow it, and turn it off again after a long time without use.

1. In the webmail, open the email settings, **POP3/IMAP**, and allow access by POP3 and IMAP ([gmx.net](https://hilfe.gmx.net/pop-imap/einschalten.html), [gmx.com](https://support.gmx.com/pop-imap/toggle.html), [WEB.DE](https://hilfe.web.de/pop-imap/einschalten.html)).
2. With two-factor authentication on, make an app password in the account's security settings ([GMX](https://hilfe.gmx.net/sicherheit/2fa/anwendungsspezifisches-passwort.html), [WEB.DE](https://hilfe.web.de/sicherheit/2fa/anwendungsspezifisches-passwort.html)) and use it in Email Setup. Otherwise use your password.

mail.com only lets other apps in with a Premium account.

## Zoho Mail

1. Turn IMAP on: in Zoho Mail, **Settings**, **Mail Accounts**, your address, **IMAP Access** ([Zoho](https://www.zoho.com/mail/help/imap-access.html)). New free accounts may not have IMAP at all.
2. With two-factor authentication on, make an app password in Zoho Accounts, **Security**, **App Passwords** ([Zoho](https://www.zoho.com/mail/help/adminconsole/two-factor-authentication.html)).

Bold7 knows `zoho.com` and `zohomail.com` addresses. With your own domain at Zoho, or an account hosted in Zoho's European or other regions, use the server names shown in Zoho Mail's settings next to IMAP Access if Bold7 does not find them.

## Yandex

Turn IMAP on in Yandex Mail (Settings, Email clients), then make an app password for Mail at https://id.yandex.com/security/app-passwords and use it in Email Setup.

## Orange (orange.fr, wanadoo.fr)

Orange gives mail apps a password of their own, separate from your Orange password: in your Espace Client, Connexion et sécurité, generate it. Newer mailboxes also have POP and IMAP access off until you turn it on in Mail Orange's settings. Both are explained on [Orange's page about mail apps](https://assistance.orange.fr/ordinateurs-peripheriques/installer-et-utiliser/l-utilisation-du-mail-et-services-associes/mail-orange/parametrer-la-boite-mail/mail-orange-comment-acceder-a-sa-boite-mail-orange-depuis-une-application-ou-un-logiciel-de-messagerie-non-fourni-par-orange_434630-964290). If you generate a new one later, delete the account in Bold7 and add it again.

## Free, SFR, La Poste, Bbox

Your address and your mailbox password. Bold7 knows the servers of `free.fr` ([Free](https://assistance.free.fr/articles/609)), `sfr.fr`, `neuf.fr`, `club-internet.fr`, `laposte.net` and `bbox.fr`.

I could not confirm the servers of SFR, La Poste and Bbox on a page of theirs, and no one has tried them with Bold7 yet. If one of them fails, I would like to hear about it (see [Still stuck](#still-stuck)), with Bold7's exact message. Some of La Poste's help pages give the identifier without `@laposte.net` as the user name; if your password is refused, that may be why, and you can then type the servers yourself from [La Poste's page](https://aide.laposte.net/kb/guide/fr/comment-parametrer-un-logiciel-de-messagerie-pour-envoyer-et-recevoir-mes-courriers-electroniques-hezo9Creu6) with that identifier in **User name**. Temporary laposte.net addresses have no IMAP access at all.

## Proton Mail

Not possible on the phone, and Bold7 says so. Proton only lets mail apps in through Proton Mail Bridge, a program for Windows, macOS and Linux on a paid plan, which does not run on phones ([Proton](https://proton.me/support/imap-smtp-and-pop3-setup)). Bold7 recognises Proton addresses, and domains whose mail Proton receives.

## Another provider

Type your address and password and click **Next**: Bold7 looks for the servers itself. If it asks for them, your provider's help pages give them. Check two things in the provider's settings too: that IMAP access is on, and whether mail apps need an app password.

Bold7 cannot connect to a server without encryption, or to a provider that only allows its own app or its own sign-in page (other than Microsoft's for personal accounts, when your version has it).

## Still stuck

Ask in the [Bold7 topic](https://discord.com/channels/1356921788038119556/1572002370160828446) on the official Zinwa Discord, or open an issue on https://github.com/codexofc/bold7/issues with your provider's name and Bold7's exact message. Never post your password or an app password.
